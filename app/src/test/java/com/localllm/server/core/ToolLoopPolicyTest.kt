package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopPolicyTest {

    @Test
    fun `0 表示不限而不是零轮`() {
        assertEquals(Int.MAX_VALUE, ToolLoopPolicy.roundCap(0))
        assertEquals(Int.MAX_VALUE, ToolLoopPolicy.roundCap(-1))
    }

    @Test
    fun `有限轮次按设置值`() {
        assertEquals(16, ToolLoopPolicy.roundCap(16))
        assertEquals(64, ToolLoopPolicy.roundCap(64))
    }

    @Test
    fun `两次同样的报错算没有进展`() {
        assertTrue(ToolLoopPolicy.noProgress("✗ a|✗ b", "✗ a|✗ b"))
    }

    @Test
    fun `报错变了说明有进展`() {
        assertFalse(ToolLoopPolicy.noProgress("✗ b", "✗ a|✗ b"))
    }

    @Test
    fun `自检干净通过不算无进展`() {
        assertFalse(ToolLoopPolicy.noProgress("", "✗ a"))
        assertFalse(ToolLoopPolicy.noProgress("", ""))
        assertFalse(ToolLoopPolicy.noProgress(null, null))
    }
}
