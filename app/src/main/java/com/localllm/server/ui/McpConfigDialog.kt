package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.localllm.server.core.AppSettings
import com.localllm.server.core.McpHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP MCP 服务器配置弹窗（对齐 Rikkahub 交互）：
 * 填 名称 + 流式HTTP地址 → 「连接并拉取工具」→ 工具列表逐项开关 → 保存。
 * 保存后聊天直接用缓存的工具列表注入，不再每次请求现拉。
 */
@Composable
fun McpConfigDialog(onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(AppSettings.mcpHttpEnabled) }
    var name by remember { mutableStateOf(AppSettings.mcpHttpName) }
    var url by remember { mutableStateOf(AppSettings.mcpHttpUrl) }

    var tools by remember {
        mutableStateOf(
            runCatching {
                McpHttpClient.parseTools(JSONArray(AppSettings.mcpToolsJson))
            }.getOrDefault(emptyList())
        )
    }
    var toolEnabled by remember {
        mutableStateOf(
            runCatching { JSONObject(AppSettings.mcpToolsEnabledJson) }.getOrDefault(JSONObject())
        )
    }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MCP 服务器配置", fontWeight = FontWeight.SemiBold) },
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
                    Text("启用 MCP", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（别名，如 Mt）") },
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
                                    tools = list
                                    if (list.isEmpty()) {
                                        status = "连接成功，但服务器没有返回工具"
                                    } else {
                                        status = "拉取成功：${list.size} 个工具，保存后生效"
                                    }
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
                        Spacer(modifier = Modifier.height(0.dp))
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .height(20.dp)
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
                        "工具（${tools.count { toolEnabled.optBoolean(it.name, true) }}/${tools.size} 已启用）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tools.forEach { t ->
                            val on = toolEnabled.optBoolean(t.name, true)
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
                                    checked = on,
                                    onCheckedChange = { checked ->
                                        val o = JSONObject(toolEnabled.toString())
                                        o.put(t.name, checked)
                                        toolEnabled = o
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
                AppSettings.mcpHttpEnabled = enabled
                AppSettings.mcpHttpName = name.trim()
                AppSettings.mcpHttpUrl = url.trim()
                val arr = JSONArray()
                tools.forEach { t ->
                    arr.put(
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("inputSchema", t.inputSchema)
                    )
                }
                AppSettings.mcpToolsJson = arr.toString()
                AppSettings.mcpToolsEnabledJson = toolEnabled.toString()
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
