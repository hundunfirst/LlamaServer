package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用户消息里的链接自动读取：
 * - GitHub 仓库链接 → 走 API 拿 项目简介 + README 原文（页面是 JS 渲染，直取 HTML 抓不到正文）
 * - 其它网页 → HTTP 直取并清洗成纯文本
 * 用于把"用户贴的链接内容"注入上下文，使模型（尤其弱模型）无需会调用工具也能读懂链接。
 */
object UrlFetch {

    private const val MAX_CHARS = 6_000
    private const val TIMEOUT_MS = 8_000

    private val GH_REPO = Regex("""https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/?(?:[?#].*)?""")

    /** 抓取并返回可读文本；失败返回 null（不影响正常对话） */
    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val gh = GH_REPO.find(url)
            if (gh != null) fetchGitHub(gh.groupValues[1], gh.groupValues[2])
            else fetchGeneric(url)
        }.getOrNull()
    }

    // ---------------- GitHub ----------------
    private fun fetchGitHub(owner: String, repo: String): String? {
        val sb = StringBuilder()

        // 1) 仓库元信息
        runCatching {
            val body = httpGet("https://api.github.com/repos/$owner/$repo", "application/vnd.github+json")
            val o = JSONObject(body)
            sb.append("【GitHub 仓库】$owner/$repo\n")
            o.optString("description").takeIf { it.isNotBlank() }?.let { sb.append("简介：$it\n") }
            val lang = o.optString("language")
            if (lang.isNotBlank()) sb.append("主要语言：$lang\n")
            if (o.has("stargazers_count")) sb.append("Star：${o.optInt("stargazers_count")}\n")
            if (o.has("forks_count")) sb.append("Fork：${o.optInt("forks_count")}\n")
            o.optString("license").takeIf { it.isNotBlank() }?.let { sb.append("License：$it\n") }
            o.optString("updated_at").takeIf { it.isNotBlank() }?.let { sb.append("最近更新：$it\n") }
        }

        // 2) README 原文
        runCatching {
            val readme = httpGet(
                "https://api.github.com/repos/$owner/$repo/readme",
                "application/vnd.github.raw"
            )
            if (readme.isNotBlank()) {
                sb.append("\n【README】\n")
                sb.append(readme.take(MAX_CHARS))
            }
        }

        return sb.toString().trim().takeIf { it.isNotBlank() }?.take(MAX_CHARS + 800)
    }

    // ---------------- 普通网页 ----------------
    private fun fetchGeneric(url: String): String? {
        val html = httpGet(url, "text/html")
        val text = html
            .replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("(?s)<style.*?</style>"), " ")
            .replace(Regex("(?s)<noscript.*?</noscript>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("</p>|</div>|</li>|</h[1-6]>|<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return text.takeIf { it.length >= 80 }?.take(MAX_CHARS)
    }

    private fun httpGet(url: String, accept: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        )
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        return try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    /** 从用户文本中提取第一个 http(s) 链接 */
    fun firstUrl(text: String): String? =
        Regex("""https?://[^\s，。；、）)】"'<>]+""").find(text)?.value
}
