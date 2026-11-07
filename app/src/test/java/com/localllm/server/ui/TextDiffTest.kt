package com.localllm.server.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件改动的行级差异。抽成独立文件后才有这些测试——以前它埋在 ChatScreen 里只能靠眼睛看。
 */
class TextDiffTest {

    @Test
    fun `统计新增与删除行数`() {
        val (add, del) = diffStat("a\nb\nc", "a\nB\nc\nd")
        assertTrue("应当有新增行", add > 0)
        assertTrue("应当有删除行", del > 0)
    }

    @Test
    fun `内容相同时没有变化`() {
        val (add, del) = diffStat("same\ntext", "same\ntext")
        assertEquals(0, add)
        assertEquals(0, del)
    }

    @Test
    fun `首次创建文件时旧内容为空`() {
        val (add, del) = diffStat(null, "line1\nline2")
        assertTrue(add > 0)
        assertEquals(0, del)
    }

    @Test
    fun `diff 结果带增删标记`() {
        val lines = diffLines("a\nb", "a\nc")
        assertTrue("应当有上下文或删除标记", lines.any { it.first == '-' || it.first == ' ' })
        assertTrue("应当有新增标记", lines.any { it.first == '+' })
    }

    @Test
    fun `超大文件按上限截断`() {
        val big = (1..2000).joinToString("\n") { "line$it" }
        val lines = diffLines("", big, limit = 100)
        assertTrue("不能把两千行都吐给界面", lines.size <= 101)
    }
}
