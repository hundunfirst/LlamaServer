package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.server.core.ServerUiState

@Composable
fun StatusScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.serverState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val projectors by vm.projectors.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showCheckup by remember { mutableStateOf(false) }
    var showAccessHelp by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("服务状态", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showCheckup = true }) {
                Text("设备体检")
            }
            Text(
                "检查系统/内存/存储/引擎/网络等",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ① 端口（置顶）：字段 + 与「启动服务」同款的保存按钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "端口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CompactField(
                        value = settings.port,
                        onValueChange = { v ->
                            vm.updateSettings { it.copy(port = v.filter(Char::isDigit)) }
                        },
                        number = true,
                        modifier = Modifier.width(78.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { vm.saveSettings() }) { Text("保存") }
                }
                // ② API 地址（右侧 ❓ 查看接入示例）
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "API 地址",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            state.endpoint,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    IconButton(onClick = { showAccessHelp = true }) {
                        Icon(
                            Icons.Default.HelpOutline,
                            contentDescription = "接入说明",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(state.endpoint))
                    }) { Text("复制地址") }
                }
                // ③ 模型与生效参数
                val rawName = models.find { it.file.absolutePath == selectedModel }?.name
                val modelName = com.localllm.server.core.AppSettings.aliasFor(state.modelPath) ?: rawName
                Text(
                    "当前模型：${modelName ?: "未选择"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                // 文件被删后进程仍握着旧文件继续跑（Linux 语义），显示上要说清楚，否则容易误判。
                // 删文件不会触发重组，所以这里每 3 秒自己查一次。
                val runningPath = state.modelPath
                var runningFileGone by remember(runningPath) { mutableStateOf(false) }
                LaunchedEffect(runningPath) {
                    while (runningPath != null) {
                        runningFileGone = !java.io.File(runningPath).exists()
                        kotlinx.coroutines.delay(3000)
                    }
                }
                if (runningFileGone) {
                    Text(
                        "⚠ 该模型文件已被删除，服务仍在用已删文件运行；停止后请重新选择模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                val effCtx = com.localllm.server.core.AppSettings.effectiveCtx(selectedModel)
                val effOut = com.localllm.server.core.AppSettings.effectiveOut(selectedModel)
                val ctxOver = com.localllm.server.core.AppSettings.localModelParam(selectedModel)?.ctx != null
                val outOver = com.localllm.server.core.AppSettings.localModelParam(selectedModel)?.out != null
                fun k(n: Int) = if (n >= 1024) "${n / 1024}K" else "$n"
                Text(
                    "生效参数：上下文 ${k(effCtx)}${if (ctxOver) "（模型级）" else ""}" +
                            " · 输出上限 ${k(effOut)}${if (outOver) "（模型级）" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val effMmproj = com.localllm.server.core.AppSettings.effectiveMmproj(selectedModel)
                val mmprojName = projectors.find { it.file.absolutePath == effMmproj }?.name
                Text(
                    "多模态 mmproj：${mmprojName ?: "未启用"}" +
                            if (com.localllm.server.core.AppSettings.isMmprojOverridden(selectedModel)) "（模型级）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // ④ 运行信息 / 报错（下排）
                if (state.message.isNotBlank()) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val running = state.status == ServerUiState.Status.RUNNING
                if (running) {
                    if (selectedModel != null && selectedModel != state.modelPath) {
                        Text(
                            "⚠ 模型已变更，重启服务后生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (effMmproj != state.runningMmproj) {
                        Text(
                            if (effMmproj == null) "⚠ 多模态已关闭，重启服务后生效"
                            else "⚠ 多模态已变更，重启服务后生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 状态芯片与启停按钮同排
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(state.status)
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
    }

    // 接入示例弹窗（API 地址右侧 ❓ 打开）
    if (showAccessHelp) {
        AlertDialog(
            onDismissRequest = { showAccessHelp = false },
            title = { Text("客户端接入示例（Rikkahub 等）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("· 接口类型：OpenAI 兼容", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "· Base URL：${state.endpoint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text("· API Key：留空（仅本机）或你设置的令牌", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "保持本应用前台服务运行，即可在同机其他应用中调用模型。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showAccessHelp = false }) { Text("知道了") } }
        )
    }

    if (showCheckup) {
        DeviceCheckupDialog(onDismiss = { showCheckup = false })
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
