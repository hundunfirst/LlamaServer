package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP 型 MCP 客户端（app 侧，不经过 llama-server）。
 * 传输：JSON-RPC 2.0 over HTTP POST（MCP Streamable HTTP 的最小实现）。
 * 兼容两种响应：application/json 单帧，或 text/event-stream（取最后一个 data: JSON）。
 *
 * 用途：聊天时 tools/list 拉工具 → 转 OpenAI tools 注入请求 → 模型回 tool_call → tools/call 执行回传。
 * 安全：仅用于用户显式配置的受信端点；URL 为 http 时依赖 manifest 的 usesCleartextTraffic。
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

    /** 拉取工具列表。url 形如 http://192.168.1.5:8787/mcp */
    suspend fun listTools(url: String, token: String? = null): List<McpTool> =
        withContext(Dispatchers.IO) {
            val resp = rpc(
                url = url,
                token = token,
                body = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", rpcId.getAndIncrement())
                    .put("method", "tools/list")
                    .put("params", JSONObject())
            )
            val tools = resp.optJSONArray("tools") ?: JSONArray()
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
            val resp = rpc(
                url = url,
                token = token,
                body = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", rpcId.getAndIncrement())
                    .put("method", "tools/call")
                    .put("params", JSONObject().put("name", name).put("arguments", arguments))
            )
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

    private fun rpc(url: String, token: String?, body: JSONObject): JSONObject {
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
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code：${err.take(300)}")
            }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
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
