package com.localllm.server.core

/**
 * 技能链的走向判定。纯函数、不碰 Android —— 跳转逻辑容易写错，必须能单测。
 *
 * 两种分支并存：
 *   - 失败分支（[SkillChain.fallbackSkillId]）：这步被判失败 → 换备用技能重跑同一步；
 *   - 结果分支（[SkillChain.branches]）：这步跑成功但输出命中关键词 → 改道去别的步或直接结束。
 */
object SkillChainFlow {

    /** 整条链结束 */
    const val END = -1

    /**
     * 一条链最多执行多少步（含跳转往返）。
     * 「A 命中跳 B、B 命中跳回 A」是配置就能造出来的死循环，不设上限会一直生成到断电。
     */
    fun visitLimit(stepCount: Int): Int = stepCount * 3 + 4

    /** 不跳转时的下一步：顺序走，走到末尾就结束 */
    fun followingIndex(index: Int, stepCount: Int): Int =
        if (index + 1 < stepCount) index + 1 else END

    /**
     * 第 [index] 步执行完、拿到 [output] 之后，下一步走哪（[END] = 结束）。
     *
     * 指向自己的条件是无效配置（原地打转），按没配处理。
     */
    fun nextIndex(index: Int, output: String, chain: SkillChain): Int {
        val branch = chain.branches[index]
        if (branch != null && branch.matches(output)) {
            val target = branch.gotoStep
            if (target == null) return END
            if (target != index) return target
        }
        return followingIndex(index, chain.skillIds.size)
    }

    /** 命中的分支，供界面和日志描述「为什么跳了」 */
    fun matchedBranch(index: Int, output: String, chain: SkillChain): ChainBranch? =
        chain.branches[index]?.takeIf { it.matches(output) }
}
