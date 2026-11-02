package com.localllm.server.core

import android.app.Application
import java.io.File

/**
 * 终端页的日志：内存里留最近若干行给界面，同时落盘，重开 App 还在。
 *
 * 为什么要落盘：终端现在只能用来“当场看一眼”，App 一被杀（这台机器上很常见）记录就没了；
 * 而排查问题往往正是要回过头看那几行报错。
 *
 * 一行一个前缀区分种类，纯文本、方便 cat，也省得搞一套 JSON 解析：
 *   `>` 用户敲的命令、`|` 命令输出、`!` 错误、`.` 提示。
 */
object TerminalLog {

    enum class Kind { Input, Output, Info, Error }

    data class Line(val kind: Kind, val text: String)

    /** 每个会话最多留这么多行：输出可能很吵，留太多既费内存也卡滚动 */
    const val MAX_LINES = 400

    private fun mark(kind: Kind): Char = when (kind) {
        Kind.Input -> '>'
        Kind.Output -> '|'
        Kind.Info -> '.'
        Kind.Error -> '!'
    }

    private fun kindOf(m: Char): Kind = when (m) {
        '>' -> Kind.Input
        '|' -> Kind.Output
        '!' -> Kind.Error
        else -> Kind.Info
    }

    internal fun encode(kind: Kind, text: String): String = "${mark(kind)}$text"

    internal fun decode(raw: String): Line =
        if (raw.isEmpty()) Line(Kind.Info, "") else Line(kindOf(raw[0]), raw.substring(1))

    /** 写盘用的整段文本：只留最近 [max] 行 */
    internal fun serialize(lines: List<Line>, max: Int = MAX_LINES): String =
        lines.takeLast(max.coerceAtLeast(1)).joinToString("\n") { encode(it.kind, it.text) }

    internal fun parse(text: String, max: Int = MAX_LINES): List<Line> {
        if (text.isBlank()) return emptyList()
        return text.split('\n').filter { it.isNotEmpty() }.takeLast(max.coerceAtLeast(1)).map(::decode)
    }

    /** 从日志里反推命令历史（最近的在后面）。重复命令保留**最后一次**的位置，符合使用直觉 */
    internal fun history(lines: List<Line>, limit: Int = 5): List<String> =
        lines.filter { it.kind == Kind.Input }
            .map { it.text.removePrefix("$ ") }
            .filter { it.isNotBlank() }
            .asReversed().distinct().asReversed()
            .takeLast(limit)

    fun dir(app: Application): File = File(app.filesDir, "terminal")

    fun fileFor(app: Application, sid: String): File {
        val safe = sid.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("").ifBlank { "default" }
        return File(dir(app), "$safe.log")
    }
}
