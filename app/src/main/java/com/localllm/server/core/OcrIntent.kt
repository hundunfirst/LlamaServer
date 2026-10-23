package com.localllm.server.core

/**
 * 「用户是否明确要求 OCR」的判据。
 *
 * 命中时调用方**不把图片发给视觉模型**，强制走 `ocr_image` 工具——这样即使模型挂了 mmproj，
 * 显式指令也优先于它的默认行为（视觉模型否则会直接读图，给的是概括而不是逐字结果，
 * 而用户要 OCR 往往正是为了表格数字、报错原文这种逐字精度）。
 *
 * 判据保守：只认「OCR / 文字识别 / 识别文字 / 提取文字 / 转成文字」这类说法。
 * 「识别这张图是什么」「这张图讲了什么」属于理解类请求，不该被拦。
 */
object OcrIntent {
    private val REQUEST = Regex(
        "(?i)ocr|文字识别|识别.{0,6}(文字|文本|字符)|提取.{0,6}(文字|文本)|转成文字|识字"
    )

    fun requested(userText: String): Boolean = REQUEST.containsMatchIn(userText)
}
