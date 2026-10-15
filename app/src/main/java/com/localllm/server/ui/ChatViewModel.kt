package com.localllm.server.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.server.core.AppSettings
import com.localllm.server.core.ChatClient
import com.localllm.server.core.ChatStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            var deltaCount = 0
            var firstDeltaMs = 0L
            var serverStats: String? = null
            try {
                val mathRule =
                    "数学公式与计算过程用 Unicode 符号直接书写（√ × ÷ ≈ ± ≤ ≥ ² ³ ½，分数写成 a/b），" +
                    "严禁使用 LaTeX 语法（不要出现 $$、\\frac、\\sqrt、\\times、\\text 等反斜杠命令和花括号）。"
                val systemText = if (AppSettings.thinkEnabled) {
                    "请始终用中文进行思考和回答。$mathRule"
                } else {
                    "请不要输出任何思考/推理过程，直接用中文给出最终答案。$mathRule"
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
                ChatClient.chatStream(
                    port = AppSettings.port,
                    messages = payloadMsgs,
                    apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                               else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                    baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                    model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                    onDelta = { delta ->
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
                    },
                    onReasoning = { r ->
                        if (AppSettings.thinkEnabled) {
                            val list = _messages.value.toMutableList()
                            val last = list.removeLast()
                            list.add(last.copy(reasoning = (last.reasoning ?: "") + r))
                            _messages.value = list
                        }
                    },
                    onFinal = { tokens, tps ->
                        serverStats = "共 $tokens token · ${String.format("%.1f", tps)} tok/s"
                    }
                )
                if (serverStats != null) {
                    _lastStats.value = serverStats
                } else if (deltaCount > 0 && firstDeltaMs > 0) {
                    val genSecs = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                    if (genSecs > 0.05) {
                        val tps = deltaCount / genSecs
                        _lastStats.value = "共 $deltaCount token · ${String.format("%.1f", tps)} tok/s"
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _streaming.value = false
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
}
