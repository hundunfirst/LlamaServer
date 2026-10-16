package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.server.core.McpHttpClient
import com.localllm.server.core.McpHttpServer
import com.localllm.server.core.McpHttpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 单个 HTTP MCP 服务器的配置弹窗（新建 initial=null / 编辑传入已有条目）。
 * 流程对齐 Rikkahub：填 名称+流式HTTP地址 → 「连接并拉取工具」→ 工具逐项开关 → 保存回列表。
 */
@Composable
fun McpConfigDialog(
    initial: McpHttpServer?,
    onDismiss: () -> Unit,
    onSave: (McpHttpServer) -> Unit
) {
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var tools by remember { mutableStateOf(initial?.tools ?: emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "添加 MCP 服务器" else "编辑 MCP 服务器", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（如 Mt）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("流式 HTTP 服务器地址") },
                    placeholder = { Text("http://127.0.0.1:8787/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val u = url.trim()
                            if (u.isBlank()) {
                                status = "请先填写服务器地址"
                                return@Button
                            }
                            loading = true
                            status = null
                            scope.launch {
                                try {
                                    val list = withContext(Dispatchers.IO) {
                                        McpHttpClient.invalidate(u)
                                        McpHttpClient.listTools(u, null)
                                    }
                                    // 保留同名工具原有的开关状态
                                    val old = tools.associateBy { it.name }
                                    tools = list.map { t ->
                                        McpHttpTool(
                                            name = t.name,
                                            description = t.description,
                                            inputSchema = t.inputSchema,
                                            enabled = old[t.name]?.enabled ?: true
                                        )
                                    }
                                    status = if (list.isEmpty()) "连接成功，但服务器没有返回工具"
                                             else "拉取成功：${list.size} 个工具，保存后生效"
                                } catch (e: Exception) {
                                    status = "拉取失败：${e.message}"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        enabled = !loading
                    ) { Text("连接并拉取工具") }
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = 12.dp).size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("拉取失败")) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (tools.isNotEmpty()) {
                    Text(
                        "工具（${tools.count { it.enabled }}/${tools.size} 已启用）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tools.forEachIndexed { idx, t ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (t.description.isNotBlank()) {
                                        Text(
                                            t.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                    }
                                }
                                Switch(
                                    checked = t.enabled,
                                    onCheckedChange = { on ->
                                        tools = tools.toMutableList().also { it[idx] = t.copy(enabled = on) }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "尚未拉取工具。填好地址后点「连接并拉取工具」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val u = url.trim()
                if (u.isNotBlank()) {
                    onSave(
                        McpHttpServer(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { "MCP" },
                            url = u,
                            enabled = enabled,
                            tools = tools
                        )
                    )
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
