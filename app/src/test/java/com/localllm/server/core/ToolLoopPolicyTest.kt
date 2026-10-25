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

    @Test
    fun `清单没做完时答后调用要执行`() {
        // 真机现场：正文 169 字 + mt_apk_delete_target 被丢，任务正文写完就结束、清单停在 1/5
        assertTrue(ToolLoopPolicy.keepTrailingCalls(true, "已经打开工作区并读取了清单，接下来处理广告。"))
    }

    @Test
    fun `正文是进度说明时答后调用要执行`() {
        assertTrue(ToolLoopPolicy.keepTrailingCalls(false, "接下来我会删除广告相关文件："))
        assertTrue(ToolLoopPolicy.keepTrailingCalls(false, "现在读取 AndroidManifest.xml"))
        assertTrue(ToolLoopPolicy.keepTrailingCalls(false, "先看一下结构…"))
    }

    @Test
    fun `正文是结论时答后调用按幻觉丢掉`() {
        assertFalse(ToolLoopPolicy.keepTrailingCalls(false, "全部完成，已生成 3 个文件，共 320 行。"))
        assertFalse(ToolLoopPolicy.keepTrailingCalls(false, "没有找到广告配置，这个 APK 可能是干净的。"))
    }

    @Test
    fun `进度词只认结尾一段`() {
        // 正文中间顺口提到「接下来」，但结尾是明确结论 —— 不该被当成进度说明
        val long = "接下来我们看广告配置。" + "配置文件里没有相关字段。".repeat(12) + "结论：此包无广告。"
        assertFalse(ToolLoopPolicy.looksLikeProgressNote(long))
    }

    @Test
    fun `只宣告下一步且零工具调用时要催办`() {
        // 真机现场：正文 108 字「…现在执行删除操作：」，零工具调用，finish_reason=stop
        assertTrue(
            ToolLoopPolicy.stalledWithoutActing(
                unfinishedTodos = false,
                answerText = "## 去广告改造进行中（续）\n\n### 第一步：继续删除核心广告文件\n\n现在执行删除操作：",
                hasToolCalls = false,
                nudged = 0
            )
        )
        assertTrue(
            ToolLoopPolicy.stalledWithoutActing(false, "先列出当前 APK 的内部文件结构，找到所有广告相关文件：", false, 0)
        )
        assertTrue(ToolLoopPolicy.stalledWithoutActing(false, "先看一下结构…", false, 0))
    }

    @Test
    fun `清单没做完时哪怕结尾是结论也催`() {
        assertTrue(
            ToolLoopPolicy.stalledWithoutActing(true, "第一阶段已完成，共清理 3 个资源。", false, 0)
        )
    }

    @Test
    fun `真调了工具就不催`() {
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, "现在执行删除操作：", true, 0))
        assertFalse(ToolLoopPolicy.stalledWithoutActing(true, "现在执行删除操作：", true, 0))
    }

    @Test
    fun `结论式正文不催`() {
        assertFalse(
            ToolLoopPolicy.stalledWithoutActing(false, "全部完成，已生成 3 个文件，共 320 行。", false, 0)
        )
        // 正文里出现「现在/然后」不算宣告，否则正常回答会被答两遍
        assertFalse(
            ToolLoopPolicy.stalledWithoutActing(false, "现在两者都能用了，然后重启一次即可。", false, 0)
        )
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, "", false, 0))
    }

    @Test
    fun `催到上限就收尾`() {
        val text = "现在执行删除操作："
        assertTrue(ToolLoopPolicy.stalledWithoutActing(false, text, false, 1))
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, text, false, ToolLoopPolicy.STALL_NUDGE_LIMIT))
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, text, false, ToolLoopPolicy.STALL_NUDGE_LIMIT + 5))
    }
}
