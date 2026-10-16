package com.localllm.server.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.server.core.AppSettings
import com.localllm.server.core.ChatClient
import com.localllm.server.core.ChatStore
import com.localllm.server.core.McpHttpClient
import com.localllm.server.core.ServerProcessManager
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
            try {
                // 提示词保持最小化：过多硬性约束会让小模型在思考里反复“检查规则”而失控。
                // LaTeX 已有客户端转换器兜底，不再要求模型禁用。
                val systemText = if (AppSettings.thinkEnabled) {
                    "用中文简洁回答。"
                } else {
                    "不要输出思考过程，直接用中文简洁回答。"
                }
                val history = _messages.value.dropLast(1)
                val payloadMsgs = listOf(ChatClient.ChatMessage("system", systemText)) +
                        history.map { m ->
                            if (m.imageB64 != null) {
                                val parts = org.json.JSONArray()
                                parts.put(org.json.JSONObject().put("type", "text").put("text", m.content))
                                parts.put(
                                    org.json.JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        org.json.JSONObject().put("url", "data:image/jpeg;base64,${m.imageB64}")
                                    )
                                )
                                ChatClient.ChatMessage(m.role, parts)
                            } else {
                                ChatClient.ChatMessage(m.role, m.content)
                            }
                        }
                val cloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()

                // HTTP 型 MCP：用弹窗里拉取并保存的工具缓存（按开关过滤），聊天不再现拉
                val mcpUrl = AppSettings.mcpHttpUrl.trim()
                val mcpTools = if (AppSettings.mcpHttpEnabled && mcpUrl.isNotBlank()) {
                    runCatching {
                        val cached = McpHttpClient.parseTools(
                            org.json.JSONArray(AppSettings.mcpToolsJson)
                        )
                        val enabledMap = org.json.JSONObject(AppSettings.mcpToolsEnabledJson)
                        val on = cached.filter { enabledMap.optBoolean(it.name, true) }
                        if (on.isEmpty()) null else McpHttpClient.toOpenAiTools(on)
                    }.getOrElse { e ->
                        _error.value = "MCP 工具注入失败：${e.message}"
                        null
                    }
                } else null

                ServerProcessManager.appLog(
                    "聊天：发送（" +
                            (if (cloud) "云端 ${AppSettings.chatCloudModel.ifBlank { "default" }}" else "本地") +
                            " · 历史 ${payloadMsgs.size - 1} 条" +
                            (mcpTools?.let { " · 工具 ${it.length()} 个" } ?: "") + "）"
                )

                var apiMsgs = payloadMsgs
                val usedTools = mutableListOf<String>()
                var timingNote: String? = null

                // 最多 5 轮工具调用，防止模型死循环
                repeat(5) { round ->
                    ensureActive() // 停止/切换后不再进入下一轮
                    var deltaCount = 0
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
                        tools = if (round == 0) mcpTools else mcpTools, // 每轮都带工具，模型可连续调用
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
                            if (AppSettings.thinkEnabled && !contentStarted && _streaming.value) {
                                val list = _messages.value.toMutableList()
                                val last = list.removeLast()
                                list.add(last.copy(reasoning = (last.reasoning ?: "") + r))
                                _messages.value = list
                            }
                        },
                        onFinal = { tokens, tps, prompt ->
                            val tpsPart = if (tps > 0) " · ${String.format("%.1f", tps)} tok/s" else ""
                            // 上下文占用 = 本次输入 + 刚生成的回复（下一轮会话的实际起点）
                            serverStats = "共 $tokens token$tpsPart" +
                                    ctxNote(prompt + tokens)
                        }
                    )

                    if (toolCalls.isEmpty()) {
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
                        ServerProcessManager.appLog("聊天：完成 · ${_lastStats.value ?: "（无统计）"}")
                        return@repeat
                    }

                    // 执行工具调用并回传结果
                    val asstCalls = org.json.JSONArray()
                    val newMsgs = apiMsgs.toMutableList()
                    toolCalls.forEach { tc ->
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
                        toolCalls.forEach { tc ->
                        val result = runCatching {
                            val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                            ServerProcessManager.appLog("MCP 调用：${tc.name}")
                            McpHttpClient.callTool(mcpUrl, null, tc.name, args)
                        }.getOrElse { e ->
                            ServerProcessManager.appLog("MCP 失败：${tc.name} — ${e.message}")
                            "工具执行失败：${e.message}"
                        }
                        if (!result.startsWith("工具执行失败")) {
                            ServerProcessManager.appLog("MCP 完成：${tc.name}")
                        }
                        newMsgs.add(ChatClient.ChatMessage("tool", result, toolCallId = tc.id))
                    }
                    apiMsgs = newMsgs
                }

                if (usedTools.isNotEmpty()) {
                    val base = _lastStats.value ?: ""
                    _lastStats.value = "🔧 ${usedTools.joinToString("、")}${if (base.isNotEmpty()) " · $base" else ""}"
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ServerProcessManager.appLog("聊天失败：${friendlyError(e)}")
                _error.value = friendlyError(e)
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
