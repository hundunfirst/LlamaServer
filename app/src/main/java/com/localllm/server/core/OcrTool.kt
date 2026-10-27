package com.localllm.server.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * `ocr_image`：把用户上传图片里的文字识别出来（本地 ML Kit，不上传、不联网）。
 *
 * 什么时候用哪条路：
 *  - 挂了 mmproj 的视觉模型自己看得见图，但**读数字会错、读不清会编**；
 *  - 纯文本模型看不到图，只能靠这个工具；
 *  - 两者结合最稳：图照发（版面、图表、手写看模型），同时把本地 OCR 的逐字结果一起给它核对
 *    （见 [promptBlock]，附件时自动跑一次）。
 */
object OcrTool {
    const val NAME = "ocr_image"

    /** 识别结果上限：整页文字一次性塞进上下文会挤爆 ctx */
    private const val MAX_CHARS = 4000

    /** 随图附带的 OCR 文本上限：这部分每轮都在上下文里，比工具结果更该省 */
    const val INLINE_MAX_CHARS = 2000

    /** 少到这个程度就当图里没字（照片、插图），不必白占上下文 */
    private const val MIN_USEFUL_CHARS = 8

    /**
     * 把本地 OCR 结果压成随图附带的一段提示文本；没有可用文字时返回 null。
     *
     * 纯函数，便于单测——这段文字会进每一轮请求的上下文，写错就是白烧 token。
     */
    fun promptBlock(text: String?, maxChars: Int = INLINE_MAX_CHARS): String? {
        val t = text?.trim().orEmpty()
        if (t.length < MIN_USEFUL_CHARS) return null
        val shown = if (t.length > maxChars) {
            t.take(maxChars) + "\n…（本地 OCR 共 ${t.length} 字，已截断；需要完整文字请调用 $NAME）"
        } else t
        return "【图片文字（本机 OCR 逐字识别，供核对数字与专有名词；版面与图形仍以图为准）】\n" +
                "```\n" + shown + "\n```"
    }

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
