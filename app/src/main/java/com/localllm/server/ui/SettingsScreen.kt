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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showModelDialog by remember { mutableStateOf(false) }

    val settingsScroll = rememberScrollState()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(settingsScroll)
                .padding(16.dp)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        SectionHeader(
            title = "服务设置",
            help = "修改后需重启服务生效。",
            titleStyle = MaterialTheme.typography.headlineSmall
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

        // ---- 助手指令（全局预设）----
        var sysPresets by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.loadSysPresets())
        }
        var activeSys by remember {
            mutableStateOf(
                com.localllm.server.core.AppSettings.activeSysPresetId
                    .ifBlank { com.localllm.server.core.AppSettings.loadSysPresets().firstOrNull()?.id ?: "" }
            )
        }
        var showPresetMgr by remember { mutableStateOf(false) }
        SectionHeader(
            title = "助手指令（system prompt）",
            help = "给所有会话的默认指令，可保存多套预设随时切换；单个会话可在聊天页右上角 🧠 按钮里单独覆盖。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = sysPresets.map { it.id to it.name },
            current = activeSys
        ) { v ->
            activeSys = v
            com.localllm.server.core.AppSettings.activeSysPresetId = v
        }
        OutlinedButton(onClick = { showPresetMgr = true }) { Text("管理预设…") }
        if (showPresetMgr) {
            SysPresetDialog(onDismiss = {
                showPresetMgr = false
                sysPresets = com.localllm.server.core.AppSettings.loadSysPresets()
                activeSys = com.localllm.server.core.AppSettings.activeSysPresetId
                    .ifBlank { sysPresets.firstOrNull()?.id ?: "" }
            })
        }

        // ---- 长期记忆 ----
        val memCtx = androidx.compose.ui.platform.LocalContext.current
        var memOn by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.memoryEnabled)
        }
        var memText by remember { mutableStateOf("") }
        var showMem by remember { mutableStateOf(false) }
        SectionHeader(
            title = "长期记忆",
            help = "把关于你的长期信息（偏好/背景/称呼）保存在本地 memory.md，每次对话自动注入；" +
                    "模型在你说「记住…」时会用 memory_write 工具追加。仅存本机，不上传。可随时清空。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                com.localllm.server.core.MemoryStore.load(memCtx).size.let { "$it 条记忆" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                memText = com.localllm.server.core.MemoryStore.load(memCtx).joinToString("\n")
                showMem = true
            }) { Text("查看/编辑") }
        }
        QuickChips(
            options = listOf("true" to "开启", "false" to "关闭"),
            current = memOn.toString()
        ) { v ->
            memOn = v.toBoolean()
            com.localllm.server.core.AppSettings.memoryEnabled = memOn
        }
        if (showMem) {
            AlertDialog(
                onDismissRequest = { showMem = false },
                title = { Text("长期记忆") },
                text = {
                    CompactFieldMultiline(
                        value = memText,
                        onValueChange = { memText = it },
                        label = "每行一条（可直接编辑）",
                        minHeight = 220
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            java.io.File(memCtx.filesDir, "memory.md").writeText(memText)
                        }
                        showMem = false
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        com.localllm.server.core.MemoryStore.clear(memCtx)
                        memText = ""
                        showMem = false
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                }
            )
        }

        // ---- 文件工具（授权目录）----
        val fileCtx = androidx.compose.ui.platform.LocalContext.current
        var fileOn by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.fileToolsEnabled)
        }
        var workDirName by remember {
            mutableStateOf(com.localllm.server.core.FileTools.rootName(fileCtx) ?: "")
        }
        val treeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    fileCtx.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                com.localllm.server.core.AppSettings.workDirTreeUri = uri.toString()
                workDirName = com.localllm.server.core.FileTools.rootName(fileCtx)
                    ?: (uri.lastPathSegment ?: "")
                fileOn = true
                com.localllm.server.core.AppSettings.fileToolsEnabled = true
                com.localllm.server.core.ServerProcessManager.appLog("已授权工作目录：$workDirName")
            }
        }
        SectionHeader(
            title = "文件工具（授权目录）",
            help = "授权一个文件夹后，模型可用 file_list / file_read / file_write / file_grep 在该目录内读写检索；" +
                    "写入自动建子目录。仅能访问你授权的目录，其他位置不可见。适合让它生成 HTML/代码/表格文件并直接落盘。\n\n" +
                    "提示：Android 不允许授权「内部存储根 / Download / Android/data」本身。" +
                    "请在内部存储根下新建专用文件夹（如 LlamaWork）并选中它，或选择 Download 下的子目录。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (workDirName.isBlank()) "未授权目录" else "当前目录：$workDirName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { treeLauncher.launch(null) }) { Text("选择目录") }
        }
        QuickChips(
            options = listOf("true" to "开启", "false" to "关闭"),
            current = fileOn.toString()
        ) { v ->
            fileOn = v.toBoolean()
            com.localllm.server.core.AppSettings.fileToolsEnabled = fileOn
        }

        // ---- 任务清单工具 ----
        var todoOn by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.todoToolEnabled)
        }
        SectionHeader(
            title = "任务清单工具",
            help = "开启后模型可用 todo_write 工具：多步任务先列计划、逐步执行并随时更新状态，" +
                    "聊天里会显示 📋 任务清单卡片。关闭则只做一次性回答。立即生效。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("true" to "开启", "false" to "关闭"),
            current = todoOn.toString()
        ) { v ->
            todoOn = v.toBoolean()
            com.localllm.server.core.AppSettings.todoToolEnabled = todoOn
        }

        // ---- 图片处理方式（多模态直读 vs 本地 OCR）----
        var imgMode by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.imageInputMode)
        }
        SectionHeader(
            title = "图片处理方式",
            help = "自动：有多模态（mmproj）就直读图片，否则用内置 OCR 抽文字（OCR 不上传图片，更省更快）；" +
                    "多模态：始终把图片交给视觉模型；OCR：始终本地识别文字后再提问。立即生效。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
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
        SectionHeader(
            title = "含图片时的联网搜索",
            help = "禁止：含图片的消息不提供搜索工具（证件照最安全）；" +
                    "先问我：模型想搜时弹窗展示将要发送的内容，你允许才发；允许：直接搜。立即生效。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("block" to "禁止", "ask" to "先问我", "allow" to "允许"),
            current = imgPolicy
        ) { v ->
            imgPolicy = v
            com.localllm.server.core.AppSettings.imageSearchPolicy = v
        }

        // ---- 联网搜索次数上限 ----
        var searchCapSel by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.searchMaxCalls.toString())
        }
        SectionHeader(
            title = "联网搜索次数上限",
            help = "每条消息最多允许调用几次联网搜索（相同查询复用不计数）。次数用完会移除搜索工具，强制模型基于已有结果作答；「不限」适合需要多轮检索的深度问题，弱模型可能反复搜索。立即生效。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("2" to "2 次", "3" to "3 次", "5" to "5 次", "0" to "不限"),
            current = searchCapSel
        ) { v ->
            searchCapSel = v
            com.localllm.server.core.AppSettings.searchMaxCalls = v.toIntOrNull() ?: 3
        }

        // ---- 搜索是否抓取正文 ----
        var fetchFull by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.searchFetchFullText)
        }
        SectionHeader(
            title = "搜索时抓取正文",
            help = "开启：每次搜索抓取前 3 条结果的网页正文（每篇约 1800 字）作为回答依据，回答更实在、不易照搬标题，代价是每次搜索多 1~3 秒；关闭：只用标题与摘要，更快但信息单薄。立即生效。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("true" to "开启（更实在）", "false" to "关闭（更快）"),
            current = fetchFull.toString()
        ) { v ->
            fetchFull = v.toBoolean()
            com.localllm.server.core.AppSettings.searchFetchFullText = fetchFull
        }

        // ---- 模型渠道（无开关：选谁用谁；启动本地服务自动回本地） ----
        SectionHeader(
            title = "模型渠道",
            help = "聊天用哪个模型在这里管：启动本地服务后自动使用本地模型；在「配置模型」里选择云端供应商的模型后，聊天走云端。多供应商与模型在弹窗里增删改。",
            titleStyle = MaterialTheme.typography.bodyLarge
        )
        val presetsNow = com.localllm.server.core.AppSettings.loadCloudPresets()
        val activePreset = presetsNow.firstOrNull {
            settings.chatCloudUrl.isNotBlank() &&
                    it.url.trim().removeSuffix("/") == settings.chatCloudUrl.trim().removeSuffix("/")
        }
        Text(
            "当前渠道：" + if (settings.chatCloudEnabled)
                (activePreset?.name ?: settings.chatCloudUrl.ifBlank { "未配置地址" }) +
                        " · " + settings.chatCloudModel.ifBlank { "未选模型" }
            else "本地模型（内置服务）",
            style = MaterialTheme.typography.bodyMedium
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

        // 上下文长度：只读下拉（点箭头/字段只出选项，不弹输入法）+ 自定义值
        var ctxExpanded by remember { mutableStateOf(false) }
        var customCtxOpen by remember { mutableStateOf(false) }
        var customCtxText by remember { mutableStateOf("") }
        androidx.compose.material3.ExposedDropdownMenuBox(
            expanded = ctxExpanded,
            onExpandedChange = { ctxExpanded = it }
        ) {
            CompactField(
                value = settings.ctxSize,
                onValueChange = {},
                label = "上下文长度 (-c)",
                readOnly = true,
                trailing = {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.ArrowDropDown,
                        contentDescription = "展开预设",
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                modifier = Modifier
                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = ctxExpanded,
                onDismissRequest = { ctxExpanded = false }
            ) {
                listOf(
                    4096 to "4K", 8192 to "8K", 16384 to "16K", 32768 to "32K",
                    65536 to "64K", 131072 to "128K", 262144 to "256K"
                ).forEach { (v, label) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("$label（$v）") },
                        onClick = {
                            vm.updateSettings { it.copy(ctxSize = v.toString()) }
                            ctxExpanded = false
                        }
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("自定义…") },
                    onClick = {
                        customCtxText = settings.ctxSize
                        ctxExpanded = false
                        customCtxOpen = true
                    }
                )
            }
        }
        if (customCtxOpen) {
            AlertDialog(
                onDismissRequest = { customCtxOpen = false },
                title = { Text("自定义上下文长度") },
                text = {
                    CompactField(
                        value = customCtxText,
                        onValueChange = { customCtxText = it.filter(Char::isDigit) },
                        label = "token 数（512 ~ 262144）",
                        number = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = customCtxText.toIntOrNull()
                        if (v != null) vm.updateSettings { it.copy(ctxSize = v.toString()) }
                        customCtxOpen = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { customCtxOpen = false }) { Text("取消") } }
            )
        }
        CompactField(
            value = settings.threads,
            onValueChange = { v -> vm.updateSettings { it.copy(threads = v.filter(Char::isDigit)) } },
            label = "CPU 线程数（0 = 自动）",
            number = true,
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("0" to "自动", "4" to "4线程", "6" to "6线程", "8" to "8线程"),
            current = settings.threads
        ) { v -> vm.updateSettings { it.copy(threads = v) } }
        CompactField(
            value = settings.ngl,
            onValueChange = { v -> vm.updateSettings { it.copy(ngl = v.filter(Char::isDigit)) } },
            label = "GPU 层数 -ngl（0 = 纯 CPU，本机实测更快，推荐）",
            number = true,
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("0" to "纯CPU", "20" to "20层", "33" to "33层", "99" to "全量GPU"),
            current = settings.ngl
        ) { v -> vm.updateSettings { it.copy(ngl = v) } }
        CompactField(
            value = settings.cacheTypeK,
            onValueChange = { v -> vm.updateSettings { it.copy(cacheTypeK = v) } },
            label = "KV 缓存 K 类型（留空 = f16）",
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("" to "f16(默认)", "q8_0" to "q8_0", "q4_0" to "q4_0", "q4_1" to "q4_1"),
            current = settings.cacheTypeK
        ) { v -> vm.updateSettings { it.copy(cacheTypeK = v) } }
        CompactField(
            value = settings.cacheTypeV,
            onValueChange = { v -> vm.updateSettings { it.copy(cacheTypeV = v) } },
            label = "KV 缓存 V 类型（留空 = f16；量化时自动开 FA）",
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("" to "f16(默认)", "q8_0" to "q8_0", "q4_0" to "q4_0", "q4_1" to "q4_1"),
            current = settings.cacheTypeV
        ) { v -> vm.updateSettings { it.copy(cacheTypeV = v) } }
        CompactField(
            value = settings.specType,
            onValueChange = { v -> vm.updateSettings { it.copy(specType = v) } },
            label = "投机解码（none / ngram-simple / draft-mtp）",
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("none" to "关闭", "ngram-simple" to "ngram", "draft-mtp" to "MTP(需模型支持)"),
            current = settings.specType
        ) { v -> vm.updateSettings { it.copy(specType = v) } }
        CompactField(
            value = settings.faMode,
            onValueChange = { v -> vm.updateSettings { it.copy(faMode = v) } },
            label = "Flash Attention（空=自动；CPU解码慢时试「关闭」）",
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

        // ---- 版本与更新 ----
        val appCtx = androidx.compose.ui.platform.LocalContext.current
        val appVer = remember {
            runCatching {
                appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        var updateInfo by remember {
            mutableStateOf<com.localllm.server.core.UpdateChecker.Info?>(null)
        }
        var checking by remember { mutableStateOf(false) }
        val updateScope = androidx.compose.runtime.rememberCoroutineScope()
        SectionHeader(
            title = "版本与更新",
            help = "从 GitHub Releases 检查新版本。发现新版会显示更新说明，点「下载」用浏览器下载 APK，安装由系统确认。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当前版本：v$appVer", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    checking = true
                    updateScope.launch {
                        val info = com.localllm.server.core.UpdateChecker.check(appVer)
                        checking = false
                        if (info == null) {
                            android.widget.Toast.makeText(
                                appCtx, "已是最新版本", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            updateInfo = info
                        }
                    }
                },
                enabled = !checking
            ) { Text(if (checking) "检查中…" else "检查更新") }
        }
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text("发现新版本 v${info.version}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(info.notes.take(600), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            appCtx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(info.apkUrl)
                                )
                            )
                        }
                        updateInfo = null
                    }) { Text("下载") }
                },
                dismissButton = { TextButton(onClick = { updateInfo = null }) { Text("稍后") } }
            )
        }


        Spacer(modifier = Modifier.height(80.dp))
        }

        // 悬浮保存按钮：仅在「有未保存改动」时出现；滚动查看内容时自动隐藏，停手 0.7s 再浮现
        val dirty by vm.settingsDirty.collectAsStateWithLifecycle()
        var fabVisible by remember { mutableStateOf(true) }
        LaunchedEffect(settingsScroll) {
            var last = settingsScroll.value
            snapshotFlow { settingsScroll.value }.collectLatest { v ->
                val down = v > last
                last = v
                if (down) fabVisible = false
                kotlinx.coroutines.delay(700) // 滚动停止后重新出现
                fabVisible = true
            }
        }
        if (dirty && fabVisible) {
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

    SectionHeader(
        title = "MCP 工具（HTTP）",
        help = "远程流式 HTTP 端点（如 MT 管理器）。可添加多个服务器，每个可单独启停；" +
                "聊天时自动注入已启用服务器的工具（以 mcp__服务器__工具 前缀防冲突）。"
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

/** 助手指令预设管理弹窗 */
@Composable
private fun SysPresetDialog(onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(com.localllm.server.core.AppSettings.loadSysPresets()) }
    var editing by remember { mutableStateOf<com.localllm.server.core.SysPreset?>(null) }
    var isNew by remember { mutableStateOf(false) }

    fun persist(newList: List<com.localllm.server.core.SysPreset>) {
        list = newList
        com.localllm.server.core.AppSettings.saveSysPresets(newList)
        if (com.localllm.server.core.AppSettings.activeSysPresetId.isBlank()) {
            com.localllm.server.core.AppSettings.activeSysPresetId = newList.first().id
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("助手指令预设") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                list.forEachIndexed { i, pr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pr.name, style = MaterialTheme.typography.bodyMedium)
                            if (pr.text.isNotBlank()) {
                                Text(
                                    pr.text.take(50),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        TextButton(onClick = {
                            editing = pr
                            isNew = false
                        }) { Text("编辑") }
                        TextButton(onClick = {
                            if (list.size > 1) {
                                persist(list.filterIndexed { k, _ -> k != i })
                            }
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedButton(onClick = {
                    editing = com.localllm.server.core.SysPreset(
                        java.util.UUID.randomUUID().toString(), "新预设", ""
                    )
                    isNew = true
                }) { Text("新增预设") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )

    editing?.let { pr ->
        var name by remember(pr.id) { mutableStateOf(pr.name) }
        var text by remember(pr.id) { mutableStateOf(pr.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "新增预设" else "编辑预设") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactField(
                        value = name,
                        onValueChange = { name = it },
                        label = "名称",
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactFieldMultiline(
                        value = text,
                        onValueChange = { text = it },
                        label = "指令内容",
                        minHeight = 140
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = pr.copy(name = name.trim().ifBlank { "预设" }, text = text.trim())
                    val newList = if (isNew) list + updated
                                  else list.map { if (it.id == pr.id) updated else it }
                    persist(newList)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}
