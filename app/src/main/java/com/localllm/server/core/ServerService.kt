package com.localllm.server.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.localllm.server.App
import com.localllm.server.MainActivity
import com.localllm.server.R
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ServerService : android.app.Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var startedForeground = false

    /** 屏幕关闭后保持推理服务的唤醒锁；只在「应该在跑」期间持有 */
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdogJob: Job? = null

    /** 自动重启的时间戳窗口，用于限流——模型本身有问题时不能无限重启烧电 */
    private val restartTimes = ArrayDeque<Long>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            ServerProcessManager.state.collect { state ->
                // startForeground 之前忽略状态（初始 STOPPED 会与 onStartCommand 竞态）
                if (startedForeground) {
                    notifyState(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppSettings.serverShouldRun = false
                stopKeepAlive()
                val stopModel = AppSettings.serverModelPath
                val stopPort = AppSettings.port
                // 停止前先把 slot 的 KV 落盘：服务还活着才有机会存，destroy 之后就晚了。
                // 存档最多十几秒，放 IO 线程阻塞；前台通知的收尾回主线程做。
                scope.launch {
                    if (stopModel != null) {
                        ServerProcessManager.saveKvBlocking(
                            stopModel, stopPort, AppSettings.effectiveCtx(stopModel)
                        )
                    }
                    ServerProcessManager.stop()
                    android.os.Handler(mainLooper).post {
                        ServiceCompat.stopForeground(
                            this@ServerService, ServiceCompat.STOP_FOREGROUND_REMOVE
                        )
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 用户主动启动：重置自动重启预算，否则上次故障把额度用完会让这次也没法自愈
                synchronized(restartTimes) { restartTimes.clear() }
                val modelPath = intent.getStringExtra(EXTRA_MODEL)
                    ?: AppSettings.selectedModelPath
                return beginForeground(modelPath, reviving = false)
            }
            else -> {
                // 进程被系统回收后重建，此时 intent 为 null 或没有 action。
                // 只有上次是「主动启动」才自己回来；主动停止过的直接收摊。
                if (!AppSettings.serverShouldRun) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val modelPath = AppSettings.serverModelPath ?: AppSettings.selectedModelPath
                return beginForeground(modelPath, reviving = true)
            }
        }
    }

    /** 进入前台 + 起服务 + 开看门狗。ACTION_START 和系统复活两条路共用 */
    private fun beginForeground(modelPath: String?, reviving: Boolean): Int {
        // 任何分支都必须先 startForeground，否则 5 秒超时直接崩
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(if (reviving) "正在恢复推理服务…" else "正在启动推理服务…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            startedForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (modelPath == null) {
            notifyState(ServerUiState(status = ServerUiState.Status.ERROR, message = "请先选择模型"))
            stopSelf()
            return START_NOT_STICKY
        }
        AppSettings.serverShouldRun = true
        AppSettings.serverModelPath = modelPath
        acquireWakeLock()
        launchServer(modelPath)
        ensureWatchdog()
        return START_STICKY
    }

    private fun launchServer(modelPath: String) {
        ServerProcessManager.start(
            modelPath = modelPath,
            port = AppSettings.port,
            // 按模型覆盖优先（精简版：上下文 / mmproj 跟随模型走）
            ctxSize = AppSettings.effectiveCtx(modelPath),
            threads = AppSettings.threads,
            ngl = AppSettings.ngl,
            lanEnabled = AppSettings.lanEnabled,
            apiToken = AppSettings.apiToken,
            cacheTypeK = AppSettings.cacheTypeK,
            cacheTypeV = AppSettings.cacheTypeV,
            specType = AppSettings.specType,
            mmprojPath = AppSettings.effectiveMmproj(modelPath),
            faMode = AppSettings.faMode,
            apiTokenEnabled = AppSettings.apiTokenEnabled,
            prismEngine = AppSettings.prismEngineEnabled
        )
    }

    /**
     * 看门狗：子进程意外死掉（状态停在 ERROR）时自动拉起。
     * 只认 ERROR——降级重启链路把状态置成 STARTING，启动中/运行中都不干预，
     * 否则会和 ServerProcessManager 自己的 ctx/spec/ngl 降级抢着重启。
     */
    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_MS)
                if (!AppSettings.serverShouldRun) return@launch
                if (ServerProcessManager.state.value.status != ServerUiState.Status.ERROR) continue
                // 端口被别的 App 占着：重试多少次都是同一个 bind 失败，只在日志里说明一次就罢手
                if (ServerProcessManager.isPortConflict()) {
                    ServerProcessManager.appLog(
                        "端口被占用，已停止自动重启（换端口或停掉占用方后再手动启动）"
                    )
                    return@launch
                }
                // 内存不足被前置拦下：绝不能重试，再试一次可能把系统带崩
                if (ServerProcessManager.isMemBlocked()) {
                    ServerProcessManager.appLog(
                        "内存不足，已停止自动重启（换更小的模型或更低的量化后再手动启动）"
                    )
                    return@launch
                }
                val modelPath = AppSettings.serverModelPath ?: return@launch
                val attempt = noteRestartAttempt()
                if (attempt < 0) {
                    ServerProcessManager.appLog(
                        "自动重启已达上限（$MAX_RESTARTS 次 / ${RESTART_WINDOW_MS / 60_000} 分钟），停止自动拉起，请手动启动"
                    )
                    return@launch
                }
                // 逐次退避：模型或引擎真有问题时，快速重试只会让手机发热
                val waitMs = listOf(2L, 5L, 10L).getOrElse(attempt - 1) { 10L } * 1000
                ServerProcessManager.appLog("检测到服务已停止，${waitMs / 1000} 秒后自动重启（第 $attempt 次）")
                delay(waitMs)
                if (!AppSettings.serverShouldRun) return@launch
                launchServer(modelPath)
            }
        }
    }

    /** 记一次重启尝试并返回是窗口内第几次；超限返回 -1 */
    private fun noteRestartAttempt(): Int {
        val now = System.currentTimeMillis()
        synchronized(restartTimes) {
            while (restartTimes.isNotEmpty() && now - restartTimes.first() > RESTART_WINDOW_MS) {
                restartTimes.removeFirst()
            }
            if (restartTimes.size >= MAX_RESTARTS) return -1
            restartTimes.addLast(now)
            return restartTimes.size
        }
    }

    private fun acquireWakeLock() {
        if (!AppSettings.keepAwakeEnabled) return
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localllm:server").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "已持有唤醒锁：屏幕关闭后保持推理服务")
        } catch (e: Exception) {
            Log.w(TAG, "唤醒锁获取失败：${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "唤醒锁释放失败：${e.message}")
        }
        wakeLock = null
    }

    private fun stopKeepAlive() {
        watchdogJob?.cancel()
        watchdogJob = null
        releaseWakeLock()
    }

    override fun onDestroy() {
        stopKeepAlive()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun notifyState(state: ServerUiState) {
        val nm = getSystemService(NotificationManager::class.java)
        val text = when (state.status) {
            ServerUiState.Status.STARTING -> "正在启动推理服务…"
            ServerUiState.Status.RUNNING -> "运行中 · ${state.endpoint}"
            ServerUiState.Status.ERROR -> "出错：${state.message}"
            ServerUiState.Status.STOPPED -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "推理服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "本地 llama-server 运行状态"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "llama_server"
        private const val NOTIFICATION_ID = 1

        /** 看门狗巡检间隔：太密会白耗电，太疏则崩溃后要等很久才恢复 */
        private const val WATCHDOG_MS = 30_000L

        /** 自动重启限流：窗口内最多这么多次，超出就交回人工 */
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 10 * 60_000L

        const val ACTION_START = "com.localllm.server.action.START"
        const val ACTION_STOP = "com.localllm.server.action.STOP"
        const val EXTRA_MODEL = "extra_model"

        fun start(context: Context, modelPath: String) {
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL, modelPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
