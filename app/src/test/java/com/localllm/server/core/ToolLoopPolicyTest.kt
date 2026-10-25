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

    // 「答后调用当幻觉丢掉」这条规则已删除（2026-10-25 用户决定）：
    // 真机上它把一句「让我重新从 CSV 里准确提取…生成正确的 Excel 文件。」之后的 file_read 丢掉了，
    // 而那正是当时唯一该做的动作。现在只按「工具本轮是否注入过」过滤，不再猜模型有没有答完。

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
    fun `markdown 加粗的步骤标题也算宣告`() {
        // 真机现场 2026-10-25：第 4 轮删完 ad_charge_ring.json 后只写了这一行就停，
        // 去掉 `**` 后末尾是「内容」不是冒号，早期判据漏判
        assertTrue(
            ToolLoopPolicy.stalledWithoutActing(
                false, "### **第一步：列出当前工作空间内容**", false, 0
            )
        )
        assertTrue(
            ToolLoopPolicy.stalledWithoutActing(
                false,
                "## 🚀 番茄小说去广告改造进行中（续）\n\n服务已恢复！现在开始继续完成剩余的去广告任务：\n\n" +
                        "### **第一步：列出当前工作空间内容**",
                false,
                0
            )
        )
        assertTrue(ToolLoopPolicy.stalledWithoutActing(false, "**第二步：清理 Manifest**", false, 0))
    }

    @Test
    fun `结尾在向用户提问时不催`() {
        // 真机现场：模型问「是否需要我立即尝试重新连接服务？」——这就是正常收尾，
        // 催了会答非所问（问号还被 markdown 加粗包着）
        assertFalse(
            ToolLoopPolicy.stalledWithoutActing(
                false, "是否要我继续？\n\n**是否需要我立即尝试重新连接服务？**", false, 0
            )
        )
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, "要不要接着删广告?", false, 0))
    }

    @Test
    fun `长篇正文里出现步骤词不催`() {
        // 给用户的操作手册常含「第一步」，但它超过宣告的长度，是正文
        val guide = "手动去广告步骤如下。" + "在解压目录里逐个核对资源表，确认没有被引用后再删除。".repeat(14) +
                "第一步：删除 assets/ad_charge_ring.json。"
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, guide, false, 0))
    }

    @Test
    fun `催到上限就收尾`() {
        val text = "现在执行删除操作："
        assertTrue(ToolLoopPolicy.stalledWithoutActing(false, text, false, 1))
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, text, false, ToolLoopPolicy.STALL_NUDGE_LIMIT))
        assertFalse(ToolLoopPolicy.stalledWithoutActing(false, text, false, ToolLoopPolicy.STALL_NUDGE_LIMIT + 5))
    }
}
