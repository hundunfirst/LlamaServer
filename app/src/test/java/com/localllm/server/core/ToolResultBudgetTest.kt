package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultBudgetTest {

    @Test
    fun `预算默认按窗口百分之十七给`() {
        // 默认 17% ≈ 原来的 1/6，但上限从 60k 提到 400k：大窗口（云端）不该被本地小窗口的尺度卡住
        assertEquals(22_282, ToolResultBudget.budgetChars(131_072))
        assertEquals(2_000, ToolResultBudget.budgetChars(4_096))        // 小窗口有下限
        assertEquals(340_000, ToolResultBudget.budgetChars(2_000_000))  // 超大窗口按比例
    }

    @Test
    fun `比例为 0 时不截断`() {
        assertEquals(Int.MAX_VALUE, ToolResultBudget.budgetChars(131_072, 0))
    }

    @Test
    fun `比例可调`() {
        assertTrue(ToolResultBudget.budgetChars(131_072, 50) > ToolResultBudget.budgetChars(131_072, 17))
    }

    @Test
    fun `正常大小的结果原样返回`() {
        val text = "a".repeat(5_000)
        val out = ToolResultBudget.trim(text, 21_845)
        assertFalse(out.truncated)
        assertEquals(text, out.text)
    }

    @Test
    fun `超长文本保留头尾并说明怎么续读`() {
        val text = "HEAD" + "x".repeat(100_000) + "TAIL"
        val out = ToolResultBudget.trim(text, 20_000)
        assertTrue(out.truncated)
        assertEquals(text.length, out.originalChars)
        assertTrue("要保留开头，模型才知道读的是什么", out.text.startsWith("HEAD"))
        assertTrue("要保留结尾", out.text.endsWith("TAIL"))
        assertTrue("要告诉它省略了多少", out.text.contains("省略"))
        assertTrue("要给出续读方式", out.text.contains("offset"))
        assertTrue("压缩后必须真的变小", out.text.length < text.length)
        assertTrue("不能把它压得过小，宽松优先", out.text.length > 15_000)
    }

    @Test
    fun `结构化数据不从中间切开`() {
        val json = "[" + """{"a":1},""".repeat(20_000) + "]"
        val out = ToolResultBudget.trim(json, 20_000)
        assertTrue(out.truncated)
        assertFalse("绝不能给出半截 JSON", out.text.contains("\"a\":1"))
        assertTrue(out.text.contains("结构化数据"))
        assertTrue(out.text.contains("缩小查询范围"))
    }

    @Test
    fun `刚超过预算一点也照样截断但保留大部分`() {
        val text = "y".repeat(20_001)
        val out = ToolResultBudget.trim(text, 20_000)
        assertTrue(out.truncated)
        assertTrue(out.text.length >= 20_000)
    }
}
