package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 从 GGUF 头元数据判断一个文件是什么东西。
 *
 * 为什么不用文件名：`MTP` 既可能是"主模型自带 MTP 头"的能力标签，
 * 也可能是独立的 MTP 侧车文件（Bonsai 仓库里两种都有）。同理 `mmproj`
 * 只是命名习惯。真正可靠的判据是 `general.type` 与 `general.architecture`：
 * - type=model + arch=qwen35/llama…   → 主模型
 * - arch=clip（或 type=mmproj）        → 视觉投影器
 * - arch=dflash/dspark/eagle3/mtp      → 投机解码草稿/侧车
 * - type=adapter / imatrix             → LoRA 适配器 / 重要性矩阵
 */
enum class GgufKind { MAIN, MMPROJ, DRAFT, ADAPTER, IMATRIX, UNKNOWN, NOT_GGUF }

data class GgufInfo(
    val kind: GgufKind,
    val architecture: String = "",
    val name: String = "",
    val sizeLabel: String = "",
    val nTensors: Long = 0
) {
    /** 界面提示文案；主模型/未知返回 null（不需要打扰用户） */
    val warning: String?
        get() = when (kind) {
            GgufKind.DRAFT ->
                "不是主模型：这是投机解码的草稿/侧车文件（architecture=$architecture）"
            GgufKind.MMPROJ ->
                "不是主模型：这是多模态投影器（mmproj），应在模型页选作「多模态投影」"
            GgufKind.ADAPTER ->
                "不是完整模型：这是 LoRA 适配器，需要配主模型使用"
            GgufKind.IMATRIX ->
                "不是模型：这是量化用的重要性矩阵"
            GgufKind.NOT_GGUF -> "不是 GGUF 文件"
            else -> null
        }

    val isLoadableAsModel: Boolean get() = kind == GgufKind.MAIN || kind == GgufKind.UNKNOWN
}

object GgufInspector {
    /** general.* 元数据都在文件最前面，读 512KB 足够；碰到词表那种大数组就提前收工 */
    private const val HEAD_BYTES = 512 * 1024
    private val cache = HashMap<String, GgufInfo>()

    suspend fun inspect(file: File): GgufInfo = withContext(Dispatchers.IO) { inspectBlocking(file) }

    fun inspectBlocking(file: File): GgufInfo {
        val key = file.absolutePath + "@" + file.length() + "@" + file.lastModified()
        cache[key]?.let { return it }
        val info = runCatching { parse(file) }.getOrDefault(GgufInfo(GgufKind.UNKNOWN))
        if (cache.size > 128) cache.clear()
        cache[key] = info
        return info
    }

    private class Buf(private val b: ByteArray) {
        var pos = 0
        fun remaining() = b.size - pos
        fun u32(): Long {
            val v = (b[pos].toLong() and 0xFF) or
                    ((b[pos + 1].toLong() and 0xFF) shl 8) or
                    ((b[pos + 2].toLong() and 0xFF) shl 16) or
                    ((b[pos + 3].toLong() and 0xFF) shl 24)
            pos += 4
            return v
        }
        fun u64(): Long {
            val lo = u32()
            val hi = u32()
            return lo or (hi shl 32)
        }
        fun str(): String {
            val n = u64().toInt()
            val s = String(b, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }
        fun skip(n: Long) {
            pos += n.toInt()
        }
    }

    private fun parse(file: File): GgufInfo {
        if (!file.isFile || file.length() < 32) return GgufInfo(GgufKind.NOT_GGUF)
        val head = RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(minOf(HEAD_BYTES.toLong(), raf.length()).toInt())
            raf.readFully(buf)
            buf
        }
        if (head.size < 8 || String(head, 0, 4, Charsets.US_ASCII) != "GGUF") {
            return GgufInfo(GgufKind.NOT_GGUF)
        }
        val b = Buf(head)
        b.pos = 4
        b.u32()                    // version
        val nTensors = b.u64()
        val nKv = b.u64()

        var type = ""
        var arch = ""
        var name = ""
        var sizeLabel = ""
        var seen = 0
        try {
            for (i in 0 until nKv) {
                val key = b.str()
                val t = b.u32().toInt()
                when (t) {
                    8 -> {                                  // string
                        val v = b.str()
                        when (key) {
                            "general.type" -> type = v
                            "general.architecture" -> arch = v
                            "general.name" -> name = v
                            "general.size_label" -> sizeLabel = v
                        }
                    }
                    9 -> {                                  // array
                        val et = b.u32().toInt()
                        val n = b.u64()
                        if (et != 8 && et != 9) {
                            b.skip(n * scalarSize(et))
                        } else {
                            // 字符串数组（词表等）可能几十万条：已经拿到 arch 就够了，别读完
                            if (arch.isNotEmpty() || n > 4096) return classify(type, arch, name, sizeLabel, nTensors)
                            repeat(n.toInt()) { b.str() }
                        }
                    }
                    else -> b.skip(scalarSize(t))
                }
                seen++
                if (arch.isNotEmpty() && type.isNotEmpty()) break
                if (b.remaining() < 64) break
            }
        } catch (_: Exception) {
            // 头被截断/格式异常：用已解析到的部分判断
        }
        if (seen == 0) return GgufInfo(GgufKind.UNKNOWN)
        return classify(type, arch, name, sizeLabel, nTensors)
    }

    private fun scalarSize(t: Int): Long = when (t) {
        0, 1, 7 -> 1L
        2, 3 -> 2L
        4, 5, 6 -> 4L
        10, 11, 12 -> 8L
        else -> 0L
    }

    private fun classify(type: String, arch: String, name: String, sizeLabel: String, nTensors: Long): GgufInfo {
        val t = type.lowercase()
        val a = arch.lowercase()
        val kind = when {
            t == "mmproj" || a == "clip" || a == "mmproj" -> GgufKind.MMPROJ
            t == "adapter" || t == "lora" -> GgufKind.ADAPTER
            t == "imatrix" -> GgufKind.IMATRIX
            a in setOf("dflash", "dspark", "eagle3", "draft", "mtp") -> GgufKind.DRAFT
            a.isNotEmpty() -> GgufKind.MAIN
            else -> GgufKind.UNKNOWN
        }
        return GgufInfo(kind, arch, name, sizeLabel, nTensors)
    }
}
