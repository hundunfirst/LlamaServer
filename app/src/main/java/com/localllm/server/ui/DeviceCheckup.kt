package com.localllm.server.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localllm.server.core.AppSettings
import com.localllm.server.core.ServerProcessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 单条体检结果：ok=true ✅；ok=false 且 warn=true ⚠️；否则 ❌ */
data class CheckItem(
    val ok: Boolean,
    val warn: Boolean = false,
    val title: String,
    val detail: String
) {
    val icon: String get() = when {
        ok -> "✅"
        warn -> "⚠️"
        else -> "❌"
    }
}

/**
 * 设备体检：全部静态检查（不启动服务），仅网络一项实时探测。
 * 逐项回调，调用方决定 UI 刷新方式；每项独立容错，单项失败不影响整体。
 */
class DeviceCheckup(private val context: Context) {

    /** 依次执行各检查项；onItem 在调用方协程上下文回调（默认主线程） */
    suspend fun run(onItem: suspend (CheckItem) -> Unit) {
        suspend fun step(defaultTitle: String, block: suspend () -> CheckItem) {
            val item = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                CheckItem(false, true, defaultTitle, "检查失败：${e.message ?: e.javaClass.simpleName}")
            }
            onItem(item)
        }

        step("系统") { checkSystem() }
        step("CPU") { checkCpu() }
        step("内存") { checkMemory() }
        step("存储") { checkStorage() }
        step("引擎文件") { checkEngineFiles() }
        step("引擎对齐") { checkEngineDirs() }
        step("模型") { checkModels() }
        step("多模态") { checkMultimodal() }
        step("服务") { checkService() }
        step("网络") { checkNetwork() }
        step("电池") { checkBattery() }
        step("云端渠道") { checkCloud() }
        step("日志") { checkLogs() }
    }

    private fun gb(bytes: Long): String = String.format("%.1f", bytes / (1024.0 * 1024.0 * 1024.0))
    private fun mb(bytes: Long): String = String.format("%.1f", bytes / (1024.0 * 1024.0))

    private fun checkSystem(): CheckItem {
        val abis = Build.SUPPORTED_ABIS.joinToString("、")
        val bit64 = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64 位" else "32 位"
        return CheckItem(
            ok = true,
            title = "系统",
            detail = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n" +
                    "${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "ABI：$abis\n$bit64"
        )
    }

    private fun checkCpu(): CheckItem {
        val cores = Runtime.getRuntime().availableProcessors()
        return CheckItem(
            ok = cores > 0,
            warn = false,
            title = "CPU",
            detail = "逻辑核心数：$cores"
        )
    }

    private fun checkMemory(): CheckItem {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
        val low = availGb < 2.0
        return CheckItem(
            ok = !low,
            warn = low,
            title = "内存",
            detail = "总内存 ${gb(mi.totalMem)} GB，可用 ${gb(mi.availMem)} GB" +
                    if (low) "（可用不足 2 GB，加载大模型可能失败）" else ""
        )
    }

    private fun checkStorage(): CheckItem {
        val stat = StatFs(context.dataDir.path)
        val availBytes = stat.availableBytes
        val low = availBytes < 5L * 1024 * 1024 * 1024
        val filesDir = context.filesDir
        val (engSize, engCount) = dirSizeAndCount(File(filesDir, "engines"))
        return CheckItem(
            ok = !low,
            warn = low,
            title = "存储",
            detail = "应用数据可用 ${gb(availBytes)} GB" +
                    (if (low) "（不足 5 GB，请清理空间）" else "") +
                    "\nengines 目录：${mb(engSize)} MB，${engCount} 个文件\n" +
                    "filesDir：${filesDir.absolutePath}"
        )
    }

    private fun dirSizeAndCount(dir: File): Pair<Long, Int> {
        if (!dir.exists()) return 0L to 0
        var size = 0L
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                size += f.length()
                count++
            }
        }
        return size to count
    }

    private fun checkEngineFiles(): CheckItem {
        val enginesRoot = File(context.filesDir, "engines")
        if (!enginesRoot.exists()) {
            return CheckItem(
                ok = false, warn = false, title = "引擎文件",
                detail = "engines 目录不存在（❌ 引擎未安装，首次启动服务时自动释放）"
            )
        }
        val sb = StringBuilder()
        var allOk = true
        var anyEngine = false
        for (mode in listOf("cpu", "opencl")) {
            val dir = File(enginesRoot, mode)
            if (!dir.exists()) {
                sb.append("$mode/：不存在")
                sb.append("\n")
                allOk = false
                continue
            }
            anyEngine = true
            val binary = File(dir, "llama-server")
            val ggml = dir.listFiles { f -> f.isFile && f.name.startsWith("libggml") && f.name.endsWith(".so") }
                ?.sortedBy { it.name } ?: emptyList()
            if (!binary.exists()) {
                allOk = false
                sb.append("$mode/llama-server：缺失（❌）")
            } else {
                sb.append("$mode/llama-server：${mb(binary.length())} MB")
            }
            if (ggml.isEmpty()) {
                allOk = false
                sb.append("；libggml*.so：缺失（❌）")
            } else {
                sb.append("；libggml*.so ${ggml.size} 个，共 ${mb(ggml.sumOf { it.length() })} MB")
            }
            sb.append("\n")
        }
        return CheckItem(
            ok = allOk,
            warn = !allOk && anyEngine,
            title = "引擎文件",
            detail = sb.toString().trimEnd()
        )
    }

    private fun checkEngineDirs(): CheckItem {
        val enginesRoot = File(context.filesDir, "engines")
        val cpu = File(enginesRoot, "cpu").exists()
        val opencl = File(enginesRoot, "opencl").exists()
        val detail = "cpu/：${if (cpu) "存在" else "不存在"}；opencl/：${if (opencl) "存在" else "不存在"}"
        return when {
            cpu && opencl -> CheckItem(true, title = "引擎对齐", detail = detail + "（双引擎齐备）")
            cpu || opencl -> CheckItem(true, warn = true, title = "引擎对齐", detail = detail + "（仅单引擎，缺失方首次使用时释放）")
            else -> CheckItem(false, true, "引擎对齐", detail + "（引擎尚未安装，首次启动服务时释放）")
        }
    }

    private fun checkModels(): CheckItem {
        val modelsDir = File(context.filesDir, "models")
        val ggufs = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val total = ggufs.sumOf { it.length() }
        val selected = AppSettings.selectedModelPath
        val selectedOk = selected != null && File(selected).exists()
        val detail = when {
            selected == null ->
                "models/：${ggufs.size} 个 .gguf，共 ${gb(total)} GB\n未选择对话模型（启动服务前需在「模型」页选择）"
            !File(selected).exists() ->
                "models/：${ggufs.size} 个 .gguf，共 ${gb(total)} GB\n选中模型文件不存在：$selected（请重新选择）"
            else ->
                "models/：${ggufs.size} 个 .gguf，共 ${gb(total)} GB\n选中：${File(selected).name}（存在）"
        }
        return CheckItem(ok = selectedOk, warn = !selectedOk, title = "模型", detail = detail)
    }

    private fun checkMultimodal(): CheckItem {
        val projDir = File(context.filesDir, "projectors")
        val files = projDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.toList() ?: emptyList()
        val mmproj = AppSettings.selectedMmprojPath
        val mmprojOk = mmproj == null || File(mmproj).exists()
        val detail = when {
            mmproj == null ->
                "projectors/：${files.size} 个文件；未启用多模态（不附加 --mmproj）"
            !File(mmproj).exists() ->
                "projectors/：${files.size} 个文件\n选中 mmproj 不存在：${File(mmproj).name} —— 启动命令只在文件存在时追加 --mmproj，该项将被忽略"
            else ->
                "projectors/：${files.size} 个文件；已选中 ${File(mmproj).name}\n" +
                        "注：文本/视觉模型与 mmproj 是否匹配由引擎启动时校验（引擎日志报 mismatch 即不匹配），静态检查不做解析"
        }
        return CheckItem(ok = mmprojOk, warn = !mmprojOk, title = "多模态", detail = detail)
    }

    private fun checkService(): CheckItem {
        val state = ServerProcessManager.state.value
        val label = when (state.status) {
            com.localllm.server.core.ServerUiState.Status.RUNNING -> "运行中"
            com.localllm.server.core.ServerUiState.Status.STARTING -> "启动中"
            com.localllm.server.core.ServerUiState.Status.ERROR -> "出错"
            com.localllm.server.core.ServerUiState.Status.STOPPED -> "已停止"
        }
        val ok = state.status != com.localllm.server.core.ServerUiState.Status.ERROR
        val detail = "状态：$label" +
                (if (state.message.isNotBlank()) "\n${state.message}" else "") +
                "\n端点：${state.endpoint}"
        return CheckItem(ok = ok, warn = !ok, title = "服务", detail = detail)
    }

    private fun checkNetwork(): CheckItem = try {
        val conn = URL("https://hf-mirror.com").openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        try {
            val code = conn.responseCode
            val ok = code in 200..399
            CheckItem(
                ok = ok,
                warn = !ok,
                title = "网络",
                detail = if (ok) "hf-mirror.com HEAD → HTTP $code（可达）" else "hf-mirror.com HEAD → HTTP $code（可达但响应异常）"
            )
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        CheckItem(false, true, "网络", "hf-mirror.com 不可达（${e.message ?: "连接失败"}）——仅影响在线下载模型，本地推理不受影响")
    }

    private fun checkBattery(): CheckItem {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return CheckItem(false, true, "电池", "无法读取电池状态")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val state = if (plugged) "充电中" else "未充电"
        val tempText = if (tempC > 0) String.format("%.1f", tempC) + " ℃" else "未知"
        return CheckItem(ok = true, title = "电池", detail = "电量 ${pct}%，温度 $tempText，$state")
    }

    private fun checkCloud(): CheckItem {
        if (!AppSettings.chatCloudEnabled) {
            return CheckItem(true, title = "云端渠道", detail = "未启用，跳过")
        }
        val missing = buildList {
            if (AppSettings.chatCloudUrl.isBlank()) add("接口地址")
            if (AppSettings.chatCloudKey.isBlank()) add("API Key")
            if (AppSettings.chatCloudModel.isBlank()) add("模型名")
        }
        return if (missing.isEmpty()) {
            CheckItem(true, title = "云端渠道", detail = "已启用，地址/Key/模型名均已填写（${AppSettings.chatCloudModel}）")
        } else {
            CheckItem(false, false, "云端渠道", "已启用但缺少：${missing.joinToString("、")}（❌ 聊天将无法走云端）")
        }
    }

    /** 日志仅存内存（ServerProcessManager.logBuffer，最多 500 行），代码确认无外部日志目录 */
    private fun checkLogs(): CheckItem = CheckItem(
        ok = true,
        title = "日志",
        detail = "日志保存在内存（最多 500 行），不写外部存储——无日志目录可检查，跳过文件检查"
    )
}

/** 体检对话框：逐项展示 + 底部复制报告 */
@Composable
fun DeviceCheckupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<CheckItem>() }
    var running by remember { mutableStateOf(true) }

    fun start() {
        items.clear()
        running = true
        scope.launch {
            DeviceCheckup(context).run { item -> items.add(item) }
            running = false
        }
    }

    LaunchedEffect(Unit) { start() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备体检", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEach { item -> CheckItemRow(item) }
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.padding(start = 10.dp))
                        Text("正在检查…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { start() }, enabled = !running) { Text("重新体检") }
                Button(
                    onClick = {
                        val report = buildString {
                            append("设备体检报告\n")
                            append("设备：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）\n")
                            items.forEach { append(it.icon).append(" ").append(it.title).append("：").append(it.detail).append("\n") }
                        }
                        clipboard.setText(AnnotatedString(report))
                    },
                    enabled = items.isNotEmpty() && !running
                ) { Text("复制报告") }
            }
        }
    )
}

@Composable
private fun CheckItemRow(item: CheckItem) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Text(item.icon)
            Spacer(modifier = Modifier.padding(start = 6.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            item.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
