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

            // ---- 全盘文件访问（Linux 环境需要 bind /storage）----
            // targetSdk 28 + WRITE_EXTERNAL_STORAGE = legacy storage，即 /sdcard 全盘读写；
            // 授权后 Linux 环境（run_bash）里的 /storage 与文件工具目录是同一棵树。
            var storageGranted by remember {
                mutableStateOf(
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        fileCtx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            }
            val storageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                storageGranted = grants[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                if (storageGranted) {
                    com.localllm.server.core.ServerProcessManager.appLog("已开启全盘文件访问（legacy storage）")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (storageGranted) "已开启全盘文件访问"
                    else "未开启全盘访问——Linux 环境（run_bash）的 /storage 需要它",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = {
                    storageLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }) { Text(if (storageGranted) "已授权" else "开启全盘访问") }
            }

            // ---- 工作目录回收站 ----
            // Android 11+ 删掉的文件 30 天内仍留在工作目录里（名字带 .trashed-），占空间且看不见。
            var trashCount by remember { mutableStateOf<Int?>(null) }
            var trashBytes by remember { mutableStateOf(0L) }
            var purgePending by remember { mutableStateOf(false) }
            var purging by remember { mutableStateOf(false) }
            val trashScope = androidx.compose.runtime.rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        trashCount == null -> "回收站：点右侧扫描（删掉的文件 30 天内仍占空间）"
                        trashCount == 0 -> "回收站是空的"
                        else -> "回收站里有 ${trashCount} 项，共 ${trashBytes / 1024} KB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        purging = true
                        trashScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val (n, b) = com.localllm.server.core.FileTools.trashSummary(fileCtx)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                purging = false
                                trashCount = n
                                trashBytes = b
                                if (n > 0) purgePending = true
                            }
                        }
                    },
                    enabled = !purging && workDirName.isNotBlank()
                ) { Text(if (purging) "…" else "清理回收站") }
            }
            if (purgePending) {
                AlertDialog(
                    onDismissRequest = { purgePending = false },
                    title = { Text("永久删除回收站里 ${trashCount ?: 0} 项？") },
                    text = {
                        Text(
                            "共 ${trashBytes / 1024} KB，删除后无法恢复。\n\n" +
                                    "只清理工作目录（$workDirName）下的回收站文件，不动其他位置。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            purgePending = false
                            purging = true
                            trashScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val (ok, fail) = com.localllm.server.core.FileTools.purgeTrash(fileCtx)
                                val (n, b) = com.localllm.server.core.FileTools.trashSummary(fileCtx)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    purging = false
                                    trashCount = n
                                    trashBytes = b
                                    android.widget.Toast.makeText(
                                        fileCtx,
                                        if (fail == 0) "已删除 $ok 项"
                                        else "删除 $ok 项，$fail 项失败（可能被系统占用）",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { purgePending = false }) { Text("取消") }
                    }
                )
            }

            // ---- 危险操作确认 ----
            var approvalOn by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.toolApprovalEnabled)
            }
            SectionHeader(
                title = "危险操作确认",
                help = "开启后，模型每次要执行命令（run_bash）、写入/修改文件、或调用带写语义的 MCP 工具，" +
                        "都会先弹框把具体命令或路径给你看，点「允许」才真的执行；点「总是允许」只放行本会话内同类操作。\n\n" +
                        "危险命令（删除、改权限、磁盘操作、管道执行远程脚本等）会标红，并且每次都问、不给免打扰。" +
                        "确认框 10 分钟没人回答按拒绝处理。\n\n" +
                        "关掉 = 模型可以不经确认直接改你的文件。除非你在旁边盯着，否则不建议关。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("true" to "开启（推荐）", "false" to "关闭"),
                current = approvalOn.toString()
            ) { v ->
                approvalOn = v.toBoolean()
                com.localllm.server.core.AppSettings.toolApprovalEnabled = approvalOn
            }

            // ---- 图片压缩 ----
            var imgMode by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.imageCompressMode)
            }
            SectionHeader(
                title = "图片压缩",
                help = "重编码是截图里小字、小数点糊掉的主因，所以默认不压缩：原图直传，" +
                        "只有超过 6MB 才会降采样（再大请求体会拖垮预填充）。\n\n" +
                        "自动：小图直传，大图压到长边 3072、质量 95。\n\n" +
                        "省上下文：一律压到长边 1600、质量 85——图片占的 token 最少，代价是小字容易糊。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("original" to "不压缩", "auto" to "自动", "small" to "省上下文"),
                current = imgMode
            ) { v ->
                imgMode = v
                com.localllm.server.core.AppSettings.imageCompressMode = v
            }

            // ---- 附件上限（文本文件）----
            var attachMb by remember {
                androidx.compose.runtime.mutableIntStateOf(com.localllm.server.core.AppSettings.attachmentMaxMb)
            }
            var attachPct by remember {
                androidx.compose.runtime.mutableIntStateOf(
                    com.localllm.server.core.AppSettings.attachmentBudgetPct
                )
            }
            SectionHeader(
                title = "附件上限",
                help = "文本类附件（代码/PDF/docx/xlsx 抽出的文本）的体积上限，以及它最多能占多少上下文。\n\n" +
                        "这两条只是本地兜底：用云端大模型时上下文更大，可以调高或设成不限。" +
                        "设成不限后，超长附件会一直送到模型的上下文上限为止（那时由模型端报错）。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "单个文本附件上限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            QuickChips(
                options = listOf("32" to "32MB", "64" to "64MB", "128" to "128MB", "0" to "不限"),
                current = attachMb.toString()
            ) { v ->
                attachMb = v.toInt()
                com.localllm.server.core.AppSettings.attachmentMaxMb = attachMb
            }
            Text(
                "附件最多占上下文",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("45" to "45%", "70" to "70%", "100" to "100%", "0" to "不截断"),
                current = attachPct.toString()
            ) { v ->
                attachPct = v.toInt()
                com.localllm.server.core.AppSettings.attachmentBudgetPct = attachPct
            }

            // ---- 其余本地上限 ----
            var resultPct by remember {
                androidx.compose.runtime.mutableIntStateOf(
                    com.localllm.server.core.AppSettings.toolResultBudgetPct
                )
            }
            var errChars by remember {
                androidx.compose.runtime.mutableIntStateOf(com.localllm.server.core.AppSettings.errorMaxChars)
            }
            SectionHeader(
                title = "其余本地上限",
                help = "这两条都是本地兜底，用云端大窗口时可以放开：\n\n" +
                        "· 工具结果回传上限：单个工具（读文件、搜索等）返回的内容最多占多少上下文，" +
                        "超了会砍掉中间部分。\n" +
                        "· 错误提示长度：界面上那条红字最多显示多少字。设成不限要小心——" +
                        "引擎有时会把几十 KB 的原始响应塞进错误里，铺到界面上会让页面卡死。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text("工具结果占上下文", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            QuickChips(
                options = listOf("17" to "17%", "33" to "33%", "50" to "50%", "0" to "不截断"),
                current = resultPct.toString()
            ) { v ->
                resultPct = v.toInt()
                com.localllm.server.core.AppSettings.toolResultBudgetPct = resultPct
            }
            Text("错误提示长度", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            QuickChips(
                options = listOf("300" to "300 字", "1000" to "1000 字", "0" to "不限"),
                current = errChars.toString()
            ) { v ->
                errChars = v.toInt()
                com.localllm.server.core.AppSettings.errorMaxChars = errChars
            }

            }
            GroupHeader(
                title = "运行与策略",
                summary = "思考模式、代码自检、主题、MCP 注入、轮次上限、纯净模式、定时任务",
                open = settingGroup == "g4"
            ) { settingGroup = if (settingGroup == "g4") null else "g4" }
            if (settingGroup == "g4") {
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
                help = "每轮 = 一次完整生成 + 一次工具回传。默认「不限」：任务做到模型自己不再调工具为止，\n" +
                        "想中途停下点停止按钮即可。\n\n" +
                        "以前默认 8~16 轮的理由是「每多一轮就是一次完整生成」——那是 CPU 时代的账。" +
                        "NPU 上每轮便宜一个数量级，次数上限反而成了任务做不完的主因（日志里是「轮次用尽，任务未收敛」）。\n\n" +
                        "仍然可以设成有限轮次：模型如果陷进工具循环，每一轮都是完整生成，耗电和耗时都会明显增加。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("0" to "不限", "16" to "16 轮", "32" to "32 轮", "64" to "64 轮"),
                current = roundLimit
            ) { v ->
                roundLimit = v
                com.localllm.server.core.AppSettings.toolRoundLimit = v.toIntOrNull() ?: 0
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

            // ---- 定时任务 ----
            val schedCtx = androidx.compose.ui.platform.LocalContext.current
            var schedTasks by remember {
                mutableStateOf(com.localllm.server.core.ScheduledTasks.load(schedCtx))
            }
            var schedEditing by remember {
                mutableStateOf<com.localllm.server.core.ScheduledTasks.Task?>(null)
            }
            var schedAdding by remember { mutableStateOf(false) }
            SectionHeader(
                title = "定时任务",
                help = "到点把一条指令排进任务队列，并发一条通知；点通知打开 App 就开始执行。\n\n" +
                        "为什么不是完全无人值守：后台拉起本地引擎、以及与界面共用同一份会话存储这两件事" +
                        "还没做，硬说\"全自动\"只会让你以为跑了而其实没跑。\n\n" +
                        "另：定时任务会真的耗电——锁屏时大模型跑一轮，手机是明显发热的。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            if (schedTasks.isEmpty()) {
                Text(
                    "还没有定时任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            schedTasks.forEach { task ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            task.describe() + " · " + task.prompt.take(24),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = task.enabled,
                        onCheckedChange = { on ->
                            val next = schedTasks.map {
                                if (it.id == task.id) it.copy(enabled = on) else it
                            }
                            schedTasks = next
                            com.localllm.server.core.ScheduledTasks.save(schedCtx, next)
                            if (on) com.localllm.server.core.ScheduledTasks.scheduleNext(schedCtx, task.copy(enabled = true))
                            else com.localllm.server.core.ScheduledTasks.cancel(schedCtx, task)
                        }
                    )
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable {
                                val next = schedTasks.filterNot { it.id == task.id }
                                schedTasks = next
                                com.localllm.server.core.ScheduledTasks.save(schedCtx, next)
                                com.localllm.server.core.ScheduledTasks.cancel(schedCtx, task)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = { schedAdding = true },
                modifier = Modifier.padding(top = 4.dp)
            ) { Text("添加定时任务") }
            if (schedAdding || schedEditing != null) {
                val editing = schedEditing
                var name by remember(schedAdding, editing?.id) {
                    mutableStateOf(editing?.name ?: "")
                }
                var prompt by remember(schedAdding, editing?.id) {
                    mutableStateOf(editing?.prompt ?: "")
                }
                var mode by remember(schedAdding, editing?.id) {
                    mutableStateOf(if ((editing?.everyMinutes ?: 0) > 0) "interval" else "daily")
                }
                var minutes by remember(schedAdding, editing?.id) {
                    mutableStateOf((editing?.everyMinutes ?: 60).toString())
                }
                var hourText by remember(schedAdding, editing?.id) {
                    mutableStateOf((editing?.dailyHour ?: 8).toString())
                }
                var minuteText by remember(schedAdding, editing?.id) {
                    mutableStateOf((editing?.dailyMinute ?: 0).toString())
                }
                AlertDialog(
                    onDismissRequest = { schedAdding = false; schedEditing = null },
                    title = { Text(if (editing == null) "添加定时任务" else "编辑定时任务") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CompactField(
                                value = name,
                                onValueChange = { name = it },
                                label = "名称",
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            CompactField(
                                value = prompt,
                                onValueChange = { prompt = it },
                                label = "到点要执行的指令",
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            QuickChips(
                                options = listOf("daily" to "每天定时", "interval" to "每隔一段时间"),
                                current = mode
                            ) { mode = it }
                            if (mode == "daily") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CompactField(
                                        value = hourText,
                                        onValueChange = { hourText = it.filter(Char::isDigit) },
                                        label = "时（0-23）",
                                        number = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    CompactField(
                                        value = minuteText,
                                        onValueChange = { minuteText = it.filter(Char::isDigit) },
                                        label = "分（0-59）",
                                        number = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                CompactField(
                                    value = minutes,
                                    onValueChange = { minutes = it.filter(Char::isDigit) },
                                    label = "每多少分钟",
                                    number = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val nm = name.trim().ifBlank { "定时任务" }
                            val pr = prompt.trim()
                            if (pr.isNotEmpty()) {
                                val task = com.localllm.server.core.ScheduledTasks.Task(
                                    id = editing?.id ?: ("sched-" + System.currentTimeMillis()),
                                    name = nm,
                                    prompt = pr,
                                    everyMinutes = if (mode == "interval")
                                        (minutes.toIntOrNull() ?: 60).coerceIn(5, 1440) else 0,
                                    dailyHour = (hourText.toIntOrNull() ?: 8).coerceIn(0, 23),
                                    dailyMinute = (minuteText.toIntOrNull() ?: 0).coerceIn(0, 59),
                                    enabled = true
                                )
                                val next = schedTasks.filterNot { it.id == task.id } + task
                                schedTasks = next
                                com.localllm.server.core.ScheduledTasks.save(schedCtx, next)
                                com.localllm.server.core.ScheduledTasks.scheduleNext(schedCtx, task)
                                android.widget.Toast.makeText(
                                    schedCtx, "已添加：${task.describe()}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            schedAdding = false
                            schedEditing = null
                        }) { Text("保存") }
                    },
                    dismissButton = {
                        TextButton(onClick = { schedAdding = false; schedEditing = null }) { Text("取消") }
                    }
                )
            }

            }
            GroupHeader(
                title = "搜索与网络",
                summary = "任务清单、联网搜索策略与配额",
                open = settingGroup == "g5"
            ) { settingGroup = if (settingGroup == "g5") null else "g5" }
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

/**
 * 设置分组头：一行标题 + 一句话说清这组里有什么，点一下展开/收起。
 * 加它是因为设置项只增不减，平铺下去要滑好几屏；分组后首屏只有分组标题。
 */
@Composable
private fun GroupHeader(title: String, summary: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (open) "收起" else "展开",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
