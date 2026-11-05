package com.localllm.server.core

/**
 * agent 干活时的**可见状态**，以及「停止」信号。
 *
 * 为什么需要：它操作别的 App 时我们的界面在后台，用户看不到任何东西——
 * 十几秒没动静就不知道是「在思考」还是「已经崩了」（真机反馈过多次）。
 * 现在由 [A11yService] 用无障碍悬浮窗画一条常驻状态条：阶段 + 已过多少秒，
 * 超过阈值标红提示可能卡住，点一下就停。
 *
 * 阶段由 [com.localllm.server.ui.ChatViewModel] 在回合/工具/审批处推进，
 * 每秒钟由服务重绘一次（见 A11yService 的 ticker）。
 */
object AgentHud {

    enum class Stage {
        /** 没有任务在跑：不显示 */
        Idle,

        /** 已发出请求，等模型吐字 */
        Thinking,

        /** 正在执行某个工具（命令、adb、屏幕操作…） */
        RunningTool,

        /** 等用户在 App 里点确认 */
        WaitingApproval,

        /** 本轮出错 */
        Error,
    }

    /** 超过这么久没进展就提示「可能卡住」 */
    const val STUCK_MS = 60_000L

    /** 「出错 / 已停止」显示多久后自动消失：终态没人会来关它，久了就成了赖在屏幕上的错误 */
    private const val ERROR_LINGER_MS = 8_000L

    @Volatile
    private var stage: Stage = Stage.Idle

    @Volatile
    private var detail: String = ""

    @Volatile
    private var since: Long = 0L

    @Volatile
    private var stopRequested: Boolean = false

    fun update(newStage: Stage, text: String = "") {
        // 同一阶段只是换文案（例如连续几个工具），保留计时更真实
        if (newStage != stage) since = System.currentTimeMillis()
        stage = newStage
        detail = text
        refresh()
    }

    fun endTurn() {
        stage = Stage.Idle
        detail = ""
        refresh()
    }

    fun isActive(): Boolean = stage != Stage.Idle

    /** 当前状态条该显示的文字；[Stage.Idle] 或用户关掉了状态条 → null（隐藏） */
    fun text(): String? {
        if (!AppSettings.hudEnabled) return null
        val s = stage
        if (s == Stage.Idle) return null
        val age = System.currentTimeMillis() - since
        // 「出错 / 已停止」是终态：AGENT 那边收尾之后没人会再来改它，
        // 一直挂在屏幕上就成了"错误消不掉"（真机上一直留到重装 App 才消失）
        if (s == Stage.Error && age > ERROR_LINGER_MS) {
            stage = Stage.Idle
            detail = ""
            refresh()
            return null
        }
        val sec = (age / 1000).coerceAtLeast(0)
        val head = when (s) {
            Stage.Thinking -> "等模型"
            Stage.RunningTool -> "执行中"
            Stage.WaitingApproval -> "等你确认"
            Stage.Error -> "出错"
            Stage.Idle -> return null
        }
        val tail = if (detail.isBlank()) "" else "：$detail"
        val stuck = (s == Stage.Thinking || s == Stage.RunningTool) && age > STUCK_MS
        return if (stuck) "助手 $head$tail · ${sec}s —— 可能卡住，点此停止"
        else "助手 $head$tail · ${sec}s"
    }

    /** 用户点了状态条：既要拦掉后续动作，也要**真正中断这一轮生成** */
    fun requestStop() {
        stopRequested = true
        ServerProcessManager.appLog("用户在状态条上点了停止：中断本轮，并拒绝后续屏幕/adb 操作")
        update(Stage.Error, "已请求停止")
        runCatching { stopHook?.invoke() }
    }

    /**
     * 真正「停掉这一轮」的钩子（由 ChatViewModel 在初始化时挂上）。
     * 不放进来直接调用是为了不让 core 依赖 ui 层。
     */
    @Volatile
    var stopHook: (() -> Unit)? = null

    /** 取一次「是否要停」并复位 */
    fun consumeStop(): Boolean {
        if (!stopRequested) return false
        stopRequested = false
        return true
    }

    fun peekStop(): Boolean = stopRequested

    private fun refresh() {
        A11yService.instance?.refreshHud()
    }
}
