package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnSummaryLabelTest {

    private fun step(type: String, label: String = "x") = ChatStore.Step(type, label)

    @Test
    fun `没有步骤时只有已完成`() {
        assertEquals("✓ 已完成", ChatStore.turnSummaryLabel(emptyList()))
    }

    @Test
    fun `只有工具调用时数工具`() {
        assertEquals(
            "✓ 已完成 · 3 次工具调用",
            ChatStore.turnSummaryLabel(listOf(step("tool"), step("tool"), step("tool")))
        )
    }

    @Test
    fun `工具加搜索都列出`() {
        assertEquals(
            "✓ 已完成 · 2 次工具调用 · 1 次搜索",
            ChatStore.turnSummaryLabel(listOf(step("tool"), step("search"), step("tool")))
        )
    }

    @Test
    fun `只有搜索时数搜索`() {
        assertEquals(
            "✓ 已完成 · 2 次搜索",
            ChatStore.turnSummaryLabel(listOf(step("search"), step("search")))
        )
    }

    @Test
    fun `只有思考步时数思考`() {
        assertEquals(
            "✓ 已完成 · 2 段思考",
            ChatStore.turnSummaryLabel(listOf(step("think"), step("think")))
        )
    }

    @Test
    fun `已有思考步时 hasReasoning 不重复计数`() {
        assertEquals(
            "✓ 已完成 · 1 段思考",
            ChatStore.turnSummaryLabel(listOf(step("think")), hasReasoning = true)
        )
    }

    @Test
    fun `思考还没落成步骤时靠 hasReasoning 计数`() {
        assertEquals(
            "✓ 已完成 · 1 段思考",
            ChatStore.turnSummaryLabel(emptyList(), hasReasoning = true)
        )
    }

    @Test
    fun `有工具调用时思考段不挤进摘要`() {
        assertEquals(
            "✓ 已完成 · 1 次工具调用",
            ChatStore.turnSummaryLabel(listOf(step("tool"), step("think")), hasReasoning = true)
        )
    }

    @Test
    fun `中间正文算消息条数`() {
        assertEquals(
            "✓ 已完成 · 2 条消息",
            ChatStore.turnSummaryLabel(listOf(step("text"), step("text")))
        )
    }

    @Test
    fun `工具加中间正文都列出`() {
        assertEquals(
            "✓ 已完成 · 2 次工具调用 · 1 条消息",
            ChatStore.turnSummaryLabel(listOf(step("think"), step("text"), step("tool"), step("tool")))
        )
    }
}
