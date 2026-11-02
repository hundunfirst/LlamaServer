package com.localllm.server.core

/**
 * 技能检索：按当前这条用户输入，在技能的**名字 / 描述 / 正文**里打分，挑出最相关的。
 *
 * 为什么要有它：原来提示词里只列技能名字和一句话描述，**正文不进去**。
 * 结果「把流程存为技能」这个功能基本等于没用——模型看到名字也不知道里面写了什么，
 * 到头来还是自己重新摸一遍；技能越多，这段名单越是纯占上下文。
 *
 * 打分规则（名字 > 描述 > 正文）：名字最能代表用途；正文常常几百字，
 * 命中一两个词就把整篇文章当成「相关」会把噪声也一起塞进提示词。
 */
object SkillRetrieval {

    data class Hit(val skill: Skill, val score: Int)

    /** 中文里没有分词器，用二字词近似；英文按单词 */
    private val EN_WORD = Regex("[A-Za-z][A-Za-z0-9_+#.-]+")
    private val CJK_RUN = Regex("[\\u4e00-\\u9fa5]{2,}")

    /** 这些词几乎每句话都有，参与打分只会把无关技能拉上来 */
    private val STOPWORDS = setOf(
        "帮我", "一下", "这个", "那个", "什么", "怎么", "可以", "能不", "我要", "想要",
        "现在", "然后", "还是", "就是", "已经", "因为", "所以", "麻烦", "谢谢", "的话",
    )

    internal fun keywords(text: String): List<String> {
        val out = LinkedHashSet<String>()
        EN_WORD.findAll(text).forEach { m ->
            val w = m.value.lowercase()
            if (w.length >= 2) out.add(w)
        }
        CJK_RUN.findAll(text).forEach { m ->
            val s = m.value
            for (i in 0..s.length - 2) {
                val bi = s.substring(i, i + 2)
                if (bi !in STOPWORDS) out.add(bi)
            }
        }
        return out.toList()
    }

    /** 名字被整段命中（用户直接说「用 XXX」）时给的重分 */
    internal const val NAME_IN_QUERY_BONUS = 12

    /**
     * 选出相关技能：分数降序、最多 [max] 条、只留 score ≥ [minScore]。
     * [minScore] 默认 3 = 至少「描述里命中一个词」或「名字里命中一个词」以上，
     * 避免只凭正文里一个偶合的词就注入整段正文。
     */
    internal fun rank(
        skills: List<Skill>,
        query: String,
        max: Int = 2,
        minScore: Int = 3,
    ): List<Hit> {
        if (skills.isEmpty()) return emptyList()
        val keys = keywords(query)
        if (keys.isEmpty()) return emptyList()
        val q = query.lowercase()
        return skills.mapNotNull { s ->
            var score = 0
            val name = s.name.lowercase()
            val desc = s.desc.lowercase()
            val body = s.prompt.lowercase()
            for (k in keys) {
                if (name.contains(k)) score += 3
                if (desc.contains(k)) score += 2
                if (body.contains(k)) score += 1
            }
            if (name.isNotBlank() && q.contains(name)) score += NAME_IN_QUERY_BONUS
            if (score >= minScore) Hit(s, score) else null
        }.sortedByDescending { it.score }.take(max)
    }

    /**
     * 拼进提示词的文本：名字 + 正文（截断）。
     * [bodyLimit] 是每条技能正文的上限——技能正文可能是整段操作手册，
     * 两条 700 字已经够模型照着做了，再多就把这一轮的上下文吃干净。
     */
    internal fun hintText(hits: List<Hit>, bodyLimit: Int = 700): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("【相关技能（下面这些是以前做成功过的做法，能套用就直接套用）】")
        hits.forEach { h ->
            sb.append("\n\n■ ").append(h.skill.name)
            if (h.skill.desc.isNotBlank()) sb.append("（").append(h.skill.desc.take(40)).append("）")
            val body = h.skill.prompt.trim()
            if (body.isNotBlank()) {
                sb.append("\n").append(if (body.length > bodyLimit) body.take(bodyLimit) + "…" else body)
            }
        }
        return sb.toString()
    }
}
