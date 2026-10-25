package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogNoiseTest {

    @Test
    fun `连续 warmup 折成一行摘要`() {
        val lines = listOf(
            "服务已就绪",
            "0.11 W warmup: *****",
            "0.11 W warmup: list of unsupported ops (backend=HTP0):",
            "0.11 W warmup: MUL_MAT: type = f32, ne = [3072 8464 1 1]",
            "0.11 W warmup: MUL_MAT: type = f32, ne = [1024 8464 1 1]",
            "聊天：发送"
        )
        val rows = LogNoise.collapse(lines)
        assertEquals(3, rows.size)
        assertEquals("服务已就绪", rows[0].text)
        assertEquals(4, rows[1].collapsed)
        assertTrue(rows[1].text.contains("4 行"))
        assertEquals("聊天：发送", rows[2].text)
    }

    @Test
    fun `孤立的一行 warmup 照常显示`() {
        val rows = LogNoise.collapse(
            listOf("错误：内存不足", "0.11 W warmup: flash attention is enabled", "服务已停止")
        )
        assertEquals(3, rows.size)
        assertEquals(0, rows[1].collapsed)
        assertTrue(rows[1].text.contains("flash attention"))
    }

    @Test
    fun `被真实日志隔开的两段分别折叠`() {
        val rows = LogNoise.collapse(
            listOf(
                "0.1 W warmup: A", "0.1 W warmup: B",
                "正在加载模型",
                "0.2 W warmup: C", "0.2 W warmup: D", "0.2 W warmup: E"
            )
        )
        assertEquals(3, rows.size)
        assertEquals(2, rows[0].collapsed)
        assertEquals("正在加载模型", rows[1].text)
        assertEquals(3, rows[2].collapsed)
    }

    @Test
    fun `普通行不算噪音`() {
        assertFalse(LogNoise.isNoise("MCP 完成：mcp__MT__mt_apk_list"))
        assertFalse(LogNoise.isNoise("聊天：完成 · 共 199 token"))
        assertTrue(LogNoise.isNoise("0.22.847.351 W warmup: MUL_MAT: type = f32, ne = [1024 8464 1 1]"))
    }
}
