package com.localllm.server.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 把剪贴板里「复制的一个文件」解析成能写进输入框的路径。
 *
 * Android 的文本框不接受粘贴文件（剪贴板里是 content:// URI，不是文本），
 * 但用户复制文件的真实意图通常是「引用它」——所以把 URI 换成路径，
 * 插到输入框里让模型自己去 file_read/编辑。
 *
 * 解析不出可用路径时返回 null（例如来自相册这类虚拟文档，没有真实文件路径）。
 */
object FileRef {

    /** @return (显示名, 可插入的路径)；路径优先用工作目录内的相对路径 */
    fun resolve(context: Context, uri: Uri): Pair<String, String>? {
        if (uri.scheme == "file") {
            val p = uri.path ?: return null
            return File(p).name to p
        }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:")           // LlamaWork/demo/a.csv
        if (rel.isBlank()) return null
        val name = rel.substringAfterLast('/')
        val root = workDirRelative(context)
        val path = if (root != null && rel.startsWith("$root/")) {
            rel.removePrefix("$root/")                     // 工作目录内 → 相对路径
        } else {
            "/storage/emulated/0/$rel"                     // 工作目录外 → 绝对路径
        }
        return name to path
    }

    /** 工作目录在共享存储里的相对段（如 "LlamaWork"） */
    private fun workDirRelative(context: Context): String? {
        val tree = AppSettings.workDirTreeUri
        if (tree.isBlank()) return null
        return runCatching {
            DocumentsContract.getTreeDocumentId(Uri.parse(tree)).removePrefix("primary:")
        }.getOrNull()
    }
}
