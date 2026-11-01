package com.localllm.server.core

import android.content.Context
import java.io.File

/**
 * 写入快照：每次 file_write / file_patch 之前，把目标文件**写入前的内容**存一份。
 *
 * 为什么要有它：模型一动手就是连改几个文件，改错时用户没法一个个回滚——
 * 没有撤销的自主执行等于赌博。这里只做最小的那件事：能退回到「这次写入之前」。
 *
 * 两种路径都支持：
 *  - 绝对路径（/sdcard/...）直接读写文件；
 *  - 相对路径走 SAF 授权目录（复用 FileTools.documentFor）。
 *
 * 快照按会话分目录，只保留最近 [KEEP_PER_SESSION] 份，避免把用户存储吃满。
 */
object FileSnapshots {

    private const val KEEP_PER_SESSION = 40
    private const val MAX_CONTENT = 2 * 1024 * 1024 // 超过 2MB 的文件不存快照（内容多半是二进制）

    data class Snap(val path: String, val ts: Long, val existed: Boolean, val content: String?)

    fun dir(baseDir: File, sessionKey: String): File {
        val safe = sessionKey.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return File(File(baseDir, "snapshots"), safe.ifBlank { "default" })
    }

    /** 写入前调用：记下这份文件的「现状」。失败不影响写入本身。 */
    fun capture(context: Context, baseDir: File, sessionKey: String, path: String) {
        if (path.isBlank()) return
        runCatching {
            val existed = exists(context, path)
            val content = if (existed) readText(context, path) else null
            if (content != null && content.length > MAX_CONTENT) return
            val d = dir(baseDir, sessionKey)
            if (!d.isDirectory && !d.mkdirs()) return
            val ts = System.currentTimeMillis()
            val o = org.json.JSONObject()
                .put("path", path)
                .put("ts", ts)
                .put("existed", existed)
            if (content != null) o.put("content", content)
            val name = "${ts}_${Integer.toHexString(path.hashCode())}.json"
            File(d, name).writeText(o.toString())
            trim(d)
        }
    }

    /** 该文件最近一次快照 */
    fun latest(baseDir: File, sessionKey: String, path: String): Snap? =
        list(baseDir, sessionKey).firstOrNull { it.path == path }

    fun list(baseDir: File, sessionKey: String): List<Snap> {
        val d = dir(baseDir, sessionKey)
        if (!d.isDirectory) return emptyList()
        return d.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
            .mapNotNull { f ->
                runCatching {
                    val o = org.json.JSONObject(f.readText())
                    Snap(
                        path = o.optString("path"),
                        ts = o.optLong("ts"),
                        existed = o.optBoolean("existed"),
                        content = if (o.isNull("content")) null else o.optString("content")
                    )
                }.getOrNull()
            }
    }

    /**
     * 撤销：把文件恢复到最近一次快照记录的状态。
     * 原本不存在的（本次新建）就删掉；原本存在的就写回旧内容。
     * 返回一句人话，直接能显示给用户。
     */
    fun restore(context: Context, baseDir: File, sessionKey: String, path: String): String {
        val snap = latest(baseDir, sessionKey, path)
            ?: return "没有找到 $path 的写入记录，无法撤销"
        return runCatching {
            if (!snap.existed) {
                if (delete(context, path)) "已撤销：删除了本次新建的 ${path.substringAfterLast('/')}"
                else "撤销失败：删除 $path 失败"
            } else {
                val old = snap.content ?: return "撤销失败：快照里没有内容"
                if (writeText(context, path, old)) "已撤销：$path 已恢复到写入前（${old.length} 字）"
                else "撤销失败：写回 $path 失败"
            }
        }.getOrElse { "撤销失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 撤销后把这个快照丢掉，避免连点两次以为是两次撤销 */
    fun forget(baseDir: File, sessionKey: String, path: String) {
        val d = dir(baseDir, sessionKey)
        val snap = latest(baseDir, sessionKey, path) ?: return
        d.listFiles()?.firstOrNull { it.name.startsWith("${snap.ts}_") }?.delete()
    }

    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: return
        files.drop(KEEP_PER_SESSION).forEach { it.delete() }
    }

    // ---- 底层读写：绝对路径走 File，相对路径走 SAF ----

    private fun exists(context: Context, path: String): Boolean = if (path.startsWith("/")) {
        File(path).isFile
    } else {
        FileTools.documentFor(context, path)?.isFile == true
    }

    private fun readText(context: Context, path: String): String? = runCatching {
        if (path.startsWith("/")) {
            val f = File(path)
            if (!f.isFile) null
            else if (f.length() > MAX_CONTENT) null
            else f.readText(Charsets.UTF_8)
        } else {
            val doc = FileTools.documentFor(context, path) ?: return null
            if (!doc.isFile || doc.length() > MAX_CONTENT) return null
            context.contentResolver.openInputStream(doc.uri)?.use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        }
    }.getOrNull()

    private fun writeText(context: Context, path: String, text: String): Boolean = runCatching {
        if (path.startsWith("/")) {
            val f = File(path)
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            f.writeText(text, Charsets.UTF_8)
            true
        } else {
            val doc = FileTools.documentFor(context, path) ?: return false
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } != null
        }
    }.getOrDefault(false)

    private fun delete(context: Context, path: String): Boolean = runCatching {
        if (path.startsWith("/")) File(path).delete()
        else FileTools.documentFor(context, path)?.delete() ?: false
    }.getOrDefault(false)
}
