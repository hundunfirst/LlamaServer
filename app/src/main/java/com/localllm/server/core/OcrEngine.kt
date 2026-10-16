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
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalStateException("无法解码图片")
        return withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(bmp, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val sb = StringBuilder()
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(line.text)
                            }
                        }
                        cont.resume(sb.toString().trim())
                    }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        }
    }
}
