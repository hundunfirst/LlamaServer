package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 魔搭（ModelScope）客户端：只读检索 + 文件直链。
 *
 * 和 [HfMirrorClient] 同构，供 [ModelCatalog] 统一切换。
 *
 * 接口现状（2026-11 实测）：
 * - **关键词搜索的公开接口已不可用**（`/api/v1/models?Name=`、`/api/v1/dolphin/models` 全部 404），
 *   所以这里只能按仓库名直连：用户给 `owner/name` 或模型页链接；
 * - 仓库详情 `/api/v1/models/{owner}/{name}` 可读；
 * - 文件列表 `/api/v1/models/{owner}/{name}/repo/files?Revision=master&Recursive=true` 可读，
 *   返回 `{Data:{Files:[{Path,Size,Type:"blob"}]}}`；
 * - 文件直链 `/{owner}/{name}/resolve/master/{path}` 可用，并且**支持 Range 续传**。
 */
object ModelScopeClient {

    const val BASE = "https://modelscope.cn"

    data class FileInfo(val path: String, val sizeBytes: Long)

    /** 把用户输入（仓库名 / 模型页链接）规整成 `owner/name`；认不出返回 null */
    fun normalizeRepo(input: String): String? {
        var t = input.trim()
        if (t.isEmpty()) return null
        // 去掉常见前后缀：https://modelscope.cn/models/xxx/yyy
        listOf("https://www.modelscope.cn/", "https://modelscope.cn/", "http://")
            .forEach { p -> if (t.startsWith(p)) t = t.removePrefix(p) }
        if (t.startsWith("models/")) t = t.removePrefix("models/")
        t = t.substringBefore('?').substringBefore('#').trimEnd('/')
        val parts = t.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts[0] + "/" + parts[1]
    }

    /** 仓库里 master 分支的 .gguf（含 mmproj）；仓库不存在或读不到返回 null */
    suspend fun listGgufFiles(repo: String): List<FileInfo>? = withContext(Dispatchers.IO) {
        val json = runCatching {
            val conn = open("$BASE/api/v1/models/$repo/repo/files?Revision=master&Recursive=true")
            try {
                if (conn.responseCode != 200) return@runCatching null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return@withContext null

        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@withContext null
        if (!root.optBoolean("Success", true)) return@withContext null
        val files = root.optJSONObject("Data")?.optJSONArray("Files") ?: return@withContext null
        (0 until files.length()).mapNotNull { i ->
            val o = files.optJSONObject(i) ?: return@mapNotNull null
            // Type 是 blob（文件），tree 是目录
            if (o.optString("Type") != "blob") return@mapNotNull null
            val path = o.optString("Path")
            if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            FileInfo(path = path, sizeBytes = o.optLong("Size", -1))
        }.sortedBy { it.path }
    }

    /** 文件直链（支持 Range） */
    fun resolveUrl(repo: String, path: String): String {
        val enc = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "$BASE/models/$repo/resolve/master/$enc"
    }

    /** 仓库 README（优先中文），取不到返回空串 */
    suspend fun fetchReadme(repo: String, maxChars: Int = 1800): String = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "README_zh.md", "README.zh.md", "README-CN.md", "README.zh-cn.md", "README.md"
        )
        for (f in candidates) {
            val text = runCatching {
                val conn = open(resolveUrl(repo, f))
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) return@withContext text.take(maxChars)
        }
        ""
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) localllm")
        }
}
