package com.localllm.server.core

/**
 * 工具循环的判据，抽成纯函数便于单测。
 *
 * 「0 = 不限」值得单独兜一层：如果直接拿 0 进 `for (round in 0 until 0)`，一圈都不会跑，
 * 表现是「发完消息什么都没有」——日志里还看不出原因。
 *
 * 2026-10-30 用户决定去掉两条启发式：`stalledWithoutActing`（只宣告下一步就催办）与
 * `noProgress`（两次同样报错判无进展）。模型写到哪停在哪，由用户决定是否继续。
 */
object ToolLoopPolicy {

    /** 设置值 → 实际轮次上限；0 或负数表示不限制 */
    fun roundCap(configured: Int): Int = if (configured <= 0) Int.MAX_VALUE else configured

    /**
     * 自驱模式的收尾判定：模型这一轮不再调工具了，那到底算不算做完？
     *
     * 判据**全部外置**，不问模型「你觉得完成了吗」——小模型的自评不可靠，
     * 问它只会得到「已完成」四个字，而文件其实是空的、命令其实报了错。
     * 这里只看三件可验证的事：有没有失败、该产出的东西产出了没、是不是在原地打转。
     */
    data class DriveVerdict(val shouldContinue: Boolean, val reason: String? = null)

    /**
     * @param wantsArtifact 这一轮被判断为「要产出成品」（复用原有 artifact 判定）
     * @param hasDeliverable 本次任务已经产出过文件
     * @param failedTools 已失败的步骤数（ok=false）
     * @param sameStall 连续两轮的失败摘要完全一致（原地打转）
     * @param continued 已经自动续跑过几次
     * @param maxContinue 自动续跑上限
     */
    fun driveVerdict(
        wantsArtifact: Boolean,
        hasDeliverable: Boolean,
        failedTools: Int,
        sameStall: Boolean,
        continued: Int,
        maxContinue: Int,
    ): DriveVerdict {
        if (continued >= maxContinue) {
            return DriveVerdict(false, "自动续跑已达上限（$maxContinue 次），停下来交给你判断")
        }
        if (sameStall) {
            return DriveVerdict(false, "连续两轮卡在同一个问题上，继续下去大概率是重复，先停下")
        }
        if (failedTools > 0) {
            return DriveVerdict(true, "还有 $failedTools 个步骤是失败的，继续修")
        }
        if (wantsArtifact && !hasDeliverable) {
            return DriveVerdict(true, "任务需要产出文件，但目前还没有任何产出")
        }
        return DriveVerdict(false, null)
    }
}
