package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showModelDialog by remember { mutableStateOf(false) }

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
                    if (settings.thinkEnabled) "开启：展示🧠思考过程（可折叠，立即生效）"
                    else "关闭：抑制思考直接作答，速度优先（立即生效，少数模型仍会思考）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.thinkEnabled,
                onCheckedChange = { v -> vm.updateThinkEnabled(v) }
            )
        }

        // ---- 图片处理方式（多模态直读 vs 本地 OCR）----
        var imgMode by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.imageInputMode)
        }
        Text(
            "图片处理方式",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "自动：有多模态（mmproj）就直读图片，否则用内置 OCR 抽文字（OCR 不上传图片，更省更快）；" +
                    "多模态：始终把图片交给视觉模型；OCR：始终本地识别文字后再提问。立即生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QuickChips(
            options = listOf("auto" to "自动", "vision" to "多模态直读", "ocr" to "OCR 提取文字"),
            current = imgMode
        ) { v ->
            imgMode = v
            com.localllm.server.core.AppSettings.imageInputMode = v
        }

        // ---- 图片轮的联网搜索策略（隐私）----
        var imgPolicy by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.imageSearchPolicy)
        }
        Text(
            "含图片时的联网搜索",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "禁止：含图片的消息不提供搜索工具（证件照最安全）；" +
                    "先问我：模型想搜时弹窗展示将要发送的内容，你允许才发；允许：直接搜。立即生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QuickChips(
            options = listOf("block" to "禁止", "ask" to "先问我", "allow" to "允许"),
            current = imgPolicy
        ) { v ->
            imgPolicy = v
            com.localllm.server.core.AppSettings.imageSearchPolicy = v
        }

        // ---- 聊天渠道（OpenAI 兼容供应商，弹窗配置） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("云端模型渠道", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "开启后聊天走云端 OpenAI 兼容接口；多供应商与模型在「配置模型」弹窗里管理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.chatCloudEnabled,
                onCheckedChange = { v -> vm.updateCloudSettings { it.copy(chatCloudEnabled = v) } }
            )
        }
        val presetsNow = com.localllm.server.core.AppSettings.loadCloudPresets()
        val activePreset = presetsNow.firstOrNull {
            settings.chatCloudUrl.isNotBlank() &&
                    it.url.trim().removeSuffix("/") == settings.chatCloudUrl.trim().removeSuffix("/")
        }
        Text(
            if (settings.chatCloudEnabled)
                "当前：" + (activePreset?.name ?: settings.chatCloudUrl.ifBlank { "未配置地址" }) +
                        " · " + settings.chatCloudModel.ifBlank { "未选模型" }
            else "当前：本地模型（内置服务）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { showModelDialog = true },
            modifier = Modifier.padding(top = 2.dp)
        ) { Text("配置模型…") }
        if (showModelDialog) {
            ModelConfigDialog(vm = vm, onDismiss = { showModelDialog = false })
        }

        // ---- MCP 服务器（实验，stdio 型，llama-server 内置客户端） ----
        McpSection()

        Text(
            "服务端口已移至「状态」页（服务状态卡片内），改动后需重启服务生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            label = { Text("GPU 层数 -ngl（0 = 纯 CPU，本机实测更快，推荐）") },
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
            label = { Text("KV 缓存 V 类型（留空 = f16；量化时自动开 FA）") },
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


// ---- MCP 工具（HTTP 多服务器）：列表卡片 + 添加/编辑弹窗 + 每服务器开关与删除 ----
@Composable
private fun McpSection() {
    var servers by remember {
        mutableStateOf(com.localllm.server.core.AppSettings.loadMcpServers())
    }
    var dialogInitial by remember { mutableStateOf<com.localllm.server.core.McpHttpServer?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }

    fun persist() {
        com.localllm.server.core.AppSettings.saveMcpServers(servers)
    }

    Text("MCP 工具（HTTP）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "远程流式 HTTP 端点（如 MT 管理器）。可添加多个服务器，每个可单独启停；" +
                "聊天时自动注入已启用服务器的工具（以 mcp__服务器__工具 前缀防冲突）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (servers.isEmpty()) {
        Text(
            "尚未添加服务器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
    servers.forEachIndexed { i, s ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${s.url} · ${s.tools.size} 个工具（启用 ${s.tools.count { it.enabled }}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Switch(
                checked = s.enabled,
                onCheckedChange = { on ->
                    servers = servers.toMutableList().also { it[i] = s.copy(enabled = on) }
                    persist()
                }
            )
            TextButton(onClick = {
                dialogInitial = s
                dialogOpen = true
            }) { Text("编辑") }
            TextButton(onClick = {
                servers = servers.filterIndexed { idx, _ -> idx != i }
                persist()
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
    Button(
        onClick = {
            dialogInitial = null
            dialogOpen = true
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("添加 MCP 服务器") }

    if (dialogOpen) {
        com.localllm.server.ui.McpConfigDialog(
            initial = dialogInitial,
            onDismiss = { dialogOpen = false },
            onSave = { updated ->
                val existing = servers.indexOfFirst { it.id == updated.id }
                servers = if (existing >= 0) {
                    servers.toMutableList().also { it[existing] = updated }
                } else {
                    servers + updated
                }
                persist()
                dialogOpen = false
            }
        )
    }
}
