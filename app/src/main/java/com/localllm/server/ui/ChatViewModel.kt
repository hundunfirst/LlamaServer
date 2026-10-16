package com.localllm.server.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.server.core.AppSettings
import com.localllm.server.core.ChatClient
import com.localllm.server.core.ChatStore
import com.localllm.server.core.McpHttpClient
import com.localllm.server.core.OcrEngine
import com.localllm.server.core.ServerProcessManager
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
    val pendingImageB64: StateFlow<String?> = _pendingImageB64.asStateFlow()

    private var streamJob: Job? = null

    fun onInput(v: String) { _input.value = v }

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
            }.onFailure {
                _error.value = "图片处理失败：${it.message}"
            }
        }
    }

    fun clearPendingImage() { _pendingImageB64.value = null }

    fun send() {
        val text = _input.value.trim()
        val image = _pendingImageB64.value
        if ((text.isEmpty() && image == null) || _streaming.value) return
        _input.value = ""
        _pendingImageB64.value = null
        append(ChatStore.Msg("user", text.ifEmpty { "请描述这张图片" }, image))
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
            try {
                // 提示词保持最小化：过多硬性约束会让小模型在思考里反复“检查规则”而失控。
                // LaTeX 已有客户端转换器兜底，不再要求模型禁用。
                val searchOn = AppSettings.webSearchEnabled
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
                val systemText = (if (AppSettings.thinkEnabled) {
                    "用中文简洁回答。"
                } else {
                    "不要输出思考过程，直接用中文简洁回答。"
                }) + if (searchAvailable) {
                    "今天是${today}。涉及最新信息时，先用 web_search 工具联网查询再回答，不要臆造日期。"
                } else ""
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
                val payloadMsgs = listOf(ChatClient.ChatMessage("system", systemText)) +
                        history.map { m ->
                            if (m.imageB64 != null) {
                                if (useOcr) {
                                    val ocrText = runCatching {
                                        OcrEngine.recognize(
                                            android.util.Base64.decode(
                                                m.imageB64, android.util.Base64.NO_WRAP
                                            )
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
                            (if (searchAvailable) " · 搜索开" else "") + "）"
                )

                // 真实注入过的工具名白名单：模型幻觉出来的工具调用一律不执行（直接结束本轮）
                val injectedNames = toolsJson?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optJSONObject("function")?.optString("name")
                            ?.takeIf { it.isNotBlank() }
                    }?.toSet()
                } ?: emptySet()
                var searchCount = 0
                var loggedNoSearch = false
                val searchedQueries = mutableMapOf<String, String>() // 规范化查询 → 结果文本（相同查询复用）

                var apiMsgs = payloadMsgs
                val usedTools = mutableListOf<String>()
                val searchSources = mutableListOf<WebSearch.Result>()
                var timingNote: String? = null

                // 最多 5 轮工具调用，防止模型死循环
                // 注意：必须用 for + break——repeat 里的 return@repeat 是 continue 语义，
                // 会导致「回答完成」后仍继续下一轮（历史 bug：同一问题最多被连答 5 遍）。
                for (round in 0 until 5) {
                    ensureActive() // 停止/切换后不再进入下一轮
                    // 提前强制：已搜过但走到第4轮仍无正文 → 立即移除搜索工具，逼模型作答
                    val forceNoTools = searchAvailable && round >= 3 &&
                            _messages.value.lastOrNull()?.content.isNullOrEmpty() == true
                    if ((forceNoTools || (searchAvailable && searchCount >= 3)) && !loggedNoSearch) {
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
                        maxTokens = if (cloud) null else 4096,
                        // 搜索配额用尽 / 多轮无正文 → 不再提供搜索工具（防止弱模型空转到轮数上限、永远不回答）
                        tools = if (forceNoTools || (searchAvailable && searchCount >= 3)) toolsNoSearch else toolsJson,
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
                                contentStarted = true
                                deltaCount++
                                abortDeltas++
                                roundChars += delta.length
                                if (firstDeltaMs == 0L) firstDeltaMs = System.currentTimeMillis()
                                val elapsed = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                                if (elapsed > 0.3) {
                                    val tps = deltaCount / elapsed
                                    _lastStats.value =
                                        "生成中 · $deltaCount token · ${String.format("%.1f", tps)} tok/s"
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
                        // 最终回复：合并统计
                        val timing = timingNote?.let { "$it · " } ?: ""
                        if (serverStats != null) {
                            _lastStats.value = timing + serverStats
                        } else if (deltaCount > 0 && firstDeltaMs > 0) {
                            val genSecs = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                            if (genSecs > 0.05) {
                                val tps = deltaCount / genSecs
                                _lastStats.value = timing + "共 $deltaCount token · ${String.format("%.1f", tps)} tok/s"
                            }
                        }
                        // 搜索来源页脚：只列正文实际引用到的来源（域名或标题命中），
                        // 命中不到时回退前 5 条，避免「正文2条、来源15条」的错配
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
                            val footer = buildString {
                                append("\n\n🔗 来源：\n")
                                picked.forEachIndexed { i, s ->
                                    append("${i + 1}. ${s.title}\n   ${s.url}\n")
                                }
                            }.trimEnd()
                            val list = _messages.value.toMutableList()
                            val last = list.removeLast()
                            list.add(last.copy(content = last.content + footer))
                            _messages.value = list
                        }
                        ServerProcessManager.appLog("聊天：完成 · ${_lastStats.value ?: "（无统计）"}")
                        break
                    }

                    // 执行工具调用并回传结果（仅 validCalls）
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
                            if (tc.name == "web_search") {
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
                                        searchedQueries[norm]!!
                                    }
                                    searchCount >= 3 -> {
                                        ServerProcessManager.appLog("搜索已达本消息上限（3次），返回提示")
                                        "本轮搜索次数已达上限，请基于已有搜索结果直接回答。"
                                    }
                                    else -> {
                                        searchCount++
                                        if (WebSearch.sanitizeQuery(query).second) {
                                            ServerProcessManager.appLog("搜索词含身份证/手机号/卡号，已脱敏后再出网")
                                        }
                                        ServerProcessManager.appLog("内置搜索：「${query.take(40)}」…")
                                        val rs = WebSearch.search(query)
                                        searchSources.addAll(rs)
                                        ServerProcessManager.appLog("内置搜索：返回 ${rs.size} 条结果")
                                        val text = WebSearch.formatResults(rs)
                                        searchedQueries[norm] = text
                                        text
                                    }
                                }
                            } else {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val route = mcpRoute[tc.name]
                                    ?: throw IllegalStateException("未路由的工具：${tc.name}")
                                ServerProcessManager.appLog("MCP 调用：${tc.name}")
                                McpHttpClient.callTool(route.first, null, route.second, args)
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

                // 兜底：轮次用尽仍无任何正文 → 无工具模式强问一次，保证用户一定拿到回答
                if (_streaming.value && !autoStopped &&
                    _messages.value.lastOrNull()?.content.isNullOrEmpty() == true
                ) {
                    ServerProcessManager.appLog("已达最大轮数（5）仍无正文，改用无工具模式强制回答…")
                    var forcedStats: String? = null
                    runCatching {
                        ChatClient.chatStream(
                            port = AppSettings.port,
                            messages = apiMsgs + listOf(
                                ChatClient.ChatMessage("user", "请直接用中文简要回答上面的问题，不要再调用任何工具。")
                            ),
                            apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                                       else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                            baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                            model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                            thinking = if (cloud) null else AppSettings.thinkEnabled,
                            maxTokens = if (cloud) null else 4096,
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
                                forcedStats = "共 $tokens token$tpsPart" + ctxNote(prompt + tokens)
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

    private fun friendlyError(e: Exception): String = when {
        e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
            "连接失败：请先在「状态」页启动服务"
        e.message?.contains("401") == true -> "鉴权失败：请在设置中检查访问令牌/云端 Key"
        else -> e.message ?: "请求失败"
    }

    /** 上下文占用（本次输入+本轮回复 = 下一轮的实际起点，服务端权威计数，含模板开销）；≥90% 加 ⚠️ */
    private fun ctxNote(usedTokens: Int): String {
        if (usedTokens <= 0) return ""
        val ctx = AppSettings.ctxSize
        if (ctx <= 0) return ""
        val cur = if (usedTokens >= 1000) String.format("%.1fk", usedTokens / 1000.0) else "$usedTokens"
        val max = if (ctx >= 1000) String.format("%.1fk", ctx / 1000.0) else "$ctx"
        val near = usedTokens.toDouble() / ctx >= 0.9
        return " · 上下文 $cur/$max" + if (near) " ⚠️" else ""
    }
}
