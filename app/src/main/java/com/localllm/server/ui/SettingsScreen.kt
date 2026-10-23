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
        // 思考等级：只有模板认 reasoning_effort 的模型会用到（模型页标签里能看到）
        Text(
            "思考等级（仅对模板认 reasoning_effort 的模型生效；模型页点标签可看该模型支持哪种）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        QuickChips(
            options = listOf("" to "模型默认", "low" to "低", "medium" to "中", "high" to "高"),
            current = settings.reasoningEffort
        ) { v -> vm.updateReasoningEffort(v) }

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

        // ---- 代码自检（WebView 沙箱）----
        var sandboxOn by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.webSandboxEnabled)
        }
        SectionHeader(
            title = "代码自检（内置浏览器沙箱）",
            help = "开启后模型可用 web_run：把生成的单文件网页放进内置 WebView 真的跑一次，" +
                    "拿回语法/运行期错误（带行号）、是否死循环、canvas 是否真的画出了内容、画面是否在动，" +
                    "然后照着错误自己修。\n\n" +
                    "这是让弱模型写对代码的关键——它看不到运行结果就永远不会自我纠正。" +
                    "生成的页面会以「运行预览」出现在聊天页底部，可以直接试玩。\n\n" +
                    "范围：只覆盖浏览器能运行的单文件产物（HTML/CSS/JS/SVG/canvas/WebGL），" +
                    "Python、shell 之类不在其中。自检期间预览面板会自动展开，占约 280dp 高度。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("true" to "开启", "false" to "关闭"),
            current = sandboxOn.toString()
        ) { v ->
            sandboxOn = v.toBoolean()
            com.localllm.server.core.AppSettings.webSandboxEnabled = sandboxOn
        }

        // ---- 主题 ----
        var themeMode by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.themeMode)
        }
        SectionHeader(
            title = "主题",
            help = "跟随系统：由手机深色模式决定。浅色/深色：强制切换。切换立即生效，不用重启。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
            current = themeMode
        ) { v ->
            themeMode = v
            com.localllm.server.core.AppSettings.themeMode = v
        }

        // ---- MCP 工具注入策略 ----
        var mcpMode by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.mcpToolMode)
        }
        SectionHeader(
            title = "MCP 工具注入策略",
            help = "「按需挑选」(默认)：工具多于 24 个时按当前消息筛，只注入命中的几个——" +
                    "这是为 CPU 预填充省时间，代价是模型有时看不到真正需要的那个工具。\n\n" +
                    "「全部注入」：一个不裁，模型能看到所有工具。NPU 引擎的预填充很快（12B 实测 3 秒，" +
                    "CPU 要 180 秒），所以速度上基本不是问题；但如果工具很多、模型又小，" +
                    "它在几十个工具里挑错或编造工具名的概率会上升。\n\n" +
                    "工具多又想可靠时，建议优先提高模型规模，而不是继续裁工具。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("auto" to "按需挑选", "all" to "全部注入"),
            current = mcpMode
        ) { v ->
            mcpMode = v
            com.localllm.server.core.AppSettings.mcpToolMode = v
        }

        // ---- 工具调用轮次上限 ----
        var roundLimit by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.toolRoundLimit.toString())
        }
        SectionHeader(
            title = "工具调用轮次上限",
            help = "每轮 = 一次完整生成 + 一次工具回传。多步任务（写文件 → 自检 → 改错 → 再自检）" +
                    "很容易超过 8 轮，以前撞上限就停，日志里是「轮次用尽，任务未收敛」。\n\n" +
                    "调高后长任务更容易一次做完；但模型如果陷进工具循环，每一轮都是完整生成，" +
                    "会明显更耗电、更久。NPU 引擎下每轮更便宜，可以给大一些。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("8" to "8 轮", "16" to "16 轮", "24" to "24 轮", "32" to "32 轮"),
            current = roundLimit
        ) { v ->
            roundLimit = v
            com.localllm.server.core.AppSettings.toolRoundLimit = v.toIntOrNull() ?: 16
        }

        // ---- 纯净模式 ----
        var pureOn by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.pureMode)
        }
        SectionHeader(
            title = "纯净模式（不注入任何工具）",
            help = "开启后不向模型提供任何工具：不注入内置工具，也不连接 MCP 服务器。" +
                    "工具定义是 prompt 里最占地方的部分——接了 50 多个 MCP 工具时能到 9K token，" +
                    "小模型光处理输入就要几分钟。纯聊天/问答时打开它，首字时间会明显变快。\n\n" +
                    "代价：模型不能读写文件、不能联网搜索、不能跑自检、不能调用 MCP。" +
                    "需要它动手干活时记得关掉。",
            titleStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        QuickChips(
            options = listOf("true" to "开启", "false" to "关闭"),
            current = pureOn.toString()
        ) { v ->
            pureOn = v.toBoolean()
            com.localllm.server.core.AppSettings.pureMode = pureOn
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

        // 图片处理不再有「自动 / 多模态 / OCR」档位：
        // 挂了 mmproj 的模型直接看图；纯文本模型由 ocr_image 工具按需识别文字。
        // 用户一句「识别这张图的文字」就是触发条件，不需要全局开关。

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Bonsai 优化引擎（PrismML fork）", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (settings.ngl != "0" && settings.ngl.isNotBlank())
                        "当前 ngl>0，会用原 opencl 引擎（该 fork 没有 GPU 后端），此开关暂时不生效。"
                    else
                        "纯 CPU 模式改用 PrismML fork 引擎：支持 PQ2_0/PTQ1_0 打包的 Bonsai 模型，" +
                                "PQ2_0 的 kernel 在 ARM 上有优化（Bonsai 4B PQ2_0 实测 18.4 tok/s，原引擎 9.0）。" +
                                "普通 GGUF 也兼容。保存后需重启服务生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.prismEngineEnabled,
                onCheckedChange = { v -> vm.updateSettings { it.copy(prismEngineEnabled = v) } }
            )
        }

        // ---- Hexagon NPU 引擎 ----
        var npuOn by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.npuEnabled)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hexagon NPU（高通专用）", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "走 DSP 做推理，比 GPU/CPU 快很多（本机实测 0.8B Q4_K_M：预填充 2491 tok/s、解码 69 tok/s）。" +
                            "打开后强制 ngl=99 并走 --device HTP0，会盖掉 GPU 与 Prism 两个开关。" +
                            "NPU 只吃 Q4_0/IQ4_NL/Q4_K 这类量化，三元打包（PQ2_0）用不了。保存后重启服务生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = npuOn,
                onCheckedChange = { v ->
                    npuOn = v
                    com.localllm.server.core.AppSettings.npuEnabled = v
                }
            )
        }

        // ---- 模型加载模式（llama.cpp -lm）----
        var loadMode by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.loadMode)
        }
        Text(
            "模型加载模式",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "「自动」(mmap) 是唯一能让模型大于内存还能跑的模式——权重留在文件映射里，按需换页。" +
                    "「直接读(dio)」会关闭 mmap、把权重读进内存，只在「模型装得下但系统太忙、mmap 抖得厉害」时才更快；" +
                    "模型大于内存时选它会直接内存不足（实测会触发系统重启）。" +
                    "「锁内存」会强制把模型常驻，内存不够别碰。改动在下次启动服务时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QuickChips(
            options = listOf(
                "" to "自动(默认)",
                "dio" to "直接读(dio)",
                "mmap" to "mmap",
                "mlock" to "锁内存"
            ),
            current = loadMode
        ) { v ->
            loadMode = v
            com.localllm.server.core.AppSettings.loadMode = v
        }

        // ---- 允许超出内存的模型（逃生阀）----
        var allowOver by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.allowOverMemory)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("允许超出内存的模型", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "默认关闭。关闭时，模型超过本机内存 85% 会被直接拦下——那种启动大概率把内存吃穿、" +
                            "触发系统 OOM 重启。打开后可强行启动，用于实验（例如试 35B 在 NPU 上的表现）," +
                            "风险自担。立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = allowOver,
                onCheckedChange = { v ->
                    allowOver = v
                    com.localllm.server.core.AppSettings.allowOverMemory = v
                }
            )
        }
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
            label = "投机解码（none / ngram-simple / draft-mtp；draft-mtp 需模型自带 nextn 层，本机实测更慢）",
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("none" to "关闭", "ngram-simple" to "ngram"),
            current = settings.specType
        ) { v -> vm.updateSettings { it.copy(specType = v) } }
        CompactField(
            value = settings.faMode,
            onValueChange = { v -> vm.updateSettings { it.copy(faMode = v) } },
            label = "Flash Attention（空=自动；一般别关，会慢近一半）",
            modifier = Modifier.fillMaxWidth()
        )
        QuickChips(
            options = listOf("" to "自动", "on" to "开启", "off" to "关闭"),
            current = settings.faMode
        ) { v -> vm.updateSettings { it.copy(faMode = v) } }
        // 实测（docs/TROUBLESHOOTING.md #25f）：0.5B -fa off 30 tok/s，自动 58 tok/s。
        // 这个开关点错了没有任何外部症状，只会让人觉得「手机变慢了」，所以必须当场提醒。
        if (settings.faMode == "off") {
            Text(
                "⚠ 实测：Flash Attention 关闭会掉约一半解码速度（0.5B：30 → 58 tok/s）。" +
                        "解码是内存带宽瓶颈，关闭后注意力走非融合路径、KV 读取量放大，上下文越长越慢。" +
                        "除排查兼容性问题外，建议改回「自动」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            )
        }

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

        // ---- 保活：屏幕关闭后保持服务 ----
        var keepAwake by remember {
            mutableStateOf(com.localllm.server.core.AppSettings.keepAwakeEnabled)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("屏幕关闭后保持运行", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "持有唤醒锁，避免 Doze 把推理服务冻结（否则息屏后局域网访问会断）。" +
                            "代价是耗电增加。改动在下次启动服务时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = keepAwake,
                onCheckedChange = { v ->
                    keepAwake = v
                    com.localllm.server.core.AppSettings.keepAwakeEnabled = v
                }
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
