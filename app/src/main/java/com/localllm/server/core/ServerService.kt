package com.localllm.server.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.localllm.server.App
import com.localllm.server.MainActivity
import com.localllm.server.R
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ServerService : android.app.Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var startedForeground = false

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
            ACTION_START -> {
                // 任何分支都必须先 startForeground，否则 5 秒超时直接崩
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification("正在启动推理服务…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    startedForeground = true
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground 失败", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val modelPath = intent.getStringExtra(EXTRA_MODEL)
                    ?: AppSettings.selectedModelPath
                if (modelPath == null) {
                    notifyState(ServerUiState(status = ServerUiState.Status.ERROR, message = "请先选择模型"))
                    stopSelf()
                    return START_NOT_STICKY
                }
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
            ACTION_STOP -> {
                ServerProcessManager.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
