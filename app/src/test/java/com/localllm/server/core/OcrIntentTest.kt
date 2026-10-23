package com.localllm.server.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 意图判据的回归测试。
 * 这条判据决定「图片发不发给视觉模型」，判错的两个方向都有代价：
 * 该走 OCR 却发图 → 视觉模型直接读图，用户要的逐字结果变成概括；
 * 不该走 OCR 却拦下图 → 模型看不到图，只能瞎猜。
 */
class OcrIntentTest {

    @Test
    fun `明确要求 OCR 的说法都命中`() {
        val hits = listOf(
            "用OCR识别一下",
            "ocr",
            "帮我 OCR 这张截图",
            "识别这张图的文字",
            "把图片里的文字识别出来",
            "提取图中的文字",
            "提取一下文本",
            "把这张图转成文字",
            "识别文字并整理成表格"
        )
        hits.forEach { assertTrue("应命中却未命中：$it", OcrIntent.requested(it)) }
    }

    @Test
    fun `理解类请求不该被拦`() {
        val misses = listOf(
            "识别这张图是什么",
            "这张图讲了什么",
            "看看这个 UI 有没有问题",
            "这个截图里按钮在哪",
            "帮我分析这张架构图",
            "这张照片拍的是哪里",
            ""
        )
        misses.forEach { assertFalse("不该命中却命中：$it", OcrIntent.requested(it)) }
    }
}
