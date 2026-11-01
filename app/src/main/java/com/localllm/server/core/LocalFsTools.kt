package com.localllm.server.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 走真实文件系统的文件工具（绝对路径）。
 *
 * 为什么要单独一份：原来的 `FileTools` 全部建立在 SAF 文档树之上（`DocumentFile.fromTreeUri`），
 * 路径只能是授权目录里的相对路径——**就算开了全盘访问，也出不了那一个目录**。
 * 拿到 legacy storage 权限后，`/sdcard/...` 在进程内就是普通目录，直接 `java.io.File` 读写即可。
 *
 * 安全边界：只放行共享存储（`/storage/emulated/0`，`/sdcard` 指向它）与 App 自己的目录。
 * 系统目录（`/system`、`/data/data/其他包`）一律拒绝——那是 run_bash 的活，不该由"写文件"顺手做。
 */
object LocalFsTools {

    /** 全盘访问是否可用：legacy storage + 已授予写外部存储 */
    fun available(context: Context): Boolean {
        val legacy = runCatching { Environment.isExternalStorageLegacy() }.getOrDefault(false)
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return legacy && granted
    }

    private const val MAX_LIST_ENTRIES = 200
    private const val MAX_READ_BYTES = 4 * 1024 * 1024
    private const val MAX_READ_CHARS = 200_000
    private const val DEFAULT_READ_CHARS = 20_000
    private const val MAX_WRITE_CHARS = 400_000

    /** 绝对路径解析 + 边界检查；拒绝时会带上「怎么开权限」的说明 */
    private fun resolve(context: Context, path: String): File {
        if (!available(context)) {
            throw IllegalStateException(
                "没有全盘文件访问权限：请到 设置 → 文件工具 → 开启全盘访问，然后再试。" +
                        "（也可以改用相对路径，走已授权的工作目录）"
            )
        }
        val f = File(path).canonicalFile
        val shared = File(Environment.getExternalStorageDirectory().absolutePath).canonicalFile
        val appDir = context.filesDir.canonicalFile.parentFile
        val inShared = f.path == shared.path || f.path.startsWith(shared.path + "/")
        val inApp = appDir != null && (f.path == appDir.path || f.path.startsWith(appDir.path + "/"))
        if (!inShared && !inApp) {
            throw IllegalStateException(
                "只能访问共享存储（${shared.path}）和应用自己的目录：$path。" +
                        "系统目录请用 run_bash。"
            )
        }
        return f
    }

    fun execute(context: Context, name: String, args: org.json.JSONObject): String = when (name) {
        FileTools.TOOL_LIST -> list(context, args.optString("path"))
        FileTools.TOOL_READ -> read(
            context, args.optString("path"),
            args.optInt("offset", 0), args.optInt("limit", 0)
        )

        FileTools.TOOL_WRITE -> write(
            context, args.optString("path"), args.optString("content")
        )

        FileTools.TOOL_PATCH -> patch(
            context, args.optString("path"),
            args.optString("old"), args.optString("new")
        )

        FileTools.TOOL_GREP -> grep(context, args.optString("pattern"), args.optString("path"))
        else -> throw IllegalStateException("未知文件工具：$name")
    }

    private fun list(context: Context, pathIn: String): String {
        val path = pathIn.ifBlank { Environment.getExternalStorageDirectory().absolutePath }
        val dir = resolve(context, path)
        if (!dir.exists()) throw IllegalStateException("目录不存在：$path")
        if (!dir.isDirectory) throw IllegalStateException("不是目录：$path")
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ).orEmpty()
        // 同上：Android 11+ 删掉的文件还在同目录，只是名字带 .trashed- 前缀
        val live = children.filterNot { it.name.startsWith(".trashed-") }
        val trashed = children.size - live.size
        val tail = if (trashed > 0) "（另有 $trashed 个文件在回收站，已隐藏）" else ""
        if (live.isEmpty()) return "（目录为空）" + tail
        val sb = StringBuilder("目录内容（共 ${live.size} 项，本次列出 ${minOf(live.size, MAX_LIST_ENTRIES)} 项）：\n")
        live.take(MAX_LIST_ENTRIES).forEachIndexed { idx, f ->
            sb.append("${idx + 1}. ").append(if (f.isDirectory) "[目录] " else "[文件] ").append(f.name)
            if (f.isFile) sb.append("  ").append(f.length()).append(" B")
            sb.append('\n')
        }
        return sb.toString().trimEnd() + if (tail.isEmpty()) "" else "\n" + tail
    }

    private fun read(context: Context, path: String, offsetIn: Int, limitIn: Int): String {
        if (path.isBlank()) throw IllegalStateException("缺少 path 参数")
        val f = resolve(context, path)
        if (!f.exists()) throw IllegalStateException("文件不存在：$path")
        if (!f.isFile) throw IllegalStateException("不是文件：$path")
        if (f.length() > MAX_READ_BYTES) {
            throw IllegalStateException("文件过大（${f.length()} B），超过 $MAX_READ_BYTES B 上限")
        }
        val bytes = f.readBytes()
        val mime = mimeOf(f.name)
        val docKind = DocExtract.isDocument(f.name, mime)
        val doc = if (docKind) DocExtract.extract(bytes, f.name, mime) else null
        val full = doc?.text ?: String(bytes, Charsets.UTF_8)
        if (full.isEmpty()) return doc?.note ?: "（文件为空或没有可读内容）"

        val offset = offsetIn.coerceAtLeast(0)
        if (offset >= full.length) {
            return "已到文件末尾：全文共 ${full.length} 字，offset=$offset 超出范围，不用再读。"
        }
        val limit = if (limitIn > 0) minOf(limitIn, MAX_READ_CHARS) else DEFAULT_READ_CHARS
        val end = minOf(offset + limit, full.length)
        val sb = StringBuilder()
        sb.append("【").append(if (doc != null) doc.kind.uppercase() + " 已抽取文本" else "纯文本")
            .append(" · 共 ").append(full.length).append(" 字 · 本次 ")
            .append(offset).append('–').append(end).append(" 字】\n")
        doc?.note?.let { sb.append('[').append(it).append("]\n") }
        sb.append(full, offset, end)
        if (end < full.length) {
            sb.append("\n\n[还有 ").append(full.length - end)
                .append(" 字未读：继续读请传 offset=").append(end).append(']')
        }
        return sb.toString()
    }

    private fun write(context: Context, path: String, raw: String): String {
        if (path.isBlank()) throw IllegalStateException("缺少 path 参数")
        val content = stripToolMarkup(raw)
        if (content.length > MAX_WRITE_CHARS) {
            throw IllegalStateException("内容过大（${content.length} 字），超过 $MAX_WRITE_CHARS 上限，请拆分写入")
        }
        val f = resolve(context, path)
        if (f.isDirectory) throw IllegalStateException("这是目录，不能写入：$path")
        val existed = f.exists()
        f.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IllegalStateException("无法创建目录：${it.path}") }
        f.writeText(content, Charsets.UTF_8)
        return "已写入 ${if (existed) "（覆盖）" else ""}$path ，${content.length} 字"
    }

    private fun patch(context: Context, path: String, old: String, new: String): String {
        if (path.isBlank()) throw IllegalStateException("缺少 path 参数")
        if (old.isEmpty()) throw IllegalStateException("old 不能为空：请给出要替换的原片段")
        val f = resolve(context, path)
        if (!f.isFile) throw IllegalStateException("文件不存在：$path")
        val text = f.readText(Charsets.UTF_8)
        val first = text.indexOf(old)
        if (first < 0) {
            throw IllegalStateException(
                "没找到要替换的内容（缩进/空格必须完全一致）。先 file_read 看实际内容再改。"
            )
        }
        if (text.indexOf(old, first + old.length) >= 0) {
            throw IllegalStateException(
                "这段内容在文件里出现多次，无法确定改哪一处：请把 old 扩长（多带几行上下文）再试。"
            )
        }
        val updated = text.replaceFirst(old, new)
        f.writeText(updated, Charsets.UTF_8)
        return "已修改 $path（替换 ${old.length} → ${new.length} 字，全文 ${text.length} → ${updated.length} 字）"
    }

    private fun grep(context: Context, patternIn: String, pathIn: String): String {
        val pattern = patternIn.trim()
        if (pattern.isEmpty()) throw IllegalStateException("缺少 pattern 参数")
        val rootPath = pathIn.ifBlank { Environment.getExternalStorageDirectory().absolutePath }
        val root = resolve(context, rootPath)
        if (!root.exists()) throw IllegalStateException("路径不存在：$rootPath")
        val needle = pattern.lowercase()
        val hits = ArrayList<String>()
        var scanned = 0

        fun walk(f: File, depth: Int) {
            if (hits.size >= 60 || scanned >= 400 || depth > 4) return
            if (f.isDirectory) {
                // 跳过明显的重目录，别把整个存储扫一遍
                if (f.name.startsWith(".") || f.name.equals("Android", true)) return
                f.listFiles()?.forEach { walk(it, depth + 1) }
                return
            }
            if (!f.isFile || f.length() > 2_000_000) return
            if (!looksTextual(f.name)) return
            scanned++
            runCatching {
                f.forEachLine { line ->
                    if (hits.size < 60 && line.lowercase().contains(needle)) {
                        hits += "${f.absolutePath}: ${line.trim().take(160)}"
                    }
                }
            }
        }
        walk(root, 0)

        if (hits.isEmpty()) return "没有匹配「$pattern」的内容（扫了 $scanned 个文本文件）"
        val sb = StringBuilder("匹配「$pattern」（扫了 $scanned 个文本文件，最多列 60 条）：\n")
        hits.forEach { sb.append(it).append('\n') }
        return sb.toString().trimEnd()
    }

    private val TEXT_EXT = setOf(
        "txt", "md", "json", "csv", "js", "ts", "css", "html", "htm", "xml", "yml", "yaml",
        "py", "kt", "java", "c", "cpp", "h", "sh", "log", "ini", "conf", "properties", "gradle"
    )

    private fun looksTextual(name: String): Boolean =
        TEXT_EXT.contains(name.substringAfterLast('.', "").lowercase())

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private val TOOL_MARKERS = listOf("</tool_call>", "<tool_call>", "<function=", "<parameter=")

    private fun stripToolMarkup(content: String): String {
        val cut = TOOL_MARKERS.map { content.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return content
        return content.substring(0, cut).trimEnd()
    }
}
