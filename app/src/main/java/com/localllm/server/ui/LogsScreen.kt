package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 运行日志：
 * - 跟随策略：仅当用户停在底部（或主动点「最新」）时才自动滚到最新；
 *   上翻阅读历史时新条目绝不顶动视口。
 * - 非跟随态底部显示 ↦「最新」悬浮箭头，点击一键回到最新条目。
 * - 错误/失败行标红，便于扫读。
 */
@Composable
fun LogsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val logs by vm.logLines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // pinned = 跟随模式；用户上翻 → false（出现箭头）；点箭头/滚回底部 → true
    var pinned by remember { mutableStateOf(true) }

    // 用户上翻检测（index 变小=看历史 → 解除钉住；变大时若已贴底 → 重新钉住）
    LaunchedEffect(listState) {
        var prev = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { idx ->
            if (idx < prev) {
                pinned = false
            } else if (idx > prev && !pinned) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                if (last != null && (last.offset + last.size) - info.viewportEndOffset <= 8) {
                    pinned = true
                }
            }
            prev = idx
        }
    }

    // 有新条目：仅在跟随态（或本来就贴底）时滚动；同时用 layoutInfo 重新校准贴底状态
    LaunchedEffect(logs.size) {
        if (logs.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        val atBottom = last != null && last.index == logs.size - 1 &&
                (last.offset + last.size) - info.viewportEndOffset <= 64
        if (atBottom) pinned = true
        if (pinned) listState.animateScrollToItem(logs.size - 1)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("运行日志", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(logs.joinToString("\n")))
                        android.widget.Toast.makeText(
                            ctx, "已复制 ${logs.size} 条日志", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) { Text("复制") }
                    Text(
                        "${logs.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    OutlinedButton(onClick = vm::clearLogs) { Text("清空") }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { line ->
                    val isError = line.contains("错误") || line.contains("失败") ||
                            line.contains(" E ") || line.contains("E llama") ||
                            line.contains("E srv") || line.contains("E cmn")
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        // 兜底：万一还有超长行漏过入库截断，也不让它把排版拖死
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 非跟随态：右下角「最新」箭头，点击一键回底
        if (!pinned) {
            SmallFloatingActionButton(
                onClick = {
                    pinned = true
                    scope.launch {
                        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text("最新 ↓", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
