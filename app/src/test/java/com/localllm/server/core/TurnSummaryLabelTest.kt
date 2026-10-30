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

    @Test
    fun `思考预览_完成态取全文第一行并去掉星号`() {
        assertEquals(
            "先看 pubspec 里锁的版本",
            ChatStore.thinkingPreview("**先看 pubspec 里锁的版本**\n然后看依赖树\n\n第二段", active = false)
        )
    }

    @Test
    fun `思考预览_进行中只取已写完首行的最近一段`() {
        assertEquals(
            "再看依赖树",
            ChatStore.thinkingPreview("先看 pubspec\n\n再看依赖树\n", active = true)
        )
    }

    @Test
    fun `思考预览_进行中半句话不抖`() {
        assertEquals("", ChatStore.thinkingPreview("只写到一半没有换行", active = true))
        assertEquals("", ChatStore.thinkingPreview("半句（还没写完", active = true))
    }

    @Test
    fun `思考预览_进行中最近一段优先`() {
        assertEquals(
            "再看依赖树",
            ChatStore.thinkingPreview("先看 pubspec\n\n再看依赖树\n\n还在想", active = true)
        )
    }
}
