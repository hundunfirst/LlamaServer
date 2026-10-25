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

    /**
     * 正文后面跟着的工具调用该执行还是当幻觉丢掉。
     *
     * 原来的规则是「正文够长（≥60 字）就算答完了，答后调用一律忽略」——用来挡弱模型答完再补一次
     * 假工具调用。但多步任务里模型常常先写一句进度（「接下来删除目标文件：」）再发工具调用，
     * 这条规则会把真正的下一步丢掉，任务于是「正文写完就结束」，清单还停在 1/5。
     *
     * 判据收敛成两个「确实还没做完」的信号：
     *  - 清单里还有没打勾的项（模型自己列的计​划，最可靠）；
     *  - 正文读起来是进度说明而不是结论（结尾是冒号/省略号，或含「接下来/然后/现在…」）。
     * 两个都不满足时才按幻觉丢掉——那样即使这条调用是破坏性的（删除、覆盖），也不会被误执行。
     */
    fun keepTrailingCalls(unfinishedTodos: Boolean, answerText: String): Boolean =
        unfinishedTodos || looksLikeProgressNote(answerText)

    /** 模型「只宣告下一步、不真调工具」时，同一条用户消息最多催办几次 */
    const val STALL_NUDGE_LIMIT = 2

    /** 宣告式的正文都很短；长篇正文是真正的答复，不催 */
    private const val ANNOUNCE_MAX_CHARS = 240

    /** 「第一步 / 第 2 步」这类小标题——模型写完它就停，正文其实是下一步的宣告 */
    private val STEP_ANNOUNCE = Regex("第\\s*[0-9一二三四五六七八九十]+\\s*步")

    private val ANNOUNCE_MARKERS = listOf(
        "接下来", "下一步", "现在开始", "开始执行", "让我先", "让我来"
    )

    /**
     * 这一轮该不该催办：模型写了一句「现在执行删除操作：」就停下，一个工具都没调。
     *
     * 真机现场：4B 模型这轮正文 108 字、零工具调用、finish_reason=stop，工具循环把「没有工具调用」
     * 当成答复结束直接收尾，任务原地不动、清单停在 1/5，用户只能手打「继续」。
     * 这一句并不是结论，是下一步的宣告——催一次它就会真的动手。
     *
     * 判据宁可窄，四种结尾才算宣告：
     *  - 清单没做完（模型自己列的计划，最可靠）；
     *  - 结尾是冒号/省略号；
     *  - 含「第一步 / 第 2 步」这类小标题（真机上模型爱写成 `### **第一步：列出…**`，
     *    去掉 markdown 修饰符后末尾是「内容」而不是冒号，早期版本漏判）；
     *  - 含「接下来 / 下一步 / 现在开始」这类词。
     * 正文里出现「现在/然后」不算——正常回答也常这么写，催了会变成同一问题答两遍；
     * 结尾在向用户提问（问号）也不算，那本来就是正常收尾。
     */
    fun stalledWithoutActing(
        unfinishedTodos: Boolean,
        answerText: String,
        hasToolCalls: Boolean,
        nudged: Int
    ): Boolean {
        if (hasToolCalls || nudged >= STALL_NUDGE_LIMIT) return false
        val text = answerText.trim()
        if (text.isEmpty()) return false
        val tail = stripMarkdownTail(text.takeLast(120))
        if (tail.isEmpty()) return false
        // 在问用户「要不要我继续」——正常收尾，催了会答非所问
        if (tail.endsWith("？") || tail.endsWith("?")) return false
        if (unfinishedTodos) return true
        if (text.length > ANNOUNCE_MAX_CHARS) return false
        if (tail.endsWith("：") || tail.endsWith(":") ||
            tail.endsWith("…") || tail.endsWith("...")
        ) {
            return true
        }
        if (STEP_ANNOUNCE.containsMatchIn(tail)) return true
        return ANNOUNCE_MARKERS.any { tail.contains(it) }
    }

    /** 去掉结尾的 markdown 修饰符：`### **第一步：列出…**` 末尾是 `**`，不剥掉就看不出它是个标题 */
    private fun stripMarkdownTail(s: String): String =
        s.trimEnd().trimEnd('*', '_', '`', '#', ' ', '\n', '\t', '\r')

    private val PROGRESS_MARKERS = listOf(
        "接下来", "下一步", "然后", "现在", "我将", "我会", "正在", "即将", "准备", "接着"
    )

    /** 正文结尾是否像「进度说明」：只查末尾一段，避免正文里随手提到的词造成误判 */
    internal fun looksLikeProgressNote(text: String): Boolean {
        val tail = text.trim().takeLast(80)
        if (tail.isEmpty()) return false
        if (tail.endsWith("：") || tail.endsWith(":") || tail.endsWith("…") || tail.endsWith("...")) {
            return true
        }
        return PROGRESS_MARKERS.any { tail.contains(it) }
    }
}
