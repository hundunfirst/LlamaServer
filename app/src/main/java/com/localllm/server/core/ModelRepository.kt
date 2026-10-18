package com.localllm.server.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ModelFile(
    val name: String,
    val file: File,
    val sizeBytes: Long
)

/** 回收站条目：原文件名、删除时间、原来在哪个目录（models / projectors） */
data class TrashedFile(
    val file: File,
    val originalName: String,
    val deletedAt: Long,
    val wasProjector: Boolean
) {
    val sizeBytes: Long get() = file.length()
}

/**
 * 模型存储按目录区分类型（与文件名无关）：
 * - files/models/      对话主模型（必选）
 * - files/projectors/  多模态投影 mmproj（可选，由用户在导入时指定类型、启动前手动选配）
 */
class ModelRepository(private val context: Context) {
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val projectorsDir: File = File(context.filesDir, "projectors").apply { mkdirs() }

    /** 回收站：删除只是同盘改名移动（GB 级文件瞬间完成），保留 [TRASH_KEEP_DAYS] 天后自动清 */
    private val trashDir: File = File(context.filesDir, "trash").apply { mkdirs() }

    init {
        migrateLegacyProjectors()
    }

    /** 旧版本把 mmproj 混在 models/ 里，按文件名迁移一次 */
    private fun migrateLegacyProjectors() {
        modelsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true) &&
                    f.name.contains("mmproj", ignoreCase = true)
        }?.forEach { f ->
            var target = File(projectorsDir, f.name)
            if (target.exists()) {
                target = File(projectorsDir, "migrated_${f.name}")
            }
            f.renameTo(target)
        }
    }

    fun listChatModels(): List<ModelFile> = listDir(modelsDir)

    fun listProjectors(): List<ModelFile> = listDir(projectorsDir)

    private fun listDir(dir: File): List<ModelFile> {
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { ModelFile(it.name, file = it, sizeBytes = it.length()) }
            ?: emptyList()
    }

    suspend fun importFromUri(uri: Uri, asProjector: Boolean): Result<ModelFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = if (asProjector) projectorsDir else modelsDir
                val displayName = queryDisplayName(uri) ?: "model.gguf"
                val safeName = sanitizeFileName(displayName).let {
                    if (it.endsWith(".gguf", ignoreCase = true)) it else "$it.gguf"
                }
                var target = File(dir, safeName)
                if (target.exists()) {
                    val base = safeName.removeSuffix(".gguf").removeSuffix(".GGUF")
                    var i = 1
                    while (target.exists()) {
                        target = File(dir, "$base($i).gguf")
                        i++
                    }
                }
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法打开文件: $uri")
                input.use { source ->
                    target.outputStream().use { dest ->
                        source.copyTo(dest, bufferSize = 1024 * 256)
                    }
                }
                ModelFile(target.name, file = target, sizeBytes = target.length())
            }
        }

    fun delete(model: ModelFile): Boolean {
        clearSelectionFor(model.file)
        return model.file.delete()
    }

    // ---- 回收站 -------------------------------------------------------------

    /**
     * 移入回收站（不是真删）。同盘 rename，几 GB 也是瞬间；失败就返回 false，
     * 让上层提示，绝不悄悄改成直接删除。
     */
    fun trash(model: ModelFile): Boolean {
        val src = model.file
        if (!src.isFile) return false
        clearSelectionFor(src)
        trashDir.mkdirs()
        val wasProjector = src.parentFile?.absolutePath == projectorsDir.absolutePath
        val target = File(trashDir, "${System.currentTimeMillis()}-${if (wasProjector) "p" else "m"}-${src.name}")
        return src.renameTo(target)
    }

    fun listTrash(): List<TrashedFile> {
        val now = System.currentTimeMillis()
        return trashDir.listFiles { f -> f.isFile }?.mapNotNull { f ->
            // 名字格式：<删除时间戳>-<p|m>-<原文件名>
            val first = f.name.indexOf('-')
            val second = if (first > 0) f.name.indexOf('-', first + 1) else -1
            if (second < 0) {
                TrashedFile(f, f.name, f.lastModified(), wasProjector = false)
            } else {
                val ts = f.name.take(first).toLongOrNull() ?: now
                val flag = f.name.substring(first + 1, second)
                TrashedFile(f, f.name.substring(second + 1), ts, wasProjector = flag == "p")
            }
        }?.sortedByDescending { it.deletedAt } ?: emptyList()
    }

    fun restore(t: TrashedFile): Boolean {
        val dir = if (t.wasProjector) projectorsDir else modelsDir
        var target = File(dir, t.originalName)
        if (target.exists()) {
            val base = t.originalName.removeSuffix(".gguf").removeSuffix(".GGUF")
            var i = 1
            while (target.exists()) {
                target = File(dir, "$base(恢复$i).gguf")
                i++
            }
        }
        return t.file.renameTo(target)
    }

    fun purge(t: TrashedFile): Boolean = t.file.delete()

    fun emptyTrash(): Int = listTrash().count { it.file.delete() }

    /** 回收站会占着几十 GB，超过保留期就自动清掉（App 启动时调一次） */
    fun purgeExpired(keepDays: Int = TRASH_KEEP_DAYS): Int {
        val deadline = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        return listTrash().filter { it.deletedAt < deadline }.count { it.file.delete() }
    }

    private fun clearSelectionFor(file: File) {
        if (AppSettings.selectedModelPath == file.absolutePath) {
            AppSettings.selectedModelPath = null
        }
        if (AppSettings.selectedMmprojPath == file.absolutePath) {
            AppSettings.selectedMmprojPath = null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "model.gguf" }
    }

    companion object {
        const val TRASH_KEEP_DAYS = 7
    }
}
