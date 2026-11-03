package com.localllm.server.core

/**
 * 「这轮到底写没写出文件」的判据。
 *
 * 起因：一次真的生成了 `跑单记账_周统计.xlsx` 的轮次，被判定成「回答声称生成了文件，但本轮没有写文件调用」——
 * 因为那个判据只看消息上的 genFiles，而 make_xlsx 的产物从来没登记进去；用 run_bash 里
 * `python3 x.py` 写文件时同样漏。判据错了比没有更糟：会在时间线上插一条错误提示，还误导自驱。
 *
 * 所以拆成两层：先看回答有没有声称（[claimsFile]），再拿回答里**点名的文件名**去文件系统核对
 * （[mentionedNames] + 调用方的存在性检查）。名字存在就算真的产出了。
 */
object ReplyClaims {

    /**
     * 声称产出了文件的说法。刻意保守：只在明确说"生成/保存/写入/构建完成"时才算。
     * 不收「文件已」——「文件已读取」「文件已存在」都不是产出，误收会把只读操作也当成写了文件。
     */
    private val CLAIM = Regex(
        "已生成|生成完成|已保存|保存到|已写入|已创建|已写好|已输出|输出到|下载查看|构建完成|打包完成|编译完成|已构建|已打包"
    )

    /**
     * 常见的产出物后缀；带路径的（`/a/b/c.csv`、`sub/out.csv`）也整段抽出来，方便按工作目录解析。
     * **apk/aab/jar 必须在内**：模型常把 apk 编译到 app/build/outputs/…，漏了它产物卡就只剩源码文件。
     */
    private val NAME = Regex(
        """[\w\u4e00-\u9fa5./\-]+\.(?:apk|aab|jar|xlsx|xls|docx|doc|pdf|csv|tsv|md|txt|log|html|htm|json|""" +
            """py|js|ts|kt|java|sh|zip|png|jpe?g|webp|gif|mp4|mp3|bin|so|gguf|ipynb|ya?ml|xml|toml|ini)""",
        RegexOption.IGNORE_CASE
    )

    fun claimsFile(text: String): Boolean = CLAIM.containsMatchIn(text)

    /** 回答里点名的文件名（去重、按出现顺序、最多 [limit] 个） */
    fun mentionedNames(text: String, limit: Int = 3): List<String> {
        if (text.isBlank()) return emptyList()
        return NAME.findAll(text)
            .map { it.value.trim().trimEnd('.', ',', '、', '。') }
            .filter { it.contains('.') && !it.startsWith(".") }
            .distinct()
            .take(limit)
            .toList()
    }
}
