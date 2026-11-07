package com.localllm.server.ui

/**
 * 文本差异：用于展示文件被改了哪几行。
 *
 * 从 ChatScreen.kt 里抽出来的纯逻辑（不依赖 Compose），抽出来才能单测——
 * 以前改 diff 只能靠肉眼比对界面。
 */
internal fun diffStat(prev: String?, cur: String): Pair<Int, Int> {
    val newLines = cur.lineSequence().toList()
    if (prev == null) return newLines.size to 0
    val pool = prev.lineSequence().groupingBy { it }.eachCount().toMutableMap()
    var added = 0
    for (line in newLines) {
        val n = pool[line] ?: 0
        if (n > 0) pool[line] = n - 1 else added++
    }
    return added to pool.values.sum()
}

/**
 * 改动对比：行级 LCS 生成统一 diff（+ / - / 空格前缀）。
 * 行数超过上限就退化——LCS 的 DP 是 O(n·m)，手机上几千行会卡住。
 */
internal fun diffLines(prev: String, cur: String, limit: Int = 700): List<Pair<Char, String>> {
    val a = prev.lineSequence().take(limit).toList()
    val b = cur.lineSequence().take(limit).toList()
    val dp = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val out = ArrayList<Pair<Char, String>>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> {
                out += ' ' to a[i]; i++; j++
            }

            dp[i + 1][j] >= dp[i][j + 1] -> {
                out += '-' to a[i]; i++
            }

            else -> {
                out += '+' to b[j]; j++
            }
        }
    }
    while (i < a.size) { out += '-' to a[i]; i++ }
    while (j < b.size) { out += '+' to b[j]; j++ }
    return out
}

