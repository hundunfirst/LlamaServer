package com.localllm.server.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ServerUiState(
    val status: Status = Status.STOPPED,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val modelPath: String? = null,
    /** 本次服务实际加载的 mmproj（可能与用户当前选配不同，需重启生效） */
    val runningMmproj: String? = null,
    val message: String = ""
) {
    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    val endpoint: String get() = "http://$host:$port/v1"
    val healthUrl: String get() = "http://$host:$port/health"
}

object ServerProcessManager {
    private const val TAG = "ServerProcess"
    private const val MAX_LOG_LINES = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    @Volatile
    private var degradedRetryUsed = false

    /** 降级重启进行中：此时读线程观察到的退出是预期行为，不置错误 */
    @Volatile
    private var restartPending = false

    /** 仅识别 GPU/显存相关的分配失败（范围过宽会误伤正常启动） */
    private fun allocFailed(): Boolean = synchronized(logBuffer) {
        logBuffer.any {
            it.contains("GPU 显存分配失败") || it.contains("failed to allocate OpenCL buffer")
        }
    }

    /** 静默结束当前子进程（不改变 UI 状态），供降级重启前清理现场 */
    private fun killCurrentQuiet() {
        val p = process ?: return
        p.destroy()
        try {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        }
        process = null
    }

    /** GPU 分配失败 → 自动降级重启一次（ngl 减半，到 0 切 CPU 引擎）；返回是否已触发 */
    private fun degradeAndRestart(
        modelPath: String, port: Int, ctxSize: Int, threads: Int, ngl: Int,
        lanEnabled: Boolean, apiToken: String, cacheTypeK: String, cacheTypeV: String,
        specType: String, mmprojPath: String?, faMode: String, apiTokenEnabled: Boolean,
        displayHost: String
    ): Boolean {
        synchronized(this) {
            if (degradedRetryUsed || ngl <= 0) return false
            degradedRetryUsed = true
            val newNgl = maxOf(ngl / 2, 0)
            appendLog("检测到显存/内存分配失败，自动降级重启：ngl=$ngl → $newNgl")
            restartPending = true
            killCurrentQuiet() // 必须先杀掉旧进程，否则重试 start() 会因"已有进程"直接返回，卡死在启动中
            _state.value = ServerUiState(
                status = ServerUiState.Status.STARTING,
                host = displayHost,
                port = port,
                modelPath = modelPath,
                runningMmproj = mmprojPath,
                message = "显存不足，已自动降级重启…"
            )
            scope.launch {
                kotlinx.coroutines.delay(500)
                start(
                    modelPath, port, ctxSize, threads, newNgl, lanEnabled, apiToken,
                    cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled
                )
            }
            return true
        }
    }

    @Volatile
    private var process: Process? = null
    private var readerJob: Job? = null
    private var healthJob: Job? = null
    private val logBuffer = ArrayDeque<String>()

    fun start(
        modelPath: String,
        port: Int,
        ctxSize: Int,
        threads: Int,
        ngl: Int,
        lanEnabled: Boolean,
        apiToken: String,
        cacheTypeK: String = "",
        cacheTypeV: String = "",
        specType: String = "none",
        mmprojPath: String? = null,
        faMode: String = "",
        apiTokenEnabled: Boolean = false
    ) {
        synchronized(this) {
            if (process?.isAlive == true) return
            degradedRetryUsed = false
            restartPending = false
            // 清空上一次启动的日志残留：否则历史分配失败会误触发本次降级
            synchronized(logBuffer) {
                logBuffer.clear()
                _logLines.value = emptyList()
            }
            val useGpu = ngl > 0
            val engineDir = try {
                BinaryInstaller.ensureInstalled(useGpu)
            } catch (e: Exception) {
                appendLog("错误：引擎安装失败: ${e.message}")
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = "引擎安装失败: ${e.message}")
                return
            }
            val binary = File(engineDir, "llama-server")
            if (!binary.exists()) {
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = "引擎不完整，请重装 APP")
                return
            }
            if (!File(modelPath).exists()) {
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = "模型文件不存在: $modelPath")
                return
            }

            // 绑定地址：局域网模式必须 0.0.0.0（否则手机本机客户端连不上回环）
            val bindHost = if (lanEnabled) "0.0.0.0" else "127.0.0.1"
            // 展示地址：局域网模式显示真实 IP，方便外部客户端填写
            val displayHost = if (lanEnabled) currentLanIp() else "127.0.0.1"
            val cmd = buildCommand(
                binary, modelPath, bindHost, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled
            )
            appendLog("启动: ${cmd.joinToString(" ")}")

            // 直接 exec（经 sh 包装会在部分 ROM 上触发 SIGABRT；本机 sh 的 ulimit -v 也是空操作，
            // 内存防护改由：源头缩图 + 分配失败自动降级 兜底，见 docs/TROUBLESHOOTING #20）
            val pb = ProcessBuilder(cmd)
                .directory(engineDir)
                .redirectErrorStream(true)
            // CPU 引擎不搜 /vendor（避免 VNDK 库遮蔽系统库）；OpenCL 引擎需要 vendor 的 libOpenCL
            pb.environment()["LD_LIBRARY_PATH"] =
                if (useGpu) engineDir.absolutePath + ":/vendor/lib64" else engineDir.absolutePath

            _state.value = ServerUiState(
                status = ServerUiState.Status.STARTING,
                host = displayHost,
                port = port,
                modelPath = modelPath,
                runningMmproj = mmprojPath,
                message = "正在启动…"
            )
            // 局域网模式下状态里的 host 仅作展示，健康检查始终走回环
            val healthHost = "127.0.0.1"

            try {
                val p = pb.start()
                process = p
            } catch (e: Exception) {
                appendLog("错误：启动失败: ${e.message}")
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = "启动失败: ${e.message}")
                return
            }

            readerJob?.cancel()
            readerJob = scope.launch {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let { appendLog(it) }
                        }
                    }
                    val exit = try { process?.waitFor() } catch (_: Exception) { null }
                    appendLog("进程已退出 (code=$exit)")
                    // GPU 分配失败导致的崩溃 → 自动降级重启
                    if (ngl > 0 && allocFailed() &&
                        degradeAndRestart(
                            modelPath, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                            cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, displayHost
                        )
                    ) {
                        return@launch
                    }
                    // 降级重启中的退出是预期；其余（含启动期加载失败）一律置错误，避免卡在"启动中"
                    if (_state.value.status != ServerUiState.Status.STOPPED && !restartPending) {
                        _state.value = ServerUiState(
                            status = ServerUiState.Status.ERROR,
                            host = displayHost,
                            port = port,
                            modelPath = modelPath,
                            message = "进程已退出 (code=$exit)，请查看日志"
                        )
                    }
                } catch (e: Exception) {
                    // 停止服务时 destroy() 会以 InterruptedIOException 中断读取——预期行为，不是崩溃
                    appendLog("日志流已关闭")
                }
            }

            healthJob?.cancel()
            healthJob = scope.launch {
                var attempts = 0
                while (attempts < 90) {
                    delay(2000)
                    attempts++
                    val p = process
                    if (p == null || !p.isAlive) return@launch
                    if (checkHealth(healthHost, port)) {
                        // 就绪即校验：若加载阶段发生过显存分配失败（进程可能仍存活），立即降级重启
                        if (ngl > 0 && allocFailed() &&
                            degradeAndRestart(
                                modelPath, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                                cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, displayHost
                            )
                        ) {
                            return@launch
                        }
                        _state.value = _state.value.copy(
                            status = ServerUiState.Status.RUNNING,
                            message = "运行中",
                            host = displayHost,
                            port = port,
                            modelPath = modelPath
                        )
                        appendLog("健康检查通过，服务就绪")
                        return@launch
                    }
                }
                // 超时未就绪：置错误，避免永远停在"启动中"
                appendLog("健康检查超时，服务未能就绪")
                if (_state.value.status == ServerUiState.Status.STARTING && !restartPending) {
                    _state.value = ServerUiState(
                        status = ServerUiState.Status.ERROR,
                        host = displayHost,
                        port = port,
                        modelPath = modelPath,
                        runningMmproj = mmprojPath,
                        message = "启动超时，请查看日志"
                    )
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            healthJob?.cancel()
            val p = process ?: run {
                _state.value = ServerUiState()
                return
            }
            appendLog("正在停止服务…")
            // 先置 STOPPED，防止读线程在 destroy 竞态窗口里把状态改成 ERROR
            _state.value = ServerUiState()
            p.destroy()
            try {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
            }
            process = null
            appendLog("服务已停止")
        }
    }

    fun clearLogs() {
        logBuffer.clear()
        _logLines.value = emptyList()
    }

    /** 取第一个活跃 IPv4（优先 wlan），用于状态页展示真实局域网地址 */
    private fun currentLanIp(): String {
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val ordered = ifaces.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            for (ni in ordered) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        return a.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    private fun buildCommand(
        binary: File,
        modelPath: String,
        host: String,
        port: Int,
        ctxSize: Int,
        threads: Int,
        ngl: Int,
        lanEnabled: Boolean,
        apiToken: String,
        cacheTypeK: String,
        cacheTypeV: String,
        specType: String,
        mmprojPath: String?,
        faMode: String,
        apiTokenEnabled: Boolean = false
    ): List<String> {
        val cmd = mutableListOf(
            binary.absolutePath,
            "-m", modelPath,
            "--host", host,
            "--port", port.toString(),
            "-c", ctxSize.toString(),
            // 现代模型（尤其多模态 Qwen-VL 等）需要完整 jinja 模板才能正确组装对话
            "--jinja"
        )
        if (threads > 0) {
            cmd += listOf("-t", threads.toString())
        }
        if (ngl > 0) {
            cmd += listOf("-ngl", ngl.toString())
        }
        // 注：纯 CPU 模式下 OpenCL 后端共存会拖慢解码（见 docs/TROUBLESHOOTING #19），
        // 根治方案为双引擎目录按 ngl 选择（进行中）；--device 只接受 GPU 设备名，不能填 cpu。
        if (lanEnabled && apiTokenEnabled && apiToken.isNotEmpty()) {
            cmd += listOf("--api-key", apiToken)
        }
        if (cacheTypeK.isNotEmpty()) {
            cmd += listOf("--cache-type-k", cacheTypeK)
        }
        if (cacheTypeV.isNotEmpty()) {
            cmd += listOf("--cache-type-v", cacheTypeV)
        }
        if (specType.isNotEmpty() && specType != "none") {
            cmd += listOf("--spec-type", specType)
            if (specType.contains("ngram")) {
                cmd += listOf("--spec-draft-n-max", "32")
            }
        }
        if (mmprojPath != null && File(mmprojPath).exists()) {
            cmd += listOf("--mmproj", mmprojPath)
        }
        if (faMode == "on" || faMode == "off") {
            cmd += listOf("-fa", faMode)
        }
        // MCP（stdio 型，llama-server 内置客户端；Cursor 兼容格式）
        runCatching {
            val arr = org.json.JSONArray(AppSettings.mcpServersJson)
            if (arr.length() > 0) {
                val servers = org.json.JSONObject()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name")
                    val command = o.optString("command")
                    if (name.isBlank() || command.isBlank()) continue
                    val entry = org.json.JSONObject().put("command", command)
                    val argsArr = org.json.JSONArray()
                    o.optString("args").trim().split(Regex("\\s+"))
                        .filter { it.isNotBlank() }.forEach { argsArr.put(it) }
                    entry.put("args", argsArr)
                    servers.put(name, entry)
                }
                if (servers.length() > 0) {
                    cmd += listOf(
                        "--mcp-servers-json",
                        org.json.JSONObject().put("mcpServers", servers).toString()
                    )
                }
            }
        }
        return cmd
    }

    private fun checkHealth(host: String, port: Int): Boolean {
        return try {
            val url = URL("http://$host:$port/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 高频噪音行（slot 调度/计时细节）——开发者可通过 logcat 查看原文，日志页直接过滤 */
    private fun isNoiseLine(line: String): Boolean =
        line.contains("slot print_timing") || line.contains("slot get_availabl") ||
                line.contains("slot launch_slot_") || line.contains("slot release") ||
                line.contains("graphs reused") || line.contains("graph reallocs")

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        if (isNoiseLine(line)) return
        val zh = translateLine(line)
        synchronized(logBuffer) {
            logBuffer.addLast(zh)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            _logLines.value = logBuffer.toList()
        }
    }

    /** 常见引擎日志的中文映射（纯字符串替换，无性能影响；原文附在后面便于排障） */
    private fun translateLine(line: String): String {
        val rules = listOf(
            "llama_server: initializing" to "引擎初始化…",
            "load_model: loading model" to "正在加载模型",
            "load_model: model loaded" to "模型加载完成",
            "loaded multimodal model" to "多模态投影加载完成",
            "listening on" to "服务已监听",
            "健康检查通过" to "服务已就绪",
            "failed to allocate OpenCL buffer" to "GPU 显存分配失败",
            "failed to allocate" to "内存分配失败",
            "mismatch between text model" to "错误：mmproj 与主模型架构不匹配",
            "you may be using wrong mmproj" to "提示：请选择配套的多模态投影文件",
            "exiting due to model loading error" to "因模型加载失败，服务退出",
            "unauthorized" to "鉴权失败：令牌不正确",
            "error while handling argument" to "启动参数错误",
            "进程已退出" to "服务进程已退出",
            "正在停止服务" to "正在停止服务",
            "服务已停止" to "服务已停止",
            "启动:" to "启动命令：",
            "load_hparams: more info" to "（可忽略）模型超参提示"
        )
        for ((en, zh) in rules) {
            if (line.contains(en)) return "$zh\n$line"
        }
        return line
    }
}
