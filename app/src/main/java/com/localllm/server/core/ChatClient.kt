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
    data class ChatMessage(
        val role: String,
        val content: Any?,
        /** assistant 消息携带的 tool_calls（多轮工具调用回传用） */
        val toolCalls: JSONArray? = null,
        /** tool 结果消息对应的 tool_call_id */
        val toolCallId: String? = null
    )

    /** 流式聚合出的一个工具调用 */
    data class ToolCall(
        val id: String,
        val name: String,
        /** 未解析的 JSON 参数串（模型可能分片输出，已拼接完整） */
        val arguments: String
    )

    /** 当前进行中的请求连接（单会话单请求）；停止按钮通过 abort() 立即断开 */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /** 立即中断正在进行的流式请求：断开连接让阻塞中的读马上返回，
     *  对思考流（reasoning）同样生效——仅靠协程取消对阻塞 readLine 无效。 */
    fun abort() {
        val c = activeConn
        activeConn = null
        try {
            c?.disconnect()
        } catch (_: Exception) {
        }
    }

    suspend fun chatStream(
        port: Int,
        messages: List<ChatMessage>,
        apiToken: String?,
        onDelta: (String) -> Unit,
        /** 思考过程增量（thinking 模型的 reasoning_content） */
        onReasoning: (String) -> Unit = {},
        /** 服务端权威统计：completion_tokens 与 predicted tok/s（流式末尾 usage 块） */
        /** 流结束后回调聚合的 tool_calls（无则空列表） */
        onFinal: (completionTokens: Int, serverTps: Double, promptTokens: Int) -> Unit = { _, _, _ -> },
        /** 非空时使用云端 OpenAI 兼容端点（如 https://api.xxx.com）；为空走本机回环 */
        baseUrl: String? = null,
        model: String = "local",
        /** 本地 jinja 模板思考真开关（chat_template_kwargs.enable_thinking）；null=不干预（云端模板自管） */
        thinking: Boolean? = null,
        /** 本地生成上限：防止退化复读跑到撑满上下文（约几十分钟）；null=不设 */
        maxTokens: Int? = null,
        /** OpenAI tools 数组；null/空则不注入 */
        tools: JSONArray? = null,
        /** 流结束后回调聚合的 tool_calls（无则空列表） */
        onToolCalls: (List<ToolCall>) -> Unit = {},
        /** 耗时诊断：ttfbMs=首字节，firstTokenMs=首个内容增量（云端慢排查用） */
        onTiming: (ttfbMs: Long, firstTokenMs: Long?) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val url = if (baseUrl.isNullOrBlank()) {
            URL("http://127.0.0.1:$port/v1/chat/completions")
        } else {
            // 通用 OpenAI 兼容拼接：裸域名补 /v1，带版本路径（/v1、/paas/v4 …）原样追加
            URL(OpenAiCompat.chatUrl(baseUrl))
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
        activeConn = conn
        val t0 = System.currentTimeMillis()
        var ttfbMs = -1L
        var firstTokenMs: Long? = null
        try {
            val arr = JSONArray()
            messages.forEach { m ->
                val o = JSONObject().put("role", m.role)
                // Android org.json：null 会写成 "null" 字符串，显式处理
                o.put("content", m.content ?: JSONObject.NULL)
                m.toolCalls?.let { o.put("tool_calls", it) }
                m.toolCallId?.let { o.put("tool_call_id", it) }
                arr.put(o)
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
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools)
            }
            if (maxTokens != null && maxTokens > 0) {
                body.put("max_tokens", maxTokens)
            }
            if (thinking != null) {
                body.put("chat_template_kwargs", JSONObject().put("enable_thinking", thinking))
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (ttfbMs < 0) ttfbMs = System.currentTimeMillis() - t0
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code $err")
            }
            // tool_calls 按 index 聚合（流式分片：id/name/arguments 各自来）
            val tcId = mutableMapOf<Int, String>()
            val tcName = mutableMapOf<Int, String>()
            val tcArgs = mutableMapOf<Int, StringBuilder>()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    // 每行都检查取消（含思考流/工具流）：abort() 断连后此处也会抛
                    currentCoroutineContext().ensureActive()
                    if (!l.startsWith("data:")) continue
                    val payload = l.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = JSONObject(payload)
                    // 末尾 usage 块（choices 为空）：权威 token 数与生成速率
                    val usage = obj.optJSONObject("usage")
                    if (usage != null) {
                        val timings = obj.optJSONObject("timings")
                        val tokens = usage.optInt("completion_tokens", 0)
                        val prompt = usage.optInt("prompt_tokens", 0)
                        // tps 可能缺失（云端 OpenAI 风格无 timings）：照样采纳权威 token 数
                        val tps = timings?.optDouble("predicted_per_second", 0.0) ?: 0.0
                        if (tokens > 0) onFinal(tokens, tps, prompt)
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
                    // 工具调用分片
                    val toolCallsArr = delta.optJSONArray("tool_calls")
                    if (toolCallsArr != null) {
                        for (i in 0 until toolCallsArr.length()) {
                            val tc = toolCallsArr.optJSONObject(i) ?: continue
                            val idx = tc.optInt("index", i)
                            if (!tc.isNull("id") && tc.optString("id").isNotEmpty()) {
                                tcId[idx] = tc.optString("id")
                            }
                            val fn = tc.optJSONObject("function")
                            if (fn != null) {
                                if (!fn.isNull("name") && fn.optString("name").isNotEmpty()) {
                                    tcName[idx] = fn.optString("name")
                                }
                                val args = if (fn.isNull("arguments")) "" else fn.optString("arguments")
                                if (args.isNotEmpty()) {
                                    tcArgs.getOrPut(idx) { StringBuilder() }.append(args)
                                }
                            }
                        }
                        continue
                    }
                    // Android org.json 的坑：显式 null 会被 optString 转成字符串 "null"
                    val text = if (delta.isNull("content")) "" else delta.optString("content")
                    if (text.isNotEmpty()) {
                        if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - t0
                        onDelta(text)
                    }
                }
            }
            onTiming(ttfbMs, firstTokenMs)
            val calls = tcId.keys.sorted().mapNotNull { idx ->
                val name = tcName[idx] ?: return@mapNotNull null
                ToolCall(
                    id = tcId[idx] ?: "call_$idx",
                    name = name,
                    arguments = tcArgs[idx]?.toString() ?: "{}"
                )
            }
            onToolCalls(calls)
        } finally {
            if (activeConn === conn) activeConn = null
            conn.disconnect()
        }
    }
}
