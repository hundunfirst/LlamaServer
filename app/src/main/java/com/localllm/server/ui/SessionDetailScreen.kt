package com.localllm.server.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localllm.server.core.ChatStore

/**
 * 会话详情：把一条会话里散落的数据集中看。
 * 五个 tab 对应五种问题——「它都干了什么」（轨迹）、「改了哪些文件」（改动/文件）、
 * 「现在做到哪」（进度）、「上下文被谁吃了」（上下文）。
 *
 * 数据全部来自会话本身（steps / genFiles / task / 每轮耗时与 token），
 * 没有的字段显式显示为「—」，不编造。
 */
@Composable
internal fun SessionDetailDialog(
    session: ChatStore.Session,
    usage: ChatViewModel.Usage?,
    contextLimit: Int,
    onDismiss: () -> Unit,
    onOpenGenFile: (ChatStore.GenFile) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("轨迹", "改动", "文件", "进度", "上下文")

    val turns = remember(session.messages) { groupTurns(session.messages) }
    val totalMs = session.messages.sumOf { it.elapsedMs }
    val stepCount = session.messages.sumOf { it.steps.size }
    val files = remember(session.messages) {
        session.messages.flatMap { it.genFiles }.reversed().distinctBy { it.path }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            session.title.ifBlank { "会话" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            "共 ${turns.size} 轮",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 三个总览数字
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    DetailStat(formatDuration(totalMs), "用时", Modifier.weight(1f))
                    DetailStat(stepCount.toString(), "步骤", Modifier.weight(1f))
                    DetailStat(files.size.toString(), "改动文件", Modifier.weight(1f))
                }

                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    labels.forEachIndexed { i, label ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(label, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }

                when (tab) {
                    0 -> TurnTrail(turns)
                    1 -> ChangeList(files, onOpenGenFile)
                    2 -> FileList(files, onOpenGenFile)
                    3 -> ProgressList(turns)
                    else -> ContextTab(usage, contextLimit, turns)
                }
            }
        }
    }
}

// ---- 轨迹 ----

private data class Turn(val index: Int, val user: ChatStore.Msg?, val replies: List<ChatStore.Msg>)

private fun groupTurns(messages: List<ChatStore.Msg>): List<Turn> {
    val turns = mutableListOf<Turn>()
    var currentUser: ChatStore.Msg? = null
    var replies = mutableListOf<ChatStore.Msg>()
    var i = 0
    for (m in messages) {
        if (m.role == "user") {
            if (currentUser != null || replies.isNotEmpty()) {
                turns += Turn(++i, currentUser, replies)
                replies = mutableListOf()
            }
            currentUser = m
        } else {
            replies += m
        }
    }
    if (currentUser != null || replies.isNotEmpty()) turns += Turn(++i, currentUser, replies)
    return turns
}

@Composable
private fun TurnTrail(turns: List<Turn>) {
    if (turns.isEmpty()) {
        EmptyHint("还没有对话")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(turns) { turn ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "第 ${turn.index} 轮",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        val ms = turn.replies.sumOf { it.elapsedMs }
                        Text(
                            formatDuration(ms),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    turn.user?.let { u ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            u.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    val steps = turn.replies.flatMap { it.steps }
                    val tokens = turn.replies.sumOf { it.promptTokens + it.outputTokens }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${steps.size} 步" + if (tokens > 0) " · ${compactTokens(tokens)} tokens" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (steps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            steps.forEach { st ->
                                StepRow(st = st)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- 改动 / 文件 ----

@Composable
private fun ChangeList(files: List<ChatStore.GenFile>, onOpen: (ChatStore.GenFile) -> Unit) {
    if (files.isEmpty()) {
        EmptyHint("这条会话没有写过文件")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(files) { f ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(f) }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        f.path.substringAfterLast('/').ifBlank { f.path },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        f.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row {
                        Text(
                            "${f.content.lineSequence().count()} 行 · ${f.content.length} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "查看 ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileList(files: List<ChatStore.GenFile>, onOpen: (ChatStore.GenFile) -> Unit) {
    if (files.isEmpty()) {
        EmptyHint("没有产出文件")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(files) { f ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(f) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        f.path.substringAfterLast('/').ifBlank { f.path },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        f.path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(
                    "${f.content.lineSequence().count()} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- 进度 ----

@Composable
private fun ProgressList(turns: List<Turn>) {
    // 任务快照在 Msg.task 上，清单在 Msg.todos 上（两处分开存的）
    val items = turns.mapNotNull { t ->
        val last = t.replies.lastOrNull() ?: return@mapNotNull null
        if (last.task == null && last.todos.isEmpty()) null else Triple(t.index, last.task, last.todos)
    }
    if (items.isEmpty()) {
        EmptyHint("没有任务记录")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items) { (index, task, todos) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "第 $index 轮",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            when {
                                task?.stopped == true -> "已停止"
                                task == null -> "无任务快照"
                                else -> "${task.rounds} 轮工具"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task?.stopped == true) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    task?.goal?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (todos.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        todos.forEach { t ->
                            val done = t.status == "completed"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (done) "✓" else "○",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (done) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    t.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    task?.lastError?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ---- 上下文 ----

@Composable
private fun ContextTab(
    usage: ChatViewModel.Usage?,
    limit: Int,
    turns: List<Turn>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("最近一次请求", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    if (usage == null) {
                        Text("还没有数据", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val used = usage.prompt + usage.output
                        val pct = if (limit > 0) (used * 100L / limit).toInt().coerceIn(0, 100) else 0
                        Text(
                            "$pct% · ${compactTokens(used)} / ${compactTokens(limit)}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "系统 ${usage.systemTokens} · 工具 ${usage.toolTokens} · 消息 ${usage.messageTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "输入 ${compactTokens(usage.prompt)} · 输出 ${compactTokens(usage.output)}" +
                                    if (usage.prompt > 0) " · 缓存命中 ${usage.cacheHit * 100 / usage.prompt}%" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        val rows = turns.flatMap { t -> t.replies.map { t.index to it } }
            .filter { it.second.promptTokens > 0 || it.second.outputTokens > 0 }
        if (rows.isNotEmpty()) {
            item {
                Text("逐轮用量", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rows) { (index, msg) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "第 $index 轮",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            "输入 ${compactTokens(msg.promptTokens)} · 输出 ${compactTokens(msg.outputTokens)}" +
                                    (if (msg.elapsedMs > 0) " · ${formatDuration(msg.elapsedMs)}" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

// ---- 小部件 ----

@Composable
private fun DetailStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 37:33 这种读法：不足一分钟给秒，超过一小时给 时:分 */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val totalSec = ms / 1000
    return when {
        totalSec < 60 -> "${totalSec} 秒"
        totalSec < 3600 -> "${totalSec / 60} 分 ${totalSec % 60} 秒"
        else -> String.format(java.util.Locale.CHINA, "%d:%02d", totalSec / 3600, (totalSec % 3600) / 60)
    }
}
