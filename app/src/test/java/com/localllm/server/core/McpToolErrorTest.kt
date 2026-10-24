package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结构化错误的解析与人话转换。
 *
 * 样本全部来自真机日志（MT 的 MCP 服务）：原样回传时模型读不出「重试没用」「缺的是工作区」，
 * 于是换着名字调同类工具，直到把失败上限撞满。
 */
class McpToolErrorTest {

    private val workspaceMissing = """
        {"ok":false,"data":null,"error":{"code":"WORKSPACE_NOT_FOUND","message":"Workspace not found.",
        "severity":"fatal","recoverable":false,"retrySameArguments":false,"argument":null,"badValue":null,
        "badValueType":null,"allowedValues":[],"example":null,"diagnostics":null},"nextActions":[]}
    """.trimIndent()

    private val badLimit = """
        {"ok":false,"data":null,"error":{"code":"INVALID_ARGUMENT","message":"Invalid limit",
        "severity":"warning","recoverable":true,"retrySameArguments":false,"argument":"limit",
        "badValue":"49152","badValueType":"number","allowedValues":["1","2000"],"example":"{}",
        "diagnostics":{"suggestedLocator":null}},"nextActions":[]}
    """.trimIndent()

    private val mtPeers = listOf(
        "mt_apk_list", "mt_apk_read_text", "mt_apk_workspace_open",
        "mt_apk_workspace_list", "mt_apk_session_create"
    )

    @Test
    fun `解析工作区缺失的结构化错误`() {
        val info = McpToolError.parse(workspaceMissing)!!
        assertEquals("WORKSPACE_NOT_FOUND", info.code)
        assertEquals("Workspace not found.", info.message)
        assertEquals(false, info.recoverable)
        assertEquals(false, info.retrySameArguments)
        assertTrue(info.allowedValues.isEmpty())
    }

    @Test
    fun `解析参数越界错误`() {
        val info = McpToolError.parse(badLimit)!!
        assertEquals("INVALID_ARGUMENT", info.code)
        assertEquals("limit", info.argument)
        assertEquals("49152", info.badValue)
        assertEquals(listOf("1", "2000"), info.allowedValues)
    }

    @Test
    fun `摘要要说清哪个参数越界以及允许范围`() {
        val s = McpToolError.summarize(McpToolError.parse(badLimit)!!, badLimit)
        assertTrue("要点名参数名：$s", s.contains("limit"))
        assertTrue("要给出实际传入的值：$s", s.contains("49152"))
        assertTrue("要给出允许范围：$s", s.contains("1–2000"))
        assertTrue("要提醒原参数重试没用：$s", s.contains("原参数重试"))
    }

    @Test
    fun `摘要要保留错误码和原因`() {
        val s = McpToolError.summarize(McpToolError.parse(workspaceMissing)!!, workspaceMissing)
        assertTrue(s.contains("WORKSPACE_NOT_FOUND"))
        assertTrue(s.contains("Workspace not found."))
    }

    @Test
    fun `缺工作区时点名该先调哪个工具`() {
        val info = McpToolError.parse(workspaceMissing)!!
        val advice = McpToolError.advice(info, "mt_apk_list", mtPeers)!!
        assertTrue("要让模型去开工作区：$advice", advice.contains("mt_apk_workspace_open"))
        assertFalse("不能建议它再调自己", advice.contains("mt_apk_list 或"))
    }

    @Test
    fun `参数越界时建议改参数而不是重建上下文`() {
        val info = McpToolError.parse(badLimit)!!
        val advice = McpToolError.advice(info, "mt_apk_read_text", mtPeers)!!
        assertTrue("要说改 limit：$advice", advice.contains("limit"))
        assertFalse("这不是上下文问题：$advice", advice.contains("上下文"))
    }

    @Test
    fun `无关的错误码不硬凑建议`() {
        val info = McpToolError.Info("SOMETHING_ODD", "boom", null, null, emptyList(), null, null)
        assertNull(McpToolError.advice(info, "mt_apk_list", mtPeers))
    }

    @Test
    fun `没有同类打开工具时给通用说明`() {
        val info = McpToolError.parse(workspaceMissing)!!
        val advice = McpToolError.advice(info, "mt_apk_list", listOf("mt_apk_list"))!!
        assertTrue(advice.contains("上下文"))
        assertFalse(advice.contains("先调用")) // 没有可点名的工具，不能编一个
    }

    @Test
    fun `非结构化文本解析不出来`() {
        assertNull(McpToolError.parse("工具挂了"))
        assertNull(McpToolError.parse("{}"))
    }

    @Test
    fun `错误 JSON 混在说明文字里也能解析`() {
        val info = McpToolError.parse("调用失败：$workspaceMissing")!!
        assertEquals("WORKSPACE_NOT_FOUND", info.code)
    }

    @Test
    fun `非数值枚举按列举展示`() {
        val info = McpToolError.Info(
            "INVALID_ARGUMENT", "bad mode", "mode", "fast", listOf("auto", "manual"), true, false
        )
        val s = McpToolError.summarize(info, "")
        assertTrue(s.contains("auto"))
        assertTrue(s.contains("manual"))
        assertFalse("两个枚举值不该被当成范围：$s", s.contains("–"))
    }
}
