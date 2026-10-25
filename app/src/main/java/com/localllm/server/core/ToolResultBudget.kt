package com.localllm.server.core

/**
 * 单条工具结果回传给模型时的体积预算。
 *
 * 现场：一次 mt_apk_read_text 把上下文从 15.5k 顶到 108.6k token，再读一次就是
 * 201837 token，超过 131072 的窗口，服务端 400，整轮白跑（还白付了 8 分钟预填充）。
 *
 * 这里刻意宽松：预算按窗口的 1/6 给，正常大小的文件读取/搜索结果完全碰不到，
 * 只有真会把窗口挤爆的返回才动刀，而且保留头尾、明说接下来怎么续读。
 */
object ToolResultBudget {

    data class Trimmed(val text: String, val truncated: Boolean, val originalChars: Int)

    /**
     * 单条工具结果能占多少字符：窗口 token 的 1/6，按最坏情况折算（中文约 1 字符 = 1 token，
     * 代码约 3~4 字符 = 1 token）。上下限只是防极端配置，131k 窗口下是 21845 字符。
     */
    fun budgetChars(ctxTokens: Int): Int = (ctxTokens / 6).coerceIn(2_000, 60_000)

    fun trim(result: String, budget: Int): Trimmed {
        if (result.length <= budget) return Trimmed(result, false, result.length)
        if (looksStructured(result)) {
            // 把 JSON 从中间切开只会得到半截结构，模型会以为工具坏了——宁可不给，让它缩小范围
            return Trimmed(
                "（工具返回了 ${result.length} 字符的结构化数据，超过单次可回传的 $budget 字符，" +
                        "已整体省略，避免给出半截 JSON）\n请缩小查询范围或分页后再取一次。",
                true,
                result.length
            )
        }
        val head = budget * 3 / 4
        val tail = budget - head
        val omitted = result.length - head - tail
        return Trimmed(
            result.take(head) +
                    "\n\n…（此处省略 $omitted 字符，原文共 ${result.length} 字符。" +
                    "需要看这一段的话，用 offset/limit 之类的分页参数再读一次）\n\n" +
                    result.takeLast(tail),
            true,
            result.length
        )
    }

    private fun looksStructured(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }
}
