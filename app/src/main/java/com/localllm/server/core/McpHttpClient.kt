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

    /**
     * 失败归因。分类的意义不是给日志看，而是决定「要不要重试」和「告诉模型下一步做什么」：
     * 连不上和超时是两回事，参数写错和工具自身报错也是两回事，模型据此才知道该换工具还是改参数。
     */
    enum class FailureKind(val label: String) {
        UNREACHABLE("服务器不可达"),
        TIMEOUT("调用超时"),
        SERVER("服务器内部错误"),
        AUTH("鉴权失败"),
        BAD_ARGS("参数不被接受"),
        TOOL("工具自身报错"),
        PROTOCOL("协议异常"),
        UNKNOWN("调用异常")
    }

    class McpException(
        val kind: FailureKind,
        val detail: String,
        /** 同样参数再调一次是否可能成功（网络抖动/5xx/空响应才算） */
        val retryable: Boolean = false,
        val httpCode: Int? = null
    ) : Exception("${kind.label}：$detail")

    private val rpcId = AtomicInteger(1)

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val RETRY_DELAY_MS = 500L
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
            val resp = guarded {
                withTransientRetry {
                    withRetry(url, token) {
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
                }
            }
            if (resp.has("error")) {
                throw rpcError("工具列表获取失败", resp.getJSONObject("error"))
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

    /** 调用一个工具，返回结果文本（content[].text 拼接）。错误抛 [McpException]。 */
    suspend fun callTool(url: String, token: String?, name: String, arguments: JSONObject): String =
        withContext(Dispatchers.IO) {
            val resp = guarded {
                withTransientRetry {
                    withRetry(url, token) {
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
                }
            }
            if (resp.has("error")) {
                throw rpcError("工具 $name 执行失败", resp.getJSONObject("error"))
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
                throw McpException(FailureKind.TOOL, sb.toString().take(500).ifBlank { "工具报告错误但没给原因" })
            }
            sb.toString().ifBlank { "(工具无返回内容)" }
        }

    /**
     * 给模型看的失败说明：说清是哪一类问题 + 下一步能做什么。
     * 直接把异常原文塞回去，模型看到「HTTP 500」只会拿同样的参数再试一遍。
     */
    fun explain(name: String, e: Throwable): String {
        val f = classify(e)
        val advice = when (f.kind) {
            FailureKind.UNREACHABLE ->
                "这个服务器现在连不上，同样参数再调一次不会有不同结果：请改用其他工具，或直接基于已有信息回答，" +
                        "并在回答里说明该服务没连上。"
            FailureKind.TIMEOUT ->
                "已经自动重试过一次仍然超时。可以把请求拆小一点再试一次，或者换其他方式完成这件事。"
            FailureKind.SERVER ->
                "已自动重试过一次，服务器仍然报错——不是参数写法的问题，原样重调不会有不同结果。"
            FailureKind.AUTH ->
                "令牌或权限不对，本次不要再调用这个服务器的任何工具，直接告诉用户去检查 MCP 配置。"
            FailureKind.BAD_ARGS ->
                "按上面的原因修正参数后可以再调用一次；连续两次同样报错说明这个工具不接受这种用法，换别的路子。"
            FailureKind.TOOL ->
                "这是工具自己报的错，换一组参数可能就能成功；同一个工具连续两次失败就不要再试了。"
            FailureKind.PROTOCOL ->
                "已重试过一次。再失败就换工具，或直接回答。"
            FailureKind.UNKNOWN ->
                "同样参数再失败一次就换其他方式完成，不要反复重试同一个调用。"
        }
        return "工具「$name」调用失败（${f.kind.label}）：${f.detail}\n→ $advice"
    }

    /** 异常 → 归因。纯函数，便于单测。 */
    internal fun classify(e: Throwable): McpException = when (e) {
        is McpException -> e
        is java.net.SocketTimeoutException ->
            McpException(FailureKind.TIMEOUT, "读取超时（${READ_TIMEOUT_MS / 1000}s）", retryable = true)
        is java.net.ConnectException ->
            McpException(FailureKind.UNREACHABLE, "连接被拒绝，服务可能没启动")
        is java.net.UnknownHostException ->
            McpException(FailureKind.UNREACHABLE, "地址解析不了：${e.message.orEmpty()}")
        is java.net.NoRouteToHostException ->
            McpException(FailureKind.UNREACHABLE, "网络不可达")
        is javax.net.ssl.SSLException ->
            McpException(FailureKind.PROTOCOL, "TLS 握手失败：${e.message.orEmpty()}")
        is java.net.SocketException ->
            McpException(FailureKind.UNREACHABLE, "连接中断：${e.message.orEmpty()}")
        else -> McpException(FailureKind.UNKNOWN, (e.message ?: e.javaClass.simpleName).take(300))
    }

    /** 把这一层所有异常收成 [McpException]，调用方不用再猜异常类型 */
    private fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
        }

    /** 瞬时故障（超时 / 5xx / 空响应）自动重试一次；连不上不重试——服务没起来，再等 8 秒也没用 */
    private fun <T> withTransientRetry(block: () -> T): T =
        try {
            block()
        } catch (e: McpException) {
            if (!e.retryable) throw e
            Thread.sleep(RETRY_DELAY_MS)
            block()
        }

    /** JSON-RPC error 对象 → 带归因的异常（code 决定是参数问题还是工具问题） */
    private fun rpcError(prefix: String, err: JSONObject): McpException {
        val msg = err.optString("message", err.toString()).take(300)
        val code = err.optInt("code", 0)
        val kind = when (code) {
            -32602 -> FailureKind.BAD_ARGS          // invalid params
            -32601 -> FailureKind.PROTOCOL          // method not found：服务端没这个工具
            -32603, -32000 -> FailureKind.TOOL      // internal / server error
            else -> FailureKind.TOOL
        }
        return McpException(kind, "$prefix：$msg", httpCode = null)
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
                throw rpcError("初始化失败", resp.getJSONObject("error"))
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
        ) ?: throw McpException(FailureKind.PROTOCOL, "initialize 空响应", retryable = true)

    /** 先握手，执行 rpc；HTTP 404（会话过期）时重新握手并重试一次 */
    private fun <T> withRetry(url: String, token: String?, block: () -> T): T {
        ensureSession(url, token)
        return try {
            block()
        } catch (e: McpException) {
            if (e.httpCode == 404) {
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
            ?: throw McpException(FailureKind.PROTOCOL, "空响应", retryable = true)
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
                throw httpFailure(code, err)
            }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            if (raw.isBlank()) {
                if (allowEmpty) return null
                throw McpException(FailureKind.PROTOCOL, "空响应", retryable = true)
            }
            return parseResponse(raw)
        } finally {
            conn.disconnect()
        }
    }

    /** HTTP 状态码 → 归因。404 单独留标记，会话过期要靠它触发重新握手。 */
    internal fun httpFailure(code: Int, body: String): McpException {
        val detail = "HTTP $code" + if (body.isBlank()) "" else "：${body.take(300)}"
        return when (code) {
            401, 403 -> McpException(FailureKind.AUTH, detail, httpCode = code)
            404 -> McpException(FailureKind.PROTOCOL, detail, httpCode = code)
            400, 405, 406, 415, 422 -> McpException(FailureKind.BAD_ARGS, detail, httpCode = code)
            408, 429 -> McpException(FailureKind.SERVER, detail, retryable = true, httpCode = code)
            in 500..599 -> McpException(FailureKind.SERVER, detail, retryable = true, httpCode = code)
            else -> McpException(FailureKind.PROTOCOL, detail, httpCode = code)
        }
    }

    /** 单 JSON 对象或 SSE 帧都能吃；SSE 取最后一个含 result/error 的 data 帧 */
    internal fun parseResponse(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw McpException(FailureKind.PROTOCOL, "空响应", retryable = true)
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
        return last ?: throw McpException(
            FailureKind.PROTOCOL, "响应里没有 JSON-RPC 帧：${trimmed.take(200)}", retryable = true
        )
    }
}
