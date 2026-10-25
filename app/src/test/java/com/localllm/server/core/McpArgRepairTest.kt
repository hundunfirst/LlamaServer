package com.localllm.server.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpArgRepairTest {

    private fun info(code: String) = McpToolError.Info(
        code = code,
        message = "",
        argument = null,
        badValue = null,
        allowedValues = emptyList(),
        recoverable = null,
        retrySameArguments = null
    )

    @Test
    fun `把路径当 workspaceId 时换成服务端分配过的 id`() {
        // 真机现场：模型拿 mt://current-apk 当 workspaceId，服务端只认 open 返回的 m5l2wls9
        val args = JSONObject().put("workspaceId", "mt://current-apk").put("limit", 50)
        val r = McpArgRepair.repair(info("WORKSPACE_NOT_FOUND"), args, "m5l2wls9")
        assertEquals("m5l2wls9", r!!.args.getString("workspaceId"))
        assertEquals(50, r.args.getInt("limit"))
        assertTrue(r.note.contains("m5l2wls9"))
    }

    @Test
    fun `没有已知 id 时不凭空改参数`() {
        val args = JSONObject().put("workspaceId", "mt://current-apk")
        assertNull(McpArgRepair.repair(info("WORKSPACE_NOT_FOUND"), args, null))
        assertNull(McpArgRepair.repair(info("WORKSPACE_NOT_FOUND"), args, ""))
    }

    @Test
    fun `id 本来就对得上就不重试`() {
        val args = JSONObject().put("workspaceId", "m5l2wls9")
        assertNull(McpArgRepair.repair(info("WORKSPACE_NOT_FOUND"), args, "m5l2wls9"))
    }

    @Test
    fun `模型没传 workspaceId 时不注入`() {
        // 猜工具签名会造成「参数不存在」这类更难查的错
        val args = JSONObject().put("limit", 10)
        assertNull(McpArgRepair.repair(info("WORKSPACE_NOT_FOUND"), args, "m5l2wls9"))
    }

    @Test
    fun `编辑会话失效时清空 editSessionId`() {
        val args = JSONObject()
            .put("workspaceId", "m5l2wls9")
            .put("editSessionId", "8c572dec")
        val r = McpArgRepair.repair(info("EDIT_SESSION_NOT_FOUND"), args, "m5l2wls9")
        assertEquals("", r!!.args.getString("editSessionId"))
        assertEquals("m5l2wls9", r.args.getString("workspaceId"))
        assertTrue(r.note.contains("8c572dec"))
    }

    @Test
    fun `editSessionId 本来为空就不重试`() {
        val args = JSONObject().put("workspaceId", "m5l2wls9").put("editSessionId", "")
        assertNull(McpArgRepair.repair(info("EDIT_SESSION_NOT_FOUND"), args, "m5l2wls9"))
    }

    @Test
    fun `参数越界这类错误不碰`() {
        val args = JSONObject().put("workspaceId", "mt://current-apk").put("limit", 100000)
        assertNull(McpArgRepair.repair(info("INVALID_ARGUMENT"), args, "m5l2wls9"))
        assertNull(McpArgRepair.repair(null, args, "m5l2wls9"))
    }

    @Test
    fun `从 open 结果里记住 workspaceId`() {
        val result = """{"ok":true,"data":{"workspaceId":"m5l2wls9","temporary":false,
            "apkFileName":"x.apk"},"error":null,"nextActions":[]}"""
        assertEquals("m5l2wls9", McpArgRepair.rememberedWorkspaceId(result))
    }

    @Test
    fun `结果里没有 workspaceId 时记不住`() {
        assertNull(McpArgRepair.rememberedWorkspaceId("""{"ok":true,"data":{"items":[]}}"""))
        assertNull(McpArgRepair.rememberedWorkspaceId("(工具无返回内容)"))
    }
}
