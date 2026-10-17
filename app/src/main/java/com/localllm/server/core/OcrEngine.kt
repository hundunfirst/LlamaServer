package com.localllm.server.core

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本地 OCR：ML Kit 中文文字识别（bundled 离线模型，无需网络/GMS 在线组件）。
 *
 * 与多模态（mmproj 视觉模型）的关系：
 * - 图片只走一条路，不叠加——有视觉模型且模式=自动时直接喂图；否则本地 OCR 抽文字后
 *   只把文字放进消息（不传图片 base64），省掉整张图的 token 与视觉前向开销，本地小模型更快更稳。
 * - 不修改对话提示词，未使用时不产生任何性能影响。
 */
object OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别 JPEG 字节中的文字（按行拼接）；识别为空返回空串 */
    suspend fun recognize(jpegBytes: ByteArray): String {
        val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalStateException("无法解码图片")
        // 预处理：偏小的图放大 2 倍 + 转灰度，小字号与小数点识别率明显更好
        val bmp = runCatching {
            val maxDim = maxOf(raw.width, raw.height)
            val scaled = if (maxDim in 1..1600) {
                val s = minOf(2.0f, 3200f / maxDim)
                android.graphics.Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * s).toInt().coerceAtLeast(1),
                    (raw.height * s).toInt().coerceAtLeast(1),
                    true
                )
            } else raw
            val gray = android.graphics.Bitmap.createBitmap(
                scaled.width, scaled.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(gray)
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(0f) }
                )
            }
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            if (scaled !== raw) scaled.recycle()
            gray
        }.getOrDefault(raw)
        return withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(bmp, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        // 按坐标重建行：多列/卡片式布局直接拼行序会串行，导致数字错位
                        data class El(val x: Int, val y: Int, val h: Int, val text: String)

                        val els = mutableListOf<El>()
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                val bb = line.boundingBox ?: continue
                                if (line.text.isBlank()) continue
                                els.add(El(bb.left, bb.top, bb.height(), line.text))
                            }
                        }
                        els.sortBy { it.y }
                        val rows = mutableListOf<MutableList<El>>()
                        for (e in els) {
                            val row = rows.lastOrNull()
                            if (row != null &&
                                kotlin.math.abs(e.y - row.first().y) < maxOf(12, row.first().h / 2)
                            ) {
                                row.add(e)
                            } else {
                                rows.add(mutableListOf(e))
                            }
                        }
                        val sb = StringBuilder()
                        for (row in rows) {
                            row.sortBy { it.x }
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(row.joinToString(" | ") { it.text })
                        }
                        cont.resume(sb.toString().trim())
                    }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        }
    }
}
