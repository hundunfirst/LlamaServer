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

/**
 * 模型存储按目录区分类型（与文件名无关）：
 * - files/models/      对话主模型（必选）
 * - files/projectors/  多模态投影 mmproj（可选，由用户在导入时指定类型、启动前手动选配）
 */
class ModelRepository(private val context: Context) {
    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val projectorsDir: File = File(context.filesDir, "projectors").apply { mkdirs() }

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
        if (AppSettings.selectedModelPath == model.file.absolutePath) {
            AppSettings.selectedModelPath = null
        }
        if (AppSettings.selectedMmprojPath == model.file.absolutePath) {
            AppSettings.selectedMmprojPath = null
        }
        return model.file.delete()
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
}
