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

    /** 单行字符上限：超长单行会拖死日志页的文本测量（见 capLine 说明） */
    private const val MAX_LOG_LINE_CHARS = 1200

    /** NPU 引擎下发的设备名（ggml-hexagon 注册的默认单会话） */
    private const val NPU_DEVICE = "HTP0"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())

    /** prefill（处理输入）进度 0..1；null = 不在 prefill 阶段 */
    private val _prefill = MutableStateFlow<Double?>(null)
    val prefill: StateFlow<Double?> = _prefill
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    @Volatile
    private var degradedRetryUsed = false

    /** 降级重启进行中：此时读线程观察到的退出是预期行为，不置错误 */
    @Volatile
    private var restartPending = false

    /** 上下文/KV 过大导致的 context 创建失败（与量化不支持区分开：后者重试无意义） */
    private fun ctxFailed(): Boolean = synchronized(logBuffer) {
        logBuffer.any { it.contains("failed to create llama_context") }
    }

    @Volatile
    private var ctxDegradeUsed = false

    /** MTP 退回只做一次，避免反复试 */
    @Volatile
    private var specDisabledUsed = false

    /** 上下文过大 → 自动减半重试一次 */
    private fun degradeCtxAndRestart(
        modelPath: String, port: Int, ctxSize: Int, threads: Int, ngl: Int,
        lanEnabled: Boolean, apiToken: String, cacheTypeK: String, cacheTypeV: String,
        specType: String, mmprojPath: String?, faMode: String, apiTokenEnabled: Boolean,
        displayHost: String, prismEngine: Boolean, specDisabled: Boolean = false
    ): Boolean {
        synchronized(this) {
            if (ctxDegradeUsed || ctxSize <= 2048) return false
            ctxDegradeUsed = true
            val newCtx = maxOf(ctxSize / 2, 2048)
            appendLog("上下文过大导致 context 创建失败，自动减半重试：-c $ctxSize → $newCtx")
            restartPending = true
            killCurrentQuiet()
            _state.value = ServerUiState(
                status = ServerUiState.Status.STARTING,
                host = displayHost,
                port = port,
                modelPath = modelPath,
                runningMmproj = mmprojPath,
                message = "上下文过大，已自动减半重试…"
            )
            scope.launch {
                kotlinx.coroutines.delay(500)
                start(
                    modelPath, port, newCtx, threads, ngl, lanEnabled, apiToken,
                    cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, prismEngine,
                    specDisabled = specDisabled
                )
            }
            return true
        }
    }

    /** 仅识别 GPU/显存相关的分配失败（范围过宽会误伤正常启动） */
    private fun allocFailed(): Boolean = synchronized(logBuffer) {
        logBuffer.any {
            it.contains("GPU 显存分配失败") || it.contains("failed to allocate OpenCL buffer")
        }
    }

    /** 引擎拒绝 MTP 投机解码（模型不带 nextn 层 / 该引擎未编入），两种引擎报错原文都在这 */
    private fun specFailed(): Boolean = synchronized(logBuffer) {
        logBuffer.any {
            it.contains("failed to create MTP context") || it.contains("doesn't contain MTP layers")
        }
    }

    /** MTP 起不来 → 去掉 --spec-type 重试一次，把失败从用户视野里拿掉 */
    private fun degradeSpecAndRestart(
        modelPath: String, port: Int, ctxSize: Int, threads: Int, ngl: Int,
        lanEnabled: Boolean, apiToken: String, cacheTypeK: String, cacheTypeV: String,
        specType: String, mmprojPath: String?, faMode: String, apiTokenEnabled: Boolean,
        displayHost: String, prismEngine: Boolean
    ): Boolean {
        synchronized(this) {
            if (specDisabledUsed) return false
            specDisabledUsed = true
            appendLog("MTP 投机解码未能启动，去掉该参数重试（其他设置不变）")
            restartPending = true
            killCurrentQuiet()
            _state.value = ServerUiState(
                status = ServerUiState.Status.STARTING,
                host = displayHost,
                port = port,
                modelPath = modelPath,
                runningMmproj = mmprojPath,
                message = "MTP 不可用，正在按普通模式重试…"
            )
            scope.launch {
                kotlinx.coroutines.delay(500)
                start(
                    modelPath, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                    cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, prismEngine,
                    specDisabled = true
                )
            }
            return true
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
        displayHost: String, prismEngine: Boolean, specDisabled: Boolean = false
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
                    cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, prismEngine,
                    specDisabled = specDisabled
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
        apiTokenEnabled: Boolean = false,
        /** 纯 CPU 模式下是否改用 PrismML fork 引擎（见 BinaryInstaller.MODE_PRISM） */
        prismEngine: Boolean = false,
        /** 上一轮带的 MTP 投机解码导致启动失败时置 true，防止反复重试 */
        specDisabled: Boolean = false
    ) {
        synchronized(this) {
            if (process?.isAlive == true) return
            degradedRetryUsed = false
            ctxDegradeUsed = false
            specDisabledUsed = false
            restartPending = false
            portConflict = false
            memBlocked = false
            // 清空上一次启动的日志残留：否则历史分配失败会误触发本次降级
            synchronized(logBuffer) {
                logBuffer.clear()
                _logLines.value = emptyList()
            }
            val npu = AppSettings.npuEnabled
            // NPU 在这套参数里等同于 GPU，靠 -ngl 决定卸载范围，所以强制拉满
            val effNgl = if (npu) 99 else ngl
            val useGpu = effNgl > 0 && !npu
            val engineMode = BinaryInstaller.modeFor(useGpu, prismEngine, npu)
            val engineDir = try {
                BinaryInstaller.ensureInstalled(engineMode)
            } catch (e: Exception) {
                appendLog("错误：引擎安装失败: ${e.message}")
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = "引擎安装失败: ${e.message}")
                return
            }
            appendLog(
                when (engineMode) {
                    BinaryInstaller.MODE_PRISM -> "引擎：prism（PrismML fork，支持 PQ2_0/PTQ1_0）"
                    BinaryInstaller.MODE_OPENCL -> "引擎：opencl（上游，GPU）"
                    BinaryInstaller.MODE_HTP -> "引擎：htp（Hexagon NPU，--device HTP0 -ngl 99）"
                    else -> "引擎：cpu（上游）"
                }
            )
            val binary = File(engineDir, "llama-server")
            if (!binary.exists()) {
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = "引擎不完整，请重装 APP")
                return
            }
            if (!File(modelPath).exists()) {
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = "模型文件不存在: $modelPath")
                return
            }
            // 内存闸：模型 + 投影器超过物理内存的 85% 就拒绝启动。
            // 判据用「物理总量」而不是「当前可用量」：可用量随后台 App 波动，拿它做阈值会误拦
            // 本来能跑的模型（比如可用量掉到 4GB 时把 3.7GB 的 4B 拦掉）。
            // 不拦的后果实测过：35B（14.44GB）在 15.44GB 内存的机器上一路 mmap + 分配，
            // 把系统内存吃穿，OOM 直接把手机带进重启。
            val needBytes = File(modelPath).length() + (mmprojPath?.let { File(it).length() } ?: 0L)
            val totalBytes = BinaryInstaller.totalBytes()
            if (totalBytes > 0 && needBytes > (totalBytes * 0.85).toLong()) {
                memBlocked = true
                val msg = "内存不足：模型约 ${gib(needBytes)}，本机内存 ${gib(totalBytes)}。" +
                        "加载它会吃穿内存导致系统重启，已拦下。请换更小的模型或更低的量化。"
                appendLog("错误：$msg")
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = msg)
                return
            }
            // 主模型校验：草稿/投影器/适配器当主模型加载必定失败，这里直接拦下说清楚原因
            val gi = GgufInspector.inspectBlocking(File(modelPath))
            if (!gi.isLoadableAsModel) {
                appendLog("错误：${gi.warning}")
                _state.value = ServerUiState(
                    status = ServerUiState.Status.ERROR,
                    message = gi.warning ?: "这个文件不能作为主模型加载"
                )
                return
            }
            // 引擎 × 打包门控：PrismML 打包（三元量化 + 哈达玛变换）只有 prism 引擎认得。
            // 以前这种情况要等加载阶段炸，报错还看不出原因，这里直接说清该切哪里。
            if (GgufInspector.isPrismPacked(File(modelPath)) && engineMode != BinaryInstaller.MODE_PRISM) {
                val how = when {
                    npu -> "当前开着 Hexagon NPU，NPU 后端不认这种三元打包——请关掉 NPU 后再用 prism 引擎启动"
                    useGpu -> "当前 ngl=$ngl 走 GPU 引擎，而 prism 引擎没有 GPU 后端——把 ngl 设为 0 再启动"
                    else -> "请到「设置 → 服务设置」打开「Bonsai 优化引擎（PrismML fork）」"
                }
                val msg = "这个模型是 PrismML 打包（三元量化），只有 prism 引擎能加载：$how"
                appendLog("错误：$msg")
                _state.value = ServerUiState(status = ServerUiState.Status.ERROR, message = msg)
                return
            }

            // 投机解码：模型自带 MTP 层（nextn）时默认**不用**——本机实测带它反而慢 25%
            // （批量验证在这台 CPU 上几乎不省时间，见 docs/TROUBLESHOOTING #25d）。
            // 想强开就在设置里填 draft-mtp：只有模型真带 nextn 层时才生效，否则自动忽略；
            // 万一引擎拒绝，下面还有一次去掉参数的自动重试，加载不会中断。
            // draft-mtp 在 cpu / opencl / prism 三套引擎里都支持，所以这里不按引擎分支。
            val hasMtp = try {
                GgufInspector.hasMtpHead(File(modelPath))
            } catch (e: Exception) {
                appendLog("MTP 检测跳过：${e.message}")
                false
            }
            val specEffective = when {
                specDisabled -> if (specType == "draft-mtp") "none" else specType
                specType == "draft-mtp" -> if (hasMtp) "draft-mtp" else "none"
                else -> specType
            }
            if (hasMtp && specEffective != "draft-mtp") {
                appendLog("检测到 MTP 层（nextn）：当前引擎带它更慢，已跳过（想强开可在设置里填 draft-mtp）")
            } else if (specEffective == "draft-mtp") {
                appendLog("投机解码：draft-mtp（模型自带 nextn 层）")
            }

            // 绑定地址：局域网模式必须 0.0.0.0（否则手机本机客户端连不上回环）
            val bindHost = if (lanEnabled) "0.0.0.0" else "127.0.0.1"
            // 展示地址：局域网模式显示真实 IP，方便外部客户端填写
            val displayHost = if (lanEnabled) currentLanIp() else "127.0.0.1"
            // 端口预检：别的 App 占着端口时，llama-server 只会吐一句英文
            // 「couldn't bind HTTP server socket」就退出，从日志完全看不出是端口冲突。
            // 这里提前试绑一次，并尽力查出占用方，直接把结论说成人话。
            val occupant = portOccupant(bindHost, port)
            if (occupant != null) {
                portConflict = true
                val detail = if (occupant.isBlank()) "" else "（${occupant}）"
                val msg = "端口 $port 已被其他应用占用${detail}，服务无法启动。请在「设置」里换一个端口，" +
                        "或先停掉占用它的应用再试。"
                appendLog("错误：$msg")
                _state.value = ServerUiState(
                    status = ServerUiState.Status.ERROR,
                    host = displayHost,
                    port = port,
                    modelPath = modelPath,
                    message = msg
                )
                return
            }
            val cmd = buildCommand(
                binary, modelPath, bindHost, port, ctxSize, threads, effNgl, lanEnabled, apiToken,
                cacheTypeK, cacheTypeV, specEffective, mmprojPath, faMode, apiTokenEnabled,
                npuDevice = if (npu) NPU_DEVICE else null
            )
            appendLog("启动: ${cmd.joinToString(" ")}")

            // 直接 exec（经 sh 包装会在部分 ROM 上触发 SIGABRT；本机 sh 的 ulimit -v 也是空操作，
            // 内存防护改由：源头缩图 + 分配失败自动降级 兜底，见 docs/TROUBLESHOOTING #20）
            val pb = ProcessBuilder(cmd)
                .directory(engineDir)
                .redirectErrorStream(true)
            // CPU 引擎不搜 /vendor（避免 VNDK 库遮蔽系统库）；OpenCL 引擎需要 vendor 的 libOpenCL。
            // NPU 也要带 /vendor/lib64：构建产物里 libggml-opencl.so 是整条链的硬依赖
            // （llama-server/libllama/libggml 都 DT_NEEDED 它），而它自己又 NEEDED libOpenCL.so，
            // 少了这一段会在 exec 阶段就报 "library libggml-opencl.so not found"，连 main 都进不去。
            pb.environment()["LD_LIBRARY_PATH"] =
                if (useGpu || npu) engineDir.absolutePath + ":/vendor/lib64" else engineDir.absolutePath
            // NPU 必须额外给 DSP 侧指路：libggml-htp-v81.so 这类 skel 由 fastRPC 在 CDSP 上加载，
            // 找的是 ADSP_LIBRARY_PATH 而不是 LD_LIBRARY_PATH。漏了它 DSP 侧起不来，
            // 报却只是找不到设备，极难往这块想。
            if (npu) {
                pb.environment()["ADSP_LIBRARY_PATH"] = engineDir.absolutePath
            }

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
                            line?.let {
                                appendLog(it)
                                // 抓 prefill 进度：这个阶段一个 token 都不吐，界面看着像卡死。
                                // 服务端日志形如：prompt processing, n_tokens = 20480, progress = 0.53
                                if (it.contains("prompt processing")) {
                                    val p = Regex("progress = ([0-9.]+)").find(it)
                                        ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                                    if (p != null) {
                                        _prefill.value = p.coerceIn(0.0, 1.0)
                                    } else if (it.contains("n_tokens = 0")) {
                                        _prefill.value = null
                                    }
                                }
                                if (it.contains("print_timing:") && !it.contains("prompt processing")) {
                                    // 进入 decode 阶段，进度条收掉
                                    _prefill.value = null
                                }
                                // 端口在预检和真正 bind 之间被别人抢走时，这里兜住
                                if (it.contains("couldn't bind") || it.contains("Address already in use")) {
                                    portConflict = true
                                }
                            }
                        }
                    }
                    val exit = try { process?.waitFor() } catch (_: Exception) { null }
                    appendLog("进程已退出 (code=$exit)")
                    // 端口被占：降级重试（减 ctx / 减 ngl / 去投机）全是无效动作，直接给结论
                    if (portConflict) {
                        val who = probeOccupantLabel(port)
                        val detail = if (who.isBlank()) "" else "（${who}）"
                        val msg = "端口 $port 已被其他应用占用${detail}，" +
                                "服务无法启动。请在「设置」里换一个端口，或先停掉占用它的应用再试。"
                        appendLog("错误：$msg")
                        _state.value = ServerUiState(
                            status = ServerUiState.Status.ERROR,
                            host = displayHost,
                            port = port,
                            modelPath = modelPath,
                            message = msg
                        )
                        return@launch
                    }
                    // MTP 被引擎拒绝 → 去掉投机参数重试一次（用户不该看到这次失败）
                    if (specEffective == "draft-mtp" && specFailed() &&
                        degradeSpecAndRestart(
                            modelPath, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                            cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, displayHost, prismEngine
                        )
                    ) {
                        return@launch
                    }
                    // 上下文过大 → 先减半重试（优先于 ngl 降级）
                    if (ctxFailed() &&
                        degradeCtxAndRestart(
                            modelPath, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                            cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, displayHost, prismEngine, specDisabled
                        )
                    ) {
                        return@launch
                    }
                    // GPU 分配失败导致的崩溃 → 自动降级重启。
                    // NPU 下不降级：Hexagon 没有「少卸载几层」这种退路，减半 ngl 只会得到半吊子配置。
                    if (!npu && ngl > 0 && allocFailed() &&
                        degradeAndRestart(
                            modelPath, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                            cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, displayHost, prismEngine, specDisabled
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
                        // 就绪即校验：若加载阶段发生过显存分配失败（进程可能仍存活），立即降级重启。
                        // NPU 下同样不降级（见上面的说明）。
                        if (!npu && ngl > 0 && allocFailed() &&
                            degradeAndRestart(
                                modelPath, port, ctxSize, threads, ngl, lanEnabled, apiToken,
                                cacheTypeK, cacheTypeV, specType, mmprojPath, faMode, apiTokenEnabled, displayHost, prismEngine, specDisabled
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
        synchronized(logBuffer) {
            logBuffer.clear()
            _logLines.value = emptyList()
            runCatching { logFile?.writeText("") }
        }
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
        apiTokenEnabled: Boolean = false,
        /** 非空则走 Hexagon NPU（传 "HTP0"），会追加 --device */
        npuDevice: String? = null
    ): List<String> {
        // 参数拼装的纯逻辑在 buildServerArgs 里（可单测），这里只负责把 Android 侧的状态喂进去
        val loraPath = AppSettings.effectiveLora(modelPath)
        val (args, logs) = buildServerArgs(
            binaryPath = binary.absolutePath,
            modelPath = modelPath,
            host = host,
            port = port,
            ctxSize = ctxSize,
            threads = threads,
            ngl = ngl,
            npuDevice = npuDevice,
            lanEnabled = lanEnabled,
            apiToken = apiToken,
            cacheTypeK = cacheTypeK,
            cacheTypeV = cacheTypeV,
            specType = specType,
            mmprojPath = mmprojPath,
            mmprojExists = mmprojPath != null && File(mmprojPath).exists(),
            faMode = faMode,
            apiTokenEnabled = apiTokenEnabled,
            loraPath = loraPath,
            loraScale = AppSettings.effectiveLoraScale(modelPath),
            loadMode = AppSettings.loadMode
        )
        logs.forEach { appendLog(it) }
        return args
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

    /** 高频噪音行：slot print_timing 是每 3 秒一次的心跳进度（会把有用条目挤出缓冲），必须过滤；
     *  slot 调度/graph 细节同理。每请求级信息由 app 侧「聊天：完成」等条目提供。 */
    /**
     * 端口预检：端口可用返回 null；被占用则返回占用方的一句话身份（查不出时为空串）。
     */
    private fun portOccupant(bindHost: String, port: Int): String? {
        val free = try {
            java.net.ServerSocket().use { s ->
                // 必须和 llama-server 一样开 SO_REUSEADDR：否则上次连接留下的 TIME_WAIT
                // 会让预检误报「被占用」，而服务其实绑得上——那种误报比查不出占用方更难解释。
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(bindHost, port))
            }
            true
        } catch (e: Exception) {
            false
        }
        if (free) return null
        return probeOccupantLabel(port)
    }

    /**
     * 尽力识别端口占用方。原计划读 /proc/net/tcp 拿 uid 再映射包名，但实测 Android 17 上
     * 连本 App 自己读都是 EACCES，此路不通。改成 HTTP 探一下：本地推理服务基本都实现了
     * /v1/models，从模型名和 owned_by 就能看出是谁占着端口。
     */
    private fun probeOccupantLabel(port: Int): String {
        val body = runCatching {
            val conn = java.net.URL("http://127.0.0.1:$port/v1/models").openConnection()
                    as java.net.HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.inputStream.use { ins ->
                val buf = ByteArray(4096)
                val n = ins.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
            }
        }.getOrNull() ?: return ""
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
        val owner = Regex("\"owned_by\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
        return when {
            id.isNotBlank() && owner.isNotBlank() -> "对方在提供模型 $id，标识 $owner"
            id.isNotBlank() -> "对方在提供模型 $id"
            else -> ""
        }
    }

    private fun isNoiseLine(line: String): Boolean =
        line.contains("slot print_timing") || line.contains("slot get_availabl") ||
                line.contains("slot launch_slot_") ||
                line.contains("graphs reused") || line.contains("graph reallocs")

    /** 供聊天等 app 侧组件写入同一日志缓冲（日志页可见） */
    fun appLog(line: String) = appendLog(line)

    // ---- 日志持久化：files/logs.txt，启动加载、每行追加、超 1MB 轮转 ----
    @Volatile
    private var logFile: java.io.File? = null

    /** 本次启动是否因端口被占用而失败：端口冲突重试多少次结果都一样，看门狗据此不再空转 */
    @Volatile
    private var portConflict = false

    fun isPortConflict(): Boolean = portConflict

    /**
     * 本次启动是否因内存不足被前置拦下。
     * 这类失败重试没有意义，而且真去重试可能把系统内存再吃穿一次（实测 35B 在 15GB 机器上
     * 直接把系统带进重启），所以看门狗必须认识它。
     */
    @Volatile
    private var memBlocked = false

    fun isMemBlocked(): Boolean = memBlocked

    fun init(context: android.content.Context) {
        val f = java.io.File(context.filesDir, "logs.txt")
        logFile = f
        runCatching {
            if (f.exists() && f.length() > 0) {
                // 老版本可能已经把超长单行写进了 logs.txt，读回来也要截断，
                // 否则升级后一开日志页还是卡（不能让历史脏数据把界面锁死）。
                val tail = f.readLines().takeLast(MAX_LOG_LINES).map { capLine(it) }
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.addAll(tail)
                    _logLines.value = logBuffer.toList()
                }
            }
        }
    }

    private fun persistLineLocked(line: String) {
        val f = logFile ?: return
        runCatching {
            f.appendText(line + "\n")
            if (f.length() > 1024 * 1024) {
                val tail = f.readLines().takeLast(MAX_LOG_LINES)
                f.writeText(tail.joinToString("\n") + "\n")
            }
        }
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        if (isNoiseLine(line)) return
        val zh = capLine(translateLine(line))
        synchronized(logBuffer) {
            logBuffer.addLast(zh)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            _logLines.value = logBuffer.toList()
            persistLineLocked(zh)
        }
    }

    /**
     * 单行长度上限。模型复读时会把整段工具调用参数吐进日志，一行能到几万字符
     * （实测被 file_write/web_run 的 HTML 参数撑到 40 KB+）。日志页是逐行 Text 渲染，
     * 这种超长单行会让文本测量卡死主线程，连带整个界面点不动，所以入库前就截断。
     */
    private fun capLine(s: String): String =
        if (s.length <= MAX_LOG_LINE_CHARS) s
        else s.take(MAX_LOG_LINE_CHARS) + "…（本行共 ${s.length} 字，已截断）"

    /** 字节 → 「x.x GB」，只用于给人看的提示 */
    private fun gib(bytes: Long): String =
        String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)

    /** 常见引擎日志的中文映射（纯字符串替换，无性能影响；原文附在后面便于排障） */
    private fun translateLine(line: String): String {
        val rules = listOf(
            "llama_server: initializing" to "引擎初始化…",
            "load_model: loading model" to "正在加载模型",
            "load_model: model loaded" to "模型加载完成",
            "loaded multimodal model" to "多模态投影加载完成",
            "listening on" to "服务已监听",
            "健康检查通过" to "服务已就绪",
            "couldn't bind HTTP server socket" to "端口被其他应用占用：无法监听该端口" +
                    "（常见于同时装了别的本地推理 App）。请在「设置」里换一个端口后重试",
            "Address already in use" to "端口已被占用（Address already in use）",
            "failed to open session" to "NPU 会话打不开：常见原因是 DSP 侧内存装不下这个模型，" +
                    "或已有另一个进程占着 NPU（DSP 会话是独占的）",
            "device HTP0 did not report memory" to "（可忽略）NPU 未上报可用显存，自动分层规划会跳过它",
            "failed to create context" to "上下文创建失败：多为内存/显存不足，或该量化格式不被当前后端支持",
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
            "print_timings : prompt eval" to "预填充耗时",
            "print_timings : eval time" to "生成耗时",
            "slot release" to "请求完成（slot 释放）",
            "llama_model_loader: failed to load model" to "模型加载失败（引擎可能不支持该量化格式：建议换 Q4/Q5 的 GGUF，或升级引擎）",
            "failed to create llama_context" to "上下文/内存不足：请调小该模型的上下文长度后重试",
            "unknown model architecture" to "引擎不支持该模型架构（需要升级引擎）",
            "quantized V cache requires flash_attn" to "错误：量化 V 缓存需要开启 Flash Attention（设置里把 FA 改为开/自动）",
            "load_hparams: more info" to "（可忽略）模型超参提示"
        )
        for ((en, zh) in rules) {
            if (line.contains(en)) return "$zh\n$line"
        }
        return line
    }
}
