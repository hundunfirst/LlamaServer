package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能链跳转的走向判定。
 * 这类逻辑一旦写错就是「该跳没跳 / 跳了不回来 / 死循环」，只靠真机看输出很难定位，所以单测兜住。
 */
class SkillChainFlowTest {

    private fun chain(vararg ids: String, branches: Map<Int, ChainBranch> = emptyMap()) =
        SkillChain("c1", "测试链", ids.toList(), null, branches)

    @Test
    fun `没有条件时按顺序走`() {
        val c = chain("a", "b", "c")
        assertEquals(1, SkillChainFlow.nextIndex(0, "随便什么输出", c))
        assertEquals(2, SkillChainFlow.nextIndex(1, "随便什么输出", c))
    }

    @Test
    fun `最后一步之后是结束`() {
        val c = chain("a", "b")
        assertEquals(SkillChainFlow.END, SkillChainFlow.nextIndex(1, "输出", c))
    }

    @Test
    fun `命中关键词时跳到指定步`() {
        val c = chain("a", "b", "c", branches = mapOf(0 to ChainBranch("没有可用数据", 2)))
        assertEquals(2, SkillChainFlow.nextIndex(0, "初步结论：没有可用数据，无法继续", c))
    }

    @Test
    fun `命中关键词且目标为空表示提前结束`() {
        val c = chain("a", "b", "c", branches = mapOf(1 to ChainBranch("已经完成", null)))
        assertEquals(SkillChainFlow.END, SkillChainFlow.nextIndex(1, "报告已经完成，无需加工", c))
    }

    @Test
    fun `没命中关键词时顺序继续`() {
        val c = chain("a", "b", "c", branches = mapOf(0 to ChainBranch("没有可用数据", 2)))
        assertEquals(1, SkillChainFlow.nextIndex(0, "数据齐全，继续下一步", c))
    }

    @Test
    fun `关键词匹配忽略大小写`() {
        val c = chain("a", "b", "c", branches = mapOf(0 to ChainBranch("ERROR", null)))
        assertEquals(SkillChainFlow.END, SkillChainFlow.nextIndex(0, "结果里出现 error：超时", c))
    }

    @Test
    fun `指向自己的条件被忽略而不是原地打转`() {
        val c = chain("a", "b", branches = mapOf(0 to ChainBranch("继续", 0)))
        assertEquals(1, SkillChainFlow.nextIndex(0, "继续", c))
    }

    @Test
    fun `空白关键词不算命中`() {
        val c = chain("a", "b", branches = mapOf(0 to ChainBranch("   ", null)))
        assertEquals(1, SkillChainFlow.nextIndex(0, "任何输出", c))
        assertFalse(ChainBranch("", null).matches("任何输出"))
    }

    @Test
    fun `往返跳转有执行次数上限`() {
        // A 命中跳 B、B 命中跳回 A：nextIndex 本身合法，靠上限收口
        val c = chain("a", "b", branches = mapOf(0 to ChainBranch("继续", 1), 1 to ChainBranch("继续", 0)))
        assertEquals(1, SkillChainFlow.nextIndex(0, "继续", c))
        assertEquals(0, SkillChainFlow.nextIndex(1, "继续", c))
        val limit = SkillChainFlow.visitLimit(c.skillIds.size)
        assertTrue("上限必须大于步数，否则正常流程都跑不完", limit > c.skillIds.size)
        assertTrue("上限不能太大，否则死循环要烧很久才停", limit <= 20)
    }

    @Test
    fun `matchedBranch 只在命中时返回`() {
        val c = chain("a", "b", branches = mapOf(0 to ChainBranch("命中", null)))
        assertNotNull(SkillChainFlow.matchedBranch(0, "这里命中了关键词", c))
        assertNull(SkillChainFlow.matchedBranch(0, "没有那个词", c))
        assertNull(SkillChainFlow.matchedBranch(1, "这里命中了关键词", c))
    }
}
