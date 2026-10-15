package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 OpenAI Chat Completions 客户端（SSE 流式）。
 * 始终走回环 127.0.0.1，与局域网绑定无关；令牌开启时随请求携带。
 */
object ChatClient {

    /** content 可为纯文本字符串，或 OpenAI 多模态 parts 数组（文本+图片） */
    data class ChatMessage(val role: String, val content: Any)

    suspend fun chatStream(
        port: Int,
        messages: List<ChatMessage>,
        apiToken: String?,
        onDelta: (String) -> Unit,
        /** 思考过程增量（thinking 模型的 reasoning_content） */
        onReasoning: (String) -> Unit = {},
        /** 服务端权威统计：completion_tokens 与 predicted tok/s（流式末尾 usage 块） */
        onFinal: (completionTokens: Int, serverTps: Double) -> Unit = { _, _ -> },
        /** 非空时使用云端 OpenAI 兼容端点（如 https://api.xxx.com）；为空走本机回环 */
        baseUrl: String? = null,
        model: String = "local"
    ) = withContext(Dispatchers.IO) {
        val url = if (baseUrl.isNullOrBlank()) {
            URL("http://127.0.0.1:$port/v1/chat/completions")
        } else {
            val b = baseUrl.trim().removeSuffix("/")
            URL(if (b.endsWith("/v1")) "$b/chat/completions" else "$b/v1/chat/completions")
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json")
            if (!apiToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $apiToken")
            }
        }
        try {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            val body = JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("stream_options", JSONObject().put("include_usage", true))
                // 默认采样：抑制小模型复读循环（客户端可自行覆盖时以后再开放）
                .put("temperature", 0.7)
                .put("top_p", 0.95)
                .put("repeat_penalty", 1.15)
                .put("messages", arr)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code $err")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data:")) continue
                    val payload = l.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = JSONObject(payload)
                    // 末尾 usage 块（choices 为空）：权威 token 数与生成速率
                    val usage = obj.optJSONObject("usage")
                    if (usage != null) {
                        val timings = obj.optJSONObject("timings")
                        val tokens = usage.optInt("completion_tokens", 0)
                        val tps = timings?.optDouble("predicted_per_second", 0.0) ?: 0.0
                        if (tokens > 0 && tps > 0) onFinal(tokens, tps)
                        continue
                    }
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    // 思考过程（thinking 模型）
                    if (!delta.isNull("reasoning_content")) {
                        val reason = delta.optString("reasoning_content")
                        if (reason.isNotEmpty()) onReasoning(reason)
                    }
                    // Android org.json 的坑：显式 null 会被 optString 转成字符串 "null"
                    val text = if (delta.isNull("content")) "" else delta.optString("content")
                    if (text.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        onDelta(text)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
