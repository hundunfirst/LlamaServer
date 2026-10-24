package com.localllm.server.core

/**
 * 工具循环的判据，抽成纯函数便于单测。
 *
 * 「0 = 不限」值得单独兜一层：如果直接拿 0 进 `for (round in 0 until 0)`，一圈都不会跑，
 * 表现是「发完消息什么都没有」——日志里还看不出原因。
 */
object ToolLoopPolicy {

    /** 设置值 → 实际轮次上限；0 或负数表示不限制 */
    fun roundCap(configured: Int): Int = if (configured <= 0) Int.MAX_VALUE else configured

    /**
     * 自检是否「没有进展」：两次报的错一模一样，说明上一轮修改没起作用。
     * 空指纹表示自检干净通过，不算无进展。
     */
    fun noProgress(currentSig: String?, lastSig: String?): Boolean =
        !currentSig.isNullOrEmpty() && currentSig == lastSig
}
