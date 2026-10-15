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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DownloadScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val browsingRepo by vm.browsingRepo.collectAsStateWithLifecycle()
    val repoFiles by vm.repoFiles.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var pendingFile by remember { mutableStateOf<Pair<String, String>?>(null) } // repo to path
    var dlExpanded by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (searchResults.isEmpty()) vm.showRecommendedRepos()
    }
    // 进入仓库浏览时自动收起下载区，把屏幕还给文件列表
    LaunchedEffect(browsingRepo) {
        if (browsingRepo != null) dlExpanded = false
    }

    pendingFile?.let { (repo, path) ->
        AlertDialog(
            onDismissRequest = { pendingFile = null },
            title = { Text("选择保存类型") },
            text = { Text("下载后存为哪一类？（与文件名无关）\n\n$path") },
            confirmButton = {
                TextButton(onClick = {
                    pendingFile = null
                    vm.startDownload(repo, path, asProjector = false)
                }) { Text("对话模型") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingFile = null }) { Text("取消") }
                    TextButton(onClick = {
                        pendingFile = null
                        vm.startDownload(repo, path, asProjector = true)
                    }) { Text("多模态投影") }
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("在线下载（hf-mirror.com）", style = MaterialTheme.typography.headlineSmall)

        // 下载任务区：折叠摘要条（默认收起，带总网速与进度）
        if (downloads.isNotEmpty()) {
            val active = downloads.filter { !it.finished && it.error == null }
            val totalSpeed = active.sumOf { it.speedBps }
            val overall = if (active.isNotEmpty() && active.all { it.totalBytes > 0 }) {
                active.sumOf { it.readBytes }.toFloat() / active.sumOf { it.totalBytes }
            } else -1f
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dlExpanded = !dlExpanded }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "下载任务 (${active.size})" +
                                if (totalSpeed > 0) " · ${formatSpeed(totalSpeed)}/s" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (dlExpanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!dlExpanded && overall >= 0f) {
                    LinearProgressIndicator(
                        progress = { overall.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (dlExpanded) {
                    downloads.forEach { d ->
                        DownloadCard(
                            state = d,
                            onCancel = { vm.cancelDownload(d.id) },
                            onDismiss = { vm.dismissDownload(d.id) },
                            onPause = { vm.pauseDownload(d.id) },
                            onResume = { vm.resumeDownload(d.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(12.dp))
        // 智能提示（浏览器式）：聚焦搜索框显示内置模型名，输入即过滤，点击直达搜索
        if (false) { /* 已按用户要求移除快捷搜索提示 */ }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("搜索模型（如 Qwen2.5-VL）") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.searchRepos(query) }, enabled = !searching) {
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (browsingRepo == null) {
            Text(
                if (searchResults.isEmpty()) "输入关键词搜索，或从下方推荐开始："
                else "选择一个仓库（含 GGUF 的排前）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(searchResults, key = { it.id }) { repo ->
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openRepo(repo.id) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(repo.id, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            val caps = com.localllm.server.core.HfMirrorClient.capabilityLabels(repo)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                caps.forEach { cap ->
                                    androidx.compose.material3.SuggestionChip(
                                        onClick = {},
                                        label = { Text(cap, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                if (repo.downloads > 0) {
                                    Text(
                                        "下载 ${repo.downloads}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::closeRepo) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(browsingRepo!!, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            // 模型介绍入口：不拉文档，一键跳浏览器看原文
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://hf-mirror.com/${browsingRepo}")
                        )
                    )
                }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "查看模型介绍与详情",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text("浏览器打开 →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (repoFiles.isEmpty()) {
                Text("读取中或该仓库没有 .gguf 文件…", style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(repoFiles, key = { browsingRepo + "/" + it.path }) { f ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (f.sizeBytes > 0) formatSize2(f.sizeBytes) else "大小未知",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            val anyActive = downloads.any { !it.finished && it.error == null }
                            Button(
                                onClick = { pendingFile = Pair(browsingRepo!!, f.path) },
                                enabled = downloads.size < 3 || !anyActive
                            ) { Text("下载") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    state: MainViewModel.DownloadState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下载：${state.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (state.error == null && !state.finished) {
                    if (state.paused) {
                        TextButton(onClick = onResume) { Text("继续") }
                    } else {
                        TextButton(onClick = onPause) { Text("暂停") }
                    }
                    TextButton(onClick = onCancel) { Text("取消") }
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            }
            // 已下载 / 总大小 · 实时网速（同一行）
            if (state.error == null && !state.finished) {
                val sizeText = if (state.totalBytes > 0) {
                    "${formatSize2(state.readBytes)} / ${formatSize2(state.totalBytes)}"
                } else {
                    "${formatSize2(state.readBytes)}（已收）"
                }
                val speedText = when {
                    state.paused -> " · ⏸ 已暂停（断点已保留）"
                    state.speedBps > 0 -> " · ${formatSpeed(state.speedBps)}/s"
                    else -> ""
                }
                Text(
                    sizeText + speedText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                state.error != null ->
                    Text("失败：${state.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                state.finished ->
                    Text("✅ 完成，已自动选中（可在模型页调整）", color = MaterialTheme.colorScheme.primary)
                else -> {
                    if (state.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (state.readBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private fun formatSize2(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
}

private fun formatSpeed(bytesPerSec: Long): String {
    return if (bytesPerSec >= 1024 * 1024) {
        String.format("%.1f MB", bytesPerSec / (1024.0 * 1024.0))
    } else {
        String.format("%d KB", bytesPerSec / 1024)
    }
}
