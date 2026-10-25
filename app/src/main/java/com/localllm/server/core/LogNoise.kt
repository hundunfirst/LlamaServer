package com.localllm.server.core

/**
 * 日志页的显示层噪音折叠。
 *
 * 引擎每次加载模型都会打近百行 `W warmup: MUL_MAT: type = f32, ne = [...]`（后端不支持算子的清单），
 * 在真机日志里比真实事件多一个数量级，翻日志时整屏都是这些，找不到想看的那条。
 *
 * 只在显示层折叠：内存里的行和 `logs.txt` 都不动——排查时还要用原文（日志页「复制」取的也是原文）。
 */
object LogNoise {

    /** 显示用的一行；[collapsed] > 0 表示这是一条折叠摘要，吞掉了这么多行原文 */
    data class Row(val text: String, val collapsed: Int = 0)

    private const val MARK = "warmup:"

    fun isNoise(line: String): Boolean = line.contains(MARK)

    /**
     * 连续噪音折成一行摘要。
     * 孤立的一行噪音照常显示——为了省一行把单独一条告警也吞掉，得不偿失。
     */
    fun collapse(lines: List<String>): List<Row> {
        val out = ArrayList<Row>(lines.size)
        val run = ArrayList<String>()
        fun flush() {
            when (run.size) {
                0 -> Unit
                1 -> out.add(Row(run[0]))
                else -> out.add(
                    Row("… ${run.size} 行 warmup 提示已折叠（「复制」拿到的是原文）", run.size)
                )
            }
            run.clear()
        }
        for (line in lines) {
            if (isNoise(line)) run.add(line) else { flush(); out.add(Row(line)) }
        }
        flush()
        return out
    }
}
