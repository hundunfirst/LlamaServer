package com.localllm.server.core

import org.junit.Assert.assertEquals
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

    // 「答后调用当幻觉丢掉」这条规则已删除（2026-10-25 用户决定）：
    // 真机上它把一句「让我重新从 CSV 里准确提取…生成正确的 Excel 文件。」之后的 file_read 丢掉了，
    // 而那正是当时唯一该做的动作。现在只按「工具本轮是否注入过」过滤，不再猜模型有没有答完。

    // 「只宣告下一步就催办」（stalledWithoutActing）与「两次同样报错判无进展」（noProgress）
    // 已删除（2026-10-30 用户决定）：模型写到哪停在哪，由用户决定是否继续。

    // ---- 自驱模式的收尾判定 ----

    @Test
    fun `有失败步骤就继续修`() {
        val v = ToolLoopPolicy.driveVerdict(
            wantsArtifact = true, hasDeliverable = true,
            failedTools = 2, sameStall = false, continued = 0, maxContinue = 5
        )
        assertEquals(true, v.shouldContinue)
    }

    @Test
    fun `该产出却什么都没产出就继续`() {
        val v = ToolLoopPolicy.driveVerdict(
            wantsArtifact = true, hasDeliverable = false,
            failedTools = 0, sameStall = false, continued = 0, maxContinue = 5
        )
        assertEquals(true, v.shouldContinue)
    }

    @Test
    fun `闲聊没有产出要求就正常收尾`() {
        val v = ToolLoopPolicy.driveVerdict(
            wantsArtifact = false, hasDeliverable = false,
            failedTools = 0, sameStall = false, continued = 0, maxContinue = 5
        )
        assertEquals(false, v.shouldContinue)
    }

    @Test
    fun `相同失败重复出现就停下而不是空转`() {
        val v = ToolLoopPolicy.driveVerdict(
            wantsArtifact = true, hasDeliverable = false,
            failedTools = 1, sameStall = true, continued = 2, maxContinue = 5
        )
        assertEquals(false, v.shouldContinue)
    }

    @Test
    fun `达到续跑上限就收手`() {
        val v = ToolLoopPolicy.driveVerdict(
            wantsArtifact = true, hasDeliverable = false,
            failedTools = 1, sameStall = false, continued = 5, maxContinue = 5
        )
        assertEquals(false, v.shouldContinue)
    }
}
