package com.localllm.server.core

import android.content.Context
import java.io.File

/**
 * 读会话里产物的原始字节（SAF 授权目录内的相对路径，或真实绝对路径）。
 *
 * 为什么单独出来：产物卡里的 `GenFile.content` 只有文本类才有内容，二进制（apk/pdf/xlsx）
 * 是空的。查看器要真正显示它们，必须回文件系统读——两条路径都要支持，否则
 * run_bash 生成的 APK 就"预览不了"。
 */
object FileBytes {

    /** @return null = 文件不存在 / 读失败 / 超过 [maxBytes] */
    fun read(context: Context, path: String, maxBytes: Long = 64L * 1024 * 1024): ByteArray? {
        runCatching {
            val doc = FileTools.documentFor(context, path)?.takeIf { it.isFile }
            if (doc != null) {
                if (doc.length() > maxBytes) return null
                return context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            }
            val f = realFile(context, path) ?: return null
            if (f.length() > maxBytes) return null
            return f.readBytes()
        }
        return null
    }

    /** 文件大小（字节）；拿不到返回 -1 */
    fun sizeOf(context: Context, path: String): Long = runCatching {
        val doc = FileTools.documentFor(context, path)?.takeIf { it.isFile }
        if (doc != null) return doc.length()
        realFile(context, path)?.length() ?: -1L
    }.getOrDefault(-1L)

    /** 真实路径的文件：绝对路径直接用；相对路径按工作目录拼（proot 里写出来的文件两处都能落到） */
    fun realFile(context: Context, path: String): File? {
        val f = if (path.startsWith("/")) File(path)
        else LinuxRuntime.workdirAbsPath(context)?.let { File("$it/$path") } ?: return null
        return f.takeIf { it.isFile }
    }

    /** 供查看器判断类型用：按扩展名归类 */
    fun kindOf(name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> Kind.Image
            "pdf" -> Kind.Pdf
            "xlsx", "xls", "docx", "doc", "pptx", "ppt" -> Kind.Office
            "md", "markdown", "txt", "log", "csv", "tsv", "json", "xml", "yml", "yaml",
            "html", "htm", "css", "py", "js", "ts", "kt", "java", "sh", "gradle", "ini", "toml" -> Kind.Text
            "apk", "aab", "zip", "so", "bin", "gguf", "mp4", "mp3", "wav", "jar" -> Kind.Binary
            else -> Kind.Unknown
        }
    }

    enum class Kind { Text, Image, Pdf, Office, Binary, Unknown }
}
