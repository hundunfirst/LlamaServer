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

/**
 * 设置页「运行与策略」分组：代码自检、主题、MCP 注入、轮次上限、纯净模式、定时任务。抽成独立 Composable，块内状态自持。
 */
@Composable
internal fun SettingGroupRun() {

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

            // ---- adb（无线调试）免确认 ----
            var adbAuto by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.adbAutoAllow)
            }
            // 说明默认收起（点 ⓘ 才展开）：这两项的解释很长，摆在那儿太吵
            var adbHelp by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("adb 操作免确认", style = MaterialTheme.typography.bodyLarge)
                    if (adbHelp) {
                        Text(
                            "打开后模型用 adb 操作手机（点击/滑动/输入/装应用）不再逐条问你。" +
                                    "不打开的话，它一边操作、你一边在自己手机上点「允许」，两边会抢界面；" +
                                    "而且本 App 在后台时弹窗看不见，表现就是「卡住没反应」。\n" +
                                    "卸载应用、清除数据、删文件、改系统设置、重启这些仍然每次都问。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "ⓘ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { adbHelp = !adbHelp }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                androidx.compose.material3.Switch(
                    checked = adbAuto,
                    onCheckedChange = { on ->
                        adbAuto = on
                        com.localllm.server.core.AppSettings.adbAutoAllow = on
                    }
                )
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
                help = "到点由 App 自己执行这条指令，跑完发一条通知（含结果摘要，点开进会话看全文）。\n\n" +
                        "「跑在哪里」可以选本地模型，也可以直接选云端渠道：云端到点直接请求接口，" +
                        "不启本地引擎、不用先下模型，但要联网。\n\n" +
                        "任务行上点「改」可以改指令/时间/模型，不必删了重加。\n\n" +
                        "无人值守开着时：后台会自己拉起本地引擎（前台服务，会看到「推理服务」通知），" +
                        "跑完如果引擎是这次临时拉起的就顺手关掉；结果追加在你当前所在的会话里，" +
                        "和手动排队的位置一致，不会另开一条找不到的会话。\n\n" +
                        "关掉无人值守就退回老样子：只排进队列 + 发通知，点开 App 才开始跑。\n\n" +
                        "另：定时任务会真的耗电——锁屏时大模型跑一轮，手机是明显发热的。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            var schedAutoRun by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.scheduledAutoRun)
            }
            // 系统里这个 App 的通知开关如果是关的，notify() 会静默无效——任务跑完了你什么都不知道。
            // 这里当场提示并给一个跳转，别让人去猜为什么没通知。
            val nm = androidx.core.app.NotificationManagerCompat.from(schedCtx)
            if (!nm.areNotificationsEnabled()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "通知是关的：任务跑完不会有任何提示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "去开启",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                runCatching {
                                    schedCtx.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                        ).putExtra(
                                            android.provider.Settings.EXTRA_APP_PACKAGE,
                                            schedCtx.packageName
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("无人值守（后台自动执行）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (schedAutoRun) "到点自己在后台跑完，不用打开 App"
                        else "到点只排队+通知，点开 App 才跑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = schedAutoRun,
                    onCheckedChange = { on ->
                        schedAutoRun = on
                        com.localllm.server.core.AppSettings.scheduledAutoRun = on
                    }
                )
            }
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            // 点这一行就能改（含时间/模型/指令），不必删了重加
                            .clickable {
                                schedEditing = task
                                schedAdding = false
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Text(task.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            task.describe() + " · " + task.prompt.take(24),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 上次到底跑了没、结果如何：无人值守最怕「以为跑了其实没跑」
                        if (task.lastRunAt > 0) {
                            Text(
                                "上次 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                                    .format(java.util.Date(task.lastRunAt)) +
                                        (if (task.lastNote.isBlank()) "" else " · " + task.lastNote),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1
                            )
                        }
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
                        "改",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                schedEditing = task
                                schedAdding = false
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
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
                    mutableStateOf(
                        when {
                            (editing?.onceAt ?: 0) > 0 -> "once"
                            (editing?.everyMinutes ?: 0) > 0 -> "interval"
                            else -> "daily"
                        }
                    )
                }
                var minutes by remember(schedAdding, editing?.id) {
                    mutableStateOf((editing?.everyMinutes ?: 60).toString())
                }
                var hourText by remember(schedAdding, editing?.id) {
                    // 仅一次：时刻留空由用户自己填（默认今天，见下面的 onceDate）
                    mutableStateOf(if ((editing?.onceAt ?: 0) > 0) "" else (editing?.dailyHour ?: 8).toString())
                }
                var minuteText by remember(schedAdding, editing?.id) {
                    mutableStateOf(if ((editing?.onceAt ?: 0) > 0) "" else (editing?.dailyMinute ?: 0).toString())
                }
                // 任务级设置：用哪个模型、要不要自动拉引擎、要不要每次新开会话
                var taskModel by remember(schedAdding, editing?.id) {
                    mutableStateOf(editing?.modelPath ?: "")
                }
                var taskAutoEngine by remember(schedAdding, editing?.id) {
                    mutableStateOf(editing?.autoStartEngine ?: true)
                }
                var taskNewSession by remember(schedAdding, editing?.id) {
                    mutableStateOf(editing?.newSession ?: true)
                }
                // 一次性任务的日期；默认就是**今天**，时刻由 时/分 输入决定
                var onceDate by remember(schedAdding, editing?.id) {
                    mutableStateOf(
                        editing?.onceAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                }
                var showDatePicker by remember(schedAdding, editing?.id) {
                    mutableStateOf(false)
                }
                // 云端渠道：选预设 + 里面的模型；选了就用云端跑，不需要本地引擎
                var taskPresetId by remember(schedAdding, editing?.id) {
                    mutableStateOf(editing?.endpointPresetId ?: "")
                }
                var taskCloudModel by remember(schedAdding, editing?.id) {
                    mutableStateOf(editing?.cloudModel ?: "")
                }
                // 云端候选：(预设 id, 模型名, 显示标签)
                // 预设里没配模型列表的，用预设自己的默认模型兜底——否则这个渠道选不到
                val cloudChoices = remember(schedAdding, editing?.id) {
                    com.localllm.server.core.AppSettings.loadCloudPresets().flatMap { p ->
                        val ms = p.models.filter { it.id.isNotBlank() }
                        if (ms.isEmpty()) listOf(Triple(p.id, "", p.name))
                        else ms.map { m ->
                            Triple(p.id, m.id, "${p.name}/${m.label.ifBlank { m.id }}")
                        }
                    }
                }
                // 模型候选：模型目录里现成的 gguf（含子目录），只给名字+路径
                val modelChoices = remember(schedAdding, editing?.id) {
                    val dir = java.io.File(schedCtx.filesDir, "models")
                    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".gguf", true) }
                        .map { it.absolutePath }.sorted().take(40).toList()
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
                                options = listOf(
                                    "daily" to "每天定时",
                                    "interval" to "每隔一段时间",
                                    "once" to "仅一次"
                                ),
                                current = mode
                            ) { picked ->
                                // 切到「仅一次」：日期回到今天，时刻清空让用户自己填
                                // （原来从「每天」带过来的 08:00 会让人以为已经定好了）
                                if (picked == "once" && mode != "once") {
                                    onceDate = System.currentTimeMillis()
                                    hourText = ""
                                    minuteText = ""
                                } else if (picked != "once" && mode == "once" && hourText.isBlank()) {
                                    hourText = "8"
                                    minuteText = "0"
                                }
                                mode = picked
                            }
                            if (mode == "daily" || mode == "once") {
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
                                if (mode == "once") {
                                    // 一次性：日期默认**今天**（时刻由上面两个输入框自己填）
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val nowCal = java.util.Calendar.getInstance()
                                    val pickCal = java.util.Calendar.getInstance().apply { timeInMillis = onceDate }
                                    val tomorrowCal = (nowCal.clone() as java.util.Calendar).apply {
                                        add(java.util.Calendar.DAY_OF_MONTH, 1)
                                    }
                                    val dayNum: (java.util.Calendar) -> Int = { c ->
                                        c.get(java.util.Calendar.YEAR) * 10000 +
                                            c.get(java.util.Calendar.MONTH) * 100 +
                                            c.get(java.util.Calendar.DAY_OF_MONTH)
                                    }
                                    val dayLabel = when (dayNum(pickCal)) {
                                        dayNum(nowCal) -> "今天"
                                        dayNum(tomorrowCal) -> "明天"
                                        else -> ""
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "日期：" + (if (dayLabel.isBlank()) "" else "$dayLabel ") +
                                                java.text.SimpleDateFormat(
                                                    "yyyy-MM-dd", java.util.Locale.CHINA
                                                ).format(java.util.Date(onceDate)),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "今天",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable { onceDate = System.currentTimeMillis() }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                        Text(
                                            "改日期",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable { showDatePicker = true }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                    // 落点预览：别等存完才发现排到了明天或已经过去
                                    val hh = hourText.toIntOrNull()
                                    val mm = minuteText.toIntOrNull()
                                    if (hh == null || mm == null || hh !in 0..23 || mm !in 0..59) {
                                        Text(
                                            "还没填时刻：默认今天，时分自己填（24 小时制，如 21:30）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        val at = (pickCal.clone() as java.util.Calendar).apply {
                                            set(java.util.Calendar.HOUR_OF_DAY, hh)
                                            set(java.util.Calendar.MINUTE, mm)
                                            set(java.util.Calendar.SECOND, 0)
                                            set(java.util.Calendar.MILLISECOND, 0)
                                        }
                                        val past = at.timeInMillis <= System.currentTimeMillis()
                                        Text(
                                            if (past)
                                                "今天 $hh:$mm 已经过了 → 保存后按明天 $hh:$mm 执行"
                                            else
                                                "将于 " + java.text.SimpleDateFormat(
                                                    "MM-dd HH:mm", java.util.Locale.CHINA
                                                ).format(at.time) + " 执行一次，跑完自动关闭",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (past) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary
                                        )
                                    }
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
                            Spacer(modifier = Modifier.height(10.dp))
                            // 跑在哪里：本地模型 / 云端渠道（云端不需要启本地引擎）
                            val isCloudSel = taskPresetId.isNotBlank() &&
                                cloudChoices.any { it.first == taskPresetId }
                            Text(
                                "跑在哪里",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            QuickChips(
                                options = if (cloudChoices.isEmpty()) {
                                    listOf("local" to "本地模型")
                                } else {
                                    listOf("local" to "本地模型", "cloud" to "云端渠道")
                                },
                                current = if (isCloudSel) "cloud" else "local"
                            ) { where ->
                                if (where == "cloud") {
                                    taskModel = ""
                                    if (!isCloudSel) {
                                        cloudChoices.firstOrNull()?.let {
                                            taskPresetId = it.first
                                            taskCloudModel = it.second
                                        }
                                    }
                                } else {
                                    taskPresetId = ""
                                    taskCloudModel = ""
                                }
                            }
                            Text(
                                when {
                                    isCloudSel -> {
                                        val label = cloudChoices.firstOrNull { it.first == taskPresetId }?.third
                                            ?: taskPresetId
                                        "云端 · $label" + (if (taskCloudModel.isBlank()) "" else " / $taskCloudModel")
                                    }
                                    taskModel.isBlank() -> "跟随当前选中的模型"
                                    else -> taskModel.substringAfterLast('/')
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (isCloudSel) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    cloudChoices.forEach { (presetId, modelName, label) ->
                                        FilterChip(
                                            selected = taskPresetId == presetId && taskCloudModel == modelName,
                                            onClick = {
                                                taskPresetId = presetId
                                                taskCloudModel = modelName
                                                taskModel = ""
                                            },
                                            label = {
                                                Text("☁ $label", style = MaterialTheme.typography.labelSmall)
                                            }
                                        )
                                    }
                                }
                                Text(
                                    "云端执行：到点直接请求这个渠道，不启本地引擎、不需要下载模型；但要联网",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = taskModel.isBlank(),
                                        onClick = { taskModel = "" },
                                        label = { Text("跟随当前", style = MaterialTheme.typography.labelSmall) }
                                    )
                                    modelChoices.forEach { path ->
                                        FilterChip(
                                            selected = taskModel == path,
                                            onClick = { taskModel = path },
                                            label = {
                                                Text(
                                                    java.io.File(path).name.take(22),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        )
                                    }
                                }
                                if (cloudChoices.isEmpty()) {
                                    Text(
                                        "还没有云端渠道：在「云端对话」里配一条之后，这里就能直接选云端模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (!isCloudSel) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("自动启动服务", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "到点自己把本地引擎拉起来（用上面选的模型）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    androidx.compose.material3.Switch(
                                        checked = taskAutoEngine,
                                        onCheckedChange = { taskAutoEngine = it }
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("每次新开会话", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "任务历史单独成一条会话，不跟手动聊天混在一起",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.Switch(
                                    checked = taskNewSession,
                                    onCheckedChange = { taskNewSession = it }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val nm = name.trim().ifBlank { "定时任务" }
                            val pr = prompt.trim()
                            val hh = hourText.toIntOrNull()
                            val mm = minuteText.toIntOrNull()
                            val needTime = mode == "daily" || mode == "once"
                            if (pr.isEmpty()) {
                                android.widget.Toast.makeText(
                                    schedCtx, "指令不能为空", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else if (needTime && (hh == null || mm == null || hh !in 0..23 || mm !in 0..59)) {
                                android.widget.Toast.makeText(
                                    schedCtx, "请先填时刻（时 0-23，分 0-59）",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                // 仅一次：日期 + 时:分 → 绝对时间戳；今天这个时刻已过就顺延到明天
                                var rolledToTomorrow = false
                                val onceAt = if (mode == "once") {
                                    val at = java.util.Calendar.getInstance().apply {
                                        timeInMillis = onceDate
                                        set(java.util.Calendar.HOUR_OF_DAY, hh!!)
                                        set(java.util.Calendar.MINUTE, mm!!)
                                        set(java.util.Calendar.SECOND, 0)
                                        set(java.util.Calendar.MILLISECOND, 0)
                                        if (timeInMillis <= System.currentTimeMillis()) {
                                            rolledToTomorrow = true
                                            add(java.util.Calendar.DAY_OF_MONTH, 1)
                                        }
                                    }.timeInMillis
                                    at
                                } else 0
                                val task = com.localllm.server.core.ScheduledTasks.Task(
                                    id = editing?.id ?: ("sched-" + System.currentTimeMillis()),
                                    name = nm,
                                    prompt = pr,
                                    everyMinutes = if (mode == "interval")
                                        (minutes.toIntOrNull() ?: 60).coerceIn(5, 1440) else 0,
                                    dailyHour = (hh ?: 8).coerceIn(0, 23),
                                    dailyMinute = (mm ?: 0).coerceIn(0, 59),
                                    // 编辑时别把「已停用」悄悄打开
                                    enabled = editing?.enabled ?: true,
                                    // 编辑时保留上次执行记录，别把「跑过没」一起清掉
                                    lastRunAt = editing?.lastRunAt ?: 0,
                                    lastNote = editing?.lastNote ?: "",
                                    modelPath = taskModel.ifBlank { null },
                                    autoStartEngine = taskAutoEngine,
                                    newSession = taskNewSession,
                                    endpointPresetId = taskPresetId.ifBlank { null },
                                    cloudModel = taskCloudModel.ifBlank { null },
                                    onceAt = onceAt
                                )
                                val next = schedTasks.filterNot { it.id == task.id } + task
                                schedTasks = next
                                com.localllm.server.core.ScheduledTasks.save(schedCtx, next)
                                if (task.enabled) {
                                    com.localllm.server.core.ScheduledTasks.scheduleNext(schedCtx, task)
                                } else {
                                    com.localllm.server.core.ScheduledTasks.cancel(schedCtx, task)
                                }
                                val head = if (editing == null) "已添加：" else "已更新："
                                android.widget.Toast.makeText(
                                    schedCtx,
                                    head + task.describe() +
                                        (if (rolledToTomorrow) "（今天已过，顺延到明天）" else ""),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                schedAdding = false
                                schedEditing = null
                            }
                        }) { Text(if (editing == null) "添加" else "保存") }
                    },
                    dismissButton = {
                        TextButton(onClick = { schedAdding = false; schedEditing = null }) { Text("取消") }
                    }
                )
                // 一次性任务的日期选择
                if (showDatePicker) {
                    // DatePicker 按 UTC 解释毫秒：本地日期要先换成 UTC 零点，否则会整体偏一天
                    val localCal = java.util.Calendar.getInstance().apply { timeInMillis = onceDate }
                    val initialUtcMidnight = java.time.LocalDate.of(
                        localCal.get(java.util.Calendar.YEAR),
                        localCal.get(java.util.Calendar.MONTH) + 1,
                        localCal.get(java.util.Calendar.DAY_OF_MONTH)
                    ).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    val dpState = androidx.compose.material3.rememberDatePickerState(
                        initialSelectedDateMillis = initialUtcMidnight
                    )
                    androidx.compose.material3.DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                dpState.selectedDateMillis?.let { ms ->
                                    // 只取日期，时分沿用上面输入框
                                    val picked = java.time.Instant.ofEpochMilli(ms)
                                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                    val cur = java.util.Calendar.getInstance().apply { timeInMillis = onceDate }
                                    cur.set(java.util.Calendar.YEAR, picked.year)
                                    cur.set(java.util.Calendar.MONTH, picked.monthValue - 1)
                                    cur.set(java.util.Calendar.DAY_OF_MONTH, picked.dayOfMonth)
                                    onceDate = cur.timeInMillis
                                }
                                showDatePicker = false
                            }) { Text("确定") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                        }
                    ) {
                        androidx.compose.material3.DatePicker(state = dpState)
                    }
                }
            }

            }
