package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端日志的落盘格式：一行一个前缀，写出去还能原样读回来。
 * 这里的错法很隐蔽——读回来种类串了，界面上命令就显示成输出色。
 */
class TerminalLogTest {

    private fun line(kind: TerminalLog.Kind, text: String) = TerminalLog.Line(kind, text)

    @Test
    fun `编码解码往返不丢种类`() {
        val cases = listOf(
            line(TerminalLog.Kind.Input, "$ ls -la"),
            line(TerminalLog.Kind.Output, "total 8"),
            line(TerminalLog.Kind.Error, "bash: foo: command not found"),
            line(TerminalLog.Kind.Info, "（已清空 shell 会话）"),
        )
        cases.forEach {
            val back = TerminalLog.decode(TerminalLog.encode(it.kind, it.text))
            assertEquals(it, back)
        }
    }

    @Test
    fun `未知前缀按提示处理_不抛异常`() {
        assertEquals(TerminalLog.Kind.Info, TerminalLog.decode("?什么").kind)
        assertEquals("", TerminalLog.decode("").text)
    }

    @Test
    fun `只保留最近 N 行`() {
        val many = (1..50).map { line(TerminalLog.Kind.Output, "line$it") }
        val text = TerminalLog.serialize(many, max = 10)
        assertEquals(10, text.split('\n').size)
        assertTrue(text.endsWith("line50"))
        assertTrue(!text.contains("line40\n"))
    }

    @Test
    fun `解析时也按上限截断_并且跳过空行`() {
        val text = TerminalLog.serialize(
            (1..30).map { line(TerminalLog.Kind.Output, "l$it") }, max = 100
        ) + "\n\n"
        val parsed = TerminalLog.parse(text, max = 5)
        assertEquals(5, parsed.size)
        assertEquals("l30", parsed.last().text)
    }

    @Test
    fun `空文件解析成空列表`() {
        assertTrue(TerminalLog.parse("").isEmpty())
        assertTrue(TerminalLog.parse("   ").isEmpty())
    }

    @Test
    fun `命令历史只取输入行_去重且留最近`() {
        val lines = listOf(
            line(TerminalLog.Kind.Input, "$ ls"),
            line(TerminalLog.Kind.Output, "a.txt"),
            line(TerminalLog.Kind.Input, "$ cd /tmp"),
            line(TerminalLog.Kind.Input, "$ ls"),   // 重复，只留最后一次
            line(TerminalLog.Kind.Error, "boom"),
        )
        assertEquals(listOf("cd /tmp", "ls"), TerminalLog.history(lines))
    }
}
