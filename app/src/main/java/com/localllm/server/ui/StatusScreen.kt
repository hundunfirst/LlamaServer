package com.localllm.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.server.core.HealthCheck
import com.localllm.server.core.ServerUiState
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.serverState.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val selectedMmproj by vm.selectedMmproj.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val projectors by vm.projectors.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var healthItems by remember { mutableStateOf<List<HealthCheck.Item>?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("服务状态", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            healthItems = try {
                                HealthCheck.run(context)
                            } catch (e: Exception) {
                                listOf(HealthCheck.Item(HealthCheck.Level.FAIL, "体检失败", e.message ?: "未知错误"))
                            }
                            checking = false
                        }
                    }
                },
                enabled = !checking
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("体检中…")
                } else {
                    Text("设备体检")
                }
            }
            Text(
                "检查系统/内存/存储/引擎/网络等",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(state.status)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("API 地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.endpoint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(state.endpoint))
                    }) { Text("复制地址") }
                }
                val rawName = models.find { it.file.absolutePath == selectedModel }?.name
                val modelName = com.localllm.server.core.AppSettings.aliasFor(state.modelPath) ?: rawName
                Text(
                    "当前模型：${modelName ?: "未选择"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val mmprojName = projectors.find { it.file.absolutePath == selectedMmproj }?.name
                Text(
                    "多模态 mmproj：${mmprojName ?: "未启用"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 提示：选配变更不会热加载，需重启服务才生效
                val running = state.status == ServerUiState.Status.RUNNING
                if (running) {
                    if (selectedModel != null && selectedModel != state.modelPath) {
                        Text(
                            "⚠ 模型已变更，重启服务后生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (selectedMmproj != state.runningMmproj) {
                        Text(
                            if (selectedMmproj == null) "⚠ 多模态已关闭，重启服务后生效"
                            else "⚠ 多模态已变更，重启服务后生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = vm::startServer,
                enabled = state.status == ServerUiState.Status.STOPPED ||
                        state.status == ServerUiState.Status.ERROR
            ) { Text("启动服务") }
            OutlinedButton(
                onClick = vm::stopServer,
                enabled = state.status == ServerUiState.Status.RUNNING ||
                        state.status == ServerUiState.Status.STARTING
            ) { Text("停止服务") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("客户端接入示例（Rikkahub 等）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("· 接口类型：OpenAI 兼容", style = MaterialTheme.typography.bodySmall)
                Text("· Base URL：${state.endpoint}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("· API Key：留空（仅本机）或你设置的令牌", style = MaterialTheme.typography.bodySmall)
                Text(
                    "保持本应用前台服务运行，即可在同机其他应用中调用模型。",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start
                )
            }
        }
    }

    healthItems?.let { items ->
        val okCount = items.count { it.level == HealthCheck.Level.OK }
        val warnCount = items.count { it.level == HealthCheck.Level.WARN }
        val failCount = items.count { it.level == HealthCheck.Level.FAIL }
        AlertDialog(
            onDismissRequest = { healthItems = null },
            title = { Text("设备体检结果") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "共${items.size}项：${okCount}通过 ${warnCount}警告 ${failCount}失败",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items) { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.CenterVertically)
                                        .background(
                                            when (item.level) {
                                                HealthCheck.Level.OK -> Color(0xFF43A047)
                                                HealthCheck.Level.WARN -> Color(0xFFFB8C00)
                                                HealthCheck.Level.FAIL -> MaterialTheme.colorScheme.error
                                                HealthCheck.Level.INFO -> MaterialTheme.colorScheme.outline
                                            },
                                            CircleShape
                                        )
                                )
                                Column {
                                    Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        item.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { healthItems = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val sb = StringBuilder()
                    sb.appendLine("设备体检报告（共${items.size}项：${okCount}通过 ${warnCount}警告 ${failCount}失败）")
                    items.forEach { i ->
                        val tag = when (i.level) {
                            HealthCheck.Level.OK -> "[通过]"
                            HealthCheck.Level.WARN -> "[警告]"
                            HealthCheck.Level.FAIL -> "[失败]"
                            HealthCheck.Level.INFO -> "[信息]"
                        }
                        sb.appendLine("$tag ${i.title}：${i.detail}")
                    }
                    clipboard.setText(AnnotatedString(sb.toString()))
                }) { Text("复制报告") }
            }
        )
    }
}

@Composable
private fun StatusChip(status: ServerUiState.Status) {
    val (label, color) = when (status) {
        ServerUiState.Status.RUNNING -> "运行中" to MaterialTheme.colorScheme.primary
        ServerUiState.Status.STARTING -> "启动中" to MaterialTheme.colorScheme.secondary
        ServerUiState.Status.ERROR -> "出错" to MaterialTheme.colorScheme.error
        ServerUiState.Status.STOPPED -> "已停止" to MaterialTheme.colorScheme.outline
    }
    SuggestionChip(onClick = {}, label = { Text(label, color = color, fontWeight = FontWeight.Bold) })
}
