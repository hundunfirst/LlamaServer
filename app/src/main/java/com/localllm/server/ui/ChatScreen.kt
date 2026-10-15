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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
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
    data class Table(val s: String) : Seg()
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
    if (last < content.length) parseBlocks(content.substring(last), out)
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
                val buf = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(lines[i].trim())
                    i++
                }
                out.add(Seg.Table(buf.toString()))
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

/** 行内 Markdown：**加粗**、*斜体*、`等宽代码` */
private fun inlineMd(s: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val re = Regex("""(\*\*[^*\n]+\*\*|`[^`\n]+`|\*[^*\n]+\*)""")
        var last = 0
        for (m in re.findAll(s)) {
            append(s.substring(last, m.range.first))
            val t = m.value
            when {
                t.startsWith("**") -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(t.removeSurrounding("**"))
                }
                t.startsWith("`") -> withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = androidx.compose.ui.graphics.Color(0xFFB71C1C).takeIf { false }
                            ?: androidx.compose.ui.graphics.Color(0xFFC62828)
                    )
                ) { append(t.removeSurrounding("`")) }
                else -> withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(t.trim('*'))
                }
            }
            last = m.range.last + 1
        }
        if (last < s.length) append(s.substring(last))
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
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachImage(uri)
    }

    // 智能贴底滚动：新消息必到底；流式增长仅当"最后一条消息可见且离其底部<400px"（用户未上翻）时跟随
    var prevCount by remember { mutableStateOf(-1) }
    val lastLen = messages.lastOrNull()?.let { it.content.length + (it.reasoning?.length ?: 0) } ?: 0
    LaunchedEffect(messages.size, lastLen, streaming) {
        if (messages.isEmpty()) {
            prevCount = -1
            return@LaunchedEffect
        }
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        val atLastMsgBottom = lastVisible != null &&
                lastVisible.index == messages.size - 1 &&
                (lastVisible.offset + lastVisible.size) - info.viewportEndOffset <= 400
        val newMessage = messages.size != prevCount
        prevCount = messages.size
        if (newMessage || atLastMsgBottom) {
            // 锚定最后一条的底部：先滚到该条，再把超出视口的部分补滚掉
            listState.scrollToItem(messages.size - 1)
            val v = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (v != null) {
                val over = (v.offset + v.size) - listState.layoutInfo.viewportEndOffset
                if (over > 0) listState.scroll { scrollBy(over.toFloat()) }
            }
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

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { showSessions = true }) {
                Icon(Icons.Default.List, contentDescription = "会话列表")
            }
            IconButton(onClick = vm::newChat, enabled = !streaming) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "新会话")
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
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "服务运行中即可直接对话，无需额外配置。\n长按/⋮ 菜单支持复制、重发、编辑；代码块可一键复制或保存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 48.dp, start = 8.dp, end = 8.dp)
                    )
                }
            }
            itemsIndexed(messages) { i, msg ->
                val isUser = msg.role == "user"
                val waiting = !isUser && streaming && msg.content.isEmpty() && msg.reasoning.isNullOrEmpty()
                MessageBubble(
                    msg = msg,
                    isUser = isUser,
                    waiting = waiting,
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
                    }
                )
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
                TextButton(onClick = vm::clearPendingImage) { Text("移除") }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { pickImage.launch(arrayOf("image/*")) }, enabled = !streaming) {
                Icon(Icons.Default.Image, contentDescription = "附加图片", tint = MaterialTheme.colorScheme.primary)
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
                IconButton(onClick = vm::send, enabled = input.isNotBlank()) {
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
    showStats: Boolean,
    stats: String,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuClose: () -> Unit,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    onEdit: () -> Unit
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
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
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
                                        if (thinkOpen) "🧠 思考过程" else "🧠 已深度思考，点击查看",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { thinkOpen = !thinkOpen }) {
                                        Text(if (thinkOpen) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (thinkOpen) {
                                    Text(
                                        msg.reasoning!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
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
                                            Text(inlineMd(l), style = MaterialTheme.typography.bodyMedium)
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
                                    is Seg.Table -> Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surface,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            seg.s,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                    }
                                    is Seg.Code -> CodeBlock(seg.lang, seg.s)
                                }
                            }
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose) {
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = onCopy
                        )
                        if (isUser) {
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
                        }
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
                if (isHtml) {
                    IconButton(
                        onClick = { saveHtml(context, code) },
                        modifier = Modifier.width(36.dp)
                    ) { Icon(Icons.Default.Download, contentDescription = "保存文件", modifier = Modifier.width(16.dp)) }
                }
            }
        }
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun saveHtml(context: android.content.Context, code: String) {
    try {
        val name = "snippet_${System.currentTimeMillis()}.html"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/html")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("无法创建文件")
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(code.toByteArray(Charsets.UTF_8))
        }
        Toast.makeText(context, "已保存到下载：$name", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
