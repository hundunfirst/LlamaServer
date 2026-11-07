package com.localllm.server.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手回复的富文本解析。
 *
 * 从 ChatScreen 里抽出来单独成文件后才有这些测试——以前它埋在 4000 行的界面文件里，
 * 想验证「代码块会不会被吃掉」只能靠肉眼。
 */
class MarkdownTextTest {

    @Test
    fun `代码块单独成段且保留语言`() {
        val segs = parseSegments("说明：\n```kotlin\nval a = 1\n```\n结束")
        assertEquals(3, segs.size)
        assertTrue(segs[0] is Seg.Text)
        val code = segs[1] as Seg.Code
        assertEquals("kotlin", code.lang)
        assertTrue(code.s.contains("val a = 1"))
        assertTrue(segs[2] is Seg.Text)
    }

    @Test
    fun `标题列表引用表格能分出来`() {
        val segs = parseSegments(
            """
            # 标题
            - 第一项
            - 第二项
            > 引用一句
            | 列1 | 列2 |
            | --- | --- |
            | a | b |
            """.trimIndent()
        )
        assertTrue(segs.any { it is Seg.Heading && it.level == 1 })
        assertTrue(segs.any { it is Seg.ListBlock && it.lines.size == 2 })
        assertTrue(segs.any { it is Seg.Quote })
        assertTrue(segs.any { it is Seg.Table })
    }

    @Test
    fun `未闭合的代码块不会吞掉后面的内容`() {
        // 流式输出时代码块常常还没闭合，这时不能把整段都当成代码
        val segs = parseSegments("前文\n```kotlin\nval a = 1")
        assertTrue(segs.isNotEmpty())
        val text = segs.filterIsInstance<Seg.Text>().joinToString("") { it.s }
        assertTrue(text.contains("前文"))
    }

    @Test
    fun `空内容不炸`() {
        // 允许返回空串分段，只要不抛异常、不产生有实质内容的分段
        assertTrue(parseSegments("").all { it is Seg.Text && it.s.isBlank() })
        assertTrue(parseSegments("   ").all { it is Seg.Text && it.s.isBlank() })
    }

    @Test
    fun `公式转换不丢内容也不炸`() {
        // 具体字形由实现决定，这里只保证不抛异常、原文里的字符没有丢
        val sup = latexToUnicode("x^2")
        assertTrue(sup.isNotBlank() && sup.contains("2"))
        val sub = latexToUnicode("H_2O")
        assertTrue(sub.isNotBlank() && sub.contains("H") && sub.contains("O"))
    }
}
