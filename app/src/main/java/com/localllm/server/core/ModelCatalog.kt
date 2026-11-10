package com.localllm.server.core

/**
 * 模型来源的统一门面。
 *
 * 下载页只依赖这里，切换来源（魔搭 / hf-mirror）只改一处，不用在每个调用点写 if。
 *
 * 两个来源的能力**不对等**，这是接口本身的差异，不是实现偷懒：
 * - hf-mirror：支持关键词搜索（`/api/models?search=`）；
 * - 魔搭：公开的关键词搜索接口已不可用（实测全部 404），只能按 `owner/name` 直连，
 *   所以 [search] 在魔搭下返回 null，界面据此提示"直接填仓库名或粘模型页链接"。
 */
object ModelCatalog {

    enum class Source(val label: String, val host: String) {
        MODEL_SCOPE("魔搭 ModelScope", "modelscope.cn"),
        HF_MIRROR("hf-mirror.com", "hf-mirror.com");

        companion object {
            fun fromName(name: String?): Source =
                entries.firstOrNull { it.name == name } ?: MODEL_SCOPE
        }
    }

    data class Repo(
        val id: String,
        val downloads: Int = 0,
        val likes: Int = 0,
        val pipelineTag: String = "",
        val tags: List<String> = emptyList()
    )

    data class FileInfo(val path: String, val sizeBytes: Long)

    /** 关键词搜索；该来源不支持搜索时返回 null（与"搜到 0 条"区分开） */
    suspend fun search(source: Source, query: String, limit: Int = 20): List<Repo>? {
        if (source == Source.MODEL_SCOPE) return null
        return HfMirrorClient.searchRepos(query, limit).map {
            Repo(it.id, it.downloads, it.likes, it.pipelineTag, it.tags)
        }
    }

    /** 仓库内的 gguf 文件；读不到返回 null */
    suspend fun listFiles(source: Source, repo: String): List<FileInfo>? = when (source) {
        Source.MODEL_SCOPE -> ModelScopeClient.listGgufFiles(repo)?.map { FileInfo(it.path, it.sizeBytes) }
        Source.HF_MIRROR -> runCatching {
            HfMirrorClient.listGgufFiles(repo).map { FileInfo(it.path, it.sizeBytes) }
        }.getOrNull()
    }

    /** 下载直链 */
    fun resolveUrl(source: Source, repo: String, path: String): String = when (source) {
        Source.MODEL_SCOPE -> ModelScopeClient.resolveUrl(repo, path)
        Source.HF_MIRROR -> HfMirrorClient.resolveUrl(repo, path)
    }

    /** 仓库介绍（优先中文） */
    suspend fun readme(source: Source, repo: String, maxChars: Int = 1800): String = when (source) {
        Source.MODEL_SCOPE -> ModelScopeClient.fetchReadme(repo, maxChars)
        Source.HF_MIRROR -> runCatching { HfMirrorClient.fetchReadme(repo, maxChars) }.getOrDefault("")
    }

    /** 把用户输入规整成仓库 id（魔搭的支持整条模型页链接） */
    fun normalizeRepo(source: Source, input: String): String? = when (source) {
        Source.MODEL_SCOPE -> ModelScopeClient.normalizeRepo(input)
        Source.HF_MIRROR -> input.trim().trimEnd('/').takeIf {
            it.isNotBlank() && it.count { c -> c == '/' } <= 2
        }
    }

    /** 能力标签（文本/视觉/音频/视频）；魔搭没有 pipeline_tag，按仓库名与标签粗略判断 */
    fun capabilityLabels(source: Source, repo: Repo): List<String> {
        if (source == Source.HF_MIRROR) {
            return HfMirrorClient.capabilityLabels(
                HfMirrorClient.RepoInfo(repo.id, repo.downloads, repo.likes, repo.pipelineTag, repo.tags)
            )
        }
        val id = repo.id.lowercase()
        val out = mutableListOf("文本")
        if (listOf("vl", "vision", "ocr", "vlm", "image").any { id.contains(it) }) out += "视觉"
        if (listOf("audio", "asr", "tts", "speech", "voice").any { id.contains(it) }) out += "音频"
        if (id.contains("video")) out += "视频"
        return out
    }
}
