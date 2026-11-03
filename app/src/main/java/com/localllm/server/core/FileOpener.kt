package com.localllm.server.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把会话里产出的文件交给系统应用打开 / 分享 / 定位，就像文件管理器里点一下那样。
 *
 * 三条路径，按可靠性排序：
 *  1. 授权目录（SAF）内的文件 → 用 documents provider 的 content:// URI，带读权限；
 *  2. 真实文件系统里的绝对路径（run_bash 里 python 写出来的）→ 用我们自己的 FileProvider
 *     转成 content:// 。**不能直接交出 file 协议的地址**：targetSdk≥24 会抛 FileUriExposedException。
 *  3. 都拿不到 → 返回 false，由界面回退到内嵌预览并提示路径。
 *
 * APK 特别处理：给 `application/vnd.android.package-archive`，系统就会走"安装"而不是"打开"；
 * 前提是 manifest 里声明了 REQUEST_INSTALL_PACKAGES（首次会跳一次"允许安装未知应用"）。
 */
object FileOpener {

    /** 常见类型写死一份：MimeTypeMap 在部分机型上对 apk/xlsx 返回 null，导致「无法打开」 */
    private val MIME = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls" to "application/vnd.ms-excel",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pdf" to "application/pdf",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "md" to "text/markdown",
        "txt" to "text/plain",
        "log" to "text/plain",
        "json" to "application/json",
        "html" to "text/html",
        "htm" to "text/html",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "mp4" to "video/mp4",
        "mp3" to "audio/mpeg",
        "zip" to "application/zip",
        "gguf" to "application/octet-stream",
    )

    /** 纯函数，便于单测（MimeTypeMap 依赖 Android 运行时） */
    internal fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MIME[ext]?.let { return it }
        val fromSystem = runCatching {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull()
        return fromSystem ?: "application/octet-stream"
    }

    fun mimeOf(name: String): String = mimeFor(name)

    /**
     * 解析出能交给别的 App 的 URI：
     * 先按授权目录里的相对路径找（SAF），找不到再看它是不是一个真实存在的绝对路径（FileProvider）。
     */
    internal fun uriFor(context: Context, path: String, share: Boolean = false): Pair<Uri, String>? {
        val doc = FileTools.documentFor(context, path)?.takeIf { it.isFile }
        if (doc != null) {
            val name = doc.name ?: path
            return doc.uri to mimeFor(name)
        }
        val real = realFile(context, path) ?: return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", real)
        }.getOrNull() ?: return null
        return uri to mimeFor(real.name)
    }

    /** 绝对路径直接用；相对路径按授权目录的真实路径拼（run_bash 里 python 写出的文件两处都能落到） */
    private fun realFile(context: Context, path: String): File? {
        val f = if (path.startsWith("/")) File(path)
        else LinuxRuntime.workdirAbsPath(context)?.let { File("$it/$path") } ?: return null
        return f.takeIf { it.isFile }
    }

    /** 用系统应用打开（APK 会直接进安装流程）；没有能处理的 App 时返回 false */
    fun open(context: Context, path: String): Boolean {
        val (uri, mime) = uriFor(context, path) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // 安装器读的是 ClipData 里的授权，光有 FLAG 在部分机型上不够
        intent.clipData = ClipData.newRawUri(null, uri)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 分享给别的 App（微信/邮件等） */
    fun share(context: Context, path: String): Boolean {
        val (uri, mime) = uriFor(context, path) ?: return false
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(null, uri)
        val chooser = Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    /**
     * 在文件管理器里定位到文件所在目录。
     * 用的是 documents provider 的目录 MIME，AOSP 文件管理支持；部分厂商的文件管理器
     * 不认这个 MIME，此时返回 false，由界面提示「路径 + 无法打开」。
     */
    fun revealInFolder(context: Context, path: String): Boolean {
        val doc = FileTools.documentFor(context, path) ?: return false
        val dir = doc.parentFile ?: FileTools.rootDir(context) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(dir.uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
