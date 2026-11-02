package com.localllm.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.server.core.LinuxRuntime
import com.localllm.server.core.PersistentShell
import com.localllm.server.core.TerminalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端页：直接对 App 里那条常驻 bash 敲命令，不经过模型。
 *
 * 和 run_bash 用的是**同一个 shell**：这里 cd 到哪，模型下一条命令就在哪。
 * 五件配套的东西都是为「拿它排错」服务的：
 *  - 落盘（[TerminalLog]）：App 被杀掉后重开还能看到上次的报错；
 *  - 中断：跑飞了能停（代价是 shell 重建，界面会写明状态丢了）；
 *  - 历史：反复调同一个脚本时不用重敲；
 *  - 点击复制：一行报错能直接发出去；
 *  - 后台进程面板：`sleep`/`node` 之类跑起来的进程能看见、能停。
 */
@Composable
internal fun TerminalTab(sid: String) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as android.app.Application
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val logFile = remember(sid) { TerminalLog.fileFor(app, sid) }
    val lines = remember(sid) {
        mutableStateListOf<TerminalLog.Line>().apply {
            // 上次的记录（可能来自被杀掉的进程）先摆出来
            addAll(runCatching { TerminalLog.parse(if (logFile.isFile) logFile.readText() else "") }
                .getOrDefault(emptyList()))
        }
    }
    val listState = rememberLazyListState()
    var input by remember(sid) { mutableStateOf("") }
    var running by remember(sid) { mutableStateOf(false) }
    var alive by remember(sid) { mutableStateOf(PersistentShell.hasSession(sid)) }
    var cwd by remember(sid) { mutableStateOf<String?>(null) }
    var jobs by remember(sid) { mutableStateOf<List<String>>(emptyList()) }
    var ready by remember(sid) { mutableStateOf(LinuxRuntime.isReady(ctx)) }
    var preparing by remember(sid) { mutableStateOf(false) }
    val history = remember(sid, lines.size) { TerminalLog.history(lines) }

    fun persist() {
        val snapshot = lines.toList()
        scope.launch(Dispatchers.IO) {
            runCatching {
                logFile.parentFile?.mkdirs()
                logFile.writeText(TerminalLog.serialize(snapshot))
            }
        }
    }

    fun put(kind: TerminalLog.Kind, text: String) {
        if (text.isBlank()) return
        text.split('\n').forEach { lines.add(TerminalLog.Line(kind, it)) }
        while (lines.size > TerminalLog.MAX_LINES) lines.removeAt(0)
        persist()
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        input = ""
        put(TerminalLog.Kind.Input, "$ $cmd")
        running = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val initial = LinuxRuntime.workdirAbsPath(ctx) ?: "/root"
                PersistentShell.run(
                    context = ctx, sid = sid, command = cmd, initialCwd = initial,
                    cdTo = null, timeoutMs = 120_000,
                )
            }
            if (r.output.isNotBlank()) {
                put(
                    if (r.exitCode == 0) TerminalLog.Kind.Output else TerminalLog.Kind.Error,
                    r.output,
                )
            }
            if (r.timedOut) {
                put(TerminalLog.Kind.Error, "（超时 120 秒：shell 已重建，cd/env 丢失）")
            } else if (r.died) {
                // 中断按钮也是这条路：进程被杀 → EOF
                put(TerminalLog.Kind.Info, "（命令被中断或 shell 退出：下一轮会自动重建，cd/env 丢失）")
            } else if (r.exitCode != 0) {
                put(TerminalLog.Kind.Error, "（退出码 ${r.exitCode}）")
            }
            if (r.rebuilt) put(TerminalLog.Kind.Info, "（shell 已重建：之前的 cd / export 不再有效）")
            cwd = r.cwd
            alive = PersistentShell.hasSession(sid)
            jobs = r.backgroundJobs?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            running = false
        }
    }

    /** 后台进程停止：直接在当前 shell 里 kill；这是用户点的，不需要再走审批 */
    fun killJob(pid: String) {
        running = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                PersistentShell.run(
                    context = ctx, sid = sid, command = "kill $pid",
                    initialCwd = LinuxRuntime.workdirAbsPath(ctx) ?: "/root",
                    cdTo = null, timeoutMs = 20_000,
                )
            }
            put(
                if (r.exitCode == 0) TerminalLog.Kind.Info else TerminalLog.Kind.Error,
                if (r.exitCode == 0) "（已请求结束 pid $pid）" else "（kill $pid 失败：退出码 ${r.exitCode}）"
            )
            // 重新问一次还剩哪些
            val again = withContext(Dispatchers.IO) {
                PersistentShell.run(
                    context = ctx, sid = sid, command = "true",
                    initialCwd = LinuxRuntime.workdirAbsPath(ctx) ?: "/root",
                    cdTo = null, timeoutMs = 20_000,
                )
            }
            jobs = again.backgroundJobs?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            running = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ---- 状态行 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                buildString {
                    append(cwd ?: "—")
                    append(if (alive) "  ·  shell 运行中" else "  ·  shell 未建立")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (alive) {
                TextButton(onClick = {
                    PersistentShell.reset(sid)
                    alive = false
                    cwd = null
                    jobs = emptyList()
                    put(TerminalLog.Kind.Info, "（已清空 shell 会话：cd / env / 后台任务都没了）")
                }) { Text("清空", style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(lines.joinToString("\n") { it.text }))
                android.widget.Toast.makeText(ctx, "已复制全部输出", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制全部", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                lines.clear()
                persist()
            }) { Text("清屏", style = MaterialTheme.typography.labelSmall) }
        }

        // ---- 后台进程面板 ----
        if (jobs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "后台进程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                jobs.forEach { pid ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(start = 8.dp)
                    ) {
                        Text("pid $pid", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "停止",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable { killJob(pid) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (!ready) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (preparing) "正在准备 Linux 环境（解压 + 装 git/curl/python3，可能几分钟）…"
                    else "Linux 环境还没准备：首次使用要解压 rootfs 并安装基础工具",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                if (!preparing) {
                    TextButton(onClick = {
                        preparing = true
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { LinuxRuntime.prepare(ctx) }
                            preparing = false
                            ready = LinuxRuntime.isReady(ctx)
                            put(
                                TerminalLog.Kind.Info,
                                if (err.isBlank()) "（Linux 环境已就绪）" else "（准备失败：$err）"
                            )
                        }
                    }) { Text("准备环境", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        HorizontalDivider()

        // ---- 输出区 ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        "直接对这条会话的持久 shell 敲命令（不经过模型）。\n" +
                                "和模型 run_bash 用的是同一个 shell：这里 cd 到哪，模型下一条命令就在哪。\n" +
                                "点任意一行可以复制它；记录会存盘，重开 App 还在。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            itemsIndexed(lines) { _, line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    color = when (line.kind) {
                        TerminalLog.Kind.Input -> MaterialTheme.colorScheme.primary
                        TerminalLog.Kind.Error -> MaterialTheme.colorScheme.error
                        TerminalLog.Kind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    // 长行不折行：终端里换行会把表格/路径读乱，改成横向滚动
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .clickable {
                            // 点一行复制一行：排错时最常做的是把某条报错发出去
                            if (line.text.isNotBlank()) {
                                clipboard.setText(AnnotatedString(line.text))
                                android.widget.Toast.makeText(ctx, "已复制该行", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                )
            }
        }

        HorizontalDivider()

        // ---- 历史 ----
        if (history.isNotEmpty() && !running) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "历史",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                history.forEach { cmd ->
                    Text(
                        cmd.take(24),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { input = cmd }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // ---- 输入区 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp
                ),
                placeholder = {
                    Text(
                        if (running) "执行中…（可点「停止」中断）" else "输入命令，如 pwd / ls / python3 x.py",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { run(input) })
            )
            Spacer(Modifier.width(6.dp))
            if (running) {
                TextButton(onClick = {
                    // 杀掉整棵 proot 树：sh 没有「只打断前台命令、保留会话」的可靠办法
                    PersistentShell.abort(sid)
                    put(TerminalLog.Kind.Info, "（已中断：shell 会重建，cd/env 丢失）")
                }) { Text("停止", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = { run(input) }, enabled = input.isNotBlank()) {
                    Text("运行", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
