package com.localllm.server.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文件工具（SAF 授权目录内）：list / read / write / grep。
 * - 只能访问用户在设置里授权的目录（takePersistableUriPermission），不能越界
 * - 写入会自动创建子目录；单文件读写有大小上限，避免把上下文/内存打爆
 */
object FileTools {

    const val TOOL_LIST = "file_list"
    const val TOOL_READ = "file_read"
    const val TOOL_WRITE = "file_write"
    const val TOOL_GREP = "file_grep"

    val NAMES = setOf(TOOL_LIST, TOOL_READ, TOOL_WRITE, TOOL_GREP)

    private const val MAX_READ_BYTES = 200 * 1024
    private const val MAX_WRITE_CHARS = 400_000
    private const val MAX_LIST_ENTRIES = 200
    private const val MAX_GREP_FILES = 60
    private const val MAX_GREP_HITS = 40

    // ---------------- 工具定义 ----------------
    fun toolsJson(): JSONArray {
        fun fn(
            name: String,
            desc: String,
            props: JSONObject,
            required: List<String>
        ): JSONObject {
            val req = JSONArray()
            required.forEach { req.put(it) }
            return JSONObject()
                .put("type", "function")
                .put(
                    "function", JSONObject()
                        .put("name", name)
                        .put("description", desc)
                        .put(
                            "parameters", JSONObject()
                                .put("type", "object")
                                .put("properties", props)
                                .put("required", req)
                        )
                )
        }
        val pathProp = JSONObject()
            .put("type", "string")
            .put("description", "相对授权目录的路径，如 demo/index.html；省略表示根目录")

        return JSONArray().apply {
            put(
                fn(
                    TOOL_LIST, "列出授权目录下的文件（相对路径）",
                    JSONObject().put("path", pathProp), emptyList()
                )
            )
            put(
                fn(
                    TOOL_READ, "读取授权目录内的文本文件内容",
                    JSONObject().put("path", pathProp), listOf("path")
                )
            )
            put(
                fn(
                    TOOL_WRITE, "在授权目录内创建或覆盖文本文件（自动建子目录）",
                    JSONObject()
                        .put("path", pathProp)
                        .put("content", JSONObject().put("type", "string").put("description", "文件内容")),
                    listOf("path", "content")
                )
            )
            put(
                fn(
                    TOOL_GREP, "在授权目录内按关键字搜索文本文件",
                    JSONObject().put("pattern", JSONObject().put("type", "string")
                        .put("description", "要搜索的关键字")),
                    listOf("pattern")
                )
            )
        }
    }

    // ---------------- 执行 ----------------
    /** 返回给模型的结果文本；失败抛 IllegalStateException */
    fun execute(context: Context, name: String, args: JSONObject): String {
        val root = rootDir(context) ?: throw IllegalStateException(
            "尚未授权工作目录：请在 设置 → 文件工具 里选择一个文件夹"
        )
        return when (name) {
            TOOL_LIST -> listFiles(root, args.optString("path"))
            TOOL_READ -> readFile(context, root, args.optString("path"))
            TOOL_WRITE -> writeFile(context, root, args.optString("path"), args.optString("content"))
            TOOL_GREP -> grepFiles(context, root, args.optString("pattern"))
            else -> throw IllegalStateException("未知文件工具：$name")
        }
    }

    fun rootDir(context: Context): DocumentFile? {
        val uriStr = AppSettings.workDirTreeUri
        if (uriStr.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull()
    }

    /** 展示名（设置页用） */
    fun rootName(context: Context): String? = rootDir(context)?.name

    private fun segments(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }

    private fun checkPath(path: String) {
        segments(path).forEach {
            if (it == "..") throw IllegalStateException("路径不能包含 ..")
        }
    }

    private fun find(root: DocumentFile, path: String): DocumentFile? {
        checkPath(path)
        var cur: DocumentFile = root
        for (seg in segments(path)) {
            cur = cur.findFile(seg) ?: return null
        }
        return cur
    }

    private fun ensureDir(root: DocumentFile, dirs: List<String>): DocumentFile {
        var cur = root
        for (d in dirs) {
            val existing = cur.findFile(d)
            cur = if (existing != null && existing.isDirectory) existing
            else cur.createDirectory(d) ?: throw IllegalStateException("无法创建目录：$d")
        }
        return cur
    }

    private fun listFiles(root: DocumentFile, path: String): String {
        val dir = if (path.isBlank() || path == "/" || path == ".") root
        else find(root, path) ?: throw IllegalStateException("目录不存在：$path")
        if (!dir.isDirectory) throw IllegalStateException("不是目录：$path")
        val files = dir.listFiles()
        if (files.isEmpty()) return "（目录为空）"
        val sb = StringBuilder("目录内容（最多 $MAX_LIST_ENTRIES 项）：\n")
        files.take(MAX_LIST_ENTRIES).forEach { f ->
            sb.append(if (f.isDirectory) "[目录] " else "[文件] ")
                .append(f.name ?: "?")
            if (f.isFile) sb.append("  ").append(f.length()).append(" B")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun readFile(context: Context, root: DocumentFile, path: String): String {
        if (path.isBlank()) throw IllegalStateException("缺少 path 参数")
        val f = find(root, path) ?: throw IllegalStateException("文件不存在：$path")
        if (!f.isFile) throw IllegalStateException("不是文件：$path")
        if (f.length() > MAX_READ_BYTES) {
            throw IllegalStateException("文件过大（${f.length()} B），超过 $MAX_READ_BYTES B 上限")
        }
        val text = context.contentResolver.openInputStream(f.uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("无法读取文件")
        return text
    }

    /** 弱模型常把自己的工具调用语法写进文件内容末尾，落盘前截掉 */
    private val TOOL_MARKERS = listOf("</tool_call>", "<tool_call>", "<function=", "<parameter=")

    private fun stripToolMarkup(content: String): String {
        val cut = TOOL_MARKERS.map { content.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return content
        return content.substring(0, cut).trimEnd()
    }

    private fun writeFile(context: Context, root: DocumentFile, path: String, raw: String): String {
        if (path.isBlank()) throw IllegalStateException("缺少 path 参数")
        val content = stripToolMarkup(raw)
        if (content.length != raw.length) {
            ServerProcessManager.appLog(
                "写入前剥掉尾部工具调用标记：${raw.length} → ${content.length} 字（$path）"
            )
        }
        if (content.length > MAX_WRITE_CHARS) {
            throw IllegalStateException("内容过大（${content.length} 字），超过 $MAX_WRITE_CHARS 上限，请拆分写入")
        }
        checkPath(path)
        val segs = segments(path)
        if (segs.isEmpty()) throw IllegalStateException("path 不能为空")
        val fileName = segs.last()
        val parent = ensureDir(root, segs.dropLast(1))
        val existing = parent.findFile(fileName)
        val target: DocumentFile = existing ?: run {
            val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
                "html", "htm" -> "text/html"
                "txt", "md" -> "text/plain"
                "json" -> "application/json"
                "csv" -> "text/csv"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "xml" -> "application/xml"
                else -> "application/octet-stream"
            }
            parent.createFile(mime, fileName)
                ?: throw IllegalStateException("无法创建文件：$fileName")
        }
        context.contentResolver.openOutputStream(target.uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入文件")
        return "已写入 ${if (existing != null) "（覆盖）" else ""}$path ，${content.length} 字"
    }

    private fun grepFiles(context: Context, root: DocumentFile, pattern: String): String {
        if (pattern.isBlank()) throw IllegalStateException("缺少 pattern 参数")
        val hits = StringBuilder()
        var scanned = 0
        var hitCount = 0

        fun walk(dir: DocumentFile, prefix: String) {
            if (scanned >= MAX_GREP_FILES || hitCount >= MAX_GREP_HITS) return
            dir.listFiles().forEach { f ->
                if (scanned >= MAX_GREP_FILES || hitCount >= MAX_GREP_HITS) return@forEach
                val name = f.name ?: return@forEach
                if (f.isDirectory) {
                    walk(f, "$prefix$name/")
                } else {
                    scanned++
                    val len = f.length()
                    if (len <= 0 || len > MAX_READ_BYTES) return@forEach
                    val text = runCatching {
                        context.contentResolver.openInputStream(f.uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull() ?: return@forEach
                    var idx = text.indexOf(pattern)
                    var shown = 0
                    while (idx >= 0 && shown < 3 && hitCount < MAX_GREP_HITS) {
                        val start = maxOf(0, idx - 40)
                        val end = minOf(text.length, idx + pattern.length + 40)
                        hits.append("${prefix}${name}: …")
                            .append(text.substring(start, end).replace('\n', ' '))
                            .append("…\n")
                        hitCount++
                        shown++
                        idx = text.indexOf(pattern, idx + pattern.length)
                    }
                }
            }
        }

        walk(root, "")
        return if (hits.isEmpty()) "未找到包含「$pattern」的文本（扫描 $scanned 个文件）"
        else "找到 $hitCount 处（扫描 $scanned 个文件）：\n$hits"
    }
}
