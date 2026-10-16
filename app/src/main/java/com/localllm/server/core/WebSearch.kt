package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 内置网页搜索（免密钥，Bing）。
 * 做成 OpenAI function 工具 web_search 注入请求，模型在需要时效性信息时自行调用，
 * 结果以文本回传进对话轮次。国内网络实测 www.bing.com 直连 0.3s 可用（DDG 被墙，已排除）。
 * 解析思路参考业界通行的 b_algo 结果块抽取；实现为本项目自研（不复用 AGPL 代码）。
 */
object WebSearch {

    data class Result(val title: String, val url: String, val snippet: String)

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    /** 敏感信息脱敏：身份证号/手机号/银行卡号等长数字串在出网前替换（防护隐私外泄） */
    internal fun sanitizeQuery(query: String): Pair<String, Boolean> {
        var masked = false
        var q = query
        // 18位/15位身份证号（含 X 结尾）
        q = Regex("\\b\\d{17}[\\dXx]\\b|\\b\\d{15}\\b").replace(q) { masked = true; "[身份证已脱敏]" }
        // 11位手机号
        q = Regex("\\b1[3-9]\\d{9}\\b").replace(q) { masked = true; "[手机号已脱敏]" }
        // 16~19位银行卡号
        q = Regex("\\b\\d{16,19}\\b").replace(q) { masked = true; "[卡号已脱敏]" }
        return q to masked
    }

    /** 搜索并返回前 max 条结果；失败抛 IllegalStateException（模型收到失败说明后自行降级回答）。
     *  出网前自动脱敏（身份证/手机号/银行卡号）。 */
    suspend fun search(query: String, max: Int = 5): List<Result> = withContext(Dispatchers.IO) {
        val q = sanitizeQuery(query).first.trim()
        if (q.isEmpty()) throw IllegalStateException("缺少搜索关键词")
        val urlStr = "https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8") +
                "&count=$max&setlang=zh-hans"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        val raw = try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("搜索服务返回 HTTP $code")
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
        parse(raw, max)
    }

    /** 从 Bing HTML 抽取 b_algo 结果块（标题/链接/摘要）。内部可见供测试。 */
    internal fun parse(raw: String, max: Int): List<Result> {
        val blocks = raw.split("""<li class="b_algo"""").drop(1)
        val out = ArrayList<Result>(max)
        for (b in blocks) {
            if (out.size >= max) break
            val m = Regex("""<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(b) ?: continue
            val link = m.groupValues[1]
            if (!link.startsWith("http")) continue // 过滤站内跳转
            val title = stripHtml(m.groupValues[2])
            if (title.isBlank()) continue
            val snipM = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(b)
            val snippet = snipM?.let { stripHtml(it.groupValues[1]).take(220) } ?: ""
            out.add(Result(title, link, snippet))
        }
        return out
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            // 数字实体（如 &#183; &#8211;）与常见命名实体
            .replace(Regex("&#(\\d+);")) { m ->
                runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
            }
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** OpenAI tools 数组里追加 web_search 的 JSON */
    fun toolJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", "web_search")
                .put(
                    "description",
                    "联网搜索网页，获取最新/实时信息（新闻、天气、股价、时效性事实）。" +
                            "当用户问题涉及最新动态或需要核实时效性信息时调用；返回标题、链接和摘要列表。"
                )
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject().put(
                                "query", JSONObject()
                                    .put("type", "string")
                                    .put("description", "精炼的搜索关键词")
                            )
                        )
                        .put("required", JSONArray().put("query"))
                )
        )

    /** 搜索结果 → 回传给模型的工具结果文本 */
    fun formatResults(results: List<Result>): String {
        if (results.isEmpty()) return "（没有找到相关网页结果，请基于已有知识谨慎回答）"
        val sb = StringBuilder("网页搜索结果：\n")
        results.forEachIndexed { i, r ->
            sb.append("${i + 1}. ${r.title}\n   链接: ${r.url}\n")
            if (r.snippet.isNotBlank()) sb.append("   摘要: ${r.snippet}\n")
        }
        return sb.toString().trimEnd()
    }
}
