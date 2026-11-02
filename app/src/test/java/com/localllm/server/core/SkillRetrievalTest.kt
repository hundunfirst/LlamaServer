package com.localllm.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能正文级检索：名字命中要压过描述、描述压过正文，噪声词不能把无关技能拉上来。
 */
class SkillRetrievalTest {

    private fun skill(name: String, desc: String = "", prompt: String = "") =
        Skill(id = name, name = name, desc = desc, prompt = prompt)

    private val csv = skill(
        "CSV 清洗",
        desc = "按天去重、算增量、输出周统计",
        prompt = "先用 file_read 读表头，再用 awk 按日期去重，最后生成 xlsx。"
    )
    private val proxy = skill(
        "抓代理节点",
        desc = "从订阅链接解析节点",
        prompt = "curl 订阅地址，base64 解码后按行拆 ss:// 链接。"
    )
    private val all = listOf(csv, proxy)

    @Test
    fun `按描述命中挑出对应技能`() {
        val hits = SkillRetrieval.rank(all, "把这份数据按天去重一下，再算每天增量")
        assertEquals(1, hits.size)
        assertEquals("CSV 清洗", hits[0].skill.name)
    }

    @Test
    fun `用户直接点名时优先`() {
        val hits = SkillRetrieval.rank(all, "用抓代理节点那个技能，订阅地址在这")
        assertEquals("抓代理节点", hits.first().skill.name)
        assertTrue("点名要有额外加成", hits.first().score >= SkillRetrieval.NAME_IN_QUERY_BONUS)
    }

    @Test
    fun `无关问题不注入任何技能`() {
        assertTrue(SkillRetrieval.rank(all, "今天天气怎么样").isEmpty())
    }

    @Test
    fun `只有噪声词不命中`() {
        // 「帮我」「一下」这类词不该把技能拉上来
        assertTrue(SkillRetrieval.rank(all, "帮我一下").isEmpty())
    }

    @Test
    fun `正文里命中几个词也能召回_但分数低于名字命中`() {
        val byName = SkillRetrieval.rank(all, "CSV 清洗怎么做").first()
        val byBody = SkillRetrieval.rank(all, "awk 去重 生成 xlsx").first()
        assertEquals("CSV 清洗", byBody.skill.name)
        assertTrue("名字命中分数应更高：${byName.score} vs ${byBody.score}", byName.score > byBody.score)
    }

    @Test
    fun `最多返回两条`() {
        val hits = SkillRetrieval.rank(
            listOf(csv, proxy, skill("代理池", "代理 节点 订阅"), skill("订阅管理", "订阅 链接")),
            "代理 订阅 节点 链接",
            max = 2,
        )
        assertEquals(2, hits.size)
    }

    @Test
    fun `提示词里要带正文_并且按上限截断`() {
        val long = skill("长技能", "说明", "x".repeat(2000))
        val hits = SkillRetrieval.rank(listOf(long), "长技能怎么用")
        val text = SkillRetrieval.hintText(hits, bodyLimit = 100)
        assertTrue(text.contains("长技能"))
        assertTrue("正文应被截断", text.length < 300)
        assertTrue(text.contains("…"))
    }

    @Test
    fun `没有命中时返回空串而不是空标题`() {
        assertEquals("", SkillRetrieval.hintText(emptyList()))
    }

    @Test
    fun `关键词抽取_中文二字词与英文单词`() {
        val keys = SkillRetrieval.keywords("用 AWK 处理 CSV 表头")
        assertTrue(keys.contains("awk"))
        assertTrue(keys.contains("csv"))
        assertTrue(keys.contains("表头"))
    }
}
