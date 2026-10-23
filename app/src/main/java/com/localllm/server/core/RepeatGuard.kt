package com.localllm.server.core

/**
 * 复读检测：判断模型输出是不是在「原地打转」。
 *
 * 为什么必须提前拦：弱模型在长字符串里复读时，会把工具调用的参数 JSON 撑到不闭合，
 * 服务端解析失败直接返回 500，整轮白跑（实测刷到 40 KB 才炸）。
 * 提前掐断至少能保住已生成的部分，并让用户点「继续这个任务」分段重试。
 *
 * 两种形态都要覆盖，实测就是栽在第二种上：
 * 1. 短周期：一句话/一串字符反复（周期 24–200）。
 * 2. **长周期**：整个 `<tool_call>` 块（含整份 HTML，约 2.5 KB）反复——
 *    周期远超 200，旧判据完全看不见，于是没拦住。
 */
object RepeatGuard {

    /** 尾部窗口：够长到能容纳两三个长周期块 */
    private const val TAIL = 12_000

    /** 短周期形态：尾部 1200 字内周期 24–200、至少重复若干次 */
    private const val SHORT_WINDOW = 1200
    private const val MIN_PERIOD = 24
    private const val MAX_PERIOD = 200

    /** 长周期形态：取 400 字作为探针，在尾部窗口里数它出现几次 */
    private const val PROBE_LEN = 400
    private const val PROBE_HITS = 3

    fun isRepetitive(text: CharSequence): Boolean =
        shortPeriod(text) || longPeriod(text)

    /**
     * 短周期：同一段内容连续重复。
     * 阈值保守（周期 24–200、尾部 1200 字内完全周期性），
     * 避免把正常结构（连续相似的 if 块、表格行）判成复读。
     */
    private fun shortPeriod(text: CharSequence): Boolean {
        val len = text.length
        if (len < 400) return false
        val tail = text.subSequence(maxOf(0, len - SHORT_WINDOW), len).toString()
        for (p in MIN_PERIOD..minOf(MAX_PERIOD, tail.length / 4)) {
            var ok = true
            var i = p
            while (i < tail.length) {
                if (tail[i] != tail[i - p]) {
                    ok = false
                    break
                }
                i++
            }
            if (!ok) continue
            // 周期性成立，但重复单元若全是标点/空白，属于正常排版而不是复读：
            // markdown 表格的「|---|---|」横线、一条长分隔线都长这样。
            // 实测这会把宽表格的结尾误判成复读、平白掐断回答，所以要求单元里有实际内容。
            val unit = tail.substring(tail.length - p)
            if (unit.count { it.isLetterOrDigit() } >= p / 4) return true
        }
        return false
    }

    /**
     * 长周期：拿尾部 400 字做探针，在更长窗口里数出现次数。
     * 周期多大都能抓到，代价只是一次 indexOf 循环；正常回答不会把同一段 400 字重复 3 次。
     */
    private fun longPeriod(text: CharSequence): Boolean {
        val len = text.length
        if (len < PROBE_LEN * PROBE_HITS) return false
        val from = maxOf(0, len - TAIL)
        val window = text.subSequence(from, len).toString()
        if (window.length < PROBE_LEN * PROBE_HITS) return false
        val probe = window.substring(window.length - PROBE_LEN)
        // 探针本身几乎全空白/单字符时没有判别力（比如一长串空格或等号）
        if (probe.count { it.isLetterOrDigit() } < PROBE_LEN / 10) return false
        var count = 0
        var idx = window.indexOf(probe)
        while (idx >= 0) {
            count++
            if (count >= PROBE_HITS) return true
            idx = window.indexOf(probe, idx + PROBE_LEN)
        }
        return false
    }
}
