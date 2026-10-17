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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val input by vm.input.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastStats by vm.lastStats.collectAsStateWithLifecycle()
    val pendingImage by vm.pendingImageB64.collectAsStateWithLifecycle()
    val pendingFile by vm.pendingFile.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var showCompressConfirm by remember { mutableStateOf(false) }
    var showSysPrompt by remember { mutableStateOf(false) }
    var previewImageB64 by remember { mutableStateOf<String?>(null) }
    var previewFileName by remember { mutableStateOf("") }
    var showSkills by remember { mutableStateOf(false) }
    var previewFileText by remember { mutableStateOf("") }
    var showFilePreview by remember { mutableStateOf(false) }
    var sysPromptText by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }

    // 附加文件：图片走多模态/OCR，文本类文件读成文本随消息发送（其余类型暂不支持）
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) vm.attachImage(uri) else vm.attachFile(uri)
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

    // 技能选择 / 管理
    if (showSkills) {
        SkillDialog(
            onDismiss = { showSkills = false },
            onApply = { skill ->
                vm.setSessionSystemPrompt(skill.prompt)
                showSkills = false
            },
            onClear = { vm.setSessionSystemPrompt(null) }
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
                        DropdownMenuItem(
                            text = { Text("${p.name} · ${p.model.ifBlank { "未选模型" }}") },
                            onClick = {
                                com.localllm.server.core.AppSettings.activateCloudPreset(p)
                                channelMenuOpen = false
                            }
                        )
                    }
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
                IconButton(onClick = { showSessions = true }) {
                    Icon(Icons.Default.List, contentDescription = "会话列表")
                }
                IconButton(onClick = vm::newChat, enabled = !streaming) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "新会话")
                }
            }
        }

        if (showSessions) {
            AlertDialog(
                onDismissRequest = { showSessions = false },
                title = { Text("会话管理") },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(sessions.sortedByDescending { it.updatedAt }) { s ->
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
                                    Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(
                                        "${s.messages.size} 条 · ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(s.updatedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { vm.deleteSession(s.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
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
                MessageBubble(
                    msg = msg,
                    isUser = isUser,
                    waiting = waiting,
                    thinking = thinking,
                    showStats = (!isUser && i == messages.lastIndex && lastStats != null),
                    stats = lastStats ?: "",
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

        // 当前生效的技能（会话指令与某技能 prompt 完全一致时显示）
        val activeSkill = remember(sessions, activeId) {
            val cur = vm.currentSessionSystemPrompt() ?: return@remember null
            com.localllm.server.core.AppSettings.loadSkills().firstOrNull { it.prompt == cur }
        }
        activeSkill?.let { sk ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚡ 技能：${sk.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.setSessionSystemPrompt(null) }) { Text("取消技能") }
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
                    "🖼 已附带图片（已自动压缩）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { previewImageB64 = pendingImage }) { Text("预览") }
                TextButton(onClick = vm::clearPendingImage) { Text("移除") }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) startSpeech()
                    else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                enabled = !streaming
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    pickImage.launch(
                        arrayOf(
                            "image/*", "text/*",
                            "application/json", "application/xml", "application/javascript",
                            "application/x-yaml", "application/csv",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                    )
                },
                enabled = !streaming
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "附加文件（图片/文本）",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = vm::onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                maxLines = 4
            )
            if (streaming) {
                IconButton(onClick = vm::stop) {
                    Icon(Icons.Default.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(
                    onClick = vm::send,
                    enabled = input.isNotBlank() || pendingImage != null || pendingFile != null
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatStore.Msg,
    isUser: Boolean,
    waiting: Boolean,
    thinking: Boolean = false,
    showStats: Boolean,
    stats: String,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuClose: () -> Unit,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSpeak: () -> Unit = {}
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
            modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 1f)
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        // 执行步骤时间线（思考/搜索/工具），Rikkahub 风格
                        if (!isUser && msg.steps.isNotEmpty()) {
                            var stepsOpen by remember { mutableStateOf(true) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "🧩 执行步骤（${msg.steps.size}）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { stepsOpen = !stepsOpen }) {
                                        Text(
                                            if (stepsOpen) "收起" else "展开",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (stepsOpen) {
                                    msg.steps.forEachIndexed { si, st ->
                                        var detailOpen by remember(si) { mutableStateOf(false) }
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        ) {
                                            Text(
                                                when (st.type) {
                                                    "think" -> "💡"
                                                    "search" -> "🔍"
                                                    else -> "🔧"
                                                },
                                                modifier = Modifier.width(22.dp)
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(st.label, style = MaterialTheme.typography.bodySmall)
                                                st.detail?.let { d ->
                                                    Text(
                                                        if (detailOpen) d
                                                        else d.take(36) + if (d.length > 36) "…" else "",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (detailOpen)
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        else MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.clickable {
                                                            detailOpen = !detailOpen
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 思考过程（DeepSeek 风格折叠卡，默认收起）
                        if (!isUser && !msg.reasoning.isNullOrBlank()) {
                            var thinkOpen by remember { mutableStateOf(false) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when {
                                            thinkOpen -> "🧠 思考过程"
                                            thinking -> "🧠 思考中…"
                                            else -> "🧠 已深度思考，点击查看"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (thinking) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp).padding(end = 4.dp),
                                            strokeWidth = 1.5.dp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { thinkOpen = !thinkOpen }) {
                                        Text(if (thinkOpen) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (thinkOpen) {
                                    // 固定盒子：思考只能在盒内滚动（限高260dp），正文是全局页面。
                                    // 展开时滚到最新；流式期间贴近底部时自动跟随；上翻阅读则不打扰。
                                    val thinkScroll = rememberScrollState()
                                    var thinkJumped by remember { mutableStateOf(false) }
                                    LaunchedEffect(msg.reasoning?.length) {
                                        val max = thinkScroll.maxValue
                                        if (max <= 0) return@LaunchedEffect
                                        if (!thinkJumped) {
                                            thinkScroll.scrollTo(max)
                                            thinkJumped = true
                                        } else if (thinkScroll.value >= max - 80) {
                                            thinkScroll.scrollTo(max)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp)
                                            .verticalScroll(thinkScroll)
                                            .padding(top = 4.dp)
                                    ) {
                                        Text(
                                            latexToUnicode(msg.reasoning!!),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
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

/**
 * Markdown 表格：按列解析后用等权重行列渲染。
 * 列宽按各列最长内容分配权重（下限 4、上限 40 字符），窄屏自动换行但列始终对齐。
 */
@Composable
private fun MarkdownTable(rows: List<List<String>>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    val weights = remember(rows) {
        (0 until cols).map { c ->
            rows.maxOf { r -> r.getOrElse(c) { "" }.length }
                .coerceIn(4, 40)
                .toFloat()
        }
    }
    val total = weights.sum().coerceAtLeast(1f)
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
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
                        modifier = Modifier
                            .weight(weights[ci] / total)
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    )
                }
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

/** 技能选择/管理弹窗：点技能即写入本会话助手指令 */
@Composable
private fun SkillDialog(
    onDismiss: () -> Unit,
    onApply: (com.localllm.server.core.Skill) -> Unit,
    onClear: () -> Unit
) {
    var list by remember { mutableStateOf(com.localllm.server.core.AppSettings.loadSkills()) }
    var editing by remember { mutableStateOf<com.localllm.server.core.Skill?>(null) }
    var isNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("技能") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "选一个技能会作为本会话的助手指令生效；可随时取消。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                list.forEach { sk ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { onApply(sk) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(sk.name, style = MaterialTheme.typography.bodyMedium)
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
                OutlinedButton(onClick = {
                    editing = com.localllm.server.core.Skill(
                        java.util.UUID.randomUUID().toString(), "新技能", "", ""
                    )
                    isNew = true
                }) { Text("新增技能") }
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
                    val newList = if (isNew) list + updated else list.map { if (it.id == sk.id) updated else it }
                    list = newList
                    com.localllm.server.core.AppSettings.saveSkills(newList)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}
