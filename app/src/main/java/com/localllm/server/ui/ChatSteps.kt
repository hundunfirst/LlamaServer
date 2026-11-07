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

/**
 * 时间线里的步骤行、代码块、表格渲染。
 *
 * 从 ChatScreen 里搬出来的最后一批渲染组件：它们只吃步骤数据和字符串，
 * 与消息发送、流式生成完全无关。
 */
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
    val toolDetail = if (st.type == "tool" || st.type == "search" || st.type == "cloud") st.detail else null
    val hasBody = st.type == "text" ||
            liveThink ||
            !thinkBodyText.isNullOrBlank() ||
            toolArgs != null || toolDetail != null
    // 每类步骤一个颜色：光靠灰色线条图标，整条时间线看起来像日志，认不出「哪几行是一次操作」
    val stepTint = when {
        !st.ok -> MaterialTheme.colorScheme.error
        st.type == "think" -> MaterialTheme.colorScheme.tertiary
        st.type == "cloud" -> MaterialTheme.colorScheme.secondary
        st.type == "search" -> MaterialTheme.colorScheme.primary
        st.label.startsWith("写入") || st.label.startsWith("编辑") -> MaterialTheme.colorScheme.secondary
        st.label.startsWith("读取") || st.label.startsWith("列出") -> MaterialTheme.colorScheme.primary
        st.label.startsWith("运行") || st.label.startsWith("执行命令") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.foundation.text.selection.SelectionContainer {
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
                "cloud" -> Icons.Filled.AutoAwesome
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
                                        Text(
                                            latexToUnicode(thinkBodyText ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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
}

// 思考行已并入 StepRow（liveThink）：思考开始即占位，边想边更新，结束就地改标签

@OptIn(ExperimentalFoundationApi::class)
/** 日期分隔条：跨天时插一条，往上翻先看它就知道是哪天 */
@Composable
internal fun MarkdownTable(rows: List<List<String>>, modifier: Modifier = Modifier) {
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
internal fun CodeBlock(lang: String, code: String) {
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
internal fun langToFile(lang: String, code: String): Pair<String, String> = when (lang.lowercase()) {
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
internal fun saveSnippet(context: android.content.Context, lang: String, code: String) {
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
internal fun SummaryCard(content: String) {
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
internal fun highlightCode(
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
internal data class ActiveSetup(val title: String, val sub: String)

/** 技能选择/管理弹窗：技能、技能链，点选即写入本会话助手指令 */
@Composable
internal fun SkillDialog(
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
internal fun ChainBranchDialog(
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
internal fun ChainBuilderDialog(
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
internal fun sessionEndpointLabel(s: ChatStore.Session): String {
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
internal fun EndpointOption(selected: Boolean, title: String, onClick: () -> Unit) {
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
