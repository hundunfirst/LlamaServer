package com.localllm.server.core

import android.app.Application
import android.content.Context
import android.os.PowerManager
import com.localllm.server.ui.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定时任务的执行器：到点在后台自己把这一轮跑完。
 *
 * 老版本只做「排进队列 + 发通知」，两个硬伤没解决就谈不上无人值守：
 * 1. **引擎没人拉起来**——后台没有 ServerService 就只有一个 8080 连不上的空转；
 * 2. **会话各存一份**——后台若自建一个 ChatViewModel，两边写同一个 chat_sessions.json，
 *    后写的把先写的盖掉。
 * 现在前者用 [ServerService]（前台服务，闹钟触发的广播给了启动豁免）解决，
 * 后者用 [ChatViewModel.shared] 的进程内单例解决：后台跑的那一轮，界面打开就是同一份。
 *
 * 同一时刻只跑一个任务：手机上并发生成只会互相拖慢。
 */
object ScheduledRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在执行的任务 id；null = 空闲 */
    @Volatile
    private var runningTaskId: String? = null

    /** 冷启动大模型要等多久（含 KV 加载与 prefill 前的初始化） */
    private const val ENGINE_WAIT_MS = 180_000L

    /** 读到 ERROR 后再等这么久才判启动失败：启动瞬间可能还挂着上一次的旧状态 */
    private const val ERROR_GRACE_MS = 6_000L

    /** 一轮生成的总时长上限：超过就按失败收尾，别让一个跑飞的任务占整夜 */
    private const val TURN_TIMEOUT_MS = 20 * 60_000L

    /** 任务期间持锁，防止锁屏后进 Doze 把生成冻在半路 */
    private const val WAKELOCK_MS = 30 * 60_000L

    fun isRunning(taskId: String): Boolean = runningTaskId == taskId

    /**
     * 闹钟到点。无人值守开着就直接跑，否则退回「排队 + 通知」。
     * 这个方法跑在广播主线程上，只做判断与启动，真正的活都在 IO 协程里。
     */
    fun fire(context: Context, task: ScheduledTasks.Task) {
        val app = context.applicationContext as? Application ?: return
        if (!AppSettings.scheduledAutoRun) {
            PendingQueue.append(app, task.prompt)
            ServerProcessManager.appLog("定时任务到点：${task.name}（未开无人值守，已排入队列）")
            ScheduledTasks.notifyPending(app, task)
            ScheduledTasks.markRun(app, task.id, "已排队（未开无人值守）")
            return
        }
        if (runningTaskId != null) {
            PendingQueue.append(app, task.prompt)
            ServerProcessManager.appLog("定时任务到点：${task.name}（上一个任务还在跑，已排入队列）")
            ScheduledTasks.notifyResult(app, task, false, "上一个定时任务还在跑，这条已排进队列")
            ScheduledTasks.markRun(app, task.id, "已排队（上一个任务还在跑）")
            return
        }
        runningTaskId = task.id
        ScheduledTasks.notifyRunning(app, task)
        scope.launch {
            try {
                runOne(app, task)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                finish(app, task, false, "执行出错：${e.message ?: e.javaClass.simpleName}")
            } finally {
                runningTaskId = null
            }
        }
    }

    private suspend fun runOne(app: Application, task: ScheduledTasks.Task) {
        val wake = acquireWakeLock(app)
        // 引擎是这次任务临时拉起来的吗？是的话跑完关掉，别整夜占着电
        var startedEngineHere = false
        try {
            // 云端任务：直接用预设端点跑，**不需要启本地引擎**
            val cloudEndpoint = task.endpointPresetId?.let { id ->
                val preset = AppSettings.loadCloudPresets().firstOrNull { it.id == id }
                if (preset == null) {
                    finish(app, task, false, "找不到这个云端渠道预设（可能已被删除），请重新选一个")
                    return
                }
                ChatStore.Endpoint(
                    local = false,
                    baseUrl = preset.url,
                    model = task.cloudModel?.takeIf { it.isNotBlank() } ?: preset.model,
                    apiKey = preset.key,
                )
            }
            // 模型：任务里指定的优先（这就是"提前指定好模型"），否则跟随当前选中的
            val modelPath = task.modelPath?.takeIf { it.isNotBlank() }
                ?: AppSettings.selectedModelPath ?: AppSettings.serverModelPath
            if (cloudEndpoint == null && modelPath.isNullOrBlank()) {
                finish(app, task, false, "还没有选择模型，后台无法启动本地引擎")
                return
            }
            // 自动启动服务是可关的：关掉就只排队 + 通知（老行为），由用户点通知再跑
            if (cloudEndpoint == null && !task.autoStartEngine) {
                PendingQueue.append(app, task.prompt)
                finish(app, task, false, "本任务关掉了「自动启动服务」，已排入队列等用户点开执行")
                return
            }
            if (cloudEndpoint == null &&
                ServerProcessManager.state.value.status != ServerUiState.Status.RUNNING
            ) {
                startedEngineHere = !AppSettings.serverShouldRun
                ServerProcessManager.appLog(
                    "定时任务：后台拉起本地引擎（${task.name}，模型 ${modelPath!!.substringAfterLast('/')}）"
                )
                val started = runCatching { ServerService.start(app, modelPath) }.isSuccess
                if (!started) {
                    finish(app, task, false, "无法启动本地引擎（系统拒绝了前台服务启动）")
                    return
                }
                val st = waitEngineReady()
                if (st?.status != ServerUiState.Status.RUNNING) {
                    finish(
                        app, task, false,
                        if (st == null) "本地引擎 ${ENGINE_WAIT_MS / 1000} 秒内没起来（超时）"
                        else "本地引擎启动失败：${st.message}"
                    )
                    return
                }
                ServerProcessManager.appLog("定时任务：引擎已就绪，开始执行「${task.name}」")
            }
            val vm = ChatViewModel.shared(app)
            // 每次跑在**新会话**里（默认），历史与手动聊天分开；用户可关掉，落到当前会话
            val result = withTimeoutOrNull(TURN_TIMEOUT_MS) {
                vm.runBackgroundTurn(
                    prompt = task.prompt,
                    newSessionTitle = if (task.newSession) task.name else null,
                    endpoint = cloudEndpoint,
                )
            }
            if (result == null) {
                // 超时：把这一轮停掉，否则它会继续占着会话直到自然结束
                runCatching { vm.stop() }
                finish(app, task, false, "超过 ${TURN_TIMEOUT_MS / 60_000} 分钟仍未跑完，已中断")
                return
            }
            finish(
                app, task, result.ok,
                if (result.ok) {
                    val spent = ChatStore.elapsedLabel(result.elapsedMs)
                    result.text.take(1500) + (if (spent.isBlank()) "" else "\n（用时 $spent）")
                } else result.text
            )
        } finally {
            releaseWakeLock(wake)
            val busy = ChatViewModel.peek()?.generating?.value?.isNotEmpty() == true
            if (startedEngineHere && !busy) {
                ServerProcessManager.appLog("定时任务结束：关闭这次为它临时拉起的引擎")
                runCatching { ServerService.stop(app) }
            }
        }
    }

    /**
     * 等引擎就绪。
     * 注意状态机里可能残留上一次的 ERROR：刚拉起服务时先读到的还是旧状态，
     * 所以看到 ERROR 不能立刻判死，给几秒看它能不能翻成 STARTING/RUNNING。
     */
    private suspend fun waitEngineReady(): ServerUiState? {
        val deadline = System.currentTimeMillis() + ENGINE_WAIT_MS
        var errorSince: Long? = null
        while (System.currentTimeMillis() < deadline) {
            val st = ServerProcessManager.state.value
            when (st.status) {
                ServerUiState.Status.RUNNING -> return st
                ServerUiState.Status.ERROR -> {
                    val since = errorSince ?: System.currentTimeMillis().also { errorSince = it }
                    if (System.currentTimeMillis() - since > ERROR_GRACE_MS) return st
                }
                else -> errorSince = null
            }
            kotlinx.coroutines.delay(500)
        }
        return null
    }

    private fun finish(app: Application, task: ScheduledTasks.Task, ok: Boolean, text: String) {
        val oneLine = text.replace('\n', ' ').trim()
        ScheduledTasks.notifyResult(app, task, ok, text)
        ScheduledTasks.markRun(
            app, task.id,
            (if (ok) "完成：" else "失败：") + oneLine.take(40)
        )
        ServerProcessManager.appLog(
            "定时任务${if (ok) "完成" else "失败"}：${task.name} · ${oneLine.take(80)}"
        )
    }

    private fun acquireWakeLock(app: Application): PowerManager.WakeLock? = runCatching {
        val pm = app.getSystemService(PowerManager::class.java)
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localllm:scheduled").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_MS)
        }
    }.getOrNull()

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}
