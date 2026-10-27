package com.localllm.server.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OcrTool.promptBlock` 决定「随图附带的 OCR 文字」长什么样。
 * 这段内容每轮都在上下文里，所以阈值与截断必须钉死。
 */
class OcrPromptBlockTest {

    @Test
    fun `正常文字带说明一起给出`() {
        val block = OcrTool.promptBlock("金额：1,234.50 元\n单号：A20261025")
        assertTrue(block!!, block.contains("1,234.50"))
        assertTrue(block, block.contains("本机 OCR"))
        assertTrue("应放在代码块里，避免被当成指令", block.contains("```"))
    }

    @Test
    fun `字太少就当图里没字，不占上下文`() {
        assertNull(OcrTool.promptBlock(null))
        assertNull(OcrTool.promptBlock(""))
        assertNull(OcrTool.promptBlock("   "))
        // 照片/插图里偶尔蹭到一两个字，不值得每轮都带上
        assertNull(OcrTool.promptBlock("您好"))
    }

    @Test
    fun `超长时截断并说明怎么拿全文`() {
        val long = "行".repeat(OcrTool.INLINE_MAX_CHARS + 500)
        val block = OcrTool.promptBlock(long)!!
        assertTrue(block.contains("已截断"))
        assertTrue(block.contains(OcrTool.NAME))
        assertFalse("截断说明里的字数不该是全长", block.contains("共 ${long.length + 1} 字"))
        // 正文部分不该超过上限太多（留出说明文字的位置）
        assertTrue(block.length < OcrTool.INLINE_MAX_CHARS + 300)
    }

    @Test
    fun `自定义上限生效`() {
        val block = OcrTool.promptBlock("字".repeat(100), maxChars = 20)!!
        assertTrue(block.contains("已截断"))
    }
}
