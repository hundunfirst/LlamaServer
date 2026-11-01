package com.localllm.server.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.server.core.AppSettings
import com.localllm.server.core.ChatStore

private sealed class Seg {
    data class Text(val s: String) : Seg()
    data class Code(val lang: String, val s: String) : Seg()
    data class Heading(val level: Int, val s: String) : Seg()
    data class ListBlock(val lines: List<String>) : Seg()
    data class Quote(val s: String) : Seg()
    /** rows[0] 为表头，已剔除 |---|---| 分隔行 */
    data class Table(val rows: List<List<String>>) : Seg()
}

private fun parseSegments(content: String): List<Seg> {
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
private fun parseBlocks(chunk: String, out: MutableList<Seg>) {
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
private fun matchBrace(s: String, start: Int): Int? {
    var depth = 0
    for (i in start until s.length) {
        when (s[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return i }
        }
    }
    return null
}

private fun isSimpleExpr(s: String) = s.isNotBlank() && s.matches(Regex("^[A-Za-z0-9α-ωΑ-Ω.+-]+$"))

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

private fun mapScript(content: String, sup: Boolean): String? {
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
private fun latexToUnicode(src: String): String {
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
private fun inlineMd(s: String): androidx.compose.ui.text.AnnotatedString =
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel, modifier: Modifier = Modifier, modelTitle: String = "") {
    val messages by vm.messages.collectAsStateWithLifecycle()
    // 只给最近一个被中断的任务显示续跑入口：否则每中断一次就多一个按钮，
    // 而旧的点了也只会去续跑最新那个，容易误解
    val lastStoppedIdx = messages.indexOfLast { it.role == "assistant" && it.task?.stopped == true }
    val input by vm.input.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val progressBySession by vm.progress.collectAsStateWithLifecycle()
    val toastMsg by vm.toast.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastStats by vm.lastStats.collectAsStateWithLifecycle()
    val contextUsage by vm.contextUsage.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val pendingImage by vm.pendingImageB64.collectAsStateWithLifecycle()
    val imageNote by vm.pendingImageNote.collectAsStateWithLifecycle()
    val queue by vm.taskQueue.collectAsStateWithLifecycle()
    val pendingFile by vm.pendingFile.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    // 正在编辑端点的会话 id（null = 没开选择弹窗）
    var endpointEditing by remember { mutableStateOf<String?>(null) }
    // 正在设置文件夹的会话 id
    var folderEditing by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 后台会话生成完成/失败时给一次提示
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var showCompressConfirm by remember { mutableStateOf(false) }
    var showSysPrompt by remember { mutableStateOf(false) }
    var previewImageB64 by remember { mutableStateOf<String?>(null) }
    var previewFileName by remember { mutableStateOf("") }
    var showSkills by remember { mutableStateOf(false) }
    var readMsg by remember { mutableStateOf<com.localllm.server.core.ChatStore.Msg?>(null) }
    var previewGenFile by remember {
        mutableStateOf<com.localllm.server.core.ChatStore.GenFile?>(null)
    }
    var previewFileText by remember { mutableStateOf("") }
    var showFilePreview by remember { mutableStateOf(false) }
    var sysPromptText by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }

    // 相册：走系统的图片选择器（Android 13+ 是系统相册 Photo Picker，旧系统回退到图库）。
    // 之前和「文件」共用 OpenDocument，点出来是文档浏览器，跟「相册」这个名字不符。
    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.attachImage(uri)
    }

    // 附加文件：图片走多模态/OCR，文本类文件读成文本随消息发送（其余类型暂不支持）
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) vm.attachImage(uri) else vm.attachFile(uri)
        }
    }

    // 输入区「+」面板：相册 / 拍照 / 文件（同一个入口，DSH 那种底部抽屉）
    var showAttachSheet by remember { mutableStateOf(false) }
    var showUsageSheet by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var permissionEditing by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // 拍照：交给系统相机，结果写进 MediaStore 新建的图片条目（不需要 FileProvider）
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) vm.attachImage(uri)
    }

    fun launchCamera() {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "hundun_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = runCatching {
            context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "无法创建照片文件", Toast.LENGTH_SHORT).show()
            return
        }
        cameraUri = uri
        runCatching { takePhoto.launch(uri) }.onFailure {
            cameraUri = null
            Toast.makeText(context, "没有可用的相机：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 面板里「文件」用同一套解析：文本类读成文本，其余提示不支持
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) vm.attachImage(uri) else vm.attachFile(uri)
        }
    }

    // 导出生成的文件：走系统保存对话框，位置和文件名由用户定。
    // 比直接塞 Download 更可靠——工作目录里的文件可能被系统清理搬进回收站。
    var exportGenFile by remember {
        mutableStateOf<com.localllm.server.core.ChatStore.GenFile?>(null)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val gf = exportGenFile
        exportGenFile = null
        if (uri != null && gf != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(gf.content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法写入目标文件")
            }.onSuccess {
                Toast.makeText(
                    context, "已导出 ${gf.path.substringAfterLast('/')}", Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- 语音输入（系统识别器，零依赖）----
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            vm.onInput((input + text).trim())
        }
    }

    fun startSpeech() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说话…")
            }
        runCatching { speechLauncher.launch(intent) }.onFailure {
            Toast.makeText(context, "语音识别不可用（系统未安装语音服务）", Toast.LENGTH_SHORT).show()
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeech()
        else Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    // ---- TTS 朗读 ----
    var tts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val engine = android.speech.tts.TextToSpeech(context) { st ->
            ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS
        }
        tts = engine
        onDispose {
            runCatching {
                engine.stop()
                engine.shutdown()
            }
        }
    }

    // 智能贴底滚动：新消息必到底；流式增长仅当"最后一条消息可见且离其底部<400px"（用户未上翻）时跟随。
    // 跟随用增量 scrollBy(尺寸增量)，不用 scrollToItem 重锚定——重锚定在流式期间会闪动。
    var prevCount by remember { mutableStateOf(-1) }
    var prevLastSize by remember { mutableStateOf(0) }
    val lastLen = messages.lastOrNull()?.let { it.content.length + (it.reasoning?.length ?: 0) } ?: 0
    LaunchedEffect(messages.size, lastLen, streaming) {
        if (messages.isEmpty()) {
            prevCount = -1
            prevLastSize = 0
            return@LaunchedEffect
        }
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        val atLastMsgBottom = lastVisible != null &&
                lastVisible.index == messages.size - 1 &&
                (lastVisible.offset + lastVisible.size) - info.viewportEndOffset <= 400
        val newMessage = messages.size != prevCount
        val sizeDelta = (lastVisible?.size ?: 0) - prevLastSize
        prevCount = messages.size
        prevLastSize = lastVisible?.size ?: 0
        if (newMessage) {
            // 锚定最后一条的底部：先滚到该条，再把超出视口的部分补滚掉
            listState.scrollToItem(messages.size - 1)
            val v = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (v != null) {
                val over = (v.offset + v.size) - listState.layoutInfo.viewportEndOffset
                if (over > 0) listState.scroll { scrollBy(over.toFloat()) }
            }
        } else if (atLastMsgBottom && sizeDelta > 0) {
            // 增量跟随：只滚消息长出来的部分，视口不跳
            listState.scroll { scrollBy(sizeDelta.toFloat()) }
        }
    }

    editingIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingIndex = null
                    vm.editAndResend(idx, editText)
                }) { Text("保存并重发") }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = null }) { Text("取消") }
            }
        )
    }

    // 图片预览（发送前的附件图片）
    previewImageB64?.let { b64 ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { previewImageB64 = null }) {
            val bmp = remember(b64) {
                runCatching {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { previewImageB64 = null }
                )
            }
        }
    }

    // 文件预览（发送前的附件文本；PDF/Office 显示的是已抽取文本）
    if (showFilePreview) {
        AlertDialog(
            onDismissRequest = { showFilePreview = false },
            title = { Text(previewFileName, maxLines = 1) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        previewFileText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilePreview = false }) { Text("关闭") }
            }
        )
    }

    // 阅读视图：独立页面内可拖选复制（不在懒加载列表里，避免渲染问题）
    readMsg?.let { m ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { readMsg = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (m.role == "user") "用户消息" else "助手回复",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(m.content))
                            Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
                        }) { Text("复制全文") }
                        TextButton(onClick = { readMsg = null }) { Text("关闭") }
                    }
                    androidx.compose.material3.HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "长按并拖动可选取任意片段复制",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                m.content,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (!m.reasoning.isNullOrBlank()) {
                            androidx.compose.material3.HorizontalDivider()
                            Text(
                                "🧠 思考过程",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    m.reasoning!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 生成文件预览
    previewGenFile?.let { gf ->
        val lang = gf.path.substringAfterLast('.', "").lowercase()
        AlertDialog(
            onDismissRequest = { previewGenFile = null },
            title = {
                Text(gf.path.substringAfterLast('/'), maxLines = 1)
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        highlightCode(
                            lang, gf.content,
                            keywordColor = MaterialTheme.colorScheme.primary,
                            stringColor = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                            commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            numberColor = androidx.compose.ui.graphics.Color(0xFF6A1B9A)
                        ),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(gf.content))
                    Toast.makeText(context, "已复制文件内容", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        exportGenFile = gf
                        // 带上原文件名（含扩展名），系统对话框会据此预填
                        exportLauncher.launch(gf.path.substringAfterLast('/'))
                    }) { Text("导出") }
                    TextButton(onClick = { previewGenFile = null }) { Text("关闭") }
                }
            }
        )
    }

    // 技能选择 / 管理
    if (showSkills) {
        val cur = sessions.find { it.id == activeId }
        SkillDialog(
            onDismiss = { showSkills = false },
            onApply = { skill ->
                vm.setSessionSystemPrompt(skill.prompt, skillId = skill.id)
                Toast.makeText(context, "已应用技能：${skill.name}", Toast.LENGTH_SHORT).show()
                showSkills = false
            },
            onApplyChain = { chain ->
                // 走执行器：每步独立生成、失败可换方案，不再把各步提示词拼成 system prompt
                vm.setSessionChain(chain.id)
                Toast.makeText(context, "已应用技能链：${chain.name}（逐步执行）", Toast.LENGTH_SHORT).show()
                showSkills = false
            },
            onClear = { vm.setSessionSystemPrompt(null) },
            appliedSkillId = cur?.appliedSkillId,
            appliedChainId = cur?.appliedChainId
        )
    }

    // 本会话助手指令
    if (showSysPrompt) {
        AlertDialog(
            onDismissRequest = { showSysPrompt = false },
            title = { Text("本会话助手指令") },
            text = {
                Column {
                    Text(
                        "留空则使用全局预设；这里的内容只影响本会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CompactFieldMultiline(
                        value = sysPromptText,
                        onValueChange = { sysPromptText = it },
                        label = "系统指令（system prompt）",
                        minHeight = 140
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setSessionSystemPrompt(sysPromptText)
                    showSysPrompt = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.setSessionSystemPrompt(null)
                    showSysPrompt = false
                }) { Text("清除覆盖") }
            }
        )
    }

    // 压缩上下文确认（摘要会替代原文进入后续对话）
    if (showCompressConfirm) {
        AlertDialog(
            onDismissRequest = { showCompressConfirm = false },
            title = { Text("压缩上下文？") },
            text = {
                Text(
                    "将把较早的消息交给模型总结成一条摘要，仅保留最近 4 条原文。" +
                            "摘要在后续对话中替代原文，原消息不再发送给模型（界面里也会被摘要卡取代）。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCompressConfirm = false
                    vm.compressContext()
                }) { Text("压缩") }
            },
            dismissButton = {
                TextButton(onClick = { showCompressConfirm = false }) { Text("取消") }
            }
        )
    }

    // 删除消息确认
    deleteIndex?.let { idx ->
        val target = messages.getOrNull(idx)
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("删除这条消息？") },
            text = {
                Text(
                    (if (target?.role == "user") "用户消息" else "助手回复") +
                            "，共 ${target?.content?.length ?: 0} 字。删除后不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAt(idx)
                    deleteIndex = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteIndex = null }) { Text("取消") } }
        )
    }

    // 图片轮联网搜索确认（策略=先问我）：把即将发给搜索引擎的内容展示给用户
    val searchAsk by vm.searchApproval.collectAsStateWithLifecycle()
    searchAsk?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.approveSearch(false) },
            title = { Text("允许联网搜索？") },
            text = {
                Column {
                    Text(
                        "本轮消息包含图片。以下内容将被发送到搜索引擎（Bing）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(pending, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.approveSearch(true) }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { vm.approveSearch(false) }) { Text("拒绝") }
            }
        )
    }

    // 危险操作确认：命令 / 写文件 / 带写语义的 MCP 工具执行前，把要做的事原样摆给用户看
    val toolAsk by vm.toolAsk.collectAsStateWithLifecycle()
    toolAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { vm.answerToolAsk(0) },
            title = { Text(ask.title) },
            text = {
                Column {
                    Text(
                        ask.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ask.dangerous) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        ask.subjectLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            ask.subject,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                    if (ask.canAlways) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "「总是允许」= 本会话内同类操作不再询问。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (ask.canAlways) {
                        TextButton(onClick = { vm.answerToolAsk(2) }) { Text("总是允许") }
                    }
                    TextButton(onClick = { vm.answerToolAsk(1) }) { Text("允许") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerToolAsk(0) }) {
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // ---- 顶栏：标题 + 渠道快捷切换 + 会话管理 ----
        var channelMenuOpen by remember { mutableStateOf(false) }
        val cloudPresets = remember {
            mutableStateOf(com.localllm.server.core.AppSettings.loadCloudPresets())
        }
        val cloudOn = com.localllm.server.core.AppSettings.chatCloudEnabled
        val activePresetName = if (cloudOn) {
            val curUrl = com.localllm.server.core.AppSettings.chatCloudUrl.trim().removeSuffix("/")
            cloudPresets.value.find { it.url.trim().removeSuffix("/") == curUrl }?.name ?: "自定义"
        } else "本地"
        // 拉取该预设 /v1/models 并落到预设里：电脑端换了模型、云端上新都能自动发现，
        // 不用再去设置里手敲名字（手敲容易和接口对不上，还会一直停在旧名字上）
        val channelScope = androidx.compose.runtime.rememberCoroutineScope()
        fun refreshPresetModels(p: com.localllm.server.core.AppSettings.CloudPreset) {
            channelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ids = try {
                    com.localllm.server.core.OpenAiCompat.fetchModels(p.url, p.key)
                } catch (e: Throwable) {
                    com.localllm.server.core.ServerProcessManager.appLog(
                        "拉取模型列表失败：${p.name} — ${e.message}"
                    )
                    emptyList<String>()
                }
                if (ids.isEmpty()) return@launch
                val updated = com.localllm.server.core.AppSettings.loadCloudPresets().map { pr ->
                    if (pr.id != p.id) pr
                    else {
                        val old = pr.models.associateBy { it.id }
                        pr.copy(models = ids.map { id ->
                            old[id] ?: com.localllm.server.core.AppSettings.CloudModel(id)
                        })
                    }
                }
                com.localllm.server.core.AppSettings.saveCloudPresets(updated)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    cloudPresets.value = updated
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                // 渠道快捷切换
                TextButton(onClick = {
                    cloudPresets.value = com.localllm.server.core.AppSettings.loadCloudPresets()
                    channelMenuOpen = true
                    // 打开菜单时顺手刷新当前渠道的模型列表（PC 换了模型也能发现）
                    val curUrl = com.localllm.server.core.AppSettings.chatCloudUrl.trim().removeSuffix("/")
                    cloudPresets.value.firstOrNull { it.url.trim().removeSuffix("/") == curUrl }
                        ?.let { refreshPresetModels(it) }
                }) {
                    Text(
                        activePresetName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (cloudOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = channelMenuOpen,
                    onDismissRequest = { channelMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("本地引擎") },
                        onClick = {
                            com.localllm.server.core.AppSettings.chatCloudEnabled = false
                            channelMenuOpen = false
                        }
                    )
                    cloudPresets.value.forEach { p ->
                        val isActive = cloudOn && p.url.trim().removeSuffix("/") ==
                                com.localllm.server.core.AppSettings.chatCloudUrl.trim().removeSuffix("/")
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        p.name + if (isActive) "（当前）" else "",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        p.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            },
                            onClick = {
                                com.localllm.server.core.AppSettings.activateCloudPreset(p)
                                channelMenuOpen = false
                            }
                        )
                        // 该渠道下的模型直接列出来选，完整名字换行显示，不用点进输入框看
                        p.models.forEach { m ->
                            val picked = isActive && p.model == m.id
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            (if (picked) "✓ " else "　 ") + m.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2
                                        )
                                        if (m.label.isNotBlank()) {
                                            Text(
                                                m.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    com.localllm.server.core.AppSettings.activateCloudPreset(
                                        p.copy(model = m.id)
                                    )
                                    channelMenuOpen = false
                                }
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("刷新模型列表") },
                        onClick = {
                            cloudPresets.value.forEach { refreshPresetModels(it) }
                        }
                    )
                }
            }
            Row {
                var searchOn by remember {
                    mutableStateOf(com.localllm.server.core.AppSettings.webSearchEnabled)
                }
                IconButton(onClick = {
                    searchOn = !searchOn
                    com.localllm.server.core.AppSettings.webSearchEnabled = searchOn
                }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "联网搜索",
                        tint = if (searchOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 功能整合入口：技能 / 本会话指令 / 压缩上下文
                var toolsMenuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { toolsMenuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多工具",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = toolsMenuOpen,
                        onDismissRequest = { toolsMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("技能…") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                            onClick = {
                                toolsMenuOpen = false
                                showSkills = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("本会话助手指令…") },
                            leadingIcon = { Icon(Icons.Default.Psychology, null) },
                            onClick = {
                                toolsMenuOpen = false
                                sysPromptText = vm.currentSessionSystemPrompt()
                                    ?: com.localllm.server.core.AppSettings.activeSysPrompt()
                                showSysPrompt = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("压缩上下文…") },
                            leadingIcon = { Icon(Icons.Default.Compress, null) },
                            onClick = {
                                toolsMenuOpen = false
                                showCompressConfirm = true
                            }
                        )
                    }
                }
                BadgedBox(
                    badge = {
                        if (generating.isNotEmpty()) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary)
                        }
                    }
                ) {
                    IconButton(onClick = { showSessions = true }) {
                        Icon(Icons.Default.List, contentDescription = "会话列表")
                    }
                }
                IconButton(onClick = vm::newChat, enabled = generating.isEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "新会话")
                }
                // 会话详情：轨迹 / 改动 / 文件 / 进度 / 上下文
                IconButton(onClick = { showDetail = true }) {
                    Icon(Icons.Default.Info, contentDescription = "会话详情")
                }
            }
        }

        // 全局运行中指示：别的会话在生成时，常驻一条可点击的提示（点一下切过去）
        val bgSession = generating.firstOrNull { it != activeId }
        if (bgSession != null) {
            val bgTitle = sessions.find { it.id == bgSession }?.title ?: "另一条会话"
            val bgProgress = progressBySession[bgSession].orEmpty()
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clickable { vm.switchSession(bgSession) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "「$bgTitle」正在生成",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                        if (bgProgress.isNotBlank()) {
                            Text(
                                bgProgress,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                    Text(
                        "切过去 ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (showSessions) {
            // 分组可收起：会话一多，未分组那块会把正在用的挤到看不见
            var collapsed by remember { mutableStateOf(setOf<String>()) }
            AlertDialog(
                onDismissRequest = { showSessions = false },
                title = { Text("会话管理") },
                text = {
                    val groupedSessions = sessions
                        .sortedByDescending { it.updatedAt }
                        .groupBy { it.folder.orEmpty() }
                    // 一个分组都没有时不要空顶一行「未分组」，那只是噪音
                    val showHeaders = groupedSessions.keys.any { it.isNotEmpty() }
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        groupedSessions.forEach { (folderName, list) ->
                            if (showHeaders) {
                                item(key = "folder:" + folderName) {
                                    val isCollapsed = folderName in collapsed
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                collapsed = if (isCollapsed) collapsed - folderName
                                                else collapsed + folderName
                                            }
                                            .padding(top = 10.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            (if (folderName.isEmpty()) "未分组" else "📁 " + folderName) +
                                                    " · ${list.size}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (isCollapsed) "展开" else "收起",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            if (folderName in collapsed) return@forEach
                            items(list) { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.switchSession(s.id)
                                        showSessions = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = s.id == activeId, onClick = {
                                    vm.switchSession(s.id)
                                    showSessions = false
                                })
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (s.id in generating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            s.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                    Text(
                                        if (s.id in generating)
                                            "生成中… · ${progressBySession[s.id].orEmpty().ifBlank { "${s.messages.size} 条" }}"
                                        else "${s.messages.size} 条 · " +
                                                java.text.SimpleDateFormat("MM-dd HH:mm")
                                                    .format(java.util.Date(s.updatedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (s.id in generating) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (s.id in generating) FontWeight.Medium
                                        else FontWeight.Normal
                                    )
                                    // 这条会话走哪个通道：多会话并行时各绑各的（本地/云端/电脑上的模型）
                                    Text(
                                        "端点：" + sessionEndpointLabel(s) + " ›",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { endpointEditing = s.id }
                                            .padding(top = 2.dp)
                                    )
                                }
                                IconButton(onClick = { folderEditing = s.id }) {
                                    Icon(Icons.Default.Folder, contentDescription = "文件夹",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { vm.deleteSession(s.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSessions = false }) { Text("关闭") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.newChat()
                        showSessions = false
                    }) { Text("新会话") }
                }
            )
        }
        // 文件夹（工作区）：给会话分组，空 = 未分组
        folderEditing?.let { sid ->
            val cur = sessions.find { it.id == sid }
            var name by remember(sid) { mutableStateOf(cur?.folder ?: "") }
            // 已有分组直接点选：手打名字打错一个字符就多出一个分组，会话散在两处
            val existing = sessions.mapNotNull { it.folder }
                .filter { it.isNotBlank() }.distinct().sorted()
            AlertDialog(
                onDismissRequest = { folderEditing = null },
                title = { Text("文件夹") },
                text = {
                    Column {
                        if (existing.isNotEmpty()) {
                            Text(
                                "已有文件夹",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                existing.forEach { f ->
                                    FilterChip(
                                        selected = name.trim() == f,
                                        onClick = { name = f },
                                        label = { Text(f) }
                                    )
                                }
                                FilterChip(
                                    selected = name.isBlank(),
                                    onClick = { name = "" },
                                    label = { Text("未分组") }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("文件夹名（留空 = 未分组）") },
                            singleLine = true
                        )
                        if (name.isNotBlank() && name.trim() !in existing) {
                            Text(
                                "保存后新建文件夹「${name.trim()}」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Text(
                            "文件夹只用来归拢会话列表，不影响会话内容，也不改工作目录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.setSessionFolder(sid, name)
                        folderEditing = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { folderEditing = null }) { Text("取消") }
                }
            )
        }
        // 会话端点选择：多会话可以各走各的通道（本地 / 云端渠道 / 电脑上跑的模型）
        endpointEditing?.let { sid ->
            val cur = sessions.find { it.id == sid }
            val presets = remember { com.localllm.server.core.AppSettings.loadCloudPresets() }
            val globalLabel = if (
                com.localllm.server.core.AppSettings.chatCloudEnabled &&
                com.localllm.server.core.AppSettings.chatCloudUrl.isNotBlank()
            ) {
                "云端 " + com.localllm.server.core.AppSettings.chatCloudModel.ifBlank { "default" }
            } else {
                "本地服务"
            }
            AlertDialog(
                onDismissRequest = { endpointEditing = null },
                title = { Text("本会话端点") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "只影响这条会话之后的发送，其他会话不受影响。" +
                                    "电脑上跑的模型也在「设置 → 云端」里加一条 OpenAI 兼容地址即可。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        EndpointOption(
                            selected = cur?.endpoint == null,
                            title = "跟随全局（$globalLabel）",
                            onClick = {
                                vm.setSessionEndpoint(sid, null)
                                endpointEditing = null
                            }
                        )
                        EndpointOption(
                            selected = cur?.endpoint?.local == true,
                            title = "本地服务（本机 llama-server）",
                            onClick = {
                                vm.setSessionEndpoint(sid, ChatStore.Endpoint(local = true))
                                endpointEditing = null
                            }
                        )
                        presets.forEach { p ->
                            val selected = cur?.endpoint?.let { !it.local && it.baseUrl == p.url } == true
                            EndpointOption(
                                selected = selected,
                                title = "云端 · " + p.name.ifBlank { p.url } +
                                        "（" + p.model.ifBlank { "默认模型" } + "）",
                                onClick = {
                                    vm.setSessionEndpoint(
                                        sid,
                                        ChatStore.Endpoint(
                                            local = false,
                                            baseUrl = p.url,
                                            model = p.model,
                                            apiKey = p.key
                                        )
                                    )
                                    endpointEditing = null
                                }
                            )
                        }
                        if (presets.isEmpty()) {
                            Text(
                                "还没有云端渠道：去「设置 → 云端」添加一条（地址填 OpenAI 兼容的端点）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { endpointEditing = null }) { Text("关闭") }
                }
            )
        }
        if (modelTitle.isNotBlank()) {
            Text(
                "模型：$modelTitle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                // 兜底：错误文本可能夹着引擎吐回的原始响应体，别让它把排版拖死
                maxLines = 6,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "服务运行中即可直接对话，无需额外配置。\n长按/⋮ 菜单支持复制、重发、编辑、删除；代码块可一键复制或保存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 48.dp, start = 8.dp, end = 8.dp)
                    )
                }
            }
            itemsIndexed(messages) { i, msg ->
                if (msg.role == "system") {
                    SummaryCard(msg.content)
                    return@itemsIndexed
                }
                val isUser = msg.role == "user"
                val waiting = !isUser && streaming && msg.content.isEmpty() && msg.reasoning.isNullOrEmpty()
                // 思考进行中（有思考文本、正文还没开始）：思考卡显示动态"思考中"
                val thinking = !isUser && streaming && msg.content.isEmpty() && !msg.reasoning.isNullOrEmpty()
                // 会话页字号：直接给一套更大的字形，而不是缩放——缩放会让行高与字重脱节
                androidx.compose.material3.MaterialTheme(
                    typography = chatTypography(androidx.compose.material3.MaterialTheme.typography)
                ) {
                    MessageBubble(
                        msg = msg,
                        isUser = isUser,
                        waiting = waiting,
                        thinking = thinking,
                        generating = streaming && i == messages.lastIndex && !isUser,
                        showStats = (!isUser && i == messages.lastIndex && lastStats != null),
                        stats = lastStats ?: "",
                        onResumeTask = { vm.resumeStoppedTask(i) },
                        showResume = i == lastStoppedIdx,
                        menuOpen = menuIndex == i,
                        onMenuOpen = { menuIndex = i },
                        onMenuClose = { menuIndex = null },
                        onCopy = {
                            clipboard.setText(AnnotatedString(msg.content))
                            menuIndex = null
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        onResend = {
                            menuIndex = null
                            if (isUser) vm.resendAt(i)
                        },
                        onEdit = {
                            menuIndex = null
                            if (isUser) {
                                editText = msg.content
                                editingIndex = i
                            }
                        },
                        onDelete = {
                            menuIndex = null
                            deleteIndex = i
                        },
                        onOpenGenFile = { gf -> previewGenFile = gf },
                        onUndoFile = { path ->
                            val msg = vm.undoFileWrite(activeId, path)
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT)
                                .show()
                        },
                        onSaveSkill = {
                            val msg = vm.saveFlowAsSkill(activeId, i)
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT)
                                .show()
                        },
                        onReadText = { readMsg = msg },
                        onSpeak = {
                            val engine = tts
                            if (engine == null || !ttsReady) {
                                Toast.makeText(context, "朗读引擎未就绪", Toast.LENGTH_SHORT).show()
                            } else if (engine.isSpeaking) {
                                engine.stop()
                            } else {
                                engine.language = java.util.Locale.CHINA
                                engine.speak(
                                    msg.content.take(4000),
                                    android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "msg_$i"
                                )
                            }
                        }
                    )
                }
            }
        }

        // 当前生效的技能 / 技能链 / 手写指令：优先用会话里记录的结构化来源；
        // 老会话（升级前只存了 prompt 文本）回退到按文本反查技能链、技能。
        val activeSetup = remember(sessions, activeId) {
            val s = sessions.find { it.id == activeId } ?: return@remember null
            val skills = AppSettings.loadSkills()
            val chains = AppSettings.loadSkillChains()

            fun chainSetup(c: com.localllm.server.core.SkillChain) = ActiveSetup(
                "⛓ 技能链：" + c.name,
                c.skillIds.mapNotNull { cid -> skills.firstOrNull { it.id == cid }?.name }
                    .joinToString(" → ").ifBlank { "步骤缺失，建议重新选择" }
            )

            fun skillSetup(sk: com.localllm.server.core.Skill) =
                ActiveSetup("⚡ 技能：" + sk.name, sk.desc)

            // 技能链由执行器逐步驱动，不再往 systemPrompt 里写提示词，
            // 所以结构化来源必须先查——否则 systemPrompt 为空就把横幅整个挡掉了
            s.appliedChainId?.let { id -> chains.firstOrNull { it.id == id } }
                ?.let { return@remember chainSetup(it) }
            s.appliedSkillId?.let { id -> skills.firstOrNull { it.id == id } }
                ?.let { return@remember skillSetup(it) }

            val cur = s.systemPrompt ?: return@remember null
            skills.firstOrNull { it.prompt == cur }?.let { return@remember skillSetup(it) }
            chains.firstOrNull { AppSettings.chainPrompt(it) == cur }?.let { return@remember chainSetup(it) }
            ActiveSetup("📝 本会话专用指令", "${cur.length} 字，可在此取消")
        }
        activeSetup?.let { a ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            a.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (a.sub.isNotBlank()) {
                            Text(
                                a.sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                maxLines = 1
                            )
                        }
                    }
                    TextButton(onClick = { vm.setSessionSystemPrompt(null) }) { Text("取消") }
                }
            }
        }
        pendingFile?.let { f ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📄 已附带文件：${f.first}（${f.second.length} 字）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            previewFileName = f.first
                            previewFileText = f.second
                            showFilePreview = true
                        }
                )
                TextButton(onClick = {
                    previewFileName = f.first
                    previewFileText = f.second
                    showFilePreview = true
                }) { Text("预览") }
                TextButton(onClick = vm::clearPendingFile) { Text("移除") }
            }
        }
        if (pendingImage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🖼 已附带图片 · " + (imageNote ?: "已处理"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { previewImageB64 = pendingImage }) { Text("预览") }
                TextButton(onClick = vm::clearPendingImage) { Text("移除") }
            }
        }

        // 运行预览摘要行：一行，紧贴输入框上方，点开是画中画弹窗
        SandboxPanel()

        // 排队中的指令：本会话在生成时发来的下一条，跑完自动开始
        val myQueue = queue.filter { it.sid == activeId }
        if (myQueue.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "排队 ${myQueue.size} 条 · 下一条：" + myQueue.first().text.take(24),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "清空",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.clearQueue() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        // 计划模式：模型给了计划之后，这里一键退出计划模式并让它照着做
        val planModeOn = sessions.find { it.id == activeId }?.planMode == true
        val lastAssistant = messages.lastOrNull()
        if (planModeOn && !streaming && lastAssistant?.role == "assistant" &&
            !lastAssistant.content.isNullOrBlank()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable {
                        vm.setSessionPlanMode(activeId, false)
                        vm.onInput("按上面的计划执行")
                        vm.send()
                    }
                ) {
                    Text(
                        "按计划执行",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 输入区：一个撑满宽度的胶囊输入框，按钮都嵌在框内（左：加号，右：语音/发送自动切换）。
        // 语音和发送占同一个位置，框里有内容就变发送——两个按钮常驻会把输入宽度吃掉。
        val canSend = input.isNotBlank() || pendingImage != null || pendingFile != null
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = { showAttachSheet = true },
                    enabled = !streaming,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "添加附件",
                        tint = if (streaming) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.isEmpty()) {
                        Text(
                            "给 混沌智核 发消息…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = vm::onInput,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 4,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.primary
                        )
                    )
                }
                // 圆环：只在有实际用量时出现（空会话/刚切过来都没有数据）
                usage?.let { u ->
                    val limit = contextUsage?.second ?: 0
                    if (limit > 0) {
                        val pct = ((u.prompt + u.output) * 100L / limit).toInt().coerceIn(0, 100)
                        UsageRing(pct = pct, onClick = { showUsageSheet = true })
                    }
                }
                when {
                    streaming -> IconButton(
                        onClick = vm::stop,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    canSend -> IconButton(
                        onClick = vm::send,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    else -> IconButton(
                        onClick = {
                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) startSpeech()
                            else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "语音输入",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 底部抽屉：相册 / 拍照 / 文件 + 本对话（权限、计划模式）
        if (showAttachSheet) {
            val cur = sessions.find { it.id == activeId }
            AttachSheet(
                permissionLabel = permissionLabel(cur?.permission ?: com.localllm.server.core.AppSettings.defaultPermission),
                planMode = cur?.planMode == true,
                autoDrive = cur?.autoDrive == true,
                planWithCloud = cur?.planWithCloud == true,
                onDismiss = { showAttachSheet = false },
                onGallery = {
                    showAttachSheet = false
                    pickFromGallery.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onCamera = {
                    showAttachSheet = false
                    launchCamera()
                },
                onFile = {
                    showAttachSheet = false
                    pickFile.launch(
                        arrayOf(
                            "text/*",
                            "application/json", "application/xml", "application/javascript",
                            "application/x-yaml", "application/csv",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                    )
                },
                onPermission = {
                    showAttachSheet = false
                    permissionEditing = true
                },
                onPlanMode = { on -> vm.setSessionPlanMode(activeId, on) },
                onAutoDrive = { on -> vm.setSessionAutoDrive(activeId, on) },
                onPlanWithCloud = { on -> vm.setSessionPlanWithCloud(activeId, on) }
            )
        }

        // 权限档位：只影响这条会话之后的行为
        if (permissionEditing) {
            val curLevel = sessions.find { it.id == activeId }?.permission
                ?: com.localllm.server.core.AppSettings.defaultPermission
            AlertDialog(
                onDismissRequest = { permissionEditing = false },
                title = { Text("权限") },
                text = {
                    Column {
                        Text(
                            "执行命令、修改文件前要不要先问你。只影响这条会话。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionOption(
                            title = "仅可查看",
                            desc = "只看文件，不写不改、不跑命令",
                            selected = curLevel == "read"
                        ) {
                            vm.setSessionPermission(activeId, "read")
                            permissionEditing = false
                        }
                        PermissionOption(
                            title = "工作区内修改",
                            desc = "可以改文件、跑命令，但每次动手前先问你",
                            selected = curLevel == "workspace"
                        ) {
                            vm.setSessionPermission(activeId, "workspace")
                            permissionEditing = false
                        }
                        PermissionOption(
                            title = "完全权限",
                            desc = "不再确认，直接执行（危险操作也不问）",
                            selected = curLevel == "full"
                        ) {
                            vm.setSessionPermission(activeId, "full")
                            permissionEditing = false
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { permissionEditing = false }) { Text("关闭") }
                }
            )
        }

        // 会话详情（轨迹 / 改动 / 文件 / 进度 / 上下文）
        if (showDetail) {
            val curSession = sessions.find { it.id == activeId }
            if (curSession != null) {
                SessionDetailDialog(
                    session = curSession,
                    usage = usage,
                    contextLimit = contextUsage?.second ?: 0,
                    onDismiss = { showDetail = false },
                    onOpenGenFile = { gf -> previewGenFile = gf }
                )
            }
        }

        // 用量面板：输入框右边圆环点开
        val curUsage = usage
        if (showUsageSheet && curUsage != null) {
            UsageSheet(
                usage = curUsage,
                limit = contextUsage?.second ?: 0,
                onDismiss = { showUsageSheet = false }
            )
        }
    }
}

/**
 * 输入区「+」打开的底部抽屉：相册 / 拍照 / 文件，下面是「本对话」的权限与计划模式。
 * 常驻按钮只留一个加号——相册、文件、语音挤在同一行时，输入框会被压得很窄。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    permissionLabel: String,
    planMode: Boolean,
    autoDrive: Boolean,
    planWithCloud: Boolean,
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onPermission: () -> Unit,
    onPlanMode: (Boolean) -> Unit,
    onAutoDrive: (Boolean) -> Unit,
    onPlanWithCloud: (Boolean) -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetAction(
                    label = "相册",
                    icon = Icons.Default.Image,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onGallery
                )
                SheetAction(
                    label = "拍照",
                    icon = Icons.Default.PhotoCamera,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = onCamera
                )
                SheetAction(
                    label = "文件",
                    icon = Icons.Default.Folder,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                    onClick = onFile
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "本对话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SheetRow(
                        title = "权限",
                        value = permissionLabel + " ›",
                        onClick = onPermission
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlanMode(!planMode) }
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("计划模式", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (planMode) "只读、先给计划，你点头再动手" else "关闭：照常执行",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = planMode,
                            onCheckedChange = onPlanMode
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAutoDrive(!autoDrive) }
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自驱", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (autoDrive) "没做完就自己接着做，最多 5 次" else "关闭：模型说要收尾就收尾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = autoDrive,
                            onCheckedChange = onAutoDrive
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlanWithCloud(!planWithCloud) }
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("规划用云端", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (planWithCloud) "云端只出计划，文件和命令仍在本地执行"
                                else "关闭：全部由本地模型完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = planWithCloud,
                            onCheckedChange = onPlanWithCloud
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SheetRow(title: String, value: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = if (onClick == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary
        )
        if (onClick != null) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 输入框右侧的上下文圆环：环长 = 占用比例，中间一位到两位百分比。
 * 没有用量（空会话）时调用方不会渲染它。
 */
/** 行级增删统计：新建文件 = 全是新增；有旧内容时按「同样的行先抵消」算差集 */
private fun diffStat(prev: String?, cur: String): Pair<Int, Int> {
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
private fun diffLines(prev: String, cur: String, limit: Int = 700): List<Pair<Char, String>> {
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

@Composable
private fun DiffDialog(gf: ChatStore.GenFile, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val lines = remember(gf.path, gf.content, gf.prev) {
        if (gf.prev == null) emptyList() else diffLines(gf.prev, gf.content)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    gf.path.substringAfterLast('/').ifBlank { gf.path },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    gf.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (gf.prev == null) {
                    Text(
                        "这是新建的文件，共 ${gf.content.lineSequence().count()} 行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (lines.isEmpty()) {
                    Text("内容没有变化。", style = MaterialTheme.typography.bodySmall)
                } else {
                    val box = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(box)
                    ) {
                        lines.forEach { (sign, text) ->
                            Text(
                                "$sign $text",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = when (sign) {
                                    '+' -> MaterialTheme.colorScheme.primary
                                    '-' -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onOpen) { Text("打开文件") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/** 本会话里最后一次产出的文件路径（产物卡片「定位」用） */
@Composable
private fun UsageRing(pct: Int, onClick: () -> Unit) {
    val ring = when {
        pct >= 85 -> MaterialTheme.colorScheme.error
        pct >= 60 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(30.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke
            )
            drawArc(
                color = ring,
                startAngle = -90f,
                sweepAngle = 360f * (pct.coerceIn(0, 100) / 100f),
                useCenter = false,
                style = stroke
            )
        }
        Text(
            "$pct",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** token 数：过万折成「1.5万」，跟 DSH 的读法一致 */
internal fun compactTokens(n: Int): String =
    if (n >= 10000) String.format(java.util.Locale.CHINA, "%.1f万", n / 10000.0) else n.toString()

/**
 * 点圆环打开的「用量」面板：上下文明细 + 本会话用量。
 * 只做这两块——主项目没有余额/计费这回事。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UsageSheet(
    usage: com.localllm.server.ui.ChatViewModel.Usage,
    limit: Int,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("用量", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(18.dp))

            Text(
                "上下文",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val usedTotal = usage.prompt + usage.output
            val pct = if (limit > 0) (usedTotal * 100L / limit).toInt().coerceIn(0, 100) else 0
            UsageCard {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${compactTokens(usedTotal)} / ${compactTokens(limit)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = when {
                        pct >= 85 -> MaterialTheme.colorScheme.error
                        pct >= 60 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "系统 ${String.format(java.util.Locale.CHINA, "%,d", usage.systemTokens)} · " +
                            "工具 ${String.format(java.util.Locale.CHINA, "%,d", usage.toolTokens)} · " +
                            "消息 ${String.format(java.util.Locale.CHINA, "%,d", usage.messageTokens)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "本对话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            UsageCard {
                Row {
                    UsageStat(compactTokens(usage.prompt), "输入", Modifier.weight(1f))
                    UsageStat(compactTokens(usage.output), "输出", Modifier.weight(1f))
                    UsageStat(
                        if (usage.prompt > 0) "${usage.cacheHit * 100 / usage.prompt}%" else "—",
                        "缓存命中",
                        Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "共 ${usage.rounds} 轮 · 输入含缓存命中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UsageCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun UsageStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// 一体时间线的一行（DSH 样式）：思考/中间正文/搜索/工具按原始顺序渲染，每行独立可折叠。// think：折叠显示预览，展开显示完整思考（限高滚动盒，长按可复制）；
// text（中间正文）：默认展开显示迷你 markdown，可折叠；
// tool/search：折叠显示参数/结果预览，展开成「命令|参数」+「输出」两块。
/** 权限档位 → 面板上显示的名字 */
private fun permissionLabel(level: String): String = when (level) {
    "read" -> "仅可查看"
    "full" -> "完全权限"
    else -> "工作区内修改"
}

@Composable
private fun PermissionOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 会话页专用的字形表：只在消息区生效，不动设置页和顶栏。
 * 行高比字号大约 1.5 倍——手机上看长段落，行距不够会明显发闷。
 */
private fun chatTypography(base: androidx.compose.material3.Typography): androidx.compose.material3.Typography =
    base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 25.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp)
    )

/** 工具行怎么显示：图标、参数块的标签、动词。按 label 前缀判（时间线落盘的就是这些中文前缀）。 */
private data class ToolStyle(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val argTitle: String,
)

private fun toolStyle(label: String): ToolStyle = when {
    label.startsWith("执行命令") || label.startsWith("运行 ") -> ToolStyle(Icons.Default.PlayArrow, "命令")
    label.startsWith("写入") -> ToolStyle(Icons.Default.Edit, "路径")
    label.startsWith("编辑") -> ToolStyle(Icons.Default.Edit, "路径")
    label.startsWith("读取") -> ToolStyle(Icons.Default.Description, "路径")
    label.startsWith("列出") -> ToolStyle(Icons.Default.List, "目录")
    label.startsWith("搜索文件") -> ToolStyle(Icons.Default.Search, "模式")
    label.startsWith("生成文档") -> ToolStyle(Icons.Default.InsertDriveFile, "文件")
    label.startsWith("运行自检") -> ToolStyle(Icons.Default.Language, "页面")
    label.startsWith("记住") -> ToolStyle(Icons.Default.Save, "内容")
    label.startsWith("调用工具") || label.startsWith("自动修正") ->
        ToolStyle(Icons.Default.Extension, "参数")
    label.startsWith("已拒绝") -> ToolStyle(Icons.Default.Block, "内容")
    label.startsWith("文件：") -> ToolStyle(Icons.Default.Edit, "参数")
    else -> ToolStyle(Icons.Default.PlayArrow, "参数")
}

/** 从步骤参数里取被操作的文件路径（参数可能是截断的 JSON，path 总在最前面） */
private fun pathOfStep(args: String?): String? = args?.let {
    Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)
}

@Composable
internal fun StepRow(
    st: ChatStore.Step,
    thinkBody: String? = null,
    liveThink: Boolean = false,
    onOpenFile: ((String) -> Unit)? = null,
) {
    // 用 type+label 当记忆键：流式更新 detail 时展开状态不重置
    var open by remember(st.type + st.label) { mutableStateOf(st.type == "text") }
    val thinkBodyText = if (st.type == "think") (thinkBody ?: st.detail) else null
    val toolArgs = if (st.type == "tool" || st.type == "search") st.args else null
    val toolDetail = if (st.type == "tool" || st.type == "search") st.detail else null
    val hasBody = st.type == "text" ||
            liveThink ||
            !thinkBodyText.isNullOrBlank() ||
            toolArgs != null || toolDetail != null
    // 每类步骤一个颜色：光靠灰色线条图标，整条时间线看起来像日志，认不出「哪几行是一次操作」
    val stepTint = when {
        !st.ok -> MaterialTheme.colorScheme.error
        st.type == "think" -> MaterialTheme.colorScheme.tertiary
        st.type == "search" -> MaterialTheme.colorScheme.primary
        st.label.startsWith("写入") || st.label.startsWith("编辑") -> MaterialTheme.colorScheme.secondary
        st.label.startsWith("读取") || st.label.startsWith("列出") -> MaterialTheme.colorScheme.primary
        st.label.startsWith("运行") || st.label.startsWith("执行命令") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.Top,
        // 整行都能点：展开/折叠自由切换，不必对准右边那个小箭头。
        // 行内的「打开文件」自己有 clickable，子节点优先，不会被这里拦掉。
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasBody) Modifier.clickable { open = !open } else Modifier)
    ) {
        // 正文是「说给用户的话」，不占图标位——让它比时间线更靠左，层级才分得开
        if (st.type != "text") {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(stepTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (st.type) {
                        "think" -> Icons.Filled.Psychology
                        "search" -> Icons.Filled.Search
                        else -> toolStyle(st.label).icon
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = stepTint
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            when (st.type) {
                "text" -> {
                    // 中间正文：默认展开（迷你 markdown），可折叠成预览
                    val textBody = st.detail ?: ""
                    if (open) {
                        parseSegments(textBody).forEach { seg ->
                            when (seg) {
                                is Seg.Text -> if (seg.s.isNotBlank()) {
                                    Text(
                                        inlineMd(seg.s),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                                is Seg.Heading -> Text(
                                    inlineMd(seg.s),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 1.dp)
                                )
                                is Seg.ListBlock -> Column(
                                    verticalArrangement = Arrangement.spacedBy(1.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    seg.lines.forEach { l ->
                                        Text(inlineMd(l), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                is Seg.Quote -> Text(
                                    inlineMd(seg.s),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                                is Seg.Table -> MarkdownTable(seg.rows, modifier = Modifier.padding(vertical = 2.dp))
                                is Seg.Code -> CodeBlock(seg.lang, seg.s)
                            }
                        }
                    } else {
                        Text(
                            if (textBody.length > 60) textBody.take(60) + "…" else textBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    if (st.type == "think") {
                        if (liveThink) {
                            // 思考中：转圈 + 实时段预览（DSH ThinkingPill）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "思考中",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Text(
                                st.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (st.ok) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.error
                            )
                        }
                        if (!thinkBodyText.isNullOrBlank() || liveThink) {
                            if (open) {
                                // 固定盒子：思考只能在盒内滚动（限高260dp），正文是全局页面。
                                // 包 SelectionContainer：长按可选中复制思考全文。
                                val scroll = rememberScrollState()
                                if (liveThink) {
                                    // 流式期间展开：自动跟随最新
                                    var jumped by remember { mutableStateOf(false) }
                                    LaunchedEffect(thinkBodyText?.length ?: 0) {
                                        val max = scroll.maxValue
                                        if (max <= 0) return@LaunchedEffect
                                        if (!jumped) {
                                            scroll.scrollTo(max)
                                            jumped = true
                                        } else if (scroll.value >= max - 80) {
                                            scroll.scrollTo(max)
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp)
                                        .verticalScroll(scroll)
                                ) {
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        Text(
                                            latexToUnicode(thinkBodyText ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                // 折叠预览：进行中取最近段首行、完成取全文首行（DSH 规则）
                                val preview = ChatStore.thinkingPreview(thinkBodyText ?: "", active = liveThink)
                                if (preview.isNotEmpty()) {
                                    Text(
                                        preview.take(40) + if (preview.length > 40) "…" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { open = true }
                                    )
                                }
                            }
                        }
                    } else {
                        // 工具/搜索步：展开成「命令|参数」+「输出」两块
                        if (toolArgs != null || toolDetail != null) {
                            if (open) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    toolArgs?.let {
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                toolStyle(st.label).argTitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(40.dp)
                                            )
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    toolDetail?.let {
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                "输出",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(40.dp)
                                            )
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (st.ok) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.error,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    val stepPath = pathOfStep(toolArgs)
                                    if (stepPath != null && st.ok && onOpenFile != null) {
                                        Text(
                                            "打开文件 ›",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable { onOpenFile(stepPath) }
                                                .padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            } else {
                                val collapsed = when {
                                    toolArgs != null ->
                                        toolStyle(st.label).argTitle + " " + toolArgs.take(36)
                                    else -> toolDetail!!.take(36)
                                }
                                Text(
                                    collapsed + "…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!st.ok) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
        // 逐行折叠箭头（DSH 每行右侧 ^/v）
        if (hasBody) {
            Text(
                if (open) "▾" else "▸",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { open = !open }
            )
        }
    }
}

// 思考行已并入 StepRow（liveThink）：思考开始即占位，边想边更新，结束就地改标签

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatStore.Msg,
    isUser: Boolean,
    waiting: Boolean,
    thinking: Boolean = false,
    generating: Boolean = false,
    showStats: Boolean,
    stats: String,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuClose: () -> Unit,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSpeak: () -> Unit = {},
    onOpenGenFile: (com.localllm.server.core.ChatStore.GenFile) -> Unit = {},
    onUndoFile: ((String) -> Unit)? = null,
    onSaveSkill: (() -> Unit)? = null,
    onReadText: () -> Unit = {},
    onResumeTask: () -> Unit = {},
    showResume: Boolean = false
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.88f else 1f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(msg.content))
                        Toast.makeText(context, "已复制整条消息", Toast.LENGTH_SHORT).show()
                    }
                )
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        // 生成的文件卡片（file_write 产生）：聊天内可见 + 预览
                        if (!isUser && msg.genFiles.isNotEmpty()) {
                            val fileCtx = androidx.compose.ui.platform.LocalContext.current
                            var diffTarget by remember(msg.genFiles.size) {
                                mutableStateOf<ChatStore.GenFile?>(null)
                            }
                            val changed = msg.genFiles.count { it.prev != null }
                            val created = msg.genFiles.size - changed
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    buildString {
                                        if (changed > 0) append("改动了 $changed 个文件")
                                        if (changed > 0 && created > 0) append("、")
                                        if (created > 0) append("生成了 $created 个文件")
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                msg.genFiles.forEach { gf ->
                                    val stat = remember(gf.path, gf.content, gf.prev) {
                                        diffStat(gf.prev, gf.content)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (!com.localllm.server.core.FileOpener
                                                        .open(fileCtx, gf.path)
                                                ) {
                                                    onOpenGenFile(gf)
                                                }
                                            }
                                            .padding(vertical = 6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Description,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                gf.path.substringAfterLast('/').ifBlank { gf.path },
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                gf.path,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        if (stat.first > 0) {
                                            Text(
                                                "+${stat.first}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        if (stat.second > 0) {
                                            Text(
                                                "-${stat.second}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        onUndoFile?.let { undo ->
                                            Text(
                                                "撤销",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .clickable { undo(gf.path) }
                                                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "查看改动 ›",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable {
                                                diffTarget = msg.genFiles.lastOrNull { it.prev != null }
                                                    ?: msg.genFiles.last()
                                            }
                                            .padding(vertical = 4.dp)
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        "定位",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable {
                                                val lastPath = msg.genFiles.last().path
                                                if (!com.localllm.server.core.FileOpener
                                                        .revealInFolder(fileCtx, lastPath)
                                                ) {
                                                    android.widget.Toast.makeText(
                                                        fileCtx,
                                                        "文件管理器不支持打开文件夹，路径：$lastPath",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                            diffTarget?.let { gf ->
                                DiffDialog(
                                    gf = gf,
                                    onDismiss = { diffTarget = null },
                                    onOpen = { onOpenGenFile(gf) }
                                )
                            }
                        }
                        // 跑通的多步流程：可以就地沉淀成技能，下次遇到类似目标直接套用
                        val flowSteps = msg.steps.count { it.type == "tool" || it.type == "search" }
                        if (!isUser && !generating && onSaveSkill != null && flowSteps >= 3 &&
                            msg.steps.all { it.ok } && msg.task?.stopped != true
                        ) {
                            Text(
                                "把这 $flowSteps 步存为技能",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { onSaveSkill() }
                                    .padding(vertical = 4.dp)
                            )
                        }
                        // 一体式过程时间线（DSH / MiMo Desktop 风格）：思考、搜索、工具调用
                        // 按原始顺序一行行交错串在一起，整块可折叠成一行摘要，正文在下面。
                        val rawReasoning = msg.reasoning?.takeIf { it.isNotBlank() }
                        val thinkSteps = msg.steps.count { it.type == "think" }
                        val hasReasoning = rawReasoning != null && thinkSteps == 0
                        if (!isUser && (msg.steps.isNotEmpty() || rawReasoning != null)) {
                            var processOpen by remember { mutableStateOf(true) }
                            // 不再包浅灰卡片：过程行直接落在消息卡里，靠左边细线标识同轮归属（DSH 样式）
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) {
                                // 运行中只有行在长，不显示摘要头（DSH：结束才折叠成摘要）
                                if (!thinking && !generating) {
                                    // 头部：一行摘要，整行可点击切换展开/收起
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { processOpen = !processOpen }
                                            .padding(vertical = 2.dp)
                                    ) {
                                        Text(
                                            ChatStore.turnSummaryLabel(msg.steps, hasReasoning),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (processOpen) "▾" else "▸",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (processOpen || thinking || generating) {
                                    // 左边一道细线：标识下面这些行「同属这一个过程」
                                    Row(Modifier.fillMaxWidth()) {
                                        Box(
                                            Modifier
                                                .width(2.dp)
                                                .fillMaxHeight()
                                                .background(
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(
                                            Modifier.weight(1f),
                                            // 行距 8dp：2dp 时整条时间线糊成一团，读不出「几步」
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            // 思考步在思考开始时就已占位（label 思考中），跟着时间线实时长
                                            msg.steps.forEach { st ->
                                                StepRow(
                                                    st = st,
                                                    thinkBody = if (st.type == "think" && thinkSteps == 1)
                                                        rawReasoning else null,
                                                    liveThink = thinking && st.type == "think" && st.label == "思考中",
                                                    onOpenFile = { path ->
                                                        // 产物列表里有内容的走内嵌预览；
                                                        // 「读取」出来的文件不在产物列表里，交给系统应用打开，
                                                        // 打不开就直接说清楚，别让按钮点下去什么都不发生。
                                                        val inCard = msg.genFiles.firstOrNull { it.path == path }
                                                        if (inCard != null) {
                                                            onOpenGenFile(inCard)
                                                        } else if (!com.localllm.server.core.FileOpener
                                                                .open(context, path)
                                                        ) {
                                                            val tail = if (path.startsWith("/"))
                                                                "（绝对路径没有系统授权，无法交给别的应用）"
                                                            else ""
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                "没有应用能打开 $path$tail",
                                                                android.widget.Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    }
                                                )
                                            }
                                            // 老数据：只有 reasoning 没有 steps（思考未落盘的历史消息）
                                            if (!thinking && msg.steps.isEmpty() && rawReasoning != null) {
                                                StepRow(
                                                    st = ChatStore.Step("think", "思考过程", detail = rawReasoning),
                                                    thinkBody = rawReasoning
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 被打断的任务：给一个续跑入口。续跑用任务快照重建指令，不重放历史
                        if (!isUser && showResume && msg.task?.stopped == true) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = onResumeTask,
                                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                            ) {
                                Text("继续这个任务", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        // 思考已并入上面的过程时间线（think 步 liveThink），不再单独成卡
                        if (waiting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "正在思考，或处理图片中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isUser) {
                            msg.imageB64?.let { b64 ->
                                val bmp = remember(b64) {
                                    runCatching {
                                        val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    }.getOrNull()
                                }
                                bmp?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "图片",
                                        modifier = Modifier
                                            .size(140.dp)
                                            .padding(bottom = 4.dp)
                                    )
                                }
                            }
                            Text(msg.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
                        } else {
                            parseSegments(msg.content).forEach { seg ->
                                when (seg) {
                                    is Seg.Text -> if (seg.s.isNotBlank()) {
                                        Text(
                                            inlineMd(seg.s),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                    is Seg.Heading -> Text(
                                        inlineMd(seg.s),
                                        style = when (seg.level) {
                                            1 -> MaterialTheme.typography.headlineSmall
                                            2 -> MaterialTheme.typography.titleMedium
                                            else -> MaterialTheme.typography.titleSmall
                                        },
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                    is Seg.ListBlock -> Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        seg.lines.forEach { l ->
                                            val trimmed = l.trimStart()
                                            val isDone = trimmed.startsWith("- [x]") ||
                                                    trimmed.startsWith("- [X]") ||
                                                    trimmed.startsWith("* [x]") ||
                                                    trimmed.startsWith("* [X]")
                                            val isTodo = trimmed.startsWith("- [ ]") ||
                                                    trimmed.startsWith("* [ ]")
                                            val shown = when {
                                                isDone -> "☑  " + trimmed.substringAfter("]").trim()
                                                isTodo -> "☐  " + trimmed.substringAfter("]").trim()
                                                else -> l
                                            }
                                            Text(
                                                inlineMd(shown),
                                                style = MaterialTheme.typography.bodyMedium,
                                                textDecoration = if (isDone)
                                                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                                                else null
                                            )
                                        }
                                    }
                                    is Seg.Quote -> Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            inlineMd(seg.s),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    is Seg.Table -> MarkdownTable(
                                        seg.rows,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    is Seg.Code -> CodeBlock(seg.lang, seg.s)
                                }
                            }
                        }
                    }
                    if (isUser) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose) {
                            DropdownMenuItem(
                                text = { Text("复制") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = onCopy
                            )
                            DropdownMenuItem(
                                text = { Text("重发") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = onResend
                            )
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = onEdit
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = onDelete
                            )
                        }
                        IconButton(onClick = onMenuOpen, modifier = Modifier.width(36.dp)) {
                            Icon(
                                Icons.Default.MoreHoriz,
                                contentDescription = "消息操作",
                                modifier = Modifier.width(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (!isUser && msg.sources.isNotEmpty()) {
                    Column(Modifier.padding(top = 6.dp)) {
                        Text(
                            "🔗 来源（点击打开浏览器）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        msg.sources.forEach { s ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(s.url)
                                                )
                                            )
                                        }.onFailure {
                                            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(vertical = 3.dp)
                            ) {
                                Text(
                                    s.title.ifBlank { s.url }.take(64),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    runCatching { java.net.URI(s.url).host ?: s.url }.getOrDefault(s.url),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (!isUser && msg.content.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onCopy,
                            modifier = Modifier.padding(top = 0.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制回复",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = onReadText,
                            modifier = Modifier.padding(top = 0.dp)
                        ) {
                            Icon(
                                Icons.Default.TextFields,
                                contentDescription = "选取文本",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("选取", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = onSpeak,
                            modifier = Modifier.padding(top = 0.dp)
                        ) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = "朗读",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("朗读", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.padding(top = 0.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除回复",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("删除", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (showStats) {
                    Text(
                        stats,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
        }
    }
}

/** 估算单元格宽度用：中日韩字符按 2 个单位，其余按 1 */
private fun cellUnits(s: String): Int = s.sumOf { if (it.code > 0x2E7F) 2 else 1 }

private val TABLE_MIN_COL = 60.dp
private val TABLE_MAX_COL = 240.dp

/**
 * 表格：列宽按内容估算，**放不下就整表横向滑动**。
 * 以前固定 `fillMaxWidth` + 按内容比例分配权重，列一多每格被压成一条缝，
 * 最右边的列直接被裁掉，而且没有任何滑动入口——用户看不到也点不开。
 */
@Composable
private fun MarkdownTable(rows: List<List<String>>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    val minWidths = remember(rows) {
        (0 until cols).map { c ->
            val units = rows.maxOf { r -> cellUnits(r.getOrElse(c) { "" }) }
            (units.coerceIn(4, 22) * 11).dp.coerceIn(TABLE_MIN_COL, TABLE_MAX_COL)
        }
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val minTotal = minWidths.fold(0.dp) { acc, w -> acc + w }
        // 放得下：按比例撑满屏宽（小表格看起来更整齐）；放不下：保留下限，横向滚动
        val widths = if (minTotal <= maxWidth) {
            val k = maxWidth / minTotal
            minWidths.map { it * k }
        } else minWidths
        val tableWidth = widths.fold(0.dp) { acc, w -> acc + w }
        val scrollable = minTotal > maxWidth
        // 用 Column 竖排「表格 + 提示」：提示不能覆盖在表格上，否则正好压住右上/右下那部分内容
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier
                        .width(tableWidth)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, border, RoundedCornerShape(8.dp))
                ) {
                    rows.forEachIndexed { ri, row ->
                        if (ri > 0) {
                            HorizontalDivider(thickness = 0.5.dp, color = border)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        ri == 0 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        ri % 2 == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    }
                                )
                        ) {
                            repeat(cols) { ci ->
                                val cell = row.getOrElse(ci) { "" }
                                Text(
                                    inlineMd(cell),
                                    style = if (ri == 0) MaterialTheme.typography.labelMedium
                                    else MaterialTheme.typography.bodySmall,
                                    fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                                    // 短内容（数字/状态）不折行，避免 0.00 被拆成两行；长内容给足行数，不再截断
                                    maxLines = if (cell.length <= 8) 1 else 6,
                                    softWrap = cell.length > 8,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier
                                        .width(widths[ci])
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (scrollable) {
                Text(
                    "← 左右滑动看完整表格 →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val isHtml = lang == "html" || lang == "htm" || code.contains("<html", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (lang.isEmpty()) "代码" else lang.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Row {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.width(36.dp)
                ) { Icon(Icons.Default.ContentCopy, contentDescription = "复制代码", modifier = Modifier.width(16.dp)) }
                IconButton(
                    onClick = { saveSnippet(context, lang, code) },
                    modifier = Modifier.width(36.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "保存为文件",
                        modifier = Modifier.width(16.dp)
                    )
                }
            }
        }
        Text(
            highlightCode(
                lang, code,
                keywordColor = MaterialTheme.colorScheme.primary,
                stringColor = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                numberColor = androidx.compose.ui.graphics.Color(0xFF6A1B9A)
            ),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 代码块语言 → (扩展名, MIME) */
private fun langToFile(lang: String, code: String): Pair<String, String> = when (lang.lowercase()) {
    "html", "htm" -> "html" to "text/html"
    "xml" -> "xml" to "application/xml"
    "svg" -> "svg" to "image/svg+xml"
    "json" -> "json" to "application/json"
    "csv" -> "csv" to "text/csv"
    "md", "markdown" -> "md" to "text/markdown"
    "py", "python" -> "py" to "text/x-python"
    "js", "javascript" -> "js" to "application/javascript"
    "ts", "typescript" -> "ts" to "application/javascript"
    "kt", "kotlin" -> "kt" to "text/plain"
    "java" -> "java" to "text/plain"
    "sh", "bash", "shell" -> "sh" to "text/x-sh"
    "sql" -> "sql" to "text/plain"
    "yaml", "yml" -> "yml" to "text/yaml"
    "css" -> "css" to "text/css"
    else -> "txt" to "text/plain"
}

/** 把代码块按语言保存到系统下载目录（MediaStore，无需存储权限） */
private fun saveSnippet(context: android.content.Context, lang: String, code: String) {
    try {
        // HTML 内容即使没标注语言也按 html 存
        val effectiveLang = if (lang.isBlank() && code.contains("<html", ignoreCase = true)) "html" else lang
        val (ext, mime) = langToFile(effectiveLang, code)
        val name = "snippet_${System.currentTimeMillis()}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建文件")
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(code.toByteArray(Charsets.UTF_8))
        }
        Toast.makeText(context, "已保存到下载：$name", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 历史摘要卡片（上下文压缩产生的 system 消息） */
@Composable
private fun SummaryCard(content: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "📝 上下文摘要",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 轻量语法高亮：注释/字符串/数字/关键字（无法识别语言时原样返回） */
private fun highlightCode(
    lang: String,
    code: String,
    keywordColor: androidx.compose.ui.graphics.Color,
    stringColor: androidx.compose.ui.graphics.Color,
    commentColor: androidx.compose.ui.graphics.Color,
    numberColor: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString {
    val keywords: Set<String> = when (lang.lowercase()) {
        "kotlin", "kt", "kts" -> setOf(
            "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for",
            "while", "return", "import", "package", "suspend", "override", "data", "private",
            "public", "internal", "null", "true", "false", "try", "catch", "finally", "throw",
            "in", "is", "as", "by", "lateinit", "companion", "sealed", "enum"
        )
        "java" -> setOf(
            "class", "interface", "extends", "implements", "public", "private", "protected",
            "static", "final", "void", "int", "long", "double", "float", "boolean", "char",
            "new", "return", "if", "else", "for", "while", "try", "catch", "finally", "throw",
            "null", "true", "false", "import", "package"
        )
        "python", "py" -> setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from",
            "as", "try", "except", "finally", "raise", "with", "lambda", "None", "True", "False",
            "and", "or", "not", "in", "is", "yield", "async", "await"
        )
        "js", "javascript", "ts", "typescript", "jsx", "tsx" -> setOf(
            "function", "const", "let", "var", "class", "extends", "if", "else", "for", "while",
            "return", "import", "export", "default", "async", "await", "try", "catch", "finally",
            "throw", "new", "null", "undefined", "true", "false", "interface", "type"
        )
        "json" -> setOf("true", "false", "null")
        "bash", "sh", "shell", "zsh" -> setOf(
            "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "case",
            "esac", "function", "echo", "export", "cd", "sudo", "rm", "cp", "mv", "mkdir", "curl"
        )
        else -> emptySet()
    }
    if (keywords.isEmpty()) return androidx.compose.ui.text.AnnotatedString(code)
    val re = Regex(
        """(//[^\n]*|#[^\n]*|/\*[\s\S]*?\*/)|("(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_]\w*)\b"""
    )
    return androidx.compose.ui.text.buildAnnotatedString {
        var last = 0
        for (m in re.findAll(code)) {
            if (m.range.first > last) append(code.substring(last, m.range.first))
            val g1 = m.groups[1]
            val g2 = m.groups[2]
            val g3 = m.groups[3]
            val g4 = m.groups[4]
            when {
                g1 != null -> withStyle(androidx.compose.ui.text.SpanStyle(color = commentColor)) {
                    append(g1.value)
                }
                g2 != null -> withStyle(androidx.compose.ui.text.SpanStyle(color = stringColor)) {
                    append(g2.value)
                }
                g3 != null -> withStyle(androidx.compose.ui.text.SpanStyle(color = numberColor)) {
                    append(g3.value)
                }
                g4 != null && g4.value in keywords ->
                    withStyle(androidx.compose.ui.text.SpanStyle(color = keywordColor)) { append(g4.value) }
                else -> append(m.value)
            }
            last = m.range.last + 1
        }
        if (last < code.length) append(code.substring(last))
    }
}

/** 聊天页顶部/输入区显示的"当前生效"标记 */
private data class ActiveSetup(val title: String, val sub: String)

/** 技能选择/管理弹窗：技能、技能链，点选即写入本会话助手指令 */
@Composable
private fun SkillDialog(
    onDismiss: () -> Unit,
    onApply: (com.localllm.server.core.Skill) -> Unit,
    onApplyChain: (com.localllm.server.core.SkillChain) -> Unit,
    onClear: () -> Unit,
    appliedSkillId: String? = null,
    appliedChainId: String? = null
) {
    var list by remember { mutableStateOf(com.localllm.server.core.AppSettings.loadSkills()) }
    var chains by remember { mutableStateOf(com.localllm.server.core.AppSettings.loadSkillChains()) }
    var editing by remember { mutableStateOf<com.localllm.server.core.Skill?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var fallbackEdit by remember { mutableStateOf<com.localllm.server.core.SkillChain?>(null) }
    var branchEdit by remember { mutableStateOf<com.localllm.server.core.SkillChain?>(null) }
    var showChainBuilder by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("技能") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "选技能/技能链会作为本会话助手指令生效，可随时取消。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (chains.isNotEmpty()) {
                    Text(
                        "技能链",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    chains.forEach { c ->
                        val names = list.filter { it.id in c.skillIds }.map { it.name }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onApplyChain(c) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    "⛓ ${c.name}" + if (c.id == appliedChainId) "　✓ 生效中" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (c.id == appliedChainId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    names.joinToString(" → "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            TextButton(onClick = { fallbackEdit = c }) {
                                val fbName = c.fallbackSkillId
                                    ?.let { fid -> list.firstOrNull { it.id == fid }?.name }
                                Text(
                                    if (fbName != null) "备用：$fbName" else "设备用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (fbName != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { branchEdit = c }) {
                                Text(
                                    if (c.branches.isEmpty()) "条件"
                                    else "条件·${c.branches.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (c.branches.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(onClick = {
                                chains = chains.filter { it.id != c.id }
                                com.localllm.server.core.AppSettings.saveSkillChains(chains)
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    "单个技能",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                list.forEach { sk ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { onApply(sk) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                sk.name + if (sk.id == appliedSkillId) "　✓ 生效中" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (sk.id == appliedSkillId) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (sk.desc.isNotBlank()) {
                                Text(
                                    sk.desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        TextButton(onClick = {
                            editing = sk
                            isNew = false
                        }) { Text("编辑") }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showChainBuilder = true }) { Text("新建技能链…") }
                    OutlinedButton(onClick = {
                        editing = com.localllm.server.core.Skill(
                            java.util.UUID.randomUUID().toString(), "新技能", "", ""
                        )
                        isNew = true
                    }) { Text("新增技能") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClear()
                onDismiss()
            }) { Text("清除技能") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    if (showChainBuilder) {
        ChainBuilderDialog(
            skills = list,
            onDismiss = { showChainBuilder = false },
            onSave = { chain ->
                chains = chains + chain
                com.localllm.server.core.AppSettings.saveSkillChains(chains)
                showChainBuilder = false
            }
        )
    }

    editing?.let { sk ->
        var name by remember(sk.id) { mutableStateOf(sk.name) }
        var desc by remember(sk.id) { mutableStateOf(sk.desc) }
        var prompt by remember(sk.id) { mutableStateOf(sk.prompt) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "新增技能" else "编辑技能") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactField(
                        value = name,
                        onValueChange = { name = it },
                        label = "名称",
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = "简短说明（列表中显示）",
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactFieldMultiline(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = "助手指令内容",
                        minHeight = 140
                    )
                    TextButton(onClick = {
                        if (list.size > 1) {
                            list = list.filter { it.id != sk.id }
                            com.localllm.server.core.AppSettings.saveSkills(list)
                            editing = null
                        }
                    }) { Text("删除此技能", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = sk.copy(
                        name = name.trim().ifBlank { "技能" },
                        desc = desc.trim(),
                        prompt = prompt.trim()
                    )
                    val newList = if (isNew) list + updated
                                  else list.map { if (it.id == sk.id) updated else it }
                    list = newList
                    com.localllm.server.core.AppSettings.saveSkills(newList)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }

    // 失败时改用的备用技能：某一步被判失败时，用它重跑那一步（执行器据此分支）
    fallbackEdit?.let { ch ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { fallbackEdit = null },
            title = { Text("失败时改用") },
            text = {
                Column {
                    Text(
                        "技能链某一步被判定失败时，用这个技能重跑该步；不设则失败即中止并说明卡在哪一步。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    list.forEach { sk ->
                        TextButton(onClick = {
                            val updated = chains.map {
                                if (it.id == ch.id) it.copy(fallbackSkillId = sk.id) else it
                            }
                            chains = updated
                            com.localllm.server.core.AppSettings.saveSkillChains(updated)
                            fallbackEdit = null
                        }) {
                            Text(sk.name + if (ch.fallbackSkillId == sk.id) "　✓" else "")
                        }
                    }
                    TextButton(onClick = {
                        val updated = chains.map {
                            if (it.id == ch.id) it.copy(fallbackSkillId = null) else it
                        }
                        chains = updated
                        com.localllm.server.core.AppSettings.saveSkillChains(updated)
                        fallbackEdit = null
                    }) { Text("不设备用", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { fallbackEdit = null }) { Text("关闭") } }
        )
    }

    // 结果分支：某一步输出命中关键词就改道（跳步或提前结束）
    branchEdit?.let { ch ->
        ChainBranchDialog(
            chain = ch,
            skills = list,
            onDismiss = { branchEdit = null },
            onSave = { updated ->
                chains = chains.map { if (it.id == ch.id) it.copy(branches = updated) else it }
                com.localllm.server.core.AppSettings.saveSkillChains(chains)
                branchEdit = null
            }
        )
    }
}

/**
 * 分支条件编辑。与「失败改用备用技能」互补：那个管失败，这个管「做出来了、但该换条路」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainBranchDialog(
    chain: com.localllm.server.core.SkillChain,
    skills: List<com.localllm.server.core.Skill>,
    onDismiss: () -> Unit,
    onSave: (Map<Int, com.localllm.server.core.ChainBranch>) -> Unit
) {
    var branches by remember { mutableStateOf(chain.branches) }
    var open by remember { mutableStateOf(-1) }
    val names = chain.skillIds.map { id ->
        skills.firstOrNull { it.id == id }?.name ?: "（技能已删除）"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分支条件") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "某一步的输出里出现关键词时改道：跳到别的步，或直接结束整条链。不配就按顺序往下走。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                names.forEachIndexed { i, nm ->
                    val b = branches[i]
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { open = if (open == i) -1 else i }
                            ) {
                                Text("第 ${i + 1} 步 · $nm", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (b == null) "无条件，顺序继续"
                                    else "含「${b.contains}」→ " +
                                            (b.gotoStep?.let { "第 ${it + 1} 步" } ?: "结束整条链"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (b == null) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                            TextButton(onClick = { open = if (open == i) -1 else i }) {
                                Text(
                                    if (open == i) "收起" else "编辑",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (open == i) {
                            var kw by remember(i, b?.contains) { mutableStateOf(b?.contains ?: "") }
                            // -2 = 还没选；-1 = 结束整条链；≥0 = 跳到该步
                            var target by remember(i, b?.gotoStep) {
                                mutableStateOf(b?.let { it.gotoStep ?: -1 } ?: -2)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CompactField(
                                    value = kw,
                                    onValueChange = { kw = it },
                                    label = "关键词（出现在这一步的输出里）",
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("命中后：", style = MaterialTheme.typography.labelSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = target == -1,
                                        onClick = { target = -1 },
                                        label = {
                                            Text("结束整条链", style = MaterialTheme.typography.labelSmall)
                                        }
                                    )
                                    names.indices.filter { it != i }.forEach { t ->
                                        FilterChip(
                                            selected = target == t,
                                            onClick = { target = t },
                                            label = {
                                                Text("第 ${t + 1} 步", style = MaterialTheme.typography.labelSmall)
                                            }
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        enabled = kw.isNotBlank() && target != -2,
                                        onClick = {
                                            branches = branches + (i to com.localllm.server.core.ChainBranch(
                                                kw.trim(),
                                                if (target >= 0) target else null
                                            ))
                                            open = -1
                                        }
                                    ) { Text("保存这一步") }
                                    if (b != null) {
                                        TextButton(onClick = {
                                            branches = branches - i
                                            open = -1
                                        }) { Text("清除条件", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(branches) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 新建技能链：按点击顺序加入技能（至少 2 个） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainBuilderDialog(
    skills: List<com.localllm.server.core.Skill>,
    onDismiss: () -> Unit,
    onSave: (com.localllm.server.core.SkillChain) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建技能链") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactField(
                    value = name,
                    onValueChange = { name = it },
                    label = "技能链名称（如 摘要→表格）",
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "按顺序点击技能加入（点一次加入，再点移除）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    skills.forEach { sk ->
                        val idx = picked.indexOf(sk.id)
                        FilterChip(
                            selected = idx >= 0,
                            onClick = {
                                picked = if (idx >= 0) picked - sk.id else picked + sk.id
                            },
                            label = {
                                Text(
                                    if (idx >= 0) "${idx + 1}. ${sk.name}" else sk.name,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
                if (picked.size < 2) {
                    Text(
                        "至少选择 2 个技能",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (picked.size >= 2) {
                        onSave(
                            com.localllm.server.core.SkillChain(
                                java.util.UUID.randomUUID().toString(),
                                name.trim().ifBlank { "技能链" },
                                picked
                            )
                        )
                    }
                },
                enabled = picked.size >= 2
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 会话端点标签：跟随全局时把全局当前的端点写出来，免得看不出实际走哪 */
private fun sessionEndpointLabel(s: ChatStore.Session): String {
    val ep = s.endpoint
    if (ep == null) {
        val global = if (
            com.localllm.server.core.AppSettings.chatCloudEnabled &&
            com.localllm.server.core.AppSettings.chatCloudUrl.isNotBlank()
        ) "云端" else "本地"
        return "跟随全局（$global）"
    }
    return if (ep.local) "本地服务"
    else ep.model.ifBlank { ep.baseUrl.ifBlank { "云端" } }
}

/** 端点选择弹窗里的一项 */
@Composable
private fun EndpointOption(selected: Boolean, title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}
