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
import com.localllm.server.core.McpHttpClient
import com.localllm.server.core.MemoryStore
import com.localllm.server.core.OcrEngine
import com.localllm.server.core.ServerProcessManager
import com.localllm.server.core.ServerUiState
import com.localllm.server.core.TodoTool
import com.localllm.server.core.UrlFetch
import com.localllm.server.core.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private var store = ChatStore.load(app)

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

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

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
    val pendingImageB64: StateFlow<String?> = _pendingImageB64.asStateFlow()

    private var streamJob: Job? = null

    fun onInput(v: String) { _input.value = v }

    init {
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
                        _pendingImageFullB64.value =
                            android.util.Base64.encodeToString(fullBytes, android.util.Base64.NO_WRAP)
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
                // 文档（PDF/docx/xlsx）走本地文本抽取；其它按文本读取
                val doc = DocExtract.extract(bytes, name, mime)
                val text = if (doc != null) {
                    val (kind, extracted) = doc
                    if (extracted.isNullOrBlank()) {
                        _error.value = when (kind) {
                            "pdf" -> "PDF 没有可提取的文字层（可能是扫描件）：可截图后走 OCR，或使用视觉模型"
                            "docx" -> "Word 文档没有可提取的文字"
                            else -> "表格没有可提取的文字"
                        }
                        return@launch
                    }
                    ServerProcessManager.appLog("文档抽取：$name → ${kind.uppercase()} ${extracted.length} 字")
                    "（${kind.uppercase()} 已抽取文本）\n" + extracted
                } else {
                    if (bytes.size > 256 * 1024) {
                        _error.value = "文本文件超过 256KB，请精简后再试"
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
        if ((text.isEmpty() && image == null && file == null) || _streaming.value) return
        _input.value = ""
        _pendingImageB64.value = null
        _pendingFile.value = null
        ocrSourceB64 = _pendingImageFullB64.value
        _pendingImageFullB64.value = null
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
        append(ChatStore.Msg("user", content.ifBlank { fallback }, image))
        append(ChatStore.Msg("assistant", ""))
        doStream()
    }

    fun resendAt(index: Int) {
        if (_streaming.value) return
        val list = _messages.value.toMutableList()
        if (index !in list.indices || list[index].role != "user") return
        _messages.value = list.subList(0, index + 1).toMutableList()
        append(ChatStore.Msg("assistant", ""))
        doStream()
    }

    fun editAndResend(index: Int, newText: String) {
        val text = newText.trim()
        if (text.isEmpty() || _streaming.value) return
        val list = _messages.value.toMutableList()
        if (index !in list.indices || list[index].role != "user") return
        list[index] = list[index].copy(content = text, reasoning = null)
        _messages.value = list.subList(0, index + 1).toMutableList()
        append(ChatStore.Msg("assistant", ""))
        doStream()
    }

    private fun doStream() {
        streamJob = viewModelScope.launch {
            _streaming.value = true
            _error.value = null
            _lastStats.value = null
            // 模型“答完又想再答”（单次生成内二次思考）时自动截断本轮
            var autoStopped = false
            var abortDeltas = 0
            val sendStartedAt = System.currentTimeMillis()
            try {
                // 提示词保持最小化：过多硬性约束会让小模型在思考里反复“检查规则”而失控。
                // LaTeX 已有客户端转换器兜底，不再要求模型禁用。
                val searchOn = AppSettings.webSearchEnabled
                val todoOn = AppSettings.todoToolEnabled
                val fileOn = AppSettings.fileToolsEnabled && AppSettings.workDirTreeUri.isNotBlank()
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
                val fileHint = if (fileOn) {
                    "如需生成文件（HTML/代码/CSV）用 file_write；需要真正的 Excel 表格用 make_xlsx（rows 二维数组）；" +
                            "需要 PDF 用 make_pdf。都写入工作目录，并在回答里告知文件名；" +
                            "查看已有文件用 file_list / file_read / file_grep。"
                } else ""
                val todoHint = if (todoOn) {
                    "多步任务可先用 todo_write 列出计划（同一消息最多 3 次），随后必须在正文中直接给出实际产出（代码/文件内容），" +
                            "绝不能用「已完成…」之类的进度描述代替真正的内容。"
                } else ""
                val systemText = (if (basePrompt.isNotBlank())
                    "【本会话角色设定，优先遵守】" + basePrompt + " "
                else "") + todoHint + fileHint + memoryHint +
                        (if (AppSettings.thinkEnabled) {
                    "用中文简洁回答。"
                } else {
                    "不要输出思考过程，直接用中文简洁回答。"
                }) + if (searchAvailable) {
                    "今天是${today}。涉及最新信息时，先用 web_search 工具联网查询再回答，不要臆造日期。"
                } else ""
                // 用户最后一条消息里若含链接 → 抓取内容（GitHub 走 README 特判）
                // 注意：内容必须并入首条 system 文案——部分模板（Qwen）要求 system 只能在开头
                val linkText: String? = run {
                    val lastUserText = _messages.value.dropLast(1)
                        .lastOrNull { it.role == "user" }?.content ?: return@run null
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

                val history = _messages.value.dropLast(1)
                // 图片路径二选一（不叠加、不改提示词）：
                //  视觉模型直读 或 本地 OCR 抽文字（省掉整张图的 token 与视觉前向）
                val visionReady = AppSettings.selectedMmprojPath
                    ?.let { java.io.File(it).exists() } == true
                val useOcr = when (AppSettings.imageInputMode) {
                    "ocr" -> true
                    "vision" -> false
                    else -> !visionReady
                }
                var ocrLogged = false
                val systemTextFull = if (linkText == null) systemText
                else systemText + "\n\n【用户消息中链接的内容（供回答参考，可能不完整）】\n" + linkText
                val payloadMsgs = listOf(ChatClient.ChatMessage("system", systemTextFull)) +
                        history.map { m ->
                            if (m.imageB64 != null) {
                                if (useOcr) {
                                    val ocrSrc = ocrSourceB64 ?: m.imageB64
                                    val ocrText = runCatching {
                                        OcrEngine.recognize(
                                            android.util.Base64.decode(ocrSrc, android.util.Base64.NO_WRAP)
                                        )
                                    }.getOrElse { e ->
                                        ServerProcessManager.appLog("图片OCR失败：${e.message}，本次改为多模态直传")
                                        null
                                    }
                                    if (!ocrLogged) {
                                        ocrLogged = true
                                        ServerProcessManager.appLog(
                                            "图片：本地 OCR 提取 ${ocrText?.length ?: 0} 字" +
                                                    "（模式=${if (AppSettings.imageInputMode == "auto") "自动" else "OCR"}）"
                                        )
                                    }
                                    if (ocrText != null) {
                                        ChatClient.ChatMessage(
                                            m.role,
                                            (if (m.content.isNotBlank()) m.content + "\n\n" else "") +
                                                    "【图片文字识别（本地OCR）】\n$ocrText"
                                        )
                                    } else {
                                        val parts = org.json.JSONArray()
                                        parts.put(org.json.JSONObject().put("type", "text").put("text", m.content))
                                        parts.put(
                                            org.json.JSONObject().put("type", "image_url").put(
                                                "image_url",
                                                org.json.JSONObject().put("url", "data:image/jpeg;base64,${m.imageB64}")
                                            )
                                        )
                                        ChatClient.ChatMessage(m.role, parts)
                                    }
                                } else {
                                    if (!ocrLogged) {
                                        ocrLogged = true
                                        ServerProcessManager.appLog("图片：多模态直读（传图）")
                                    }
                                    val parts = org.json.JSONArray()
                                    parts.put(org.json.JSONObject().put("type", "text").put("text", m.content))
                                    parts.put(
                                        org.json.JSONObject().put("type", "image_url").put(
                                            "image_url",
                                            org.json.JSONObject().put("url", "data:image/jpeg;base64,${m.imageB64}")
                                        )
                                    )
                                    ChatClient.ChatMessage(m.role, parts)
                                }
                            } else {
                                ChatClient.ChatMessage(m.role, m.content)
                            }
                        }
                val cloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()

                // HTTP MCP 多服务器：聚合已启用服务器的工具（mcp__服务器__工具 前缀防冲突），并记录路由
                val mcpRoute = mutableMapOf<String, Pair<String, String>>() // 完整名 → (url, 原始工具名)
                val mcpAgg = org.json.JSONArray()
                AppSettings.loadMcpServers().filter { it.enabled }.forEach { s ->
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
                                        .put("description", t.description)
                                        .put("parameters", t.inputSchema)
                                )
                        )
                    }
                }

                // 工具合并：内置 web_search + 全部 MCP 服务器工具
                val toolsJson = org.json.JSONArray().also { arr ->
                    if (searchAvailable) arr.put(WebSearch.toolJson())
                    if (todoOn) arr.put(TodoTool.toolJson())
                    if (fileOn) FileTools.toolsJson().let { fa ->
                        for (i in 0 until fa.length()) arr.put(fa.getJSONObject(i))
                    }
                    if (memoryOn) arr.put(MemoryStore.toolJson())
                    if (fileOn) DocMaker.toolsJson().let { da ->
                        for (i in 0 until da.length()) arr.put(da.getJSONObject(i))
                    }
                    for (i in 0 until mcpAgg.length()) arr.put(mcpAgg.getJSONObject(i))
                }.takeIf { it.length() > 0 }
                // 搜索配额用尽后本轮请求不带 web_search：模型无工具可调，只能产出文本回答
                val toolsNoSearch = org.json.JSONArray().also { arr ->
                    for (i in 0 until mcpAgg.length()) arr.put(mcpAgg.getJSONObject(i))
                }.takeIf { it.length() > 0 }

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
                val searchCap = AppSettings.searchMaxCalls.let { if (it <= 0) Int.MAX_VALUE else it }
                var loggedNoSearch = false
                // 执行步骤时间线（思考/搜索/工具），落到消息上供 UI 展示
                val steps = mutableListOf<ChatStore.Step>()
                var thinkStartedAt = 0L
                fun syncSteps() {
                    val list = _messages.value.toMutableList()
                    if (list.isEmpty()) return
                    list[list.lastIndex] = list.last().copy(steps = steps.toList())
                    _messages.value = list
                }
                fun settleThink() {
                    if (thinkStartedAt > 0) {
                        val ms = System.currentTimeMillis() - thinkStartedAt
                        steps.add(
                            ChatStore.Step("think", "思考了 ${String.format("%.1f", ms / 1000.0)} 秒", millis = ms)
                        )
                        thinkStartedAt = 0L
                        syncSteps()
                    }
                }
                val searchedQueries = mutableMapOf<String, String>() // 规范化查询 → 结果文本（相同查询复用）

                var apiMsgs = payloadMsgs
                val usedTools = mutableListOf<String>()
                val searchSources = mutableListOf<WebSearch.Result>()
                var timingNote: String? = null

                // 最多 5 轮工具调用，防止模型死循环
                // 注意：必须用 for + break——repeat 里的 return@repeat 是 continue 语义，
                // 会导致「回答完成」后仍继续下一轮（历史 bug：同一问题最多被连答 5 遍）。
                for (round in 0 until 8) { // 多步任务（todo）需要更多轮
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
                    var firstDeltaMs = 0L
                    var serverStats: String? = null
                    var toolCalls: List<ChatClient.ToolCall> = emptyList()
                    // 正文开始后冻结思考卡：部分模型会在正文期间/之后仍吐 reasoning，
                    // 导致“回答完了思考还在滚”。首个内容 delta 到达即停止追加。
                    var contentStarted = false

                    ChatClient.chatStream(
                        port = AppSettings.port,
                        messages = apiMsgs,
                        apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                                   else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                        baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                        model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                        // 本地走 jinja 模板真开关；云端模板自管不干预
                        thinking = if (cloud) null else AppSettings.thinkEnabled,
                        // 本地生成上限：退化复读时不至于跑到撑满上下文
                        maxTokens = if (cloud) null else AppSettings.effectiveOut(AppSettings.selectedModelPath),
                        // 搜索配额用尽 / 多轮无正文 → 不再提供搜索工具（防止弱模型空转到轮数上限、永远不回答）
                        tools = if (forceNoTools || (searchAvailable && searchCount >= searchCap)) toolsNoSearch else toolsJson,
                        onToolCalls = { toolCalls = it },
                        onTiming = { ttfb, firstToken ->
                            // 云端慢排查：ttfb vs 首token 分离，判断慢在网络还是在推理
                            if (cloud) {
                                val tok = firstToken?.let { " · 首token ${it / 1000.0}s" } ?: ""
                                timingNote = "连接${ttfb / 1000.0}s$tok"
                            }
                        },
                        onDelta = { delta ->
                            // 停止后一律不再上屏（abort 断连前的在途分片也丢弃）
                            if (_streaming.value) {
                                if (!contentStarted && thinkStartedAt > 0) {
                                    val secs = (System.currentTimeMillis() - thinkStartedAt) / 1000.0
                                    steps.add(
                                        ChatStore.Step(
                                            "think",
                                            "思考了 ${String.format("%.1f", secs)} 秒",
                                            millis = System.currentTimeMillis() - thinkStartedAt
                                        )
                                    )
                                    thinkStartedAt = 0L
                                    syncSteps()
                                }
                                contentStarted = true
                                deltaCount++
                                abortDeltas++
                                roundChars += delta.length
                                if (firstDeltaMs == 0L) firstDeltaMs = System.currentTimeMillis()
                                val elapsed = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                                if (elapsed > 0.3) {
                                    val tps = deltaCount / elapsed
                                    _lastStats.value =
                                        "生成中 · $deltaCount token · ${String.format("%.1f", tps)} tok/s" +
                                                " · ${String.format("%.0f", elapsed)}s"
                                }
                                val list = _messages.value.toMutableList()
                                val last = list.removeLast()
                                list.add(last.copy(content = last.content + delta))
                                _messages.value = list
                            }
                        },
                        onReasoning = { r ->
                            when {
                                !AppSettings.thinkEnabled || !_streaming.value -> Unit
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
                                    val list = _messages.value.toMutableList()
                                    val last = list.removeLast()
                                    list.add(last.copy(reasoning = (last.reasoning ?: "") + r))
                                    _messages.value = list
                                }
                            }
                        },
                        onFinal = { tokens, tps, prompt ->
                            val tpsPart = if (tps > 0) " · ${String.format("%.1f", tps)} tok/s" else ""
                            // 上下文占用 = 本次输入 + 刚生成的回复（下一轮会话的实际起点）
                            serverStats = "共 $tokens token$tpsPart" +
                                    ctxNote(prompt + tokens)
                        }
                    )

                    // 工具调用过滤（实测：弱模型答完会幻觉/再发 tool_call 引发假的第二轮）：
                    // ① 本轮正文已成形（≥60字）→ 答后调用一律忽略，直接收尾；
                    // ② 只执行真实注入过的工具名，幻觉工具直接忽略。
                    val validCalls = when {
                        roundChars >= 60 && toolCalls.isNotEmpty() -> {
                            ServerProcessManager.appLog(
                                "正文已完整（$roundChars 字），忽略答后工具调用：" +
                                        toolCalls.joinToString { it.name } + "，本轮结束"
                            )
                            emptyList()
                        }
                        else -> {
                            val ok = toolCalls.filter { it.name in injectedNames }
                            val ignored = toolCalls.map { it.name }.toSet() - ok.map { it.name }.toSet()
                            if (ignored.isNotEmpty()) {
                                ServerProcessManager.appLog("忽略未注入的工具调用：${ignored.joinToString("、")}")
                            }
                            ok
                        }
                    }

                    if (validCalls.isEmpty()) {
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
                        ServerProcessManager.appLog("聊天：完成 · ${_lastStats.value ?: "（无统计）"}")
                        // 首轮回答完成 → 自动生成会话标题
                        val assistantCount = _messages.value.count {
                            it.role == "assistant" && it.content.isNotBlank()
                        }
                        if (assistantCount == 1) {
                            val lastUser = _messages.value.lastOrNull { it.role == "user" }?.content ?: ""
                            maybeAutoTitle(lastUser, _messages.value.lastOrNull()?.content ?: "")
                        }
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
                    newMsgs.add(ChatClient.ChatMessage("assistant", null, toolCalls = asstCalls))
                        validCalls.forEach { tc ->
                        val result = runCatching {
                            if (tc.name == TodoTool.NAME) {
                                todoCalls++
                                if (todoCalls > 3) {
                                    "任务清单更新已达上限（3 次）。请立即在正文中给出用户要的实际内容（代码/文件），不要再更新清单。"
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
                                    FileTools.TOOL_READ ->
                                        "读取 " + args.optString("path")
                                    FileTools.TOOL_LIST -> "列出目录"
                                    else -> "搜索「" + args.optString("pattern") + "」"
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
                                steps.add(ChatStore.Step("tool", "文件：${tc.name}", detail.take(120)))
                                syncSteps()
                                ServerProcessManager.appLog("文件工具完成：$detail")
                                r
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
                                ServerProcessManager.appLog("MCP 调用：${tc.name}")
                                val r = McpHttpClient.callTool(route.first, null, route.second, args)
                                steps.add(ChatStore.Step("tool", "调用工具：${tc.name}", r.take(120)))
                                syncSteps()
                                r
                            }
                        }.getOrElse { e ->
                            ServerProcessManager.appLog("工具失败：${tc.name} — ${e.message}")
                            "工具执行失败：${e.message}"
                        }
                        if (!result.startsWith("工具执行失败") && tc.name != "web_search") {
                            ServerProcessManager.appLog("MCP 完成：${tc.name}")
                        }
                        newMsgs.add(ChatClient.ChatMessage("tool", result, toolCallId = tc.id))
                    }
                    apiMsgs = newMsgs
                }

                // 兜底：无正文，或只列了清单就报"已完成"（产出过短）→ 强制无工具直接产出
                val lastContentNow = _messages.value.lastOrNull()?.content.orEmpty()
                val needsRealOutput = lastContentNow.isBlank() ||
                        (lastContentNow.length < 120 && todoCalls > 0)
                if (_streaming.value && !autoStopped && needsRealOutput) {
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
                            thinking = if (cloud) null else AppSettings.thinkEnabled,
                            maxTokens = if (cloud) null else AppSettings.effectiveOut(AppSettings.selectedModelPath),
                            tools = null,
                            onDelta = { d ->
                                if (_streaming.value) {
                                    val list = _messages.value.toMutableList()
                                    val m = list.removeLast()
                                    list.add(m.copy(content = m.content + d))
                                    _messages.value = list
                                }
                            },
                            onReasoning = { r ->
                                if (AppSettings.thinkEnabled && _streaming.value) {
                                    val list = _messages.value.toMutableList()
                                    val m = list.removeLast()
                                    list.add(m.copy(reasoning = (m.reasoning ?: "") + r))
                                    _messages.value = list
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (autoStopped) {
                    // 主动截断导致的断连：不是错误，给出截断统计
                    _lastStats.value = "共 $abortDeltas token · 已截断重复思考"
                } else {
                    ServerProcessManager.appLog("聊天失败：${friendlyError(e)}")
                    _error.value = friendlyError(e)
                }
            } finally {
                if (_messages.value.lastOrNull()?.content.isNullOrEmpty()) {
                    _messages.value = _messages.value.dropLast(1)
                    if (_error.value == null) {
                        _error.value = "无回复（服务未运行？请先在状态页启动）"
                    }
                }
                persist()
                _streaming.value = false
            }
        }
    }

    /** 停止：先关门（回调失效）→ 立即断连（思考流也停）→ 取消协程 → 落盘已生成部分 */
    fun stop() {
        ServerProcessManager.appLog("用户手动停止生成")
        _streaming.value = false
        ChatClient.abort()
        streamJob?.cancel()
        streamJob = null
        persist()
    }

    /** 新建会话并切换（保留当前会话内容） */
    fun newChat() {
        streamJob?.cancel()
        persist()
        val id = System.nanoTime().toString()
        val s = ChatStore.Session(id, "新会话", System.currentTimeMillis(), emptyList())
        _sessions.value = _sessions.value + s
        _activeId.value = id
        _messages.value = emptyList()
        _lastStats.value = null
        saveStore()
    }

    fun switchSession(id: String) {
        if (id == _activeId.value) return
        streamJob?.cancel()
        persist()
        _activeId.value = id
        _messages.value = _sessions.value.find { it.id == id }?.messages ?: emptyList()
        _lastStats.value = null
        saveStore()
    }

    fun deleteSession(id: String) {
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
        if (index == list.lastIndex && _streaming.value) {
            _streaming.value = false
            ChatClient.abort()
            streamJob?.cancel()
            streamJob = null
        }
        val m = list[index]
        _messages.value = list.toMutableList().also { it.removeAt(index) }
        ServerProcessManager.appLog(
            "已删除一条${if (m.role == "user") "用户" else "助手"}消息（${m.content.length} 字）"
        )
        persist()
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
        if (_streaming.value) {
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
        streamJob = viewModelScope.launch {
            _streaming.value = true
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
                    onDelta = { d -> if (_streaming.value) summary.append(d) },
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
                _streaming.value = false
            }
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
            "连接失败：请先在「状态」页启动服务"
        e.message?.contains("401") == true -> "鉴权失败：请在设置中检查访问令牌/云端 Key"
        else -> e.message ?: "请求失败"
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
