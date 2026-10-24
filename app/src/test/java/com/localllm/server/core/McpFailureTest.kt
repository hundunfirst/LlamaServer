package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * MCP 失败归因。判错的代价很直接：把「服务没起来」当成参数问题，模型就会拿同样的参数反复重试，
 * 每次 8 秒连接超时 + 20 秒读取超时，一条消息能烧掉几分钟。
 */
class McpFailureTest {

    @Test
    fun `连接被拒绝归为不可达且不重试`() {
        val f = McpHttpClient.classify(ConnectException("Connection refused"))
        assertEquals(McpHttpClient.FailureKind.UNREACHABLE, f.kind)
        assertFalse("服务没起来，同样参数再试一次也没用", f.retryable)
    }

    @Test
    fun `域名解析失败归为不可达`() {
        assertEquals(
            McpHttpClient.FailureKind.UNREACHABLE,
            McpHttpClient.classify(UnknownHostException("no such host")).kind
        )
    }

    @Test
    fun `读取超时可以重试一次`() {
        val f = McpHttpClient.classify(SocketTimeoutException("timeout"))
        assertEquals(McpHttpClient.FailureKind.TIMEOUT, f.kind)
        assertTrue(f.retryable)
    }

    @Test
    fun `HTTP 状态码的归因`() {
        assertEquals(McpHttpClient.FailureKind.AUTH, McpHttpClient.httpFailure(401, "").kind)
        assertEquals(McpHttpClient.FailureKind.AUTH, McpHttpClient.httpFailure(403, "").kind)
        assertEquals(McpHttpClient.FailureKind.BAD_ARGS, McpHttpClient.httpFailure(400, "bad").kind)
        assertEquals(McpHttpClient.FailureKind.BAD_ARGS, McpHttpClient.httpFailure(422, "bad").kind)
        val five = McpHttpClient.httpFailure(500, "boom")
        assertEquals(McpHttpClient.FailureKind.SERVER, five.kind)
        assertTrue(five.retryable)
        assertFalse("鉴权失败重试没有意义", McpHttpClient.httpFailure(401, "").retryable)
        assertFalse("参数错重试没有意义", McpHttpClient.httpFailure(400, "bad").retryable)
    }

    @Test
    fun `会话过期的 404 带标记以便重新握手`() {
        val f = McpHttpClient.httpFailure(404, "session gone")
        assertEquals(404, f.httpCode)
    }

    @Test
    fun `给模型的说明要说清下一步做什么`() {
        val msg = McpHttpClient.explain("search_docs", ConnectException("Connection refused"))
        assertTrue("要点名是哪个工具", msg.contains("search_docs"))
        assertTrue("要说清是哪一类问题", msg.contains(McpHttpClient.FailureKind.UNREACHABLE.label))
        assertTrue("要给出下一步，而不是只抛异常原文", msg.contains("→"))
        assertTrue("不可达就该让它别再试", msg.contains("不会有不同结果"))
    }

    @Test
    fun `未知异常也有兜底说明`() {
        val msg = McpHttpClient.explain("x", IllegalArgumentException("weird"))
        assertTrue(msg.contains(McpHttpClient.FailureKind.UNKNOWN.label))
    }

    @Test
    fun `工具结构化错误会变成点名到具体工具的建议`() {
        val info = McpToolError.Info(
            code = "WORKSPACE_NOT_FOUND", message = "Workspace not found.",
            argument = null, badValue = null, allowedValues = emptyList(),
            recoverable = false, retrySameArguments = false
        )
        val e = McpHttpClient.McpException(
            McpHttpClient.FailureKind.TOOL,
            McpToolError.summarize(info, "Workspace not found."),
            toolError = info
        )
        val msg = McpHttpClient.explain(
            "mt_apk_list", e, listOf("mt_apk_list", "mt_apk_workspace_open")
        )
        assertTrue("要保留错误码：$msg", msg.contains("WORKSPACE_NOT_FOUND"))
        assertTrue("要点名先调哪个工具：$msg", msg.contains("mt_apk_workspace_open"))
        assertTrue("要有下一步段：$msg", msg.contains("→"))
    }
}
