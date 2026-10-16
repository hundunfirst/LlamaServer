package com.localllm.server.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 通用 OpenAI 兼容端点工具（不绑定任何厂商）：
 * - 地址容错：只给域名（自动补 /v1）、带版本路径（/v1、/paas/v4、/compatible-mode/v1 …）、
 *   甚至粘贴了完整的 /chat/completions 或 /models 地址，都能拼出正确端点
 * - 拉取模型：GET {root}/models，Bearer 鉴权（附带 api-key 头兼容 Azure 风格）
 */
object OpenAiCompat {

    /** 归一化出 API 根地址（不含端点段） */
    private fun apiRoot(raw: String): String {
        var u = raw.trim().removeSuffix("/")
        for (suffix in listOf("/chat/completions", "/models")) {
            if (u.endsWith(suffix)) {
                u = u.removeSuffix(suffix).removeSuffix("/")
                break
            }
        }
        val path = runCatching { URI(u).path ?: "" }.getOrDefault("")
        // 只有域名没路径 → 补 /v1；已带路径（各家自定义前缀）→ 原样作为根
        return if (path.isEmpty() || path == "/") "$u/v1" else u
    }

    /** 聊天端点：POST */
    fun chatUrl(raw: String): String = "${apiRoot(raw)}/chat/completions"

    /** 模型列表端点：GET */
    fun modelsUrl(raw: String): String = "${apiRoot(raw)}/models"

    /**
     * 阻塞式拉取模型列表（调用方切 IO 线程）。
     * 返回 OpenAI 格式响应里的 data[].id 列表；失败抛异常，message 用户可读。
     */
    fun fetchModels(rawUrl: String, apiKey: String): List<String> {
        if (rawUrl.isBlank()) throw IllegalStateException("请先填写接口地址")
        val conn = URL(modelsUrl(rawUrl)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            val key = apiKey.trim()
            if (key.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("api-key", key)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty().take(300)
                throw IllegalStateException(if (err.isBlank()) "HTTP $code" else "HTTP $code：$err")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val data = org.json.JSONObject(body).optJSONArray("data") ?: org.json.JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }.distinct()
        } finally {
            conn.disconnect()
        }
    }
}
