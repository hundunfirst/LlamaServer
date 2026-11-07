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
 * 一条消息的气泡：用户/助手、思考过程、工具步骤、附件、时间戳。
 *
 * 从 ChatScreen 里搬出来的渲染层，只吃 ChatStore.Msg 和回调，和发送、流式逻辑无关。
 */
@Composable
internal fun DayDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
}

/** 消息时间：一条消息一行，用户消息靠右、回复靠左；老数据（at=0）不显示 */
@Composable
internal fun MsgStamp(msg: ChatStore.Msg, isUser: Boolean) {
    if (msg.at <= 0) return
    val label = ChatStore.stampLabel(msg.at) +
            (if (!isUser && msg.elapsedMs > 0) " · 用时 ${ChatStore.elapsedLabel(msg.elapsedMs)}" else "")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
internal fun MessageBubble(
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
    /** 撤销这一轮的全部文件改动（一轮常改好几个文件，逐个点不现实） */
    onUndoRound: (() -> Unit)? = null,
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
                            // 哪一行的「…」菜单开着（存路径，不用 index，列表变化也不会串）
                            var fileMenuFor by remember(msg.genFiles.size) {
                                mutableStateOf<String?>(null)
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
                                    val ext = gf.path.substringAfterLast('.', "").lowercase()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                // 点一下先进应用内查看器（和 DSH 一致）：
                                                // 文本/图片/PDF 直接看；APK 这类给「安装 / 用其他应用打开」。
                                                // 想立刻交给系统应用，用右边的「…」菜单。
                                                onOpenGenFile(gf)
                                            }
                                            .padding(vertical = 6.dp)
                                    ) {
                                        // 类型徽标：比统一的小图标好认（APK / XLSX / CSV …）
                                        androidx.compose.material3.Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                ext.ifBlank { "FILE" }.uppercase().take(4),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
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
                                        // 每行一个「…」菜单：打开 / 分享 / 定位 / 预览
                                        Box {
                                            IconButton(
                                                onClick = { fileMenuFor = gf.path },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    contentDescription = "文件操作",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = fileMenuFor == gf.path,
                                                onDismissRequest = { fileMenuFor = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(if (ext == "apk") "安装" else "打开") },
                                                    onClick = {
                                                        fileMenuFor = null
                                                        if (!com.localllm.server.core.FileOpener
                                                                .open(fileCtx, gf.path)
                                                        ) {
                                                            onOpenGenFile(gf)
                                                        }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("分享") },
                                                    onClick = {
                                                        fileMenuFor = null
                                                        if (!com.localllm.server.core.FileOpener
                                                                .share(fileCtx, gf.path)
                                                        ) {
                                                            Toast.makeText(
                                                                fileCtx, "分享失败：找不到这个文件",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("定位") },
                                                    onClick = {
                                                        fileMenuFor = null
                                                        if (!com.localllm.server.core.FileOpener
                                                                .revealInFolder(fileCtx, gf.path)
                                                        ) {
                                                            Toast.makeText(
                                                                fileCtx, "文件管理器不支持打开文件夹，路径：${gf.path}",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    }
                                                )
                                                if (gf.content.isNotBlank()) {
                                                    DropdownMenuItem(
                                                        text = { Text("在会话内预览") },
                                                        onClick = {
                                                            fileMenuFor = null
                                                            onOpenGenFile(gf)
                                                        }
                                                    )
                                                }
                                            }
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
                                    if (onUndoRound != null && msg.turnId > 0) {
                                        Text(
                                            "撤销本轮",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .clickable { onUndoRound() }
                                                .padding(vertical = 4.dp, horizontal = 8.dp)
                                        )
                                    }
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
                MsgStamp(msg, isUser)
            }
        }
    }
}

/** 估算单元格宽度用：中日韩字符按 2 个单位，其余按 1 */
internal fun cellUnits(s: String): Int = s.sumOf { if (it.code > 0x2E7F) 2 else 1 }

internal val TABLE_MIN_COL = 60.dp
internal val TABLE_MAX_COL = 240.dp

/**
 * 表格：列宽按内容估算，**放不下就整表横向滑动**。
 * 以前固定 `fillMaxWidth` + 按内容比例分配权重，列一多每格被压成一条缝，
 * 最右边的列直接被裁掉，而且没有任何滑动入口——用户看不到也点不开。
 */
