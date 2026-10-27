package com.localllm.server.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.server.core.AppSettings
import com.localllm.server.core.ChatClient
import com.localllm.server.core.ChatStore
import com.localllm.server.core.DocExtract
import com.localllm.server.core.DocMaker
import com.localllm.server.core.FileTools
import com.localllm.server.core.GgufInspector
import com.localllm.server.core.McpArgRepair
import com.localllm.server.core.McpHttpClient
import com.localllm.server.core.MemoryStore
import com.localllm.server.core.OcrIntent
import com.localllm.server.core.OcrTool
import com.localllm.server.core.RepeatGuard
import com.localllm.server.core.ServerProcessManager
import com.localllm.server.core.SkillChain
import com.localllm.server.core.SkillChainFlow
import com.localllm.server.core.ServerUiState
import com.localllm.server.core.TodoTool
import com.localllm.server.core.ToolLoopPolicy
import com.localllm.server.core.ToolResultBudget
import com.localllm.server.core.UrlFetch
import com.localllm.server.core.WebRunTool
import com.localllm.server.core.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private var store = ChatStore.load(app)

    /**
     * 每台 MCP 服务器上一次 open 成功返回的工作区 id（url → workspaceId）。
     * 模型手里的 id 会漂移（把路径当 id、或拿旧 id），这里留一份服务端自己给过的，
     * 出错时用来改参数重试（见 [McpArgRepair]）。
     */
    private val mcpWorkspaceIds = mutableMapOf<String, String>()

    private val _sessions = MutableStateFlow(store.sessions)
    val sessions: StateFlow<List<ChatStore.Session>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow(store.activeId)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _messages = MutableStateFlow(
        store.sessions.find { it.id == store.activeId }?.messages ?: emptyList()
    )
    val messages: StateFlow<List<ChatStore.Msg>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /**
     * 正在生成回答的会话 id。生成归属于发起它的会话：切走不会中断，
     * 增量与收尾都写回那个会话，而不是「当前正在看的会话」。
     * 同一时间只允许一个生成（单模型手机上并发只会互相拖慢）。
     */
    private val _generating = MutableStateFlow<Set<String>>(emptySet())
    val generating: StateFlow<Set<String>> = _generating.asStateFlow()

    /** 当前会话是否在生成：界面据此显示「停止」还是「发送」 */
    val streaming: StateFlow<Boolean> =
        combine(_activeId, _generating) { id, gen -> id in gen }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 一次性提示（生成完成等），界面消费后清空 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 各会话的生成进度（“342 token · 28.1 tok/s”），用于全局运行中指示 */
    private val _progress = MutableStateFlow<Map<String, String>>(emptyMap())
    val progress: StateFlow<Map<String, String>> = _progress.asStateFlow()

    /** 当前本地模型的模板是否认 enable_thinking；认才下发开关，云端不干预 */
    private fun thinkSwitch(cloud: Boolean): Boolean? {
        if (cloud) return null
        val p = AppSettings.selectedModelPath ?: return null
        val d = runCatching { GgufInspector.deepInfo(java.io.File(p)) }.getOrNull() ?: return null
        return if (d.hasEnableThinking) AppSettings.thinkEnabled else null
    }

    /** 模板认 reasoning_effort 才下发等级（设置里留空则用模型默认） */
    private fun thinkEffort(cloud: Boolean): String? {
        if (cloud) return null
        val p = AppSettings.selectedModelPath ?: return null
        val d = runCatching { GgufInspector.deepInfo(java.io.File(p)) }.getOrNull() ?: return null
        return if (d.hasReasoningEffort) AppSettings.reasoningEffort else null
    }

    private fun setProgress(sid: String, text: String?) {
        _progress.value =
            if (text == null) _progress.value - sid else _progress.value + (sid to text)
    }

    fun clearToast() {
        _toast.value = null
    }

    private fun isGenerating(sid: String) = sid in _generating.value

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 待用户确认的联网搜索（图片轮 + "先问我"策略）：值为将要发送给搜索引擎的内容 */
    private val _searchApproval = MutableStateFlow<String?>(null)
    val searchApproval: StateFlow<String?> = _searchApproval.asStateFlow()
    private var approvalDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** 本次发送用于 OCR 的原图（压缩图给小字识别不准）；发送后清空 */
    @Volatile
    private var ocrSourceB64: String? = null

    /** 链接抓取缓存：url → (抓取时间, 内容)，10 分钟内复用（避免重发时重复抓取） */
    private val linkCache = mutableMapOf<String, Pair<Long, String>>()

    /** 用户对「图片轮联网搜索」的确认结果 */
    fun approveSearch(allow: Boolean) {
        approvalDeferred?.complete(allow)
        approvalDeferred = null
        _searchApproval.value = null
    }

    /** 图片轮搜索确认：弹窗等待用户决定，超时（120s）视为拒绝 */
    private suspend fun askSearchApproval(query: String): Boolean {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        approvalDeferred = d
        _searchApproval.value = WebSearch.sanitizeQuery(query).first
        ServerProcessManager.appLog("图片轮联网搜索待用户确认：「${query.take(30)}」")
        val result = kotlinx.coroutines.withTimeoutOrNull(120_000L) { d.await() }
        if (result == null) {
            approvalDeferred = null
            _searchApproval.value = null
            ServerProcessManager.appLog("确认超时（120s），按拒绝处理")
            return false
        }
        return result
    }

    private val _lastStats = MutableStateFlow<String?>(null)
    val lastStats: StateFlow<String?> = _lastStats.asStateFlow()

    private val _pendingImageB64 = MutableStateFlow<String?>(null)
    /** 原图 base64（仅用于 OCR，不传模型、不落盘） */
    private val _pendingImageFullB64 = MutableStateFlow<String?>(null)

    /** 附件时用原图跑一次的本地 OCR 结果；随消息落盘，后续追问也能核对数字 */
    private val _pendingOcrText = MutableStateFlow<String?>(null)
    val pendingImageB64: StateFlow<String?> = _pendingImageB64.asStateFlow()

    private var streamJob: Job? = null
    // MCP 工具注入阈值：不超过 FULL 个就全给；超过就按关键词筛，最多注入 PICKED 个
    private val MAX_TOOLS_FULL = 24

    /**
     * 清单更新次数的**跑飞护栏**（不是步数上限）：十步的任务光打勾就要二十来次更新，
     * 所以这个值必须远高于真实任务的用量；它只在模型只刷清单、别的不干时兜底。
     * （曾经是 3，撞上就拒绝更新，长任务的清单于是停在半路。）
     */
    private val TODO_UPDATE_GUARD = 20

    /** 纯文本附件的内存上限（不是上下文上限：装不装得下由后面的预算截断决定） */
    private val PLAIN_ATTACH_MAX_BYTES = 32 * 1024 * 1024

    /**
     * 错误提示的显示长度上限。引擎会把整段工具调用参数回显进 500 响应（实测到 30 KB+），
     * 而这条文案是直接铺在聊天页上的 Text——超长文本会把 Compose 的文本测量拖死，
     * 整个界面点不动、连新会话都切不过去，只能杀进程恢复。完整内容仍然写进日志。
     */
    private val MAX_ERROR_CHARS = 300

    /** 技能链步骤的失败标志：只看行首，避免「没有错误」这类否定句误判 */
    private val CHAIN_FAIL_MARKERS = listOf(
        "错误：", "错误:", "失败：", "失败:", "无法完成", "无法继续", "执行失败", "超时"
    )
    // 实测 26 个工具 ≈ 9.1K token（每个约 350），对 0.5B 来说 prefill 就是几分钟。
    // 小模型本来也用不好二三十个工具，收到 8 个是「首字时间 vs 可用性」的折中。
    private val MAX_TOOLS_PICKED = 8
    /** 本轮结束时上下文占用过高 → 打完这一轮自动压缩一次 */
    private var autoCompressPending = false

    fun onInput(v: String) { _input.value = v }

    init {
        // 进程在后台被杀时，那一轮的 stopped 还是 false，正文也可能为空：
        // 重开时把它们标成「被打断」，于是「继续这个任务」会出现，而不是看起来什么都没发生。
        runCatching {
            val fixed = _sessions.value.map { s ->
                val last = s.messages.lastOrNull()
                val t = last?.task
                if (last != null && last.role == "assistant" && t != null &&
                    !t.stopped && last.content.isBlank()
                ) {
                    s.copy(
                        messages = s.messages.dropLast(1) +
                                last.copy(task = t.copy(stopped = true))
                    )
                } else s
            }
            if (fixed != _sessions.value) {
                _sessions.value = fixed
                if (_activeId.value == store.activeId) {
                    _messages.value = fixed.find { it.id == _activeId.value }?.messages
                        ?: _messages.value
                }
                saveStore(fixed, _activeId.value)
                ServerProcessManager.appLog("检测到被中断的任务，已标记为可续跑")
            }
        }
        // prefill 进度接到状态行：这个阶段服务器不吐 token，不显示进度看着就像卡死
        viewModelScope.launch {
            ServerProcessManager.prefill.collect { p ->
                val sid = _generating.value.firstOrNull() ?: return@collect
                if (p != null) {
                    setProgress(sid, "正在处理上下文 ${(p * 100).toInt()}%")
                    if (_activeId.value == sid) {
                        _lastStats.value = "正在处理上下文 ${(p * 100).toInt()}%"
                    }
                }
            }
        }
        // 服务就绪后自动清掉"请先启动服务"之类的连接错误（避免回到会话页仍显示红字）
        viewModelScope.launch {
            ServerProcessManager.state.collect { st ->
                if (st.status == ServerUiState.Status.RUNNING) {
                    val cur = _error.value ?: return@collect
                    if (cur.contains("启动服务") || cur.contains("连接失败")) {
                        _error.value = null
                    }
                }
            }
        }
        // 接收外部分享内容：填进输入框并提示
        viewModelScope.launch {
            com.localllm.server.core.ShareInbox.text.collect { shared ->
                val v = com.localllm.server.core.ShareInbox.consume() ?: return@collect
                _input.value = v
                _lastStats.value = "已接收分享内容（${v.length} 字），确认后点发送"
                ServerProcessManager.appLog("已接收分享内容：${v.take(60)}")
            }
        }
    }

    fun attachImage(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxDim / (sample * 2) >= 1024) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = resolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                } ?: throw IllegalStateException("图片解码失败")
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                bmp.recycle()
                _pendingImageB64.value = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                // OCR 用原图（压缩图会让小字/小数点糊掉，数字容易错）
                runCatching {
                    val fullBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    if (fullBytes != null && fullBytes.size <= 8 * 1024 * 1024) {
                        val fullB64 = android.util.Base64.encodeToString(fullBytes, android.util.Base64.NO_WRAP)
                        _pendingImageFullB64.value = fullB64
                        // 附件时就跑一次本地 OCR：视觉模型读数字会错、读不清会编，
                        // 逐字原文跟图一起给模型核对（版面仍以图为准）。
                        // 图里字太少（照片/插图）就不附带，免得白占上下文。
                        val text = runCatching { OcrTool.recognize(fullB64) }.getOrNull()
                        _pendingOcrText.value = text
                        if (OcrTool.promptBlock(text) != null) {
                            ServerProcessManager.appLog(
                                "图片：本地 OCR 预识别 ${text!!.length} 字，将与图一起送给模型"
                            )
                        }
                    }
                }
            }.onFailure {
                _error.value = "图片处理失败：${it.message}"
            }
        }
    }

    fun clearPendingImage() {
        _pendingImageB64.value = null
        _pendingImageFullB64.value = null
        _pendingOcrText.value = null
    }

    // ---- 文本类附件（文档/代码等）：读成文本随消息一起发送 ----
    private val _pendingFile = MutableStateFlow<Pair<String, String>?>(null) // (文件名, 文本)
    val pendingFile: StateFlow<Pair<String, String>?> = _pendingFile.asStateFlow()

    fun clearPendingFile() { _pendingFile.value = null }

    fun attachFile(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val name = runCatching {
                    cr.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment ?: "文件"
                val mime = cr.getType(uri) ?: ""
                val bytes = runCatching {
                    cr.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    _error.value = "读取文件失败或文件为空"
                    return@launch
                }
                // 文档走本地结构化抽取（pdf/docx/xlsx/pptx/rtf）；其它按纯文本读取
                val doc = DocExtract.extract(bytes, name, mime)
                val text = if (doc != null) {
                    if (doc.text.isBlank()) {
                        _error.value = doc.note ?: when (doc.kind) {
                            "pdf" -> "PDF 没有可提取的文字层（可能是扫描件）：可截图后走 OCR，或使用视觉模型"
                            "docx" -> "Word 文档没有可提取的文字"
                            else -> "文件里没有可提取的文字"
                        }
                        return@launch
                    }
                    ServerProcessManager.appLog(
                        "文档抽取：$name → ${doc.kind.uppercase()} ${doc.text.length} 字" +
                                (doc.note?.let { "（$it）" } ?: "")
                    )
                    "（${doc.kind.uppercase()} 已抽取文本）\n" + doc.text +
                            (doc.note?.let { "\n[$it]" } ?: "")
                } else {
                    // 纯文本只挡「读进内存会出事」的体量；至于太大装不进上下文，后面有按预算的截断
                    // （原来是 256KB 直接拒绝，比实际需要的严格得多，几十万字的日志/代码就发不进来）
                    if (bytes.size > PLAIN_ATTACH_MAX_BYTES) {
                        _error.value = "文本文件超过 ${PLAIN_ATTACH_MAX_BYTES / 1024 / 1024}MB，请分段发送"
                        return@launch
                    }
                    String(bytes, Charsets.UTF_8)
                }
                if (text.isBlank()) {
                    _error.value = "文件为空或格式不支持（支持：图片、文本、PDF、docx、xlsx）"
                    return@launch
                }
                _pendingFile.value = name to text
                ServerProcessManager.appLog("已附加文件：$name（${text.length} 字）")
            } catch (e: Exception) {
                _error.value = "读取文件失败：${e.message}"
            }
        }
    }

    fun send() {
        val text = _input.value.trim()
        val image = _pendingImageB64.value
        val file = _pendingFile.value
        if ((text.isEmpty() && image == null && file == null) || blockIfBusy()) return
        _input.value = ""
        _pendingImageB64.value = null
        _pendingFile.value = null
        ocrSourceB64 = _pendingImageFullB64.value
        _pendingImageFullB64.value = null
        val ocrText = _pendingOcrText.value
        _pendingOcrText.value = null
        val content = if (file != null) {
            // 附件按上下文预算截断：避免 prompt 超限或预填充过慢
            val ctx = AppSettings.effectiveCtx(AppSettings.selectedModelPath)
            val historyEst = _messages.value.sumOf { estimateTokens(it.content) }
            val budgetTokens = (ctx * 0.45).toInt() - historyEst - 1024
            val maxChars = maxOf(500, (budgetTokens * 1.6).toInt())
            val raw = file.second
            val kept = if (raw.length > maxChars) raw.take(maxChars) else raw
            val note = if (kept.length < raw.length)
                "\n[附件过长已截断：原文 " + raw.length + " 字，本次仅发送前 " + kept.length + " 字]"
            else ""
            ServerProcessManager.appLog(
                "附件 " + file.first + "：" + raw.length + " 字，估算 ~" + estimateTokens(raw) +
                        " tokens；预算 " + budgetTokens + "，实际发送 " + kept.length + " 字" +
                        if (note.isEmpty()) "" else "（已截断）"
            )
            "【附件：" + file.first + "】\n```\n" + kept + "\n```" + note + "\n\n" + text
        } else {
            text
        }
        val fallback = when {
            image != null -> "请描述这张图片"
            file != null -> "请阅读附件内容"
            else -> text
        }
        append(ChatStore.Msg("user", content.ifBlank { fallback }, image, ocrText = ocrText))
        append(
            ChatStore.Msg(
                "assistant", "",
                task = ChatStore.TaskState(goal = content.ifBlank { fallback }.take(300))
            )
        )
        doStream()
    }

    /**
     * 单请求模型：已经有会话在生成时，别处不让发新请求。
     * 返回 true 表示被挡住（并在状态行说明是哪条会话在跑）。
     */
    private fun blockIfBusy(): Boolean {
        val sid = _generating.value.firstOrNull() ?: return false
        if (sid == _activeId.value) return true
        val title = _sessions.value.find { it.id == sid }?.title ?: "另一条会话"
        val hint = "「$title」正在生成，切过去可停止，或等它跑完"
        _lastStats.value = hint
        _toast.value = hint
        return true
    }

    fun resendAt(index: Int) {
        if (blockIfBusy()) return
        val list = _messages.value.toMutableList()
        if (index !in list.indices || list[index].role != "user") return
        _messages.value = list.subList(0, index + 1).toMutableList()
        append(ChatStore.Msg("assistant", ""))
        doStream()
    }

    fun editAndResend(index: Int, newText: String) {
        val text = newText.trim()
        if (text.isEmpty() || blockIfBusy()) return
        val list = _messages.value.toMutableList()
        if (index !in list.indices || list[index].role != "user") return
        list[index] = list[index].copy(content = text, reasoning = null)
        _messages.value = list.subList(0, index + 1).toMutableList()
        append(ChatStore.Msg("assistant", ""))
        doStream()
    }

    private fun doStream() {
        // 生成归属于发起它的会话：后续所有增量/收尾都按 sid 找会话，不再看当前在哪个会话
        val sid = _activeId.value
        // 已经发出新指令 = 放弃之前被中断的任务：收掉旧续跑入口，
        // 否则之后误点会把续跑指令追加到末尾，和新指令混成两条任务线。
        clearStoppedTasks(sid)
        // send / 重新发送 / 编辑后重发 都会走到这里，统一补任务快照。
        // 只在 send() 里建的话，走另外两个入口的消息就没有快照，停止后也不会出现续跑按钮。
        val goalText = messagesOf(sid).lastOrNull { it.role == "user" }?.content ?: ""
        if (goalText.isNotBlank()) {
            appendToLastInline(sid) { m ->
                if (m.role == "assistant" && m.task == null) {
                    m.copy(task = ChatStore.TaskState(goal = goalText.take(300)))
                } else m
            }
        }
        streamJob = viewModelScope.launch {
            _generating.value = _generating.value + sid
            _error.value = null
            _lastStats.value = null
            // 技能链走执行器：每步一次生成、失败可换方案。
            // 旧实现是把各步提示词拼成一段「第 1 步…第 2 步…」让模型一口气做完，
            // 中间结果看不到，也没有真正的条件分支。
            val chain = _sessions.value.firstOrNull { it.id == sid }?.appliedChainId
                ?.let { cid -> AppSettings.loadSkillChains().firstOrNull { it.id == cid } }
            if (chain != null && goalText.isNotBlank()) {
                runSkillChain(sid, chain, goalText)
                return@launch
            }
            // 模型“答完又想再答”（单次生成内二次思考）时自动截断本轮
            var autoStopped = false
            // 复读中断标记（正文里同一段反复出现；实测会把 file_write 的 JSON 撑爆）
            var repetitionAborted = false
            // 工具调用参数被写崩时的降级重试：catch 里拿不到 try 内的局部变量，这里先留出口。
            // 一旦服务端因工具参数 JSON 不闭合返回 500，去掉 tools 重发一次，让用户至少拿到文字答案。
            var turnSystem: String? = null
            var turnHistory: List<ChatStore.Msg> = emptyList()
            var turnHadTools = false
            var toolJsonRetried = false
            // 心跳任务：每轮重置，必须先取消上一轮的，否则多个心跳同时写状态行，秒数会来回跳
            var heartbeat: kotlinx.coroutines.Job? = null
            var abortDeltas = 0
            val sendStartedAt = System.currentTimeMillis()
            try {
                // 提示词保持最小化：过多硬性约束会让小模型在思考里反复“检查规则”而失控。
                // LaTeX 已有客户端转换器兜底，不再要求模型禁用。
                val searchOn = AppSettings.webSearchEnabled
                val todoOn = AppSettings.todoToolEnabled
                val fileOn = AppSettings.fileToolsEnabled && AppSettings.workDirTreeUri.isNotBlank()
                val sandboxOn = AppSettings.webSandboxEnabled
                val memoryOn = AppSettings.memoryEnabled
                // 隐私护栏：本轮用户消息含图片（可能是身份证/证件等敏感件）→ 不注入任何联网搜索工具，
                // 并把模型幻觉出的 web_search 调用靠白名单挡掉（OCR 文字不会流向搜索引擎）。
                val turnHasImage = _messages.value.dropLast(1).lastOrNull()?.imageB64 != null
                // 含图片轮：仅「禁止」策略时不注入搜索工具；「先问我」由执行前弹窗把关；「允许」照常
                val searchBlockedByImage =
                    turnHasImage && AppSettings.imageSearchPolicy == "block"
                val searchAvailable = searchOn && !searchBlockedByImage
                if (searchOn && searchBlockedByImage) {
                    ServerProcessManager.appLog("本轮含图片：出于隐私保护未注入联网搜索工具")
                }
                val today = java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA)
                    .format(java.util.Date())
                // 助手指令：会话级覆盖优先，否则用全局预设
                val sessionPrompt = _sessions.value.find { it.id == _activeId.value }?.systemPrompt
                val basePrompt = sessionPrompt ?: AppSettings.activeSysPrompt()
                val memoryText = if (memoryOn) MemoryStore.injectText(getApplication()) else ""
                val memoryHint = if (memoryText.isNotBlank()) {
                    "\n\n【长期记忆（用户偏好/背景，供参考）】\n" + memoryText
                } else ""
                // 意图判别：本轮是否真的要「产出可运行/可打开的东西」。
                // 只看关键词会在多轮迭代里失灵（"颜色改成蓝色"一个关键词都不含，但仍在造物），
                // 所以叠加历史判据：上一轮已动过 web_run / 文件工具，就继续算造物任务。
                // 判错的代价不对称——误判成闲聊只是这一轮少了个自检工具，用户再说一次即可；
                // 误判成造物则会让「介绍一下茶文化」这种问题被改成写 HTML 页面。
                val history = _messages.value.dropLast(1)
                val lastUserText = history.lastOrNull { it.role == "user" }?.content ?: ""
                val artifactWordPattern = Regex(
                    // 强信号：「做个X」「写个X」式的造物动词 + 量词，X 是什么都算。
                    // 但「做个自我介绍」「帮我做个总结」要的是文字，用后视排除掉这批宾语。
                    "(写|做|生成|实现|画|搭|撸|弄|整|出|来)(个|一个|一款|一份)" +
                            "(?!自我介绍|总结|决定|选择|判断|保证|解释|说明|比较|对比|分析|介绍|回答|评价|推荐|计划|概述|概括)" +
                            // 次强信号：造物动词 + 产出物名词（允许中间夹"介绍茶文化的"这类定语）。
                            // 「整理/汇总/转成/导出」专管「把这段整理成 markdown 文档」这类改写需求。
                            "|(写|做|生成|实现|改|来个|帮我做|弄个|整个|出个|画个|搭个|撸|整理|汇总|转成|导出).{0,12}" +
                            "(页面|网页|html|程序|工具|小工具|app|应用|游戏|可视化|图表|脚本|demo|原型|界面|组件" +
                            "|markdown|\\bmd\\b|文档|报告)" +
                            // 倒装跟进：「把这个页面改成深色」「把首页调一下」——名词在前动词在后
                            "|(首页|页面|网页|界面|样式|布局|配色|组件|表格|图表|字体|字号|颜色|背景|按钮|图片).{0,10}(改|调|换|加|删|修|优化)" +
                            "|单文件|生成文件|完整代码",
                    RegexOption.IGNORE_CASE
                )
                val wantsArtifact = artifactWordPattern.containsMatchIn(lastUserText) ||
                        history.any { m ->
                            m.role == "assistant" && m.steps.any { st ->
                                st.type == "tool" &&
                                        (st.label.contains(WebRunTool.NAME) || st.label.startsWith("文件："))
                            }
                        }
                val fileHint = if (fileOn) {
                    "如需生成文件（HTML/代码/CSV）用 file_write；需要真正的 Excel 表格用 make_xlsx（rows 二维数组）；" +
                            "需要 PDF 用 make_pdf。都写入工作目录，并在回答里告知文件名；" +
                            "已经存在的文件只改一两处时，用 file_patch（给 old/new 片段），" +
                            "不要用 file_write 重吐全文——那样每轮都要重新输出整份文件，慢且容易把已对的部分改坏；" +
                            "查看已有文件用 file_list / file_read / file_grep。"
                } else ""
                val todoHint = if (todoOn && wantsArtifact) {
                    "多步任务可先用 todo_write 列出计划（同一消息最多 3 次），随后必须在正文中直接给出实际产出（代码/文件内容），" +
                            "绝不能用「已完成…」之类的进度描述代替真正的内容。"
                } else ""
                // 自检回路：弱模型看不到运行结果就永远不会自我修正
                val sandboxHint = if (sandboxOn && wantsArtifact) {
                    "本轮要的是能直接打开运行的成品。禁止用方案、架构、步骤清单、伪代码来替代代码——" +
                            "直接写出完整可运行的单文件代码，这是唯一合格的产出。" +
                            "写完必须调用 " + WebRunTool.NAME +
                            " 自检：它会把页面放进真实浏览器环境跑一次，返回语法错误与运行期错误（含行号）、" +
                            "是否死循环、canvas 是否真的画出了内容、画面是否在动。" +
                            "按返回的错误逐条修完，再自检一次；没有自检通过前不要声称已完成。" +
                            "修改已有文件时用 file_patch 只替换出错的那一小段，" +
                            "不要用 file_write 整份重写——每次重写都会把已经正确的部分改坏，也会引入新的错误。" +
                            "单文件程序控制在 500 行以内；确实超出这个规模时，先写出能跑的最小版本，" +
                            "再用后续轮次迭代补齐，不要用「接下来可以怎么做」来代替代码。"
                } else ""
                // 路由句放最前面：小模型只认开头和短句，放后面会被后面的强制句盖过。
                // 之前没有这句时，sandboxHint 的「唯一合格的产出是代码」被当成全局断言，
                // 「介绍一下茶文化」会被理解成「写一个介绍茶文化的 HTML 页面并自检」。
                // 注：曾试过把它和另外几条 hint 挪到提示末尾来「延长可复用的稳定前缀」，
                // 实测**无效**——本引擎只吃「完全相同」或「末尾微调」的缓存，中途分叉就整段重算
                // （1539 token 同提示重发 0.18s、尾部换问题 0.47s、中途分叉 10-11s），
                // 所以顺序对缓存没有收益，按指令遵循效果排序即可。
                val chatHint = if (wantsArtifact) "" else
                    "用户只是提问、求解释或求介绍时，直接用中文回答，不要建文件、不要调用任何工具。"
                val systemText = chatHint + (if (basePrompt.isNotBlank())
                    "【本会话角色设定，优先遵守】" + basePrompt + " "
                else "") + todoHint + fileHint + sandboxHint + memoryHint +
                        (if (AppSettings.thinkEnabled) {
                    // 不能只说“简洁回答”：实测弱模型会把它理解成“别用工具、直接答”，
                    // 于是把多轮验证流程提前掐断。
                    // 但也不能放任成“不要跳过动手验证”——那句会抵消上面的 chatHint，
                    // 所以这里一律带「要产出成品时」的作用域。
                    "用中文回答，简洁但保留必要步骤；要产出成品时照常调用工具，不要为了简洁跳过自检。"
                } else {
                    "不要输出思考过程，直接用中文回答；要产出成品时照常调用工具。"
                }) + if (searchAvailable) {
                    "今天是${today}。涉及最新信息时，先用 web_search 工具联网查询再回答，不要臆造日期。"
                } else ""
                // 用户最后一条消息里若含链接 → 抓取内容（GitHub 走 README 特判）
                // 注意：内容必须并入首条 system 文案——部分模板（Qwen）要求 system 只能在开头
                val linkText: String? = run {
                    val url = UrlFetch.firstUrl(lastUserText) ?: return@run null
                    val cached = linkCache[url]
                    val now = System.currentTimeMillis()
                    if (cached != null && now - cached.first < 10 * 60_000L) {
                        cached.second
                    } else {
                        val fetched = UrlFetch.fetch(url)
                        if (fetched != null) {
                            linkCache[url] = now to fetched
                            ServerProcessManager.appLog(
                                "链接预取：${url.take(60)} → ${fetched.length} 字" +
                                        if (url.contains("github.com")) "（GitHub 特判）" else ""
                            )
                            fetched
                        } else {
                            ServerProcessManager.appLog("链接预取失败：${url.take(60)}")
                            null
                        }
                    }
                }

                // 图片只有一条规则，不再有「自动 OCR / 多模态 / 按需」的设置档位：
                //   挂了 mmproj（视觉模型）→ 直接把图发过去，它自己看；
                //   没挂 mmproj（纯文本模型）→ 图发不了（它看不了），换成一句可操作的提示，
                //     模型需要文字时自己调 ocr_image 工具做本地识别。
                // 这样「用户明确要求识别文字」就成了明确触发，而不会被自动 OCR 抢在前面白跑一遍。
                //
                // 判据看「服务实际在跑什么」而不是「设置里绑了什么」：投影器不匹配时服务会
                // 自动去掉 --mmproj 重启，但设置里那条绑定还在——按设置判会把图片继续发过去，
                // 服务端直接 500（image input is not supported - you may need to provide the mmproj）。
                val svcState = ServerProcessManager.state.value
                val visionReady = if (svcState.status == ServerUiState.Status.RUNNING) {
                    svcState.runningMmproj?.let { java.io.File(it).exists() } == true
                } else {
                    AppSettings.selectedMmprojPath?.let { java.io.File(it).exists() } == true
                }
                // 显式指令优先：用户说了 OCR 相关的话，即使挂着视觉模型也**不传图**，
                // 让 ocr_image 成为唯一通路。不然视觉模型会直接读图，把「用 OCR 识别」顶掉——
                // 而用户要的往往正是 OCR 的逐字结果（表格数字、报错原文），不是视觉模型的概括。
                val ocrRequested = OcrIntent.requested(lastUserText)
                val imageVisible = visionReady && !ocrRequested
                var imageLogged = false
                // 对话里有没有图：决定是否注入 ocr_image 工具
                val hasImage = history.any { it.imageB64 != null }
                val systemTextFull = if (linkText == null) systemText
                else systemText + "\n\n【用户消息中链接的内容（供回答参考，可能不完整）】\n" + linkText
                // Qwen3.5 等模板硬性要求 system 只能是第一条，否则 raise_exception：
                // 「System message must be at the beginning」。而上下文压缩产生的「历史摘要」
                // 是以 system 角色插在历史中间的——直接让整个请求 500，且是在 prefill
                // 跑完（可能好几分钟）之后才炸。这里统一折进开头那条。
                val midSystems = history.filter { it.role == "system" && it.content.isNotBlank() }
                val systemMerged = if (midSystems.isEmpty()) systemTextFull
                else systemTextFull + "\n\n" + midSystems.joinToString("\n\n") { it.content }
                turnSystem = systemMerged
                turnHistory = history
                val payloadMsgs = listOf(ChatClient.ChatMessage("system", systemMerged)) +
                        history.filter { it.role != "system" }.map { m ->
                            // 有图就有本地 OCR 原文：视觉模型读数字常错、读不清会编，
                            // 逐字结果给它核对；版面、图表、手写仍靠图。
                            val ocrBlock = OcrTool.promptBlock(m.ocrText)
                            when {
                                m.imageB64 == null -> ChatClient.ChatMessage(m.role, m.content)
                                imageVisible -> {
                                    if (!imageLogged) {
                                        imageLogged = true
                                        ServerProcessManager.appLog(
                                            "图片：多模态直读（传图给视觉模型）" +
                                                    if (ocrBlock != null)
                                                        "，并附上本地 OCR 原文供核对数字" else ""
                                        )
                                    }
                                    val parts = org.json.JSONArray()
                                    val textPart = if (ocrBlock == null) m.content
                                    else (if (m.content.isNotBlank()) m.content + "\n\n" else "") + ocrBlock
                                    parts.put(org.json.JSONObject().put("type", "text").put("text", textPart))
                                    parts.put(
                                        org.json.JSONObject().put("type", "image_url").put(
                                            "image_url",
                                            org.json.JSONObject().put("url", "data:image/jpeg;base64,${m.imageB64}")
                                        )
                                    )
                                    ChatClient.ChatMessage(m.role, parts)
                                }
                                else -> {
                                    // 看不到图：本地 OCR 原文就是唯一的信息来源，直接给出，
                                    // 省掉「先调一次工具才拿到字」的来回。
                                    if (!imageLogged) {
                                        imageLogged = true
                                        ServerProcessManager.appLog(
                                            if (ocrBlock != null)
                                                "图片：当前模型没有视觉投影（mmproj），已直接附上本地 OCR 原文"
                                            else if (ocrRequested)
                                                "图片：用户要求 OCR，按指令走 ocr_image（不传图）"
                                            else
                                                "图片：当前模型没有视觉投影（mmproj），改由 ocr_image 工具按需识别"
                                        )
                                    }
                                    val hint = if (ocrBlock != null)
                                        "【用户上传了一张图片，你看不到图片本身。" +
                                                "上面是本机 OCR 识别出的文字，可直接依据它作答；" +
                                                "需要完整原文或换重点再识别时，可调用 ${OcrTool.NAME}。】"
                                    else if (ocrRequested)
                                        "【用户上传了一张图片，并要求用 OCR 识别其中的文字】" +
                                                "你看不到图片本身，必须调用 ocr_image 工具取得文字后再作答，不要凭空猜测内容。"
                                    else
                                        "【用户上传了一张图片】你看不到图片本身。若回答需要图上的文字" +
                                                "（截图内容、表格、文档、报错信息等），先调用 ocr_image 工具识别，再作答。"
                                    val body = listOfNotNull(
                                        m.content.takeIf { it.isNotBlank() },
                                        ocrBlock,
                                        hint
                                    ).joinToString("\n\n")
                                    ChatClient.ChatMessage(m.role, body)
                                }
                            }
                        }
                val cloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()
                // 代码/工具类任务用低温采样：量化模型在语法层抽风的概率明显下降。
                // 关键词判据是启发式的，误判影响很小（只是温度低一点），不必做成开关。
                val codeWordPattern = Regex(
                    "html|css|javascript|typescript|\\bjs\\b|\\bpython\\b|代码|脚本|程序|游戏|函数|接口|" +
                            "单文件|生成文件|写一个|实现一个|编译|报错|重构",
                    RegexOption.IGNORE_CASE
                )
                val codeTask = codeWordPattern.containsMatchIn(lastUserText)

                // HTTP MCP 多服务器：聚合已启用服务器的工具（mcp__服务器__工具 前缀防冲突），并记录路由
                val mcpRoute = mutableMapOf<String, Pair<String, String>>() // 完整名 → (url, 原始工具名)
                // 本消息内每个 MCP 工具「同一组参数」连续失败的次数：够了就拦住，
                // 不让模型拿着同样的调用反复烧 token。换了参数就重新计数——那是在按建议往前走。
                val mcpFailures = mutableMapOf<String, Pair<String, Int>>()
                val mcpAgg = org.json.JSONArray()
                AppSettings.loadMcpServers().filter { it.enabled && !AppSettings.pureMode }.forEach { s ->
                    val prefix = "mcp__" + s.name.map { c -> if (c.isLetterOrDigit()) c else '_' }
                        .joinToString("") + "__"
                    s.tools.filter { it.enabled }.forEach { t ->
                        val full = prefix + t.name
                        mcpRoute[full] = s.url to t.name
                        mcpAgg.put(
                            org.json.JSONObject()
                                .put("type", "function")
                                .put(
                                    "function", org.json.JSONObject()
                                        .put("name", full)
                                        // MCP 工具描述常常是一整段规则说明（几百到两千字），
                                        // 几十个工具累加能把 prompt 撑到几万 token，prefill 变成几分钟、
                                        // 小模型还会被压垮。截断到一句话——名字和参数结构才是模型真正需要的。
                                        .put("description", t.description.take(200))
                                        // 参数 schema 才是体积大头：MCP 工具的 inputSchema 里
                                        // 每个属性都带一段说明，几十个工具累加就是几千 token。
                                        // 这里递归剥掉所有 description，只留类型/必填/枚举。
                                        .put("parameters", trimSchema(t.inputSchema))
                                )
                        )
                    }
                }

                // 工具合并：内置 web_search + 全部 MCP 服务器工具
                // MCP 工具按需注入：工具多时（实测接到 67 个）全量注入会把 prompt 撑到几万 token，
                // prefill 变成几分钟，小模型还会选错。按当前消息匹配名字/描述，命中的才注入；
                // 一个都没命中时退回全量，保证不会因为猜不到而失去能力。
                //
                // 但「预填充慢」这个理由在 NPU 上基本不成立了（实测 12B 预填充 3 秒 vs CPU 180 秒），
                // 所以限制改成可关：设置里选「全量」就一个不裁，代价是工具选择准确率可能下降
                // （这一条跟速度无关，小模型在几十个工具里挑错是另一个问题）。
                val mcpAll = AppSettings.mcpToolMode == "all"
                val mcpSel = if (mcpAll) {
                    (0 until mcpAgg.length()).mapNotNull { mcpAgg.optJSONObject(it) }
                } else if (mcpAgg.length() > MAX_TOOLS_FULL) {
                    // 两阶段：先让模型只从「名字+一句话描述」的清单里挑工具，
                    // 再把这些工具的完整 schema 注入主请求。清单约 1/8 体积，
                    // 换掉动辄 9K token 的全量注入。挑不出东西时退回关键词筛。
                    pickMcpByModel(mcpAgg, lastUserText, cloud)
                        .ifEmpty { filterMcpForText(mcpAgg, lastUserText) }
                } else filterMcpForText(mcpAgg, lastUserText)
                val toolsJson = org.json.JSONArray().also { arr ->
                    if (searchAvailable) arr.put(WebSearch.toolJson())
                    if (todoOn) arr.put(TodoTool.toolJson())
                    if (fileOn) FileTools.toolsJson().let { fa ->
                        for (i in 0 until fa.length()) arr.put(fa.getJSONObject(i))
                    }
                    if (memoryOn) arr.put(MemoryStore.toolJson())
                    // OCR 工具：只有对话里真的出现过图片才注入，平时不白占 schema
                    if (hasImage) arr.put(OcrTool.toolJson())
                    // web_run 只在造物轮注入：纯提问轮模型看不到这个工具，
                    // 也就不会顺着「唯一合格的产出是代码」把问答改造成页面。
                    if (sandboxOn && wantsArtifact) arr.put(WebRunTool.toolJson())
                    if (fileOn) DocMaker.toolsJson().let { da ->
                        for (i in 0 until da.length()) arr.put(da.getJSONObject(i))
                    }
                    mcpSel.forEach { arr.put(it) }
                }.takeIf { !AppSettings.pureMode && it.length() > 0 }
                turnHadTools = toolsJson != null
                // 搜索配额用尽后本轮请求不带 web_search：模型无工具可调，只能产出文本回答
                val toolsNoSearch = org.json.JSONArray().also { arr ->
                    mcpSel.forEach { arr.put(it) }
                }.takeIf { !AppSettings.pureMode && it.length() > 0 }

                ServerProcessManager.appLog(
                    "采样：" + (if (codeTask) "代码任务 → temp 0.3" else "普通对话 → temp 0.7") +
                            " · top_p 0.9 · top_k 20 · repeat_penalty 1.10" +
                            (if (codeTask) "（命中代码关键词）" else "")
                )
                ServerProcessManager.appLog(
                    "聊天：发送（" +
                            (if (cloud) "云端 ${AppSettings.chatCloudModel.ifBlank { "default" }}" else "本地") +
                            " · 历史 ${payloadMsgs.size - 1} 条" +
                            (toolsJson?.let { " · 工具 ${it.length()} 个" } ?: "") +
                            (if (searchAvailable) " · 搜索开" else "") +
                            (if (fileOn) " · 文件工具" else "") + "）"
                )

                // 真实注入过的工具名白名单：模型幻觉出来的工具调用一律不执行（直接结束本轮）
                val injectedNames = toolsJson?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optJSONObject("function")?.optString("name")
                            ?.takeIf { it.isNotBlank() }
                    }?.toSet()
                } ?: emptySet()
                var searchCount = 0
                var todoCalls = 0
                var sandboxCalls = 0
                // 上一次自检的报错指纹：两次一模一样 = 没有进展（代替「最多 6 次」的计数上限）
                var lastSandboxErrors: String? = null
                val searchCap = AppSettings.searchMaxCalls.let { if (it <= 0) Int.MAX_VALUE else it }
                var loggedNoSearch = false
                // 执行步骤时间线（思考/搜索/工具），落到消息上供 UI 展示
                val steps = mutableListOf<ChatStore.Step>()
                var thinkStartedAt = 0L
                // 本轮思考正文：落到 step.detail 里，时间线才能看出「想到哪一步去调的工具」
                val roundThink = StringBuilder()
                fun syncSteps() {
                    appendToLastInline(sid) { it.copy(steps = steps.toList()) }
                    persistProgress(sid)
                }
                fun settleThink() {
                    if (thinkStartedAt > 0) {
                        val ms = System.currentTimeMillis() - thinkStartedAt
                        val body = roundThink.toString().trim().replace(Regex("\\s+"), " ")
                        steps.add(
                            ChatStore.Step(
                                "think",
                                "思考了 ${String.format("%.1f", ms / 1000.0)} 秒",
                                detail = body.take(600).ifBlank { null },
                                millis = ms
                            )
                        )
                        roundThink.setLength(0)
                        thinkStartedAt = 0L
                        syncSteps()
                    }
                }
                val searchedQueries = mutableMapOf<String, String>() // 规范化查询 → 结果文本（相同查询复用）

                var apiMsgs = payloadMsgs
                val usedTools = mutableListOf<String>()
                // OCR 工具的数据源与次数限制：优先用本轮原图（分辨率更高），否则回落到历史里最近一张；
                // 一轮最多识别两次，否则模型可能在工具循环里反复识别同一张图。
                val ocrImageB64: String? =
                    ocrSourceB64 ?: history.lastOrNull { it.imageB64 != null }?.imageB64
                var ocrCalls = 0
                var truncFix = 0
                // 「只宣告下一步、不调工具」的催办次数（详见 ToolLoopPolicy.stalledWithoutActing）
                var stallNudges = 0
                val searchSources = mutableListOf<WebSearch.Result>()
                var timingNote: String? = null

                // 注意：必须用 for + break——repeat 里的 return@repeat 是 continue 语义，
                // 会导致「回答完成」后仍继续下一轮（历史 bug：同一问题最多被连答 5 遍）。
                // 默认不限（设置里可设成有限轮次）：NPU 上每轮成本低一个数量级，次数上限反而成了任务做不完的主因。
                // 正常结束靠模型自己不再调工具（下面的 break），中途想停靠停止按钮。
                val roundCap = ToolLoopPolicy.roundCap(AppSettings.toolRoundLimit)
                // 这一轮是模型自己收尾（不再调工具）还是撞上限被打断——两者收尾方式完全不同
                var answeredNaturally = false
                for (round in 0 until roundCap) {
                    ensureActive() // 停止/切换后不再进入下一轮
                    // 提前强制：已搜过但走到第4轮仍无正文 → 立即移除搜索工具，逼模型作答
                    val forceNoTools = searchAvailable && round >= 3 &&
                            _messages.value.lastOrNull()?.content.isNullOrEmpty() == true
                    if ((forceNoTools || (searchAvailable && searchCount >= searchCap)) && !loggedNoSearch) {
                        loggedNoSearch = true
                        ServerProcessManager.appLog(
                            if (forceNoTools) "连续多轮未产出正文，本轮移除搜索工具（强制模型作答）"
                            else "搜索配额已用尽，本轮起移除搜索工具（强制模型产出回答）"
                        )
                    }
                    if (round > 0) {
                        ServerProcessManager.appLog("工具回传完成，第 ${round + 1} 轮继续生成…")
                    }
                    var deltaCount = 0
                    var roundChars = 0
                    // 本轮正文：工具轮要把它随 assistant 消息一起回传给模型，
                    // 否则模型看不到自己上一轮说了什么，等于每轮失忆重来
                    val roundText = StringBuilder()
                    var lastRepCheck = 0
                    var firstDeltaMs = 0L
                    var serverStats: String? = null
                    var toolCalls: List<ChatClient.ToolCall> = emptyList()
                    var finishReason = ""
                    // 正文开始后冻结思考卡：部分模型会在正文期间/之后仍吐 reasoning，
                    // 导致“回答完了思考还在滚”。首个内容 delta 到达即停止追加。
                    var contentStarted = false
                    // 心跳：prefill 阶段服务器只在处理完一段后才打一次进度，中间可能几十秒没动静，
                    // 光看界面像死了。这里每秒刷一次「已等待 N 秒 + 进度」，让状态一直有变化。
                    val roundStartAt = System.currentTimeMillis()
                    heartbeat?.cancel()
                    heartbeat = viewModelScope.launch {
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            if (contentStarted || !isGenerating(sid)) break
                            val secs = (System.currentTimeMillis() - roundStartAt) / 1000
                            val p = ServerProcessManager.prefill.value
                            val txt = if (p != null) {
                                "处理上下文 ${(p * 100).toInt()}% · 已等待 ${secs}s"
                            } else {
                                "等待模型响应 · 已等待 ${secs}s"
                            }
                            setProgress(sid, txt)
                            if (_activeId.value == sid) _lastStats.value = txt
                        }
                    }

                    ChatClient.chatStream(
                        port = AppSettings.port,
                        messages = apiMsgs,
                        apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                                   else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                        baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                        model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                        // 本地走 jinja 模板真开关；云端模板自管不干预。
                        // 只下发模型模板认的参数（检测见 GgufInspector.deepInfo）
                        thinking = thinkSwitch(cloud),
                        effort = thinkEffort(cloud),
                        // 本地生成上限：退化复读时不至于跑到撑满上下文
                        maxTokens = if (cloud) null else AppSettings.effectiveOut(AppSettings.selectedModelPath),
                        codeTask = codeTask,
                        // 搜索配额用尽 / 多轮无正文 → 不再提供搜索工具（防止弱模型空转到轮数上限、永远不回答）
                        tools = if (forceNoTools || (searchAvailable && searchCount >= searchCap)) toolsNoSearch else toolsJson,
                        onToolCalls = { toolCalls = it },
                        onFinish = { finishReason = it },
                        onTiming = { ttfb, firstToken ->
                            // 云端慢排查：ttfb vs 首token 分离，判断慢在网络还是在推理
                            if (cloud) {
                                val tok = firstToken?.let { " · 首token ${it / 1000.0}s" } ?: ""
                                timingNote = "连接${ttfb / 1000.0}s$tok"
                            }
                        },
                        onDelta = { delta ->
                            // 停止后一律不再上屏（abort 断连前的在途分片也丢弃）
                            if (isGenerating(sid)) {
                                if (!contentStarted) settleThink()
                                contentStarted = true
                                deltaCount++
                                abortDeltas++
                                roundChars += delta.length
                                roundText.append(delta)
                                if (firstDeltaMs == 0L) firstDeltaMs = System.currentTimeMillis()
                                val elapsed = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                                if (elapsed > 0.3) {
                                    val tps = deltaCount / elapsed
                                    val stats = "$deltaCount token · ${String.format("%.1f", tps)} tok/s" +
                                            " · ${String.format("%.0f", elapsed)}s"
                                    setProgress(sid, stats)
                                    if (_activeId.value == sid) _lastStats.value = "生成中 · $stats"
                                }
                                appendToLastInline(sid) { it.copy(content = it.content + delta) }
                                // 正文也随时间落盘：被后台清掉时至少保留已生成的部分
                                persistProgress(sid)
                                // 复读检测：每新增 800 字查一次尾部是否周期性重复。
                                // 弱模型在长字符串里会陷入复读，把工具调用的 JSON 撑到不闭合，
                                // 白跑十几分钟才报错——这里直接掐断。
                                if (roundChars - lastRepCheck >= 800) {
                                    lastRepCheck = roundChars
                                    if (RepeatGuard.isRepetitive(roundText)) {
                                        repetitionAborted = true
                                        ServerProcessManager.appLog(
                                            "检测到输出复读，已中断本轮（已生成 $roundChars 字）"
                                        )
                                        ChatClient.abort()
                                    }
                                }
                            }
                        },
                        onReasoning = { r ->
                            when {
                                !AppSettings.thinkEnabled || !isGenerating(sid) -> Unit
                                // 正文已开始却再次思考 = 模型要“换条路再答一遍”（实测同一次生成内
                                // 想→答→再想→再答）。罗马只走一次：截断本轮，保住已生成的回答。
                                contentStarted -> {
                                    if (!autoStopped) {
                                        autoStopped = true
                                        ServerProcessManager.appLog(
                                            "检测到正文后模型再次思考，已自动截断本轮（防止重复作答）"
                                        )
                                        ChatClient.abort()
                                    }
                                }
                                else -> {
                                    if (thinkStartedAt == 0L) thinkStartedAt = System.currentTimeMillis()
                                    roundThink.append(r)
                                    appendToLastInline(sid) {
                                        it.copy(reasoning = (it.reasoning ?: "") + r)
                                    }
                                }
                            }
                        },
                        onFinal = { tokens, tps, prompt ->
                            val tpsPart = if (tps > 0) " · ${String.format("%.1f", tps)} tok/s" else ""
                            // 上下文占用 = 本次输入 + 刚生成的回复（下一轮会话的实际起点）
                            serverStats = "共 $tokens token$tpsPart" +
                                    ctxNote(prompt + tokens)
                            // 超过 85% 就标记，让这一轮结束后自动压缩——再往后很容易在
                            // 下一轮中途撞顶（表现为莫名其妙的截断或答非所问）
                            val ctx = AppSettings.effectiveCtx(AppSettings.selectedModelPath)
                            if (ctx > 0 && (prompt + tokens).toDouble() / ctx > 0.85) {
                                autoCompressPending = true
                                ServerProcessManager.appLog(
                                    "上下文占用 ${prompt + tokens}/$ctx 超过 85%，本轮结束后自动压缩"
                                )
                            }
                        }
                    )

                    // 工具调用过滤：**只按「这个工具本轮是否真的注入过」判**。
                    //
                    // 以前还有一条「正文 ≥60 字且不像进度说明 → 答后调用当幻觉丢掉」，初衷是挡
                    // 弱模型答完再补一次假调用；实际代价更大：真机上模型写完一句
                    // 「让我重新从 CSV 文件里准确提取…生成正确的 Excel 文件。」（句号结尾）后跟了一个
                    // file_read，被判成幻觉丢掉，那正是当时唯一该做的动作，整轮就停在那句上。
                    // 判断「模型是不是已经答完了」这件事不该由启发式猜：调用了就执行，
                    // 真跑错由工具结果自己说话（错误会回传给模型）。
                    val unfinishedTodos = _messages.value.lastOrNull()?.todos
                        ?.any { it.status != "completed" } == true
                    val validCalls = run {
                        val ok = toolCalls.filter { it.name in injectedNames }
                        val ignored = toolCalls.map { it.name }.toSet() - ok.map { it.name }.toSet()
                        if (ignored.isNotEmpty()) {
                            ServerProcessManager.appLog("忽略未注入的工具调用：${ignored.joinToString("、")}")
                        }
                        ok
                    }

                    if (validCalls.isEmpty()) {
                        // 撞到 max_tokens（finish_reason=length）→ 自动续写，而不是把半截代码当成品交付。
                        // 截断正是「打开只有背景」的元凶：文件断在半行 JS 上。
                        val partial = _messages.value.lastOrNull()?.content ?: ""
                        if ((finishReason == "length" || finishReason == "max_tokens") &&
                            truncFix < 8 && partial.isNotBlank()
                        ) {
                            truncFix++
                            apiMsgs = apiMsgs +
                                    ChatClient.ChatMessage("assistant", partial) +
                                    ChatClient.ChatMessage(
                                        "user",
                                        "（上一条回答因输出长度上限被截断，停在半句上。请直接从中断处继续输出剩余内容，" +
                                                "不要重复已经输出的部分，不要重新开头。）"
                                    )
                            ServerProcessManager.appLog("输出撞到 max_tokens，自动续写第 $truncFix 次")
                            steps.add(ChatStore.Step("tool", "输出被截断，自动续写（第 $truncFix 次）"))
                            syncSteps()
                            continue
                        }
                        // 只写了一句「现在执行删除操作：」就停——这不是答复，是下一步的宣告。
                        // 以前这里直接当答复收尾（真机现场：正文 108 字、零工具调用，任务原地不动），
                        // 现在催它真调工具，同一轮最多催 2 次。
                        val stalled = isGenerating(sid) && ToolLoopPolicy.stalledWithoutActing(
                            unfinishedTodos = unfinishedTodos,
                            answerText = roundText.toString(),
                            hasToolCalls = toolCalls.isNotEmpty(),
                            nudged = stallNudges
                        )
                        if (stalled) {
                            stallNudges++
                            apiMsgs = apiMsgs +
                                    ChatClient.ChatMessage("assistant", roundText.toString().trim()) +
                                    ChatClient.ChatMessage(
                                        "user",
                                        "上一条只写了下一步要做什么，还没有真正调用工具。" +
                                                "现在直接调用需要的那一个工具把这一步做掉，" +
                                                "不要再重复计划，也不要重新开头。"
                                    )
                            ServerProcessManager.appLog(
                                "模型只宣告下一步、未调用任何工具，催办第 $stallNudges 次：" +
                                        roundText.toString().trim().takeLast(40)
                            )
                            steps.add(
                                ChatStore.Step("tool", "模型停在计划上，催它动手（第 $stallNudges 次）")
                            )
                            syncSteps()
                            continue
                        }
                        // 最终回复：合并统计（含本次问答总耗时，从发送算起）
                        val usedSec = (System.currentTimeMillis() - sendStartedAt) / 1000.0
                        val timing = timingNote?.let { "$it · " } ?: ""
                        val secPart = " · 用时 ${String.format("%.1f", usedSec)}s"
                        if (serverStats != null) {
                            _lastStats.value = timing + serverStats + secPart
                        } else if (deltaCount > 0 && firstDeltaMs > 0) {
                            val genSecs = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                            if (genSecs > 0.05) {
                                val tps = deltaCount / genSecs
                                _lastStats.value = timing +
                                        "共 $deltaCount token · ${String.format("%.1f", tps)} tok/s" + secPart
                            } else {
                                _lastStats.value = timing + "共 $deltaCount token" + secPart
                            }
                        } else {
                            _lastStats.value = (timing + "用时 ${String.format("%.1f", usedSec)}s").trim('·', ' ')
                        }
                        // 搜索来源 → 消息 sources 字段（UI 渲染为可点击列表，不再塞进正文文本）
                        if (searchSources.isNotEmpty()) {
                            val answerText = _messages.value.lastOrNull()?.content ?: ""
                            val cited = searchSources.filter { s ->
                                val host = runCatching {
                                    java.net.URI(s.url).host?.removePrefix("www.")
                                }.getOrNull() ?: ""
                                (host.isNotBlank() && answerText.contains(host)) ||
                                        (s.title.length >= 6 && answerText.contains(s.title.take(6)))
                            }
                            val picked = (if (cited.isNotEmpty()) cited else searchSources)
                                .distinctBy { it.url }.take(5)
                            val list = _messages.value.toMutableList()
                            list[list.lastIndex] = list.last().copy(
                                sources = picked.map { ChatStore.Source(it.title, it.url) }
                            )
                            _messages.value = list
                        }
                        // 正常跑完：清掉 stopped，界面不再显示「继续这个任务」
                        appendToLastInline(sid) { m ->
                            m.copy(task = m.task?.copy(stopped = false, rounds = round + 1))
                        }
                        ServerProcessManager.appLog("聊天：完成 · ${_lastStats.value ?: "（无统计）"}")
                        // 首轮回答完成 → 自动生成会话标题
                        val assistantCount = _messages.value.count {
                            it.role == "assistant" && it.content.isNotBlank()
                        }
                        if (assistantCount == 1) {
                            val lastUser = _messages.value.lastOrNull { it.role == "user" }?.content ?: ""
                            maybeAutoTitle(lastUser, _messages.value.lastOrNull()?.content ?: "")
                        }
                        answeredNaturally = true
                        break
                    }

                    // 执行工具调用并回传结果（仅 validCalls）
                    settleThink()
                    val asstCalls = org.json.JSONArray()
                    val newMsgs = apiMsgs.toMutableList()
                    validCalls.forEach { tc ->
                        usedTools.add(tc.name)
                        _lastStats.value = "🔧 调用 ${tc.name}…"
                        asstCalls.put(
                            org.json.JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function", org.json.JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.arguments)
                                )
                        )
                    }
                    newMsgs.add(
                        ChatClient.ChatMessage(
                            "assistant",
                            roundText.toString().trim().take(1500).ifBlank { null },
                            toolCalls = asstCalls
                        )
                    )
                        validCalls.forEach { tc ->
                        val result = runCatching {
                            if (tc.name == TodoTool.NAME) {
                                todoCalls++
                                if (todoCalls > TODO_UPDATE_GUARD) {
                                    "清单已经更新了 $todoCalls 次，别再刷清单了：请在正文里给出用户要的实际内容（代码/文件）。"
                                } else {
                                    val todos = TodoTool.parse(tc.arguments.ifBlank { "{}" })
                                    if (todos.isEmpty()) {
                                        "参数无效：未能解析任务清单，请按 tasks[{title,status}] 格式重新提交。"
                                    } else {
                                        val list = _messages.value.toMutableList()
                                        if (list.isNotEmpty()) {
                                            list[list.lastIndex] = list.last().copy(todos = todos)
                                            _messages.value = list
                                        }
                                        val done = todos.count { it.status == "completed" }
                                        ServerProcessManager.appLog("任务清单更新：$done/${todos.size} 已完成")
                                        "任务清单已更新（${done}/${todos.size} 完成）。请继续在正文中产出实际内容，完成后再次调用 ${TodoTool.NAME} 更新状态。"
                                    }
                                }
                            } else if (memoryOn && tc.name == MemoryStore.TOOL_NAME) {
                                val content = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                    .optString("content")
                                if (content.isBlank()) {
                                    "参数无效：content 不能为空"
                                } else {
                                    val count = MemoryStore.append(getApplication(), content)
                                    ServerProcessManager.appLog("长期记忆已写入：${content.take(40)}（共 $count 条）")
                                    steps.add(ChatStore.Step("tool", "记住：${content.take(30)}", "共 $count 条记忆"))
                                    syncSteps()
                                    "已写入长期记忆（当前共 $count 条）。"
                                }
                            } else if (fileOn && tc.name in DocMaker.NAMES) {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                ServerProcessManager.appLog("文档生成：${tc.name}")
                                val r = DocMaker.execute(getApplication(), tc.name, args)
                                steps.add(
                                    ChatStore.Step(
                                        "tool",
                                        "生成文档：${tc.name}",
                                        args.optString("path").take(80)
                                    )
                                )
                                syncSteps()
                                ServerProcessManager.appLog("文档生成完成：$r")
                                r
                            } else if (tc.name == OcrTool.NAME) {
                                // 图片文字识别：本地 ML Kit，不上传。模型按需调用，不是每张图都自动跑。
                                val src = ocrImageB64
                                when {
                                    src == null -> "错误：当前对话里没有可识别的图片"
                                    ocrCalls >= 2 -> "错误：本轮已识别过两次，请基于已拿到的文字作答"
                                    else -> {
                                        ocrCalls++
                                        ServerProcessManager.appLog("OCR 工具：识别用户上传的图片（第 $ocrCalls 次）")
                                        runCatching { OcrTool.recognize(src) }.getOrElse { e ->
                                            "错误：${e.message ?: "图片识别失败"}"
                                        }
                                    }
                                }
                            } else if (fileOn && tc.name in FileTools.NAMES) {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                ServerProcessManager.appLog("文件工具：${tc.name}")
                                val r = FileTools.execute(
                                    getApplication(),
                                    tc.name,
                                    args
                                )
                                val detail = when (tc.name) {
                                    FileTools.TOOL_WRITE ->
                                        "写入 " + args.optString("path") + "（" + r.length + "）"
                                    FileTools.TOOL_PATCH ->
                                        "局部修改 " + args.optString("path") + "（" +
                                                args.optString("old").length + " → " +
                                                args.optString("new").length + " 字）"
                                    FileTools.TOOL_READ ->
                                        "读取 " + args.optString("path")
                                    FileTools.TOOL_LIST -> "列出目录"
                                    else -> "搜索「" + args.optString("pattern") + "」"
                                }
                                if (tc.name == FileTools.TOOL_PATCH) {
                                    // 卡片预览要跟着更新，否则还显示替换前的旧内容
                                    val p0 = args.optString("path")
                                    runCatching {
                                        val body = FileTools.execute(
                                            getApplication(), FileTools.TOOL_READ,
                                            org.json.JSONObject().put("path", p0)
                                        )
                                        if (body.isNotBlank()) {
                                            val list = _messages.value.toMutableList()
                                            if (list.isNotEmpty()) {
                                                val last = list.last()
                                                val merged = (last.genFiles.filter { it.path != p0 } +
                                                        ChatStore.GenFile(p0, body)).takeLast(5)
                                                list[list.lastIndex] = last.copy(genFiles = merged)
                                                _messages.value = list
                                            }
                                        }
                                    }
                                }
                                if (tc.name == FileTools.TOOL_WRITE) {
                                    val p0 = args.optString("path")
                                    val body = args.optString("content")
                                    if (p0.isNotBlank() && body.isNotBlank()) {
                                        val list = _messages.value.toMutableList()
                                        if (list.isNotEmpty()) {
                                            val last = list.last()
                                            val merged = (last.genFiles.filter { it.path != p0 } +
                                                    ChatStore.GenFile(p0, body)).takeLast(5)
                                            list[list.lastIndex] = last.copy(genFiles = merged)
                                            _messages.value = list
                                        }
                                    }
                                }
                                steps.add(
                                    ChatStore.Step(
                                        "tool", "文件：${tc.name}", detail.take(800),
                                        args = args.toString().take(600),
                                        ok = !r.trimStart().startsWith("错误")
                                    )
                                )
                                syncSteps()
                                ServerProcessManager.appLog("文件工具完成：$detail")
                                r
                            } else if (sandboxOn && tc.name == WebRunTool.NAME) {
                                sandboxCalls++
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val label = args.optString("path").ifBlank { "内联代码" }
                                ServerProcessManager.appLog("沙箱自检 ${sandboxCalls}：$label")
                                steps.add(
                                    ChatStore.Step(
                                        "tool", "运行自检（第 ${sandboxCalls} 次）", label.take(80)
                                    )
                                )
                                syncSteps()
                                val r = WebRunTool.execute(getApplication(), args)
                                // 不再用「最多跑 6 次」这类计数上限：改错一次再跑一次本来就是工作方式，
                                // 计数只会把还在推进的任务掐掉。真正的死循环判据是「没有任何进展」——
                                // 两次报的错一模一样，说明上一轮修改没起作用，该收尾而不是再跑。
                                val sig = r.lineSequence()
                                    .filter { it.trimStart().startsWith("✗") }
                                    .joinToString("|").take(400)
                                val noProgress = ToolLoopPolicy.noProgress(sig, lastSandboxErrors)
                                lastSandboxErrors = sig.ifEmpty { null }
                                // 只记真正的错误：自检干净通过时报告第一行是「页面存活，无报错」，
                                // 把它当错误存下来会让续跑误以为上次没过
                                appendToLastInline(sid) { m ->
                                    val errs = r.lineSequence()
                                        .firstOrNull { it.trimStart().startsWith("✗") }
                                    val dead = if (!r.contains("【运行结果】页面存活")) {
                                        r.lineSequence().firstOrNull { it.isNotBlank() }
                                    } else null
                                    m.copy(
                                        task = m.task?.copy(
                                            lastError = (errs ?: dead)?.trim()?.take(200),
                                            rounds = round + 1
                                        )
                                    )
                                }
                                ServerProcessManager.appLog(
                                    "沙箱自检完成：" +
                                            (r.lineSequence().firstOrNull() ?: "") +
                                            if (noProgress) "（与上次报错相同，判定无进展）" else ""
                                )
                                if (noProgress) {
                                    r + "\n\n【提醒】这次报的错和上一次完全相同，说明刚才的修改没起作用。" +
                                            "请不要再重复运行自检：直接给出当前最终版本的文件，" +
                                            "并在正文里说明还有哪些检查项没通过。"
                                } else r
                            } else if (tc.name == "web_search") {
                                val query = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                    .optString("query")
                                val norm = query.trim().lowercase()
                                // 隐私策略：含图片轮次可"禁止/先问我/允许"
                                val turnPolicy =
                                    if (turnHasImage) AppSettings.imageSearchPolicy else "allow"
                                when {
                                    turnPolicy == "block" -> {
                                        ServerProcessManager.appLog("图片轮已阻止联网搜索（策略=禁止）")
                                        "本轮消息包含图片，出于隐私保护禁止联网搜索，请基于已有信息回答。"
                                    }
                                    turnPolicy == "ask" && !askSearchApproval(query) -> {
                                        ServerProcessManager.appLog("用户拒绝了本次图片轮搜索")
                                        "用户拒绝了本次联网搜索，请基于已有信息直接回答。"
                                    }
                                    norm.isNotBlank() && searchedQueries.containsKey(norm) -> {
                                        ServerProcessManager.appLog("内置搜索：相同查询「${query.take(30)}」复用已有结果（不占用配额）")
                                        steps.add(ChatStore.Step("search", "搜索网页：${query.take(60)}", "相同查询，复用已有结果"))
                                        syncSteps()
                                        searchedQueries[norm]!!
                                    }
                                    searchCount >= searchCap -> {
                                        ServerProcessManager.appLog("搜索已达本消息上限（$searchCap 次），返回提示")
                                        steps.add(ChatStore.Step("search", "搜索网页：${query.take(60)}", "已达上限，未执行"))
                                        syncSteps()
                                        "本轮搜索次数已达上限，请基于已有搜索结果直接回答。"
                                    }
                                    else -> {
                                        searchCount++
                                        if (WebSearch.sanitizeQuery(query).second) {
                                            ServerProcessManager.appLog("搜索词含身份证/手机号/卡号，已脱敏后再出网")
                                        }
                                        ServerProcessManager.appLog("内置搜索：「${query.take(40)}」…")
                                        val rs = WebSearch.search(
                                            query, fetchFullText = AppSettings.searchFetchFullText
                                        )
                                        searchSources.addAll(rs)
                                        ServerProcessManager.appLog("内置搜索：返回 ${rs.size} 条结果")
                                        val text = WebSearch.formatResults(rs)
                                        searchedQueries[norm] = text
                                        val fetched = rs.count { it.content != null }
                                        steps.add(
                                            ChatStore.Step(
                                                "search",
                                                "搜索网页：${query.take(60)}",
                                                "共 ${rs.size} 条结果" +
                                                        if (AppSettings.searchFetchFullText && fetched > 0)
                                                            "（已抓取 $fetched 篇正文）" else ""
                                            )
                                        )
                                        syncSteps()
                                        text
                                    }
                                }
                            } else {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val route = mcpRoute[tc.name]
                                    ?: throw IllegalStateException("未路由的工具：${tc.name}")
                                val argSig = args.toString()
                                val prevFail = mcpFailures[tc.name]
                                val failedBefore = if (prevFail != null && prevFail.first == argSig) prevFail.second else 0
                                if (failedBefore >= 2) {
                                    ServerProcessManager.appLog(
                                        "MCP：${tc.name} 用同一组参数已失败 $failedBefore 次，不再调用"
                                    )
                                    "工具「${route.second}」用这组参数已经失败 $failedBefore 次，同样的调用不要再发；" +
                                            "要么按上面的原因改参数，要么改用其他工具，或者直接回答。"
                                } else {
                                    ServerProcessManager.appLog("MCP 调用：${tc.name}")
                                    var firstTry =
                                        runCatching { McpHttpClient.callTool(route.first, null, route.second, args) }
                                    // 上下文类错误（workspaceId 传错 / 编辑会话过期）先自己改参数重试一次：
                                    // 这两类改完就能成，不值得再耗一整轮让模型猜（真机上它连撞四次都没猜对）
                                    val repair = firstTry.exceptionOrNull()?.let { e ->
                                        (e as? McpHttpClient.McpException)?.toolError?.let {
                                            McpArgRepair.repair(it, args, mcpWorkspaceIds[route.first])
                                        }
                                    }
                                    if (repair != null) {
                                        ServerProcessManager.appLog("MCP：${repair.note}")
                                        val retried = runCatching {
                                            McpHttpClient.callTool(route.first, null, route.second, repair.args)
                                        }
                                        if (retried.isSuccess) {
                                            steps.add(
                                                ChatStore.Step(
                                                    "tool", "自动修正参数后重试：${tc.name}",
                                                    repair.note,
                                                    args = repair.args.toString().take(600),
                                                    ok = true
                                                )
                                            )
                                            syncSteps()
                                        }
                                        firstTry = retried
                                    }
                                    firstTry.fold(
                                            onSuccess = { r ->
                                                mcpFailures.remove(tc.name)
                                                McpArgRepair.rememberedWorkspaceId(r)?.let {
                                                    mcpWorkspaceIds[route.first] = it
                                                }
                                                steps.add(
                                                    ChatStore.Step(
                                                        "tool", "调用工具：${tc.name}",
                                                        r.take(800),
                                                        args = args.toString().take(600),
                                                        ok = true
                                                    )
                                                )
                                                syncSteps()
                                                ServerProcessManager.appLog("MCP 完成：${tc.name}")
                                                r
                                            },
                                            onFailure = { e ->
                                                if (e is kotlinx.coroutines.CancellationException) throw e
                                                // 失败也要落成可见的一步，否则界面上一片正常、实际什么都没发生
                                                mcpFailures[tc.name] = argSig to (failedBefore + 1)
                                                // 同批工具名传进去：缺工作区/会话这类错要能点名「先调哪个」
                                                val msg = McpHttpClient.explain(
                                                    route.second, e, mcpRoute.values.map { it.second }
                                                )
                                                ServerProcessManager.appLog(
                                                    "MCP 失败：${tc.name} — ${msg.lineSequence().first()}"
                                                )
                                                steps.add(
                                                    ChatStore.Step(
                                                        "tool", "调用工具：${tc.name}",
                                                        msg.take(800),
                                                        args = args.toString().take(600),
                                                        ok = false
                                                    )
                                                )
                                                syncSteps()
                                                msg
                                            }
                                        )
                                }
                            }
                        }.getOrElse { e ->
                            ServerProcessManager.appLog("工具失败：${tc.name} — ${e.message}")
                            "工具执行失败：${e.message}"
                        }
                        // 回传前按窗口预算压一次：一次读取吐回 9 万字曾把上下文从 15.5k 顶到 108.6k，
                        // 下一轮直接 201837 token 超窗 400，整轮白跑。宽松裁剪，正常大小完全不受影响。
                        val budget = ToolResultBudget.budgetChars(
                            AppSettings.effectiveCtx(AppSettings.selectedModelPath)
                        )
                        val fitted = ToolResultBudget.trim(result, budget)
                        if (fitted.truncated) {
                            ServerProcessManager.appLog(
                                "工具结果过大（${fitted.originalChars} 字符），回传模型时压到 " +
                                        "${fitted.text.length} 字符（预算 $budget）"
                            )
                        }
                        newMsgs.add(ChatClient.ChatMessage("tool", fitted.text, toolCallId = tc.id))
                    }
                    apiMsgs = newMsgs
                }

                // 兜底：无正文，或只列了清单就报"已完成"（产出过短）→ 强制无工具直接产出
                val lastContentNow = messagesOf(sid).lastOrNull()?.content.orEmpty()
                // 但如果这一轮真的动过手（写文件 / 局部修改 / 跑过自检），没正文是正常的——
                // 代码任务本来就把产出放在工具调用里，强行插一轮"别调工具直接答"会打断正在进行的修复
                val productive = usedTools.any {
                    it == WebRunTool.NAME || it == FileTools.TOOL_WRITE || it == FileTools.TOOL_PATCH
                }
                val needsRealOutput = !productive && (
                        lastContentNow.isBlank() ||
                                (lastContentNow.length < 120 && todoCalls > 0)
                        )
                if (isGenerating(sid) && !autoStopped && needsRealOutput) {
                    ServerProcessManager.appLog(
                        if (lastContentNow.isBlank()) "无正文输出，改用无工具模式强制回答…"
                        else "疑似只报进度未产出内容（${lastContentNow.length} 字），强制直接产出…"
                    )
                    var forcedStats: String? = null
                    runCatching {
                        ChatClient.chatStream(
                            port = AppSettings.port,
                            messages = apiMsgs + listOf(
                                ChatClient.ChatMessage(
                                    "user",
                                    if (todoCalls > 0)
                                        "请直接输出用户要的实际内容（完整代码/文件内容），不要只描述进度，也不要再调用工具。"
                                    else
                                        "请直接用中文简要回答上面的问题，不要再调用任何工具。"
                                )
                            ),
                            apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                                       else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                            baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                            model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                            thinking = thinkSwitch(cloud),
                            effort = thinkEffort(cloud),
                            maxTokens = if (cloud) null else AppSettings.effectiveOut(AppSettings.selectedModelPath),
                            tools = null,
                            onDelta = { d ->
                                if (isGenerating(sid)) {
                                    appendToLastInline(sid) { it.copy(content = it.content + d) }
                                }
                            },
                            onReasoning = { r ->
                                if (AppSettings.thinkEnabled && isGenerating(sid)) {
                                    appendToLastInline(sid) {
                                        it.copy(reasoning = (it.reasoning ?: "") + r)
                                    }
                                }
                            },
                            onFinal = { tokens, tps, prompt ->
                                val tpsPart = if (tps > 0) " · ${String.format("%.1f", tps)} tok/s" else ""
                                forcedStats = "共 $tokens token$tpsPart" + ctxNote(prompt + tokens) +
                                        " · 用时 ${String.format("%.1f", (System.currentTimeMillis() - sendStartedAt) / 1000.0)}s"
                            }
                        )
                    }.onFailure { ServerProcessManager.appLog("强制回答失败：${it.message}") }
                    forcedStats?.let { _lastStats.value = it }
                    ServerProcessManager.appLog("聊天：完成 · ${_lastStats.value ?: "（无统计）"}")
                }

                if (usedTools.isNotEmpty()) {
                    val base = _lastStats.value ?: ""
                    _lastStats.value = "🔧 ${usedTools.joinToString("、")}${if (base.isNotEmpty()) " · $base" else ""}"
                }
                // 只有「循环是被上限耗尽的」才算被打断。
                // 这里以前只判 isGenerating(sid)——生成中当然是 true，于是只要这条消息用过工具，
                // 就算模型自己答完收尾了也会打「轮次用尽」并挂上「继续这个任务」。
                if (!answeredNaturally && isGenerating(sid)) {
                    appendToLastInline(sid) { m ->
                        if (m.role == "assistant" && m.task?.lastError != null) {
                            m.copy(task = m.task.copy(stopped = true))
                        } else m
                    }
                    ServerProcessManager.appLog(
                        "工具轮次用尽（设置里是 ${AppSettings.toolRoundLimit} 轮），任务未收敛，" +
                                "已标记为可续跑；想让它一次跑完可在设置里把上限改为「不限」"
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (autoStopped) {
                    // 主动截断导致的断连：不是错误，给出截断统计
                    _lastStats.value = "共 $abortDeltas token · 已截断重复思考"
                } else if (repetitionAborted) {
                    // 复读中断：模型在长字符串里打转，与其等它把预算烧完（实测十几分钟）
                    // 不如立刻停，让用户点「继续」换分段写法
                    _lastStats.value = "输出陷入复读，已中断（$abortDeltas token）"
                    _toast.value = "输出陷入重复已中断，可点「继续这个任务」分段重试"
                    appendToLastInline(sid) { m ->
                        if (m.role == "assistant" && m.task != null) {
                            m.copy(task = m.task.copy(stopped = true))
                        } else m
                    }
                } else {
                    val toolJsonBroke = e.message?.contains("Failed to parse tool call arguments as JSON") == true
                    if (toolJsonBroke && turnHadTools && turnSystem != null && !toolJsonRetried) {
                        toolJsonRetried = true
                        ServerProcessManager.appLog("工具调用参数格式出错，去掉工具重发一次（降级为纯文字回答）")
                        // 上一次写进消息里的是崩掉工具调用的碎片，清空后再重填，免得拼出乱七八糟的内容
                        appendToLastInline(sid) { m ->
                            if (m.role == "assistant") m.copy(content = "", steps = emptyList()) else m
                        }
                        val ok = runCatching { retryWithoutTools(sid, turnSystem!!, turnHistory) }
                        if (ok.getOrDefault(false)) {
                            _toast.value = "工具调用格式出错，已自动改用纯文字回答"
                        } else {
                            _error.value = friendlyError(e)
                        }
                    } else {
                        // 日志保留原文（appendLog 内部按 1200 字截断），界面只显示截断后的短提示
                        ServerProcessManager.appLog("聊天失败：${e.message ?: "请求失败"}")
                        _error.value = friendlyError(e)
                    }
                }
            } finally {
                val msgs = messagesOf(sid)
                val last = msgs.lastOrNull()
                // 只有「彻底空」才删。之前只判正文为空，导致点停止时
                // 连步骤、思考、生成的文件、续跑入口一起被抹掉——用户看到的就是什么都没了。
                val empty = last != null &&
                        last.content.isNullOrEmpty() &&
                        last.steps.isEmpty() &&
                        last.reasoning.isNullOrBlank() &&
                        last.genFiles.isEmpty() &&
                        last.todos.isEmpty()
                if (empty) {
                    writeMessages(sid, msgs.dropLast(1))
                    // 用户主动停止不算错误
                    if (_error.value == null && last?.task?.stopped != true) {
                        _error.value = "无回复（服务未运行？请先在状态页启动）"
                    }
                }
                heartbeat?.cancel()
                persistSession(sid)
                _generating.value = _generating.value - sid
                setProgress(sid, null)
                // 上一轮把上下文吃得太满 → 自动压一次。放在 generating 清掉之后，
                // 否则 compressContext 会自检「正在生成中」直接返回。
                if (autoCompressPending) {
                    autoCompressPending = false
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(400)
                        if (_activeId.value == sid && _generating.value.isEmpty()) {
                            compressContext()
                        }
                    }
                }
                // 在别的会话里完成时给个提示，否则用户不知道后台跑完了
                if (_activeId.value != sid) {
                    val title = _sessions.value.find { it.id == sid }?.title ?: "另一条会话"
                    val ok = _error.value == null
                    _toast.value = if (ok) "「$title」已生成完成" else "「$title」生成失败：${_error.value}"
                }
            }
        }
    }

    /** 停止：先关门（回调失效）→ 立即断连（思考流也停）→ 取消协程 → 落盘已生成部分 */
    fun stop() {
        val sid = _generating.value.firstOrNull() ?: _activeId.value
        ServerProcessManager.appLog("用户手动停止生成")
        // 立刻复位按钮状态，不等协程真正感知取消（阻塞读可能要等到下一行 SSE）
        _generating.value = _generating.value - sid
        setProgress(sid, null)
        ChatClient.abort()
        streamJob?.cancel()
        streamJob = null
        // 标记为「被打断」：界面于是显示「继续这个任务」，且状态已落盘，重开 App 也能续
        sid?.let { s ->
            appendToLastInline(s) { m ->
                if (m.role == "assistant" && m.task != null) m.copy(task = m.task.copy(stopped = true))
                else m
            }
        }
        persistSession(sid)
    }

    /**
     * 续跑被中断的那一轮。
     * 关键点：**不重放历史**，只用任务快照重建一条紧凑指令——
     * 目标、已生成的文件、计划、最后一次报错。模型看历史也记不住自己怎么想的，
     * 给它「现状」才接得上；顺带省掉几十 k token 的上下文。
     */
    fun resumeStoppedTask(index: Int) {
        if (blockIfBusy()) return
        val sid = _activeId.value ?: return
        val msgs = messagesOf(sid)
        val m = msgs.getOrNull(index) ?: return
        if (m.role != "assistant") return
        val t = m.task ?: return
        // 续跑后旧入口作废：否则每中断一次就多一个按钮，点哪个都是续跑最后一个
        writeMessages(
            sid,
            msgs.toMutableList().also { it[index] = m.copy(task = t.copy(stopped = false)) }
        )
        val files = m.genFiles.joinToString("、") { "${it.path}（${it.content.length} 字）" }
        val plan = m.todos.joinToString("；") {
            (if (it.status == "completed") "✓" else "□") + " " + it.title
        }
        val prompt = buildString {
            append("继续上一个被中断的任务。\n")
            append("目标：").append(t.goal.ifBlank { "见上面的用户消息" }).append('\n')
            if (files.isNotBlank()) {
                append("已生成的文件：").append(files).append('\n')
                append("请先用 file_read 读回最新内容，不要在假设的基础上改。\n")
            }
            if (plan.isNotBlank()) append("计划：").append(plan).append('\n')
            // 三种情况要求不同：有错才谈「只改这一处」；自检已过只是没做完，就别让它重写；
            // 不是代码任务的话根本不该提自检
            val codeLike = m.genFiles.any {
                val p = it.path.lowercase()
                p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".js") ||
                        p.endsWith(".css") || p.endsWith(".svg")
            }
            when {
                t.lastError != null -> {
                    append("最后一次检查未通过：").append(t.lastError).append('\n')
                    append("要求：只动出错的那一处及其直接相关代码，不要整份重写；改完调用 ")
                    append(WebRunTool.NAME).append(" 自检，通过前不要声称完成。")
                }
                codeLike -> {
                    append("上一次自检已通过，继续把还没做完的部分补上。\n")
                    append("要求：不要重写已经能跑的部分；每补完一块再调用一次 ")
                    append(WebRunTool.NAME).append(" 自检。")
                }
                else -> append("接着把没做完的部分做完，不要重复已经产出的内容。")
            }
        }
        ServerProcessManager.appLog("续跑被中断的任务：${t.goal.take(40)}")
        append(ChatStore.Msg("user", prompt))
        append(
            ChatStore.Msg(
                "assistant", "",
                // 清单必须带过来：界面上的进度卡片靠它，工具循环判「任务还没做完」也靠它
                // （丢了就只能看正文尾部猜，续跑轮里答后调用会被误当幻觉丢掉）
                todos = m.todos,
                task = ChatStore.TaskState(goal = t.goal, rounds = t.rounds)
            )
        )
        doStream()
    }

    /** 新建会话并切换（保留当前会话内容） */
    fun newChat() {
        if (blockIfBusy()) return
        persist()
        val id = System.nanoTime().toString()
        val s = ChatStore.Session(id, "新会话", System.currentTimeMillis(), emptyList())
        _sessions.value = _sessions.value + s
        _activeId.value = id
        _messages.value = emptyList()
        _lastStats.value = null
        saveStore()
    }

    /**
     * 切换会话：不中断正在跑的生成。生成结果的增量与收尾都按发起时会话写回，
     * 切回来就能看到它继续出字（或已经写完）。
     */
    fun switchSession(id: String) {
        if (id == _activeId.value) return
        persist()
        _activeId.value = id
        _messages.value = _sessions.value.find { it.id == id }?.messages ?: emptyList()
        _lastStats.value = null
        saveStore()
    }

    fun deleteSession(id: String) {
        if (isGenerating(id)) stop()
        val remaining = _sessions.value.filterNot { it.id == id }.ifEmpty {
            listOf(ChatStore.Session("1", "新会话", System.currentTimeMillis(), emptyList()))
        }
        _sessions.value = remaining
        if (_activeId.value == id || remaining.none { it.id == _activeId.value }) {
            _activeId.value = remaining.first().id
            _messages.value = remaining.first().messages
        }
        saveStore()
    }

    /** 粗略 token 估算：汉字约 1 token，其它字符约 1/4 token */
    private fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F) cjk++ else other++
        }
        return cjk + other / 4 + 1
    }

    /**
     * 设置/清除本会话的助手指令覆盖（null/空 = 回落到全局预设）。
     * [skillId] / [chainId] 记录这份指令是"哪个技能/技能链"带来的，界面据此显示生效标记；
     * 手写指令时两者都传 null，会清掉旧标记。
     */
    fun setSessionSystemPrompt(text: String?, skillId: String? = null, chainId: String? = null) {
        val id = _activeId.value
        val v = text?.trim()?.takeIf { it.isNotBlank() }
        _sessions.value = _sessions.value.map {
            if (it.id == id) it.copy(
                systemPrompt = v,
                appliedSkillId = if (v == null) null else skillId,
                appliedChainId = if (v == null) null else chainId,
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        saveStore()
        ServerProcessManager.appLog(
            when {
                v == null -> "本会话指令已清除（使用全局预设）"
                chainId != null -> "已应用技能链（${v.length} 字）"
                skillId != null -> "已应用技能（${v.length} 字）"
                else -> "本会话指令已更新（${v.length} 字）"
            }
        )
    }

    fun currentSessionSystemPrompt(): String? =
        _sessions.value.find { it.id == _activeId.value }?.systemPrompt

    /** 首轮回答后自动生成会话标题（仅当标题仍是默认时） */
    private suspend fun maybeAutoTitle(userText: String, assistantText: String) {
        val id = _activeId.value
        val session = _sessions.value.find { it.id == id } ?: return
        val isDefaultTitle = session.title == "新会话" ||
                session.title == userText.take(24) ||
                session.title.isBlank()
        if (!isDefaultTitle) return
        val buf = StringBuilder()
        runCatching {
            val cloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()
            ChatClient.chatStream(
                port = AppSettings.port,
                messages = listOf(
                    ChatClient.ChatMessage(
                        "system",
                        "为这段对话起一个简短标题：不超过 12 个字，不加标点与引号，直接输出标题本身。"
                    ),
                    ChatClient.ChatMessage(
                        "user",
                        "用户：" + userText.take(300) + "\n助手：" + assistantText.take(300)
                    )
                ),
                apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                           else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                thinking = if (cloud) null else false,
                maxTokens = 60,
                tools = null,
                onDelta = { d -> buf.append(d) },
                onReasoning = {},
                onFinal = { _, _, _ -> }
            )
        }.onFailure { ServerProcessManager.appLog("自动标题失败：${it.message}") }
        val title = buf.toString().trim().trim('"', '“', '”', '。', ' ')
            .replace(Regex("\\s+"), "")
        if (title.isNotBlank()) {
            _sessions.value = _sessions.value.map {
                if (it.id == id) it.copy(title = title.take(20)) else it
            }
            saveStore()
            ServerProcessManager.appLog("已自动生成会话标题：$title")
        }
    }

    fun clearError() { _error.value = null }

    /** 删除一条消息；若删的是正在生成的那条，先停止生成再删 */
    fun deleteAt(index: Int) {
        val list = _messages.value
        if (index !in list.indices) return
        if (index == list.lastIndex && isGenerating(_activeId.value)) {
            stop()
        }
        val m = list[index]
        _messages.value = list.toMutableList().also { it.removeAt(index) }
        ServerProcessManager.appLog(
            "已删除一条${if (m.role == "user") "用户" else "助手"}消息（${m.content.length} 字）"
        )
        persist()
    }

    /** 读某会话的消息：当前会话走 _messages（实时），其它会话从 _sessions 取 */
    private fun messagesOf(sid: String): List<ChatStore.Msg> =
        if (_activeId.value == sid) _messages.value
        else _sessions.value.find { it.id == sid }?.messages ?: emptyList()

    /** 写某会话的消息（只动内存：流式期间高频调用，不落盘） */
    private fun writeMessages(sid: String, list: List<ChatStore.Msg>) {
        if (_activeId.value == sid) _messages.value = list
        else _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(messages = list) else it
        }
    }

    /** 就地改写某会话最后一条消息（没有消息时忽略） */
    /**
     * 按当前消息筛选 MCP 工具：工具少时全给；多时按名字/描述关键词匹配，
     * 命中为空则退回全量（宁可慢，也不要因为猜不到而让模型没有可用的能力）。
     */
    private fun filterMcpForText(
        arr: org.json.JSONArray, text: String
    ): List<org.json.JSONObject> {
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (all.size <= MAX_TOOLS_FULL) return all
        val keys = Regex("[A-Za-z_]{3,}|[\\u4e00-\\u9fa5]{2,}")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .distinct()
            .take(40)
            .toList()
        val scored = all.map { o ->
            val fn = o.optJSONObject("function")
            val hay = ((fn?.optString("name") ?: "") + " " + (fn?.optString("description") ?: ""))
                .lowercase()
            o to keys.count { hay.contains(it) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val hits = scored.map { it.first }
        // 未命中时**不要退回全量**：57 个工具就是 9K token、几分钟 prefill，等于优化全废。
        // 工具名/描述是英文、用户消息常是中文，硬关键词本来就常常碰不上。
        // 退而求其次：按名字排序保底给几个，保证不是彻底没能力，同时不把 prompt 撑爆。
        val picked = if (hits.isEmpty()) all.sortedBy { it.optJSONObject("function")?.optString("name") ?: "" }
            .take(4)
        else hits.take(MAX_TOOLS_PICKED)
        ServerProcessManager.appLog(
            "MCP 工具注入：全部 ${all.size} 个 → " +
                    (if (hits.isEmpty()) "关键词未命中，保底注入" else "命中 ${hits.size} 个") +
                    "，实际注入 ${picked.size} 个"
        )
        return picked
    }

    /**
     * 工具路由（两阶段调用的第一阶段）：只把「工具名 + 一句话描述」的清单发给模型，
     * 让它挑出这次要用的名字；返回选中工具的完整定义。
     * 比全量注入省一个数量级——57 个工具的完整 schema ≈ 9K token，清单只要 ~1K。
     */
    private suspend fun pickMcpByModel(
        mcpAgg: org.json.JSONArray, userText: String, cloud: Boolean
    ): List<org.json.JSONObject> {
        val all = (0 until mcpAgg.length()).mapNotNull { mcpAgg.optJSONObject(it) }
        if (all.isEmpty()) return emptyList()
        val catalog = all.joinToString("\n") { o ->
            val fn = o.optJSONObject("function")
            (fn?.optString("name") ?: "") + ": " + (fn?.optString("description")?.take(60) ?: "")
        }
        val sb = StringBuilder()
        runCatching {
            ChatClient.chatStream(
                port = AppSettings.port,
                messages = listOf(
                    ChatClient.ChatMessage(
                        "system",
                        "你是工具路由器。从候选工具中挑出完成用户请求需要的工具名，" +
                                "只输出工具名、逗号分隔、最多 5 个；不需要工具就只输出 NONE。不要解释。"
                    ),
                    ChatClient.ChatMessage("user", "用户请求：$userText\n\n候选工具：\n$catalog")
                ),
                apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                maxTokens = 120,
                onDelta = { sb.append(it) }
            )
        }.onFailure {
            ServerProcessManager.appLog("工具路由失败：${it.message}")
        }
        val raw = sb.toString()
        val names = raw.split(',', '，', '、', ';', '；', '\n', ' ', '\t')
            .map { it.trim().trim('`', '"', '\'', '.', '。', ':', '：', '-', '*') }
            .filter { it.isNotBlank() && !it.equals("NONE", true) }
        // 双保险：不管模型用什么分隔符，只要工具名出现在输出里就算命中
        val sel = all.filter { o ->
            val n = o.optJSONObject("function")?.optString("name") ?: return@filter false
            n.isNotBlank() && (raw.contains(n) || names.any { it.equals(n, true) })
        }
        ServerProcessManager.appLog(
            "工具路由：候选 ${all.size} 个，模型点名 ${names.take(6)}，匹配到 ${sel.size} 个"
        )
        return sel
    }

    /** 递归剥掉 schema 里的 description（保留 type/required/enum/items 等结构信息） */
    private fun trimSchema(o: org.json.JSONObject): org.json.JSONObject {
        val out = org.json.JSONObject()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k == "description" || k == "title" || k == "examples") continue
            when (val v = o.opt(k)) {
                is org.json.JSONObject -> out.put(k, trimSchema(v))
                is org.json.JSONArray -> {
                    val a = org.json.JSONArray()
                    for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        a.put(if (e is org.json.JSONObject) trimSchema(e) else e)
                    }
                    out.put(k, a)
                }
                else -> if (v != null) out.put(k, v)
            }
        }
        return out
    }

    private fun appendToLastInline(sid: String, transform: (ChatStore.Msg) -> ChatStore.Msg) {
        val list = messagesOf(sid).toMutableList()
        if (list.isEmpty()) return
        list[list.lastIndex] = transform(list.last())
        writeMessages(sid, list)
    }

    private var lastPersistAt = 0L

    /**
     * 生成过程中也要落盘。以前只在整轮结束/手动停止时写磁盘，
     * 进程被后台清掉时这一轮（用户消息、步骤、生成的文件记录）整个消失。
     * 节流到 1.5 秒一次，避免每 token 都写文件。
     */
    /** 收掉所有「被打断」标记：新的指令意味着旧任务不再续跑 */
    private fun clearStoppedTasks(sid: String) {
        val msgs = messagesOf(sid)
        if (msgs.none { it.task?.stopped == true }) return
        runCatching {
            writeMessages(
                sid,
                msgs.map { m ->
                    if (m.task?.stopped == true) m.copy(task = m.task.copy(stopped = false)) else m
                }
            )
        }
    }

    /**
     * 生成过程中也要落盘。以前只在整轮结束/手动停止时写磁盘，
     * 进程被后台清掉时这一轮（用户消息、步骤、生成的文件记录）整个消失。
     * 节流到 1.5 秒一次。
     *
     * 注意：**磁盘 IO 必须放到 IO 线程**。整份 chat_sessions.json 含生成文件正文，
     * 可能好几 MB；在主线程（onDelta 回调）上同步写会把界面卡住——
     * 表现为切会话/新建会话没有反应，看着像 UI 坏了。
     */
    private fun persistProgress(sid: String) {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 1500) return
        lastPersistAt = now
        // 主线程只更新内存快照（便宜）
        val msgs = messagesOf(sid)
        val updated = _sessions.value.map {
            if (it.id != sid) it
            else it.copy(messages = msgs, updatedAt = System.currentTimeMillis())
        }
        _sessions.value = updated
        val snapshot = updated
        val active = _activeId.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { saveStore(snapshot, active) }
        }
    }

    /** 把某会话的内存状态落盘（含「首条用户消息 → 标题」） */
    private fun persistSession(sid: String) {
        val msgs = messagesOf(sid)
        _sessions.value = _sessions.value.map {
            if (it.id != sid) it else it.copy(
                messages = msgs,
                title = it.title.takeIf { t -> t != "新会话" }
                    ?: (msgs.firstOrNull { m -> m.role == "user" }?.content?.take(24) ?: "新会话"),
                updatedAt = System.currentTimeMillis()
            )
        }
        saveStore()
    }

    private fun append(msg: ChatStore.Msg) {
        _messages.value = _messages.value + msg
        persist()
    }

    private fun persist() {
        val activeId = _activeId.value
        val sessions = _sessions.value.map {
            if (it.id == activeId) it.copy(
                messages = _messages.value,
                title = it.title.takeIf { t -> t != "新会话" }
                    ?: (_messages.value.firstOrNull { m -> m.role == "user" }
                        ?.content?.take(24) ?: "新会话"),
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        _sessions.value = sessions
        saveStore(sessions, activeId)
    }

    private fun saveStore(
        sessions: List<ChatStore.Session> = _sessions.value,
        activeId: String = _activeId.value
    ) {
        ChatStore.save(getApplication(), ChatStore.Store(activeId, sessions))
    }


    /**
     * 上下文压缩：把较早的消息交给当前渠道模型总结成一条 system 摘要，仅保留最近若干条。
     * 触发后上下文占用大幅下降，早期的关键信息以摘要形式保留。
     */
    fun compressContext(keepRecent: Int = 4) {
        if (blockIfBusy()) {
            _lastStats.value = "正在生成中，稍后再压缩"
            return
        }
        val list = _messages.value
        val old = list.dropLast(keepRecent)
        val recent = list.takeLast(keepRecent)
        if (old.size < 2) {
            _lastStats.value = "消息太少，无需压缩"
            return
        }
        val sid = _activeId.value
        streamJob = viewModelScope.launch {
            _generating.value = _generating.value + sid
            _error.value = null
            _lastStats.value = "正在压缩上下文…"
            ServerProcessManager.appLog("上下文压缩：将前 ${old.size} 条消息总结为摘要…")
            try {
                val cloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()
                val sb = StringBuilder()
                old.forEach { m ->
                    val who = when (m.role) {
                        "user" -> "用户"
                        "assistant" -> "助手"
                        else -> "系统"
                    }
                    sb.append(who).append('：').append(m.content.take(1200)).append('\n')
                }
                val summary = StringBuilder()
                ChatClient.chatStream(
                    port = AppSettings.port,
                    messages = listOf(
                        ChatClient.ChatMessage(
                            "system",
                            "把下面的对话压缩成要点摘要：保留关键事实、结论、待办事项与用户偏好；" +
                                    "用中文；不超过 400 字；直接输出摘要正文，不要客套。"
                        ),
                        ChatClient.ChatMessage("user", sb.toString())
                    ),
                    apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                               else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                    baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                    model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                    thinking = if (cloud) null else false,
                    maxTokens = if (cloud) null else 1024,
                    tools = null,
                    onDelta = { d -> if (isGenerating(sid)) summary.append(d) },
                    onReasoning = {},
                    onFinal = { _, _, _ -> }
                )
                val text = summary.toString().trim()
                if (text.isBlank()) {
                    _error.value = "压缩失败：模型未返回摘要"
                    ServerProcessManager.appLog("上下文压缩失败：空摘要")
                } else {
                    val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date())
                    val sysMsg = ChatStore.Msg(
                        "system",
                        "📝 历史摘要（${old.size} 条消息，压缩于 $stamp）：\n$text"
                    )
                    _messages.value = listOf(sysMsg) + recent
                    persist()
                    _lastStats.value = "已压缩：${old.size} 条 + 最近 ${recent.size} 条 → 摘要 + 最近 ${recent.size} 条"
                    ServerProcessManager.appLog("上下文压缩完成：${old.size} 条 → 摘要（${text.length} 字）")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "压缩失败：${e.message}"
                ServerProcessManager.appLog("上下文压缩失败：${e.message}")
            } finally {
                _generating.value = _generating.value - sid
                setProgress(sid, null)
            }
        }
    }

    /**
     * 工具调用参数被模型写崩（复读把 JSON 撑到不闭合）时服务端直接 500，整轮就废了。
     * 这里去掉 tools 重发一次：模型通常能给纯文字答案，用户至少拿到结果。只试一次，失败仍报原错。
     */
    private suspend fun retryWithoutTools(
        sid: String,
        systemText: String,
        history: List<ChatStore.Msg>
    ): Boolean {
        val cloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()
        val msgs = listOf(
            ChatClient.ChatMessage(
                "system",
                systemText + "\n\n【注意】上一次尝试里工具调用的参数格式出错，本次请直接用文字回答，不要调用任何工具。"
            )
        ) + history.filter { it.role != "system" && it.imageB64 == null }
            .map { ChatClient.ChatMessage(it.role, it.content ?: "") }
        var wrote = false
        val ok = runCatching {
            ChatClient.chatStream(
                port = AppSettings.port,
                messages = msgs,
                apiToken = if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                onDelta = { d ->
                    if (d.isNotEmpty()) {
                        wrote = true
                        appendToLastInline(sid) { it.copy(content = (it.content ?: "") + d) }
                    }
                },
                baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                model = if (cloud) AppSettings.chatCloudModel.ifBlank { "local" } else "local",
                tools = null
            )
        }
        return ok.isSuccess && wrote
    }

    /**
     * 技能链执行器：每步独立生成一次，上一步的输出作为下一步的输入，每步结果都可见。
     *
     * 与旧实现的根本区别：旧的是「一段提示词里的步骤清单」，模型必须在一口气生成里自己记住所有步骤，
     * 中间结果既不展示也无法判断成败，所谓条件分支只能靠它自觉。这里每步都能判定成败，
     * 失败时按 `fallbackSkillId` 换一个技能重跑这一步——这才是真正的分支。
     *
     * 失败判据刻意保守：只认行首的「错误：/失败：」这类明确声明，避免「没有错误」被误判成失败。
     * 没有配备用技能时，失败就中止并把卡在哪一步说出来，不假装完成。
     */
    private suspend fun runSkillChain(sid: String, chain: SkillChain, goal: String) {
        val skills = AppSettings.loadSkills().associateBy { it.id }
        // 保留链条里的原始位置（含已删除技能留下的空洞），否则分支里的步号会整体错位
        val stepSkills = chain.skillIds.map { skills[it] }
        val fallback = chain.fallbackSkillId?.let { skills[it] }
        if (stepSkills.all { it == null }) {
            _error.value = "这条技能链里的技能都被删掉了，请重新配置"
            _generating.value = _generating.value - sid
            return
        }
        val sys = AppSettings.activeSysPrompt().ifBlank { "你是一个严谨的中文助手，回答用中文。" }
        val out = StringBuilder()
        val limit = SkillChainFlow.visitLimit(stepSkills.size)
        var executed = 0
        try {
            var input = goal
            var index = 0
            while (index != SkillChainFlow.END) {
                val sk = stepSkills.getOrNull(index)
                if (sk == null) {
                    index = SkillChainFlow.followingIndex(index, stepSkills.size)
                    continue
                }
                executed++
                if (executed > limit) {
                    _error.value = "技能链跳转次数超过上限（$limit 次），已中止——可能在几步之间来回跳，请检查条件设置"
                    ServerProcessManager.appLog("技能链：跳转次数超限（$limit），中止")
                    break
                }
                ServerProcessManager.appLog("技能链 第 ${index + 1} 步：${sk.name}")
                val prompt = sk.prompt + "\n\n【输入】\n" + input
                var text = runChainStep(sys, prompt)
                var failed = looksLikeFailure(text)
                if (failed && fallback != null) {
                    ServerProcessManager.appLog("第 ${index + 1} 步判定失败，改用备用技能：${fallback.name}")
                    val alt = runChainStep(
                        sys,
                        fallback.prompt + "\n\n【上一次尝试没能完成本步，请换一种可行做法重做】\n【输入】\n" + input
                    )
                    if (alt.isNotBlank() && !looksLikeFailure(alt)) {
                        text = alt
                        failed = false
                    } else {
                        appendToLastInline(sid) { m -> m.copy(content = out.toString().trim()) }
                        out.append("### 第 ${index + 1} 步 · ").append(sk.name).append("（已尝试备用技能仍失败）\n")
                        out.append(text.trim()).append("\n\n")
                        appendToLastInline(sid) { m -> m.copy(content = out.toString().trim()) }
                        _error.value = "第 ${index + 1} 步（${sk.name}）没能完成，技能链已中止"

                        break
                    }
                }
                val branch = SkillChainFlow.matchedBranch(index, text, chain)
                val nextIndex = SkillChainFlow.nextIndex(index, text, chain)
                out.append("### 第 ${index + 1} 步 · ").append(sk.name).append('\n')
                out.append(text.trim()).append("\n\n")
                if (branch != null) {
                    val where = branch.gotoStep?.let { "第 ${it + 1} 步" } ?: "结束整条链"
                    out.append("（输出命中「").append(branch.contains).append("」→ 跳转到").append(where).append("）\n\n")
                    ServerProcessManager.appLog("第 ${index + 1} 步命中「${branch.contains}」→ $where")
                }
                appendToLastInline(sid) { m ->
                    m.copy(
                        content = out.toString().trim(),
                        steps = m.steps + ChatStore.Step(
                            "tool", "技能链第 ${index + 1} 步：${sk.name}",
                            text.trim().take(400), 0, args = prompt.take(300), ok = !failed
                        )
                    )
                }
                persistProgress(sid)
                if (failed) {
                    _error.value = "第 ${index + 1} 步（${sk.name}）没能完成，技能链已中止"
                    break
                }
                input = text.trim()
                index = nextIndex
            }
            _lastStats.value = "技能链完成（执行 $executed 步）"
        } catch (e: Exception) {
            ServerProcessManager.appLog("技能链失败：${e.message}")
            _error.value = friendlyError(e)
        } finally {
            _generating.value = _generating.value - sid
            setProgress(sid, null)
            persistSession(sid)
        }
    }

    /** 跑一步：一次不带动工具的生成，返回完整文本 */
    private suspend fun runChainStep(system: String, prompt: String): String {
        val sb = StringBuilder()
        val cloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()
        ChatClient.chatStream(
            port = AppSettings.port,
            messages = listOf(
                ChatClient.ChatMessage("system", system),
                ChatClient.ChatMessage("user", prompt)
            ),
            apiToken = if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
            onDelta = { d -> sb.append(d) },
            baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
            model = if (cloud) AppSettings.chatCloudModel.ifBlank { "local" } else "local"
        )
        return sb.toString()
    }

    /** 步骤失败判据：只认明确声明，避免把「没有错误」这类否定句判成失败 */
    private fun looksLikeFailure(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return true
        val head = s.take(40)
        if (CHAIN_FAIL_MARKERS.any { head.startsWith(it) }) return true
        return s.contains("无法完成本步") || s.contains("无法继续执行本步")
    }

    /**
     * 应用/清除技能链。链由执行器逐步驱动，所以不再把各步提示词写成会话的 system prompt。
     */
    fun setSessionChain(chainId: String?) {
        val id = _activeId.value
        _sessions.value = _sessions.value.map {
            if (it.id == id) it.copy(
                systemPrompt = null,
                appliedSkillId = null,
                appliedChainId = chainId,
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        saveStore()
        ServerProcessManager.appLog(if (chainId == null) "已清除技能链" else "已应用技能链（执行器模式）")
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
            "连接失败：请先在「状态」页启动服务"
        e.message?.contains("401") == true -> "鉴权失败：请在设置中检查访问令牌/云端 Key"
        // 服务端把工具调用参数当 JSON 解析失败：10 次有 9 次是模型在长字符串里复读，
        // 把参数撑到不闭合。这种情况重试通常就好，说得直白点，别让用户以为是自己操作错了。
        e.message?.contains("Failed to parse tool call arguments as JSON") == true ->
            "模型把工具调用的参数写成了不合法的 JSON（通常是陷入复读），这一轮已中断。" +
                    "再发一次、或点「继续这个任务」分段重试通常就好了。"
        // 上下文被工具返回值撑爆：本地引擎会直接 400，这一轮连结果都拿不回来。
        // 原文只有一行 JSON，用户看不出该做什么，这里把该做的两步说清楚。
        e.message?.contains("exceeds the available context size") == true -> {
            val nums = Regex("\"n_prompt_tokens\":\\s*(\\d+)|\\\"n_ctx\\\":\\s*(\\d+)")
                .findAll(e.message ?: "").mapNotNull { it.groupValues.drop(1).firstOrNull { v -> v.isNotEmpty() } }
                .toList()
            val used = nums.getOrNull(0)?.toLongOrNull()
            val cap = nums.getOrNull(1)?.toLongOrNull()
            "上下文被撑满了" + (if (used != null && cap != null) "：这次请求 $used token，窗口只有 $cap" else "") +
                    "。多数情况是某次工具调用一次读回了太大的内容——让它分小段读（指定行数/范围），" +
                    "或点输入框上方的「压缩上下文」把历史压一压再重试。"
        }
        // 兜底分支可能是几十 KB 的原始响应体，必须截断（原因见 MAX_ERROR_CHARS）
        else -> (e.message ?: "请求失败").let {
            if (it.length <= MAX_ERROR_CHARS) it else it.take(MAX_ERROR_CHARS) + "…（完整内容见日志）"
        }
    }

    /** 上下文占用（本次输入+本轮回复 = 下一轮的实际起点，服务端权威计数，含模板开销）；≥90% 加 ⚠️ */
    private fun ctxNote(usedTokens: Int): String {
        if (usedTokens <= 0) return ""
        val ctx = AppSettings.effectiveCtx(AppSettings.selectedModelPath)
        if (ctx <= 0) return ""
        val cur = if (usedTokens >= 1000) String.format("%.1fk", usedTokens / 1000.0) else "$usedTokens"
        val max = if (ctx >= 1000) String.format("%.1fk", ctx / 1000.0) else "$ctx"
        val near = usedTokens.toDouble() / ctx >= 0.9
        return " · 上下文 $cur/$max" + if (near) " ⚠️" else ""
    }
}
