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

    data class Snap(val path: String, val ts: Long, val existed: Boolean, val content: String?, val turn: Long = 0)

    /**
     * 「撤销整轮」要还原成什么样：一条路径在一次生成里可能被写好几次，
     * 所以要拿**这一轮里最早的那份**（= 动手之前的样子），而不是最后一次。
     *
     * 纯函数，便于单测（真正的读写要 Context）。
     */
    internal fun planTurnRestore(snaps: List<Snap>, turn: Long): List<Snap> =
        snaps.filter { it.turn == turn }
            .groupBy { it.path }
            .mapNotNull { (_, list) -> list.minByOrNull { it.ts } }
            .sortedBy { it.path }

    fun dir(baseDir: File, sessionKey: String): File {
        val safe = sessionKey.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return File(File(baseDir, "snapshots"), safe.ifBlank { "default" })
    }

    /** 写入前调用：记下这份文件的「现状」。失败不影响写入本身。
     *  [turn] 是这次生成轮次的标识（同一条消息里的多次写入共用一个），「撤销整轮」靠它分组。 */
    fun capture(context: Context, baseDir: File, sessionKey: String, path: String, turn: Long = 0) {
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
                .put("turn", turn)
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
                        content = if (o.isNull("content")) null else o.optString("content"),
                        turn = o.optLong("turn")
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

    /**
     * 撤销某一轮的全部改动：按路径还原到这一轮动手之前的样子，
     * 然后把这一轮的快照删掉（下次再点就是撤更早的一轮）。
     *
     * 为什么需要它：一轮里改 5 个文件，逐个点撤销要点 5 次，还得记住哪几个——
     * 而模型恰恰是在一整轮里成批改文件的。
     */
    fun restoreTurn(context: Context, baseDir: File, sessionKey: String, turn: Long): String {
        if (turn == 0L) return "这一轮没有写入记录，无法整轮撤销"
        val targets = planTurnRestore(list(baseDir, sessionKey), turn)
        if (targets.isEmpty()) return "这一轮没有写入记录，无法整轮撤销"
        var ok = 0
        val failed = mutableListOf<String>()
        targets.forEach { snap ->
            val r = runCatching {
                if (!snap.existed) delete(context, snap.path)
                else snap.content?.let { writeText(context, snap.path, it) } ?: false
            }.getOrDefault(false)
            if (r) ok++ else failed += snap.path.substringAfterLast('/')
        }
        // 成功的那些快照用完即删；失败的留着，用户还能逐个重试
        val d = dir(baseDir, sessionKey)
        val snapshots = list(baseDir, sessionKey).filter { it.turn == turn }
        snapshots.forEach { s ->
            val name = s.path.substringAfterLast('/')
            if (name !in failed) {
                d.listFiles()?.firstOrNull { it.name.startsWith("${s.ts}_") }?.delete()
            }
        }
        return buildString {
            append("已撤销本轮 $ok 个文件")
            if (failed.isNotEmpty()) append("；失败 ${failed.size} 个：${failed.joinToString("、")}")
        }
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
