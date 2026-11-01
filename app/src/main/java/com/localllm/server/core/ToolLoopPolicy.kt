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
}
