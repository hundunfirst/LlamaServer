package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * hf-mirror.com 客户端（HuggingFace 国内镜像，API 路径与官方同构）。
 * 仅做只读检索与文件下载；网络调用都在 IO 线程。
 */
object HfMirrorClient {
    const val BASE = "https://hf-mirror.com"

    data class RepoInfo(
        val id: String,
        val downloads: Int,
        val likes: Int,
        val pipelineTag: String = "",
        val tags: List<String> = emptyList()
    )

    data class FileInfo(val path: String, val sizeBytes: Long)

    /** 由 HF pipeline_tag / tags 推导能力标签：文本/视觉/音频/视频（可多选） */
    fun capabilityLabels(repo: RepoInfo): List<String> {
        val all = (listOf(repo.pipelineTag) + repo.tags).map { it.lowercase() }
        val out = mutableListOf<String>()

        // 文本：对话/生成类，或本身就是多模态（VLM 也输出文本），无法判断时兜底
        val looksText = repo.pipelineTag.isBlank() ||
                repo.pipelineTag.contains("text") ||
                repo.pipelineTag == "conversational" ||
                all.any { it.contains("text-generation") || it.contains("conversational") }
        val vision = all.any {
            it.contains("vision") || it.contains("image") || it == "image-text-to-text" ||
                    it.contains("visual") || it.contains("vqa")
        }
        val audio = all.any {
            it.contains("audio") || it.contains("asr") || it.contains("tts") ||
                    it.contains("speech") || it.contains("voice")
        }
        val video = all.any { it.contains("video") }

        if (looksText || (!vision && !audio && !video)) out += "文本"
        if (vision) out += "视觉"
        if (audio) out += "音频"
        if (video) out += "视频"
        return out
    }

    suspend fun searchRepos(query: String, limit: Int = 20): List<RepoInfo> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val arr = getJsonArray("$BASE/api/models?search=$q&limit=$limit")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val tagArr = o.optJSONArray("tags")
                val tagList = (0 until (tagArr?.length() ?: 0)).mapNotNull { j ->
                    tagArr?.optString(j)?.takeIf { it.isNotBlank() }
                }
                RepoInfo(
                    id = o.optString("id"),
                    downloads = o.optInt("downloads"),
                    likes = o.optInt("likes"),
                    pipelineTag = o.optString("pipeline_tag"),
                    tags = tagList
                )
            }.filter { it.id.isNotBlank() }
                // 纯按下载量排序，保证展示稳定
                .sortedByDescending { it.downloads }
        }

    /** 仓库内 main 分支的 .gguf 文件（含 mmproj，类型由用户在导入时选择） */
    suspend fun listGgufFiles(repo: String): List<FileInfo> =
        withContext(Dispatchers.IO) {
            val arr = getJsonArray("$BASE/api/models/$repo/tree/main")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("type") != "file") return@mapNotNull null
                val path = o.optString("path")
                if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
                FileInfo(path = path, sizeBytes = o.optLong("size", -1))
            }.sortedBy { it.path }
        }

    fun resolveUrl(repo: String, path: String): String {
        val encPath = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "$BASE/$repo/resolve/main/$encPath"
    }

    /** 仓库 README（模型介绍）：优先中文版文件，均无则用默认 README */
    suspend fun fetchReadme(repo: String, maxChars: Int = 1800): String =
        withContext(Dispatchers.IO) {
            val candidates = listOf(
                "README_zh.md", "README.zh-cn.md", "README_CN.md", "README.zh.md", "README.md"
            )
            for (file in candidates) {
                val conn = open("$BASE/$repo/raw/main/$file")
                try {
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        if (text.isNotBlank()) return@withContext text.take(maxChars)
                    }
                } catch (_: Exception) {
                } finally {
                    conn.disconnect()
                }
            }
            ""
        }

    /**
     * 下载（重构版，对齐成熟下载器模式）：
     * - 全程只有一个 <name>.part 临时文件；进度以磁盘文件大小为准
     * - 支持 Range 续传；若服务器拒绝续传(200)，老断点先备份为 .part.old 再重下，成功后清理备份
     * - 完整性校验：收满服务器声明的大小才改名；中断保留断点
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val part = File(dest.parentFile, dest.name + ".part")
        val backup = File(dest.parentFile, dest.name + ".part.old")
        var completed = false
        var cancelled = false
        try {
            val startBytes = if (part.exists()) part.length() else 0L
            val conn = open(url, rangeStart = startBytes)
            try {
                val code = conn.responseCode
                if (code !in 200..299 && code != 206) throw IOException("HTTP $code")
                val resumed = (code == 206 && startBytes > 0)
                if (!resumed && startBytes > 0) {
                    // 服务器不支持续传：旧断点备份保护，绝不与新写入混在一起
                    backup.delete()
                    part.renameTo(backup)
                }
                val baseLen = if (resumed) startBytes else 0L
                val contentLen = conn.contentLengthLong
                val total = if (contentLen >= 0) baseLen + contentLen else -1L
                onProgress(baseLen, total)
                var read = baseLen
                conn.inputStream.use { input ->
                    (if (resumed) java.io.FileOutputStream(part, true) else part.outputStream())
                        .use { output ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                onProgress(read, total)
                            }
                        }
                }
                if (total >= 0 && read < total) {
                    throw IOException("连接中断：已收 ${read} / 应为 ${total}，断点已保留，重新下载可续传")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("无法完成文件写入: $dest")
                backup.delete()
                File(dest.parentFile, dest.name + ".part.meta").delete()
                completed = true
                read
            } finally {
                conn.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw e
        } finally {
            // 失败/取消：保留 part 与 old 备份，不删
        }
    }

    /** 打开连接并处理重定向；rangeStart>0 时在连接前设置 Range（连接后设置会抛异常） */
    private fun open(url: String, rangeStart: Long = -1): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LlamaServerApp/0.1")
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = conn.responseCode
            // HttpURLConnection 对部分 CDN 302 处理不全，手动兜底
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null || ++redirects > 5) throw IOException("重定向异常 ($code)")
                current = if (loc.startsWith("http")) loc else "https://hf-mirror.com$loc"
                continue
            }
            return conn
        }
    }

    private fun getJsonArray(url: String): JSONArray {
        val conn = open(url)
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONArray(text)
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("unused")
    private fun JSONObject.getLongOr(key: String, def: Long): Long =
        if (has(key) && !isNull(key)) optLong(key) else def
}
