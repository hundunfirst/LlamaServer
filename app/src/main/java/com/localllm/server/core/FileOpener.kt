package com.localllm.server.core

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap

/**
 * 把会话里生成的文件交给系统应用打开，或在文件管理器里定位到它。
 *
 * 这些文件都落在用户授权的 SAF 目录里，所以交给对方的必须是 documents provider 的
 * content:// URI，并带 FLAG_GRANT_READ_URI_PERMISSION —— 否则对方应用拿不到读权限，
 * 只会弹「无法打开」。
 *
 * 两个方法都用返回值表示成败，由界面决定回退（内嵌预览 / 提示路径），
 * 不在这里弹 Toast：这一层不该管提示长什么样。
 */
object FileOpener {

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** 用系统应用打开文件；没有应用能处理（或文件不在授权目录里）时返回 false */
    fun open(context: Context, path: String): Boolean {
        val doc = FileTools.documentFor(context, path)?.takeIf { it.isFile } ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(doc.uri, mimeOf(doc.name ?: path))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
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
