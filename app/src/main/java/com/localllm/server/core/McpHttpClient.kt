package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP 型 MCP 客户端（app 侧，不经过 llama-server）。
 * 传输：MCP Streamable HTTP（JSON-RPC 2.0 over POST）。
 *
 * 协议流程（对齐官方 SDK / Rikkahub 行为）：
 *   1. POST initialize（拿 Mcp-Session-Id 响应头，协议版本协商）
 *   2. POST notifications/initialized（无 id，202 空响应）
 *   3. 之后的 tools/list、tools/call 带 Mcp-Session-Id 头
 *   4. 收到 404 = 会话过期 → 重新握手后重试一次
 *
 * 兼容两种响应：application/json 单帧，或 text/event-stream（取最后一个 data: JSON）。
 * 仅用于用户显式配置的受信端点；http 明文依赖 manifest 的 usesCleartextTraffic。
 */
object McpHttpClient {

    data class McpTool(
        val name: String,
        val description: String,
        /** JSON Schema 对象原文（OpenAI function.parameters 直接用） */
        val inputSchema: JSONObject
    )

    private val rpcId = AtomicInteger(1)

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val PROTO_NEW = "2025-06-18"
    private const val PROTO_OLD = "2024-11-05"

    private class Session(val sessionId: String?, val proto: String)
    private val sessions = ConcurrentHashMap<String, Session>()

    /** 丢弃会话缓存（URL 变更或 404 后重新握手） */
    fun invalidate(url: String) {
        sessions.remove(url.trim())
    }

    /** 拉取工具列表。url 形如 http://192.168.1.5:8787/mcp */
    suspend fun listTools(url: String, token: String? = null): List<McpTool> =
        withContext(Dispatchers.IO) {
            val resp = withRetry(url, token) {
                rpc(
                    url = url,
                    token = token,
                    body = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", rpcId.getAndIncrement())
                        .put("method", "tools/list")
                        .put("params", JSONObject())
                )
            }
            if (resp.has("error")) {
                val err = resp.getJSONObject("error")
                throw IllegalStateException("工具列表获取失败：${err.optString("message", err.toString())}")
            }
            // JSON-RPC 约定：工具数组在 result.tools（兜底兼容顶层 tools 的非标准实现）
            val tools = resp.optJSONObject("result")?.optJSONArray("tools")
                ?: resp.optJSONArray("tools")
                ?: JSONArray()
            (0 until tools.length()).mapNotNull { i ->
                val t = tools.optJSONObject(i) ?: return@mapNotNull null
                val name = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                McpTool(
                    name = name,
                    description = t.optString("description", ""),
                    inputSchema = t.optJSONObject("inputSchema")
                        ?: JSONObject().put("type", "object")
                )
            }
        }

    /** 调用一个工具，返回结果文本（content[].text 拼接）。错误抛 IllegalStateException。 */
    suspend fun callTool(url: String, token: String?, name: String, arguments: JSONObject): String =
        withContext(Dispatchers.IO) {
            val resp = withRetry(url, token) {
                rpc(
                    url = url,
                    token = token,
                    body = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", rpcId.getAndIncrement())
                        .put("method", "tools/call")
                        .put("params", JSONObject().put("name", name).put("arguments", arguments))
                )
            }
            if (resp.has("error")) {
                val err = resp.getJSONObject("error")
                throw IllegalStateException(
                    "工具 $name 执行失败：${err.optString("message", err.toString())}"
                )
            }
            val result = resp.optJSONObject("result") ?: resp
            val content = result.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> if (sb.isNotEmpty()) sb.append('\n').append(c.optString("text"))
                              else sb.append(c.optString("text"))
                    "image" -> sb.append("[图片结果]")
                    else -> Unit
                }
            }
            if (result.optBoolean("isError") == true) {
                throw IllegalStateException("工具 $name 报告错误：${sb.take(500)}")
            }
            sb.toString().ifBlank { "(工具无返回内容)" }
        }

    /** 工具列表 → OpenAI tools 数组 */
    fun toOpenAiTools(tools: List<McpTool>): JSONArray {
        val arr = JSONArray()
        tools.forEach { t ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function", JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", t.inputSchema)
                    )
            )
        }
        return arr
    }

    /** 缓存/配置里的工具 JSON（[{name,description,inputSchema}]）→ McpTool 列表 */
    fun parseTools(arr: JSONArray): List<McpTool> =
        (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpTool(
                name = name,
                description = t.optString("description", ""),
                inputSchema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
            )
        }

    // ---- 握手 / 会话 ----

    private fun ensureSession(url: String, token: String?) {
        val key = url.trim()
        if (sessions.containsKey(key)) return
        synchronized(sessions) {
            if (sessions.containsKey(key)) return
            var proto = PROTO_NEW
            var resp = doInitialize(url, token, proto)
            if (resp.has("error")) {
                // 协议版本不被接受 → 换旧版重试一次
                proto = PROTO_OLD
                resp = doInitialize(url, token, proto)
            }
            if (resp.has("error")) {
                val err = resp.getJSONObject("error")
                throw IllegalStateException("初始化失败：${err.optString("message", err.toString())}")
            }
            val sid = lastSessionId
            // notifications/initialized：无 id，202 空响应均可
            postRpc(
                url, token, sid,
                JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"),
                allowEmpty = true
            )
            sessions[key] = Session(sid, proto)
        }
    }

    private fun doInitialize(url: String, token: String?, proto: String): JSONObject =
        postRpc(
            url, token, null, JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", rpcId.getAndIncrement())
                .put("method", "initialize")
                .put(
                    "params", JSONObject()
                        .put("protocolVersion", proto)
                        .put("capabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "LlamaServer").put("version", "0.3.0"))
                )
        ) ?: throw IllegalStateException("initialize 空响应")

    /** 先握手，执行 rpc；HTTP 404（会话过期）时重新握手并重试一次 */
    private fun <T> withRetry(url: String, token: String?, block: () -> T): T {
        ensureSession(url, token)
        return try {
            block()
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("HTTP 404") == true) {
                invalidate(url)
                ensureSession(url, token)
                block()
            } else throw e
        }
    }

    // ---- 传输 ----

    @Volatile
    private var lastSessionId: String? = null

    private fun rpc(url: String, token: String?, body: JSONObject): JSONObject {
        val sess = sessions[url.trim()]
        return postRpc(url, token, sess?.sessionId, body, proto = sess?.proto)
            ?: throw IllegalStateException("空响应")
    }

    private fun postRpc(
        url: String,
        token: String?,
        sessionId: String?,
        body: JSONObject,
        allowEmpty: Boolean = false,
        proto: String? = null
    ): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        if (!sessionId.isNullOrBlank()) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId)
        }
        if (!proto.isNullOrBlank()) {
            conn.setRequestProperty("MCP-Protocol-Version", proto)
        }
        lastSessionId = null
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { if (it.isNotBlank()) lastSessionId = it }
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code：${err.take(300)}")
            }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            if (raw.isBlank()) {
                if (allowEmpty) return null
                throw IllegalStateException("空响应")
            }
            return parseResponse(raw)
        } finally {
            conn.disconnect()
        }
    }

    /** 单 JSON 对象或 SSE 帧都能吃；SSE 取最后一个含 result/error 的 data 帧 */
    internal fun parseResponse(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw IllegalStateException("空响应")
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        // text/event-stream：逐行找 data:
        var last: JSONObject? = null
        BufferedReader(trimmed.reader()).useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                if (obj.has("result") || obj.has("error")) last = obj
            }
        }
        return last ?: throw IllegalStateException("响应中没有 JSON-RPC 帧：${trimmed.take(200)}")
    }
}
