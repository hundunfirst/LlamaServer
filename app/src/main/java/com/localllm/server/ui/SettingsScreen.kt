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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
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
fun SettingsScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    // 滚动位置由 Root 持有：标签页切换会把本页移出组合，页面自己 remember 的状态会丢，
    // 于是每次都回到最顶上（设置项很多，找上次改的那条要重新翻一遍）
    settingsScroll: ScrollState = rememberScrollState()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showModelDialog by remember { mutableStateOf(false) }
    var settingGroup by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(settingsScroll)
                .padding(16.dp)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GroupHeader(
                title = "文件与图片",
                summary = "授权目录、全盘访问、回收站、危险确认、图片与附件上限",
                open = settingGroup == "g3"
            ) { settingGroup = if (settingGroup == "g3") null else "g3" }
            if (settingGroup == "g3") {
                SettingGroupFiles()
            }
            GroupHeader(
                title = "运行与策略",
                summary = "思考模式、代码自检、主题、MCP 注入、轮次上限、纯净模式、定时任务",
                open = settingGroup == "g4"
            ) { settingGroup = if (settingGroup == "g4") null else "g4" }
            if (settingGroup == "g4") {
                SettingGroupRun()
            }
            GroupHeader(
                title = "搜索与网络",
                summary = "任务清单、联网搜索策略与配额",
                open = settingGroup == "g5"
            ) { settingGroup = if (settingGroup == "g5") null else "g5" }
            if (settingGroup == "g5") {
                SettingGroupNetwork()
            }
            if (settingGroup == "g5") {
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

            // 这一段原本跟「服务设置」同组，但中间隔着别的组，展开后内容会落到下面几个组的标题后面，
            // 看起来像串行。给它自己的组头，保证每组内容紧跟自己的标题。
            }
            GroupHeader(
                title = "引擎与模型",
                summary = "模型渠道、MCP 服务器、NPU、加载模式、KV 缓存",
                open = settingGroup == "g7"
            ) { settingGroup = if (settingGroup == "g7") null else "g7" }
            if (settingGroup == "g7") {
            SectionHeader(
            title = "思考模式",
                help = "修改后需重启服务生效。",
                titleStyle = MaterialTheme.typography.bodyLarge
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

            // 规划端点：只在「规划用云端」开着时用到，选一次就固定绑它
            val planPresets = com.localllm.server.core.AppSettings.loadCloudPresets()
                .filter { it.url.isNotBlank() }
            if (planPresets.isNotEmpty()) {
                var planExpanded by remember { mutableStateOf(false) }
                var planUrl by remember { mutableStateOf(com.localllm.server.core.AppSettings.planPresetUrl) }
                val pinnedName = planPresets
                    .firstOrNull { it.url.trim().removeSuffix("/") == planUrl.trim().removeSuffix("/") }
                    ?.name
                androidx.compose.material3.ExposedDropdownMenuBox(
                    expanded = planExpanded,
                    onExpandedChange = { planExpanded = it }
                ) {
                    CompactField(
                        value = pinnedName ?: "自动（跟随聊天渠道）",
                        onValueChange = {},
                        label = "规划端点（「规划用云端」用）",
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
                        expanded = planExpanded,
                        onDismissRequest = { planExpanded = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("自动（跟随聊天渠道）") },
                            onClick = {
                                planUrl = ""
                                com.localllm.server.core.AppSettings.planPresetUrl = ""
                                planExpanded = false
                            }
                        )
                        planPresets.forEach { p ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    planUrl = p.url
                                    com.localllm.server.core.AppSettings.planPresetUrl = p.url
                                    planExpanded = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "只影响「规划用云端」这一次调用；聊天渠道不跟着变，不用切回本地。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
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
            // 输出上限（max_tokens）：原来全局写死，模型页单模型留空时也落到这里
            var outSize by remember {
                androidx.compose.runtime.mutableIntStateOf(com.localllm.server.core.AppSettings.outSize)
            }
            var outExpanded by remember { mutableStateOf(false) }
            androidx.compose.material3.ExposedDropdownMenuBox(
                expanded = outExpanded,
                onExpandedChange = { outExpanded = it }
            ) {
                CompactField(
                    value = outSize.toString(),
                    onValueChange = {},
                    label = "输出上限 (max_tokens)",
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
                    expanded = outExpanded,
                    onDismissRequest = { outExpanded = false }
                ) {
                    listOf(
                        2048 to "2K", 4096 to "4K", 8192 to "8K", 16384 to "16K",
                        32768 to "32K", 65536 to "64K"
                    ).forEach { (v, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("$label（$v）") },
                            onClick = {
                                outSize = v
                                com.localllm.server.core.AppSettings.outSize = v
                                outExpanded = false
                            }
                        )
                    }
                }
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
                label = "GPU 层数 -ngl",
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
            // 投机解码：可编辑输入 + 下拉候选。候选取自引擎 `--spec-type` 的取值列表，
            // 只列「单机单模型下真能用」的几档——需要额外草稿模型的（draft-simple / eagle3 /
            // dflash / dspark）本 App 没有侧车模型管理，摆出来只会让人以为能用；真要用仍可手打。
            var specExpanded by remember { mutableStateOf(false) }
            val specOptions = listOf(
                "none" to "关闭",
                "draft-mtp" to "模型自带 nextn 层（NPU 上实测快约 8%，CPU 上更慢）",
                "ngram-simple" to "上下文 n-gram，不需要额外模型",
                "ngram-map-k" to "n-gram，哈希表版，适合重复度高的输出",
                "ngram-mod" to "n-gram，长匹配版",
                "ngram-cache" to "复用历史输出里的重复片段"
            )
            androidx.compose.material3.ExposedDropdownMenuBox(
                expanded = specExpanded,
                onExpandedChange = { specExpanded = it }
            ) {
                CompactField(
                    value = settings.specType,
                    onValueChange = { v -> vm.updateSettings { it.copy(specType = v) } },
                    label = "投机解码（点箭头选，或直接改文字；组合多档用逗号分隔）",
                    trailing = {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.ArrowDropDown,
                            contentDescription = "展开候选",
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = specExpanded,
                    onDismissRequest = { specExpanded = false }
                ) {
                    specOptions.forEach { (value, desc) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text(value, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                vm.updateSettings { it.copy(specType = value) }
                                specExpanded = false
                            }
                        )
                    }
                }
            }
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

            }
            // 版本与更新 ----
            val appCtx = androidx.compose.ui.platform.LocalContext.current
            // 用编译期常量：PackageInfo.versionName 字段在新版 Android 上已删除，读它会抛 NoSuchFieldError
            val appVer = com.localllm.server.BuildConfig.VERSION_NAME
            var updateInfo by remember {
                mutableStateOf<com.localllm.server.core.UpdateChecker.Info?>(null)
            }
            var checking by remember { mutableStateOf(false) }
            val updateScope = androidx.compose.runtime.rememberCoroutineScope()
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
