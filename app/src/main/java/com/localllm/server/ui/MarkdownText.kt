package com.localllm.server.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * 助手回复的富文本解析：先切块（代码块 / 标题 / 列表 / 引用 / 表格），再把行内 Markdown 与
 * LaTeX 转成 AnnotatedString。
 *
 * 从 ChatScreen.kt 里搬出来的：这些是纯解析逻辑，和界面无关，单独成文件才能写单测、
 * 也免得 ChatScreen 继续膨胀。
 */
internal sealed class Seg {
    data class Text(val s: String) : Seg()
    data class Code(val lang: String, val s: String) : Seg()
    data class Heading(val level: Int, val s: String) : Seg()
    data class ListBlock(val lines: List<String>) : Seg()
    data class Quote(val s: String) : Seg()
    /** rows[0] 为表头，已剔除 |---|---| 分隔行 */
    data class Table(val rows: List<List<String>>) : Seg()
}

internal fun parseSegments(content: String): List<Seg> {
    val out = mutableListOf<Seg>()
    val re = Regex("```([A-Za-z0-9_+-]*)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    var last = 0
    for (m in re.findAll(content)) {
        if (m.range.first > last) parseBlocks(content.substring(last, m.range.first), out)
        out.add(Seg.Code(m.groupValues[1].lowercase(), m.groupValues[2].trimEnd('\n')))
        last = m.range.last + 1
    }
    if (last < content.length) {
        val tail = content.substring(last)
        val openIdx = tail.indexOf("```")
        if (openIdx >= 0) {
            // 流式输出中代码块尚未闭合：先把前面普通内容解析，再把剩余部分当作"进行中的代码块"
            if (openIdx > 0) parseBlocks(tail.substring(0, openIdx), out)
            val after = tail.substring(openIdx + 3)
            val nl = after.indexOf('\n')
            val rawLang = if (nl >= 0) after.substring(0, nl) else ""
            val lang = rawLang.trim().filter { it.isLetterOrDigit() || it == '_' || it == '+' || it == '-' }
            val body = if (nl >= 0) after.substring(nl + 1) else after
            out.add(Seg.Code(lang.lowercase(), body.trimEnd('\n')))
        } else {
            parseBlocks(tail, out)
        }
    }
    if (out.isEmpty()) out.add(Seg.Text(content))
    return out
}

/** 非代码区按行分块：标题 / 列表 / 引用 / 表格 / 段落 */
internal fun parseBlocks(chunk: String, out: MutableList<Seg>) {
    val lines = chunk.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> { i++ }
            trimmed.matches(Regex("^#{1,6}\\s+.+")) -> {
                val level = trimmed.indexOf(' ')
                out.add(Seg.Heading(level, trimmed.substring(level + 1).trim()))
                i++
            }
            trimmed.startsWith(">") -> {
                val buf = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                out.add(Seg.Quote(buf.toString()))
            }
            trimmed.matches(Regex("^(\\s*([-*•])\\s+|\\s*\\d+[.)]\\s+).+")) -> {
                val buf = mutableListOf<String>()
                while (i < lines.size &&
                    lines[i].trim().matches(Regex("^(\\s*([-*•])\\s+|\\s*\\d+[.)]\\s+).+"))
                ) {
                    buf.add(lines[i].trim())
                    i++
                }
                out.add(Seg.ListBlock(buf))
            }
            trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2 -> {
                val raw = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    raw.add(
                        lines[i].trim()
                            .removePrefix("|")
                            .removeSuffix("|")
                            .split("|")
                            .map { it.trim() }
                    )
                    i++
                }
                // 剔除分隔行（单元格全为 --- 或 :---:）
                val sep = Regex("^:?-{2,}:?$")
                val rows = raw.filterNot { r -> r.isNotEmpty() && r.all { c -> c.matches(sep) } }
                if (rows.isNotEmpty()) out.add(Seg.Table(rows))
            }
            else -> {
                val buf = StringBuilder(trimmed)
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty() &&
                    !lines[i].trim().startsWith("#") && !lines[i].trim().startsWith(">") &&
                    !lines[i].trim().startsWith("|") &&
                    !lines[i].trim().matches(Regex("^(\\s*([-*•])\\s+|\\s*\\d+[.)]\\s+).+"))
                ) {
                    buf.append('\n').append(lines[i].trim())
                    i++
                }
                out.add(Seg.Text(buf.toString()))
            }
        }
    }
}

/** 匹配 '{' 的闭合 '}' 下标 */
internal fun matchBrace(s: String, start: Int): Int? {
    var depth = 0
    for (i in start until s.length) {
        when (s[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return i }
        }
    }
    return null
}

internal fun isSimpleExpr(s: String) = s.isNotBlank() && s.matches(Regex("^[A-Za-z0-9α-ωΑ-Ω.+-]+$"))

private val LATEX_SYMBOLS = mapOf(
    "times" to "×", "div" to "÷", "approx" to "≈", "pm" to "±", "mp" to "∓",
    "cdot" to "·", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "ne" to "≠", "neq" to "≠", "infty" to "∞", "int" to "∫", "sum" to "∑",
    "prod" to "∏", "partial" to "∂", "nabla" to "∇", "in" to "∈", "notin" to "∉",
    "subset" to "⊂", "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃",
    "cdots" to "…", "ldots" to "…", "dots" to "…", "prime" to "′",
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ",
    "sigma" to "σ", "tau" to "τ", "phi" to "φ", "chi" to "χ", "psi" to "ψ",
    "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "degree" to "°", "perp" to "⊥", "parallel" to "∥", "angle" to "∠",
    "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔",
    "lt" to "<", "gt" to ">", "leqslant" to "≤", "geqslant" to "≥", "varnothing" to "∅",
    "to" to "→", "gets" to "←", "leftrightarrow" to "↔", "mapsto" to "↦",
    "propto" to "∝", "sim" to "∼", "equiv" to "≡", "ll" to "≪", "gg" to "≫",
    "ast" to "∗", "circ" to "∘", "bullet" to "∙", "oplus" to "⊕", "otimes" to "⊗",
    "neg" to "¬", "land" to "∧", "lor" to "∨", "emptyset" to "∅",
    "therefore" to "∴", "because" to "∵", "cong" to "≅", "doteq" to "≐"
)

private val SUP_CHARS = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'x' to 'ˣ', 'i' to 'ⁱ'
)
private val SUB_CHARS = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'k' to 'ₖ', 'l' to 'ₗ',
    'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ',
    's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ'
)

internal fun mapScript(content: String, sup: Boolean): String? {
    val table = if (sup) SUP_CHARS else SUB_CHARS
    val out = StringBuilder(content.length)
    for (c in content) {
        if (c == ' ') continue
        out.append(table[c] ?: return null)
    }
    return out.toString()
}

/**
 * LaTeX → Unicode 轻量转换：模型习惯把算式写成 $$...$$/\frac/\sqrt 等，
 * App 不做完整 LaTeX 渲染，这里在渲染前转成 Unicode，历史消息同样受益。
 * 仅作用于正文/标题/列表/引用/表格单元格，代码块内容不转换。
 */
internal fun latexToUnicode(src: String): String {
    if ('\\' !in src && '$' !in src) return src
    val sb = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '$' -> i++ // $ / $$ 定界符直接去掉
            c == '\\' -> {
                var j = i + 1
                while (j < src.length && src[j].isLetter()) j++
                val name = if (j > i + 1) src.substring(i + 1, j) else ""
                if (name.isEmpty()) {
                    // \{ \} \$ \_ 等转义：保留其后那个字符
                    if (i + 1 < src.length) { sb.append(src[i + 1]); i += 2 } else i++
                } else {
                    // 只有真正读到 {…} 参数才推进 k，否则会把命令后的空格吞掉
                    var k = j
                    val args = mutableListOf<String>()
                    while (true) {
                        var m = k
                        while (m < src.length && src[m] == ' ') m++
                        if (m >= src.length || src[m] != '{') break
                        val end = matchBrace(src, m) ?: break
                        args.add(src.substring(m + 1, end))
                        k = end + 1
                    }
                    val proc = args.map { latexToUnicode(it) }
                    when (name) {
                        "frac", "dfrac", "tfrac", "cfrac" -> if (proc.size >= 2) {
                            sb.append(
                                if (isSimpleExpr(proc[0]) && isSimpleExpr(proc[1])) "${proc[0]}/${proc[1]}"
                                else "(${proc[0]})/(${proc[1]})"
                            )
                        }
                        "sqrt" -> if (proc.isNotEmpty()) {
                            sb.append(if (isSimpleExpr(proc[0])) "√${proc[0]}" else "√(${proc[0]})")
                        }
                        "text", "mathrm", "mbox", "boxed", "operatorname",
                        "mathbf", "mathit", "boldsymbol", "textbf", "underline",
                        "mathbb", "mathcal", "mathfrak" ->
                            proc.forEach { sb.append(it) }
                        "left", "right", "displaystyle", "textstyle", "limits",
                        "bigl", "bigr", "big", "Big", "Bigl", "Bigr",
                        "bigg", "Bigg", "biggl", "biggr", "Biggl", "Biggr" -> Unit
                        "hat", "bar", "vec" -> if (proc.isNotEmpty()) {
                            // 单字符基底加组合附加符（x̂/x̄/x⃗）；做不了就跳过，不硬凑
                            val mark = when (name) {
                                "hat" -> '\u0302' // combining circumflex
                                "bar" -> '\u0304' // combining macron
                                else -> '\u20D7' // combining right arrow above
                            }
                            val base = proc[0]
                            if (base.length == 1) sb.append(base).append(mark)
                            else proc.forEach { sb.append(it) }
                        }
                        "begin" -> if (args.isNotEmpty()) {
                            // \begin{env}...\end{env} → 去标记；\\ 行分隔→\n；& 列分隔→分隔符
                            val env = args[0]
                            val endTag = "\\end{$env}"
                            val endIdx = src.indexOf(endTag, k)
                            val bodyEnd = if (endIdx >= 0) endIdx else src.length
                            val sep = if (env in setOf("matrix", "pmatrix", "bmatrix", "vmatrix", "array")) "|"
                                      else "　" // cases/aligned 等用全角空格
                            val handled = src.substring(k, bodyEnd)
                                .split("\\\\") // 字面两个反斜杠 = LaTeX 行分隔
                                .joinToString("\n") { row ->
                                    row.split("&").joinToString(sep) { cell ->
                                        latexToUnicode(cell.trim())
                                    }
                                }
                            sb.append(handled)
                            k = if (endIdx >= 0) endIdx + endTag.length else src.length
                        }
                        "end" -> Unit // 未被 begin 消费的 \end{...}：去掉标记
                        else -> {
                            val sym = LATEX_SYMBOLS[name]
                            when {
                                sym != null -> { sb.append(sym); proc.forEach { sb.append(it) } }
                                proc.isNotEmpty() -> proc.forEach { sb.append(it) }
                                else -> sb.append(name) // 未知无参命令：去掉反斜杠
                            }
                        }
                    }
                    // 无参数命令不能跳到 k（k 已越过命令名后的空格，会把空格吃掉）
                    i = if (args.isEmpty()) j else k
                }
            }
            c == '^' || c == '_' -> {
                val sup = c == '^'
                var k = i + 1
                while (k < src.length && src[k] == ' ') k++
                var content: String? = null
                var end = k
                if (k < src.length && src[k] == '{') {
                    val e = matchBrace(src, k)
                    if (e != null) { content = src.substring(k + 1, e); end = e + 1 }
                } else if (k < src.length && src[k] != ' ' && src[k] != '$') {
                    content = src.substring(k, k + 1); end = k + 1
                }
                if (content == null) { sb.append(c); i++ }
                else {
                    val inner = latexToUnicode(content)
                    sb.append(mapScript(inner, sup) ?: (if (sup) "^($inner)" else "_($inner)"))
                    i = end
                }
            }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}

/** 行内 Markdown：**加粗**、*斜体*、`等宽代码`（先做 LaTeX→Unicode 转换） */
internal fun inlineMd(s: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val text = latexToUnicode(s)
        // 链接与格式分开匹配，避免跨正则取不存在的分组
        data class Hit(
            val start: Int,
            val end: Int,
            val kind: Int, // 1=Markdown链接 2=裸链接 3=加粗 4=行内代码 5=斜体
            val a: String,
            val b: String? = null
        )

        val linkRe = Regex(
            """\[([^\]\n]+)\]\((https?://[^)\s]+)\)|(https?://[^\s)\u3002\uff0c\uff1b]+)"""
        )
        val fmtRe = Regex("""(\*\*[^*\n]+\*\*|`[^`\n]+`|\*[^*\n]+\*)""")

        val hits = mutableListOf<Hit>()
        for (m in linkRe.findAll(text)) {
            val g1 = m.groups[1]
            val g2 = m.groups[2]
            val g3 = m.groups[3]
            if (g1 != null && g2 != null) {
                hits.add(Hit(m.range.first, m.range.last, 1, g1.value, g2.value))
            } else if (g3 != null) {
                hits.add(Hit(m.range.first, m.range.last, 2, g3.value))
            }
        }
        for (m in fmtRe.findAll(text)) {
            val v = m.value
            val kind = when {
                v.startsWith("**") -> 3
                v.startsWith("`") -> 4
                else -> 5
            }
            hits.add(Hit(m.range.first, m.range.last, kind, v))
        }
        hits.sortBy { it.start }

        val linkStyles = androidx.compose.ui.text.TextLinkStyles(
            style = androidx.compose.ui.text.SpanStyle(
                color = androidx.compose.ui.graphics.Color(0xFF1976D2),
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
            )
        )

        var last = 0
        for (h in hits) {
            if (h.start < last) continue
            append(text.substring(last, h.start))
            when (h.kind) {
                1 -> withLink(androidx.compose.ui.text.LinkAnnotation.Url(h.b!!, linkStyles)) {
                    append(h.a)
                }
                2 -> withLink(androidx.compose.ui.text.LinkAnnotation.Url(h.a, linkStyles)) {
                    append(h.a)
                }
                3 -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(h.a.removeSurrounding("**"))
                }
                4 -> withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = androidx.compose.ui.graphics.Color(0xFFC62828)
                    )
                ) { append(h.a.removeSurrounding("`")) }
                else -> withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                ) { append(h.a.trim('*')) }
            }
            last = h.end + 1
        }
        if (last < text.length) append(text.substring(last))
    }
