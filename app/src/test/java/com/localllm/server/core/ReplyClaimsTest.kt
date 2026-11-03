package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这轮写没写出文件」的判据：既不能漏（真的产出了却说没写），也不能乱认（说保存了其实没有）。
 */
class ReplyClaimsTest {

    @Test
    fun `明确说生成了才算声称`() {
        assertTrue(ReplyClaims.claimsFile("已生成 跑单记账_周统计.xlsx，放在工作目录下"))
        assertTrue(ReplyClaims.claimsFile("结果已保存到 daily.csv"))
        assertFalse(ReplyClaims.claimsFile("这段代码的作用是读取 csv 并统计"))
        assertFalse(ReplyClaims.claimsFile(""))
    }

    @Test
    fun `从回答里抽出点名的文件名`() {
        val text = "已生成 `跑单记账_周统计.xlsx`，另外 daily_clean.csv 也写好了"
        assertEquals(listOf("跑单记账_周统计.xlsx", "daily_clean.csv"), ReplyClaims.mentionedNames(text))
    }

    @Test
    fun `带路径的文件名整段抽出来`() {
        val names = ReplyClaims.mentionedNames("保存到 /storage/emulated/0/LlamaWork/report.md")
        assertEquals(listOf("/storage/emulated/0/LlamaWork/report.md"), names)
    }

    @Test
    fun `去重且最多三个_顺序按出现`() {
        val text = "a.csv b.csv a.csv c.md d.txt e.json"
        assertEquals(listOf("a.csv", "b.csv", "c.md"), ReplyClaims.mentionedNames(text))
    }

    @Test
    fun `没有后缀的内容不会被当成文件名`() {
        // 代码里到处都是 "python3"、"csv.DictReader" 这类，别把它们当文件
        assertTrue(ReplyClaims.mentionedNames("用 python3 读 csv，再 print 出来").isEmpty())
    }

    @Test
    fun `标点结尾会被清掉`() {
        assertEquals(listOf("out.csv"), ReplyClaims.mentionedNames("已生成 out.csv、"))
    }
}
