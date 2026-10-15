package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        Text("服务设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "修改后需重启服务生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("思考模式", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (settings.thinkEnabled) "开启：展示🧠思考过程（可折叠）"
                    else "关闭：抑制思考直接作答，速度优先（少数模型仍会思考）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.thinkEnabled,
                onCheckedChange = { v -> vm.updateSettings { it.copy(thinkEnabled = v) } }
            )
        }

        // ---- 聊天渠道（本地引擎 / 云端 OpenAI 兼容 API） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("云端模型渠道", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "开启后聊天走云端 OpenAI 兼容接口，不依赖本机服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.chatCloudEnabled,
                onCheckedChange = { v -> vm.updateSettings { it.copy(chatCloudEnabled = v) } }
            )
        }
        if (settings.chatCloudEnabled) {
            OutlinedTextField(
                value = settings.chatCloudUrl,
                onValueChange = { v -> vm.updateSettings { it.copy(chatCloudUrl = v) } },
                label = { Text("接口地址（如 https://api.deepseek.com）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.chatCloudKey,
                onValueChange = { v -> vm.updateSettings { it.copy(chatCloudKey = v) } },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.chatCloudModel,
                onValueChange = { v -> vm.updateSettings { it.copy(chatCloudModel = v) } },
                label = { Text("模型名（如 deepseek-chat）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ---- MCP 服务器（实验，stdio 型，llama-server 内置客户端） ----
        McpSection()

        OutlinedTextField(
            value = settings.port,
            onValueChange = { v -> vm.updateSettings { it.copy(port = v.filter(Char::isDigit)) } },
            label = { Text("端口") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.ctxSize,
            onValueChange = { v -> vm.updateSettings { it.copy(ctxSize = v.filter(Char::isDigit)) } },
            label = { Text("上下文长度 (-c)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf(
                "4096" to "4K", "8192" to "8K", "16384" to "16K", "32768" to "32K",
                "65536" to "64K", "131072" to "128K", "262144" to "256K"
            ),
            current = settings.ctxSize
        ) { v -> vm.updateSettings { it.copy(ctxSize = v) } }
        OutlinedTextField(
            value = settings.threads,
            onValueChange = { v -> vm.updateSettings { it.copy(threads = v.filter(Char::isDigit)) } },
            label = { Text("CPU 线程数（0 = 自动）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("0" to "自动", "4" to "4线程", "6" to "6线程", "8" to "8线程"),
            current = settings.threads
        ) { v -> vm.updateSettings { it.copy(threads = v) } }
        OutlinedTextField(
            value = settings.ngl,
            onValueChange = { v -> vm.updateSettings { it.copy(ngl = v.filter(Char::isDigit)) } },
            label = { Text("GPU 层数 -ngl（0 = 纯 CPU；Adreno 试 99）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("0" to "纯CPU", "20" to "20层", "33" to "33层", "99" to "全量GPU"),
            current = settings.ngl
        ) { v -> vm.updateSettings { it.copy(ngl = v) } }
        OutlinedTextField(
            value = settings.cacheTypeK,
            onValueChange = { v -> vm.updateSettings { it.copy(cacheTypeK = v) } },
            label = { Text("KV 缓存 K 类型（留空 = f16）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("" to "f16(默认)", "q8_0" to "q8_0", "q4_0" to "q4_0", "q4_1" to "q4_1"),
            current = settings.cacheTypeK
        ) { v -> vm.updateSettings { it.copy(cacheTypeK = v) } }
        OutlinedTextField(
            value = settings.cacheTypeV,
            onValueChange = { v -> vm.updateSettings { it.copy(cacheTypeV = v) } },
            label = { Text("KV 缓存 V 类型（留空 = f16）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("" to "f16(默认)", "q8_0" to "q8_0", "q4_0" to "q4_0", "q4_1" to "q4_1"),
            current = settings.cacheTypeV
        ) { v -> vm.updateSettings { it.copy(cacheTypeV = v) } }
        OutlinedTextField(
            value = settings.specType,
            onValueChange = { v -> vm.updateSettings { it.copy(specType = v) } },
            label = { Text("投机解码（none / ngram-simple / draft-mtp）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("none" to "关闭", "ngram-simple" to "ngram", "draft-mtp" to "MTP(需模型支持)"),
            current = settings.specType
        ) { v -> vm.updateSettings { it.copy(specType = v) } }
        OutlinedTextField(
            value = settings.faMode,
            onValueChange = { v -> vm.updateSettings { it.copy(faMode = v) } },
            label = { Text("Flash Attention（空=自动；CPU解码慢时试「关闭」）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("" to "自动", "on" to "开启", "off" to "关闭"),
            current = settings.faMode
        ) { v -> vm.updateSettings { it.copy(faMode = v) } }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("允许局域网访问", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "开启后监听 0.0.0.0，同 WiFi 下其他设备可接入；建议同时设置访问令牌。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.lanEnabled,
                onCheckedChange = { v -> vm.updateSettings { it.copy(lanEnabled = v) } }
            )
        }

        if (settings.lanEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用访问令牌", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "默认关闭：局域网客户端无需鉴权即可调用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.apiTokenEnabled,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(apiTokenEnabled = v) } }
                )
            }
            if (settings.apiTokenEnabled) {
                OutlinedTextField(
                    value = settings.apiToken,
                    onValueChange = { v -> vm.updateSettings { it.copy(apiToken = v) } },
                    label = { Text("访问令牌 (--api-key)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "客户端请求头需携带：Authorization: Bearer <令牌>",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { vm.generateToken() }) { Text("生成随机令牌") }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
        }

        // 悬浮保存按钮：任何滚动位置都可点击
        Button(
            onClick = { vm.saveSettings() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text("保存设置")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickChips(
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = current == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

// ---- MCP 服务器管理（llama-server 内置 MCP 客户端，stdio 型） ----
@Composable
private fun McpSection() {
    var list by remember {
        mutableStateOf(
            runCatching {
                val arr = org.json.JSONArray(com.localllm.server.core.AppSettings.mcpServersJson)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Triple(o.optString("name"), o.optString("command"), o.optString("args"))
                }
            }.getOrDefault(emptyList())
        )
    }
    var dialogOpen by remember { mutableStateOf(false) }
    var nameT by remember { mutableStateOf("") }
    var cmdT by remember { mutableStateOf("") }
    var argsT by remember { mutableStateOf("") }

    fun persist() {
        val arr = org.json.JSONArray()
        list.forEach { (n, c, a) ->
            arr.put(org.json.JSONObject().put("name", n).put("command", c).put("args", a))
        }
        com.localllm.server.core.AppSettings.mcpServersJson = arr.toString()
    }

    Text("MCP 服务器（实验）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "stdio 型：由 llama-server 拉起本地命令、收集工具并注入对话。" +
                "⚠ 命令等同于允许其在手机上执行，只添加可信 MCP。重启服务生效。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    list.forEachIndexed { i, (n, c, a) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(n, style = MaterialTheme.typography.bodyMedium)
                Text(c + (if (a.isNotBlank()) " " + a else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            TextButton(onClick = {
                nameT = n; cmdT = c; argsT = a
                list = list.filterIndexed { idx, _ -> idx != i }
                dialogOpen = true
            }) { Text("编辑") }
            TextButton(onClick = {
                list = list.filterIndexed { idx, _ -> idx != i }
                persist()
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
    Button(onClick = {
        nameT = ""; cmdT = ""; argsT = ""
        dialogOpen = true
    }, modifier = Modifier.fillMaxWidth()) { Text("添加 MCP 服务器") }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("MCP 服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nameT, { nameT = it }, label = { Text("名称（如 filesystem）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cmdT, { cmdT = it }, label = { Text("命令（如 npx）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(argsT, { argsT = it }, label = { Text("参数（空格分隔）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (nameT.isNotBlank() && cmdT.isNotBlank()) {
                        list = list + Triple(nameT.trim(), cmdT.trim(), argsT.trim())
                        persist()
                    }
                    dialogOpen = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text("取消") } }
        )
    }
}