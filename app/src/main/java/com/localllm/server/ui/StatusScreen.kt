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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
                // 服务端口（从设置页迁来）：一行内联，改完保存、重启生效
                Spacer(modifier = Modifier.height(4.dp))
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
                    TextButton(
                        onClick = { vm.saveSettings() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp
                        )
                    ) { Text("保存", style = MaterialTheme.typography.labelMedium) }
                    Text(
                        "改端口后需重启服务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val rawName = models.find { it.file.absolutePath == selectedModel }?.name
                val modelName = com.localllm.server.core.AppSettings.aliasFor(state.modelPath) ?: rawName
                Text(
                    "当前模型：${modelName ?: "未选择"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                // 生效参数（模型级覆盖 → 全局），并标注来源
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
                // 提示：选配变更不会热加载，需重启服务才生效
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
