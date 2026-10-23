package com.localllm.server.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * `ocr_image`：把用户上传图片里的文字识别出来（本地 ML Kit，不上传、不联网）。
 *
 * 为什么做成工具而不是自动预处理：很多图只需要看懂内容、不需要逐字转写，
 * 自动跑一遍既费时又可能在不需要时白跑；交给模型按需调用，
 * 用户一句「识别这张图的文字」就是明确的触发条件。
 * 挂了 mmproj 的视觉模型本来就看得见图，这个工具主要用于纯文本模型。
 */
object OcrTool {
    const val NAME = "ocr_image"

    /** 识别结果上限：整页文字一次性塞进上下文会挤爆 ctx */
    private const val MAX_CHARS = 4000

    fun toolJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function", JSONObject().apply {
                put("name", NAME)
                put(
                    "description",
                    "识别用户上传图片中的文字（本地离线识别，不联网）。当用户要求提取图片里的文字、" +
                            "读取截图内容，或你需要看到图上的具体文字（报错信息、表格、文档）时调用。" +
                            "图片是照片/插图且只需要理解内容时不必调用。"
                )
                put(
                    "parameters", JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties", JSONObject().apply {
                                put(
                                    "focus", JSONObject().apply {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "可选：想重点提取的内容，例如「表格里的数字」「第一段标题」。留空返回全部文字。"
                                        )
                                    }
                                )
                            }
                        )
                        put("required", JSONArray())
                    }
                )
            }
        )
    }

    /** 跑一次本地识别；没有文字可识别时抛出可读异常，由调用方转成工具错误文案 */
    suspend fun recognize(imageB64: String): String {
        val bytes = android.util.Base64.decode(imageB64, android.util.Base64.NO_WRAP)
        val text = OcrEngine.recognize(bytes).trim()
        if (text.isEmpty()) throw IllegalStateException("图片里没有识别到文字（可能是纯图片或字太小）")
        return if (text.length <= MAX_CHARS) text
        else text.take(MAX_CHARS) + "\n…（原文共 ${text.length} 字，已截断）"
    }
}
