package com.localllm.server.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 从 GGUF 头元数据判断一个文件是什么东西。
 *
 * 为什么不用文件名：`MTP` 既可能是"主模型自带 MTP 头"的能力标签，
 * 也可能是独立的 MTP 侧车文件（Bonsai 仓库里两种都有）。同理 `mmproj`
 * 只是命名习惯。真正可靠的判据是 `general.type` 与 `general.architecture`：
 * - type=model + arch=qwen35/llama…   → 主模型
 * - arch=clip（或 type=mmproj）        → 视觉投影器
 * - arch=dflash/dspark/eagle3/mtp      → 投机解码草稿/侧车
 * - type=adapter / imatrix             → LoRA 适配器 / 重要性矩阵
 */
enum class GgufKind { MAIN, MMPROJ, DRAFT, ADAPTER, IMATRIX, UNKNOWN, NOT_GGUF }

data class GgufInfo(
    val kind: GgufKind,
    val architecture: String = "",
    val name: String = "",
    val sizeLabel: String = "",
    val nTensors: Long = 0,
    /** 训练上下文长度（<arch>.context_length）；0 = 元数据里没有 */
    val ctxTrain: Int = 0,
    /** 词表大小（tokenizer.ggml.tokens 的条数）；0 = 没读到 */
    val vocabSize: Long = 0,
    /** 专家数（<arch>.expert_count）；0 = 非 MoE 或没这个键 */
    val expertCount: Int = 0
) {
    /** 界面提示文案；主模型/未知返回 null（不需要打扰用户） */
    val warning: String?
        get() = when (kind) {
            GgufKind.DRAFT ->
                "不是主模型：这是投机解码的草稿/侧车文件（architecture=$architecture）"
            GgufKind.MMPROJ ->
                "不是主模型：这是多模态投影器（mmproj），应在模型页选作「多模态投影」"
            GgufKind.ADAPTER ->
                "不是完整模型：这是 LoRA 适配器，需要配主模型使用"
            GgufKind.IMATRIX ->
                "不是模型：这是量化用的重要性矩阵"
            GgufKind.NOT_GGUF -> "不是 GGUF 文件"
            else -> null
        }

    val isLoadableAsModel: Boolean get() = kind == GgufKind.MAIN || kind == GgufKind.UNKNOWN
}

/** 模型卡片上的标签：全部来自 GGUF 自述，不看文件名 */
object ModelTags {
    data class Tag(val label: String, val detail: String)

    // 注意：这张表对应 ggml_type 枚举（张量类型），用来看某层张量是什么格式。
    // 它**不是** general.file_type 的枚举——两套前 10 项碰巧相同，第 11 项起完全不同。
    private val QUANT = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1", 8 to "Q8_0",
        9 to "Q8_1", 10 to "Q2_K", 11 to "Q3_K", 12 to "Q4_K", 13 to "Q5_K", 14 to "Q6_K", 15 to "Q8_K",
        16 to "IQ2_XXS", 17 to "IQ2_XS", 18 to "IQ3_XXS", 19 to "IQ1_S", 20 to "IQ4_NL",
        21 to "IQ3_S", 22 to "IQ2_S", 23 to "IQ4_XS", 26 to "I32", 28 to "F64", 29 to "IQ1_M",
        30 to "BF16", 34 to "TQ1_0", 35 to "TQ2_0", 39 to "MXFP4", 40 to "NVFP4", 41 to "Q1_0",
        42 to "Q2_0", 142 to "PQ2_0", 143 to "PTQ1_0"
    )

    // general.file_type 用的是 llama_ftype 枚举，比张量类型更细（能分 Q4_K_S / Q4_K_M）
    private val FTYPE = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 6 to "Q5_0", 7 to "Q5_1", 8 to "Q8_0",
        9 to "Q8_1", 10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L",
        14 to "Q4_K_S", 15 to "Q4_K_M", 16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K",
        19 to "Q2_K_S", 20 to "IQ3_XS", 21 to "IQ3_XXS", 22 to "IQ1_S", 23 to "IQ4_NL",
        24 to "IQ3_S", 25 to "IQ3_M", 26 to "IQ2_S", 27 to "IQ2_M", 28 to "IQ4_XS",
        29 to "IQ1_M", 30 to "BF16"
    )

    /** 全量标签（详情弹窗用） */
    fun of(info: GgufInfo?, deep: GgufInspector.GgufDeep?): List<Tag> {
        val out = mutableListOf<Tag>()
        if (info == null) return out
        if (info.architecture.isNotBlank()) {
            out += Tag("架构 ${info.architecture}", "general.architecture = ${info.architecture}")
        }
        if (info.sizeLabel.isNotBlank()) {
            out += Tag("${info.sizeLabel} 参数", "general.size_label = ${info.sizeLabel}")
        }
        if (info.ctxTrain >= 1024) {
            out += Tag(
                "上下文 ${kLabel(info.ctxTrain)}",
                "训练上下文 ${info.ctxTrain}（<arch>.context_length）"
            )
        }
        deep?.let { d ->
            // 官方量化看 general.file_type。不能拿 token_embd 的张量类型当整体量化：
            // Q4_K_M 量化时 embedding 常被保留成 Q6_K，会导致整卡显示成 Q6（实测踩过）。
            val official = FTYPE[d.fileType]
            when {
                official != null -> out += Tag(
                    "量化 $official",
                    "general.file_type = ${d.fileType}（$official）"
                )
                d.embedType >= 0 -> {
                    val q = QUANT[d.embedType] ?: "type${d.embedType}"
                    val why = if (d.fileType < 0) "general.file_type 缺失"
                    else "general.file_type = ${d.fileType} 不在标准表内（三元包等自定义类型）"
                    out += Tag("量化 $q", "$why，回退用 token_embd.weight 张量类型 = $q")
                }
            }
            if (d.hasMtp) {
                out += Tag("MTP", "自带 <arch>.nextn_predict_layers > 0，可用 --spec-type draft-mtp")
            }
            if (d.hasTools) {
                out += Tag("工具调用", "chat_template 里声明了工具调用（只说明模板支持，不代表调得准）")
            }
            when {
                d.hasReasoningEffort -> out += Tag(
                    "思考等级",
                    "chat_template 认 reasoning_effort（low/medium/high），设置里可选等级"
                )
                d.hasThinkingBudget -> out += Tag(
                    "思考预算",
                    "chat_template 认 thinking_budget（思考 token 预算）"
                )
                d.hasEnableThinking -> out += Tag(
                    "思考可开关",
                    "chat_template 认 enable_thinking，设置里的思考开关对它有效"
                )
                d.hasThinking -> out += Tag("思考", "chat_template 里有 thinking 声明，但没认出开关/等级参数")
            }
        }
        if (info.expertCount > 0) {
            out += Tag("MoE ${info.expertCount} 专家", "<arch>.expert_count = ${info.expertCount}")
        }
        if (info.vocabSize > 0) {
            out += Tag("词表 ${kLabel(info.vocabSize.toInt())}", "tokenizer.ggml.tokens 共 ${info.vocabSize} 条")
        }
        return out
    }

    /** 卡片上只挂一个：优先能力标签，其次量化/上下文 */
    fun headline(info: GgufInfo?, deep: GgufInspector.GgufDeep?): Tag? {
        val all = of(info, deep)
        // 越靠前越有区分度：工具调用几乎人人都有，放后面，免得每张卡长得一样
        val priority = listOf("MTP", "MoE", "量化", "工具调用", "思考", "上下文")
        for (p in priority) {
            all.firstOrNull { it.label.startsWith(p) }?.let { return it }
        }
        return all.firstOrNull()
    }

    /** 128000 显示 128K、262144 显示 256K——按模型页宣传的写法来，别把 128K 写成 125K */
    private fun kLabel(n: Int): String = when {
        n % 1_000_000 == 0 -> "${n / 1_000_000}M"
        n % 1000 == 0 -> "${n / 1000}K"
        n >= 1024 -> "${n / 1024}K"
        else -> n.toString()
    }
}

object GgufInspector {
    /** general.* 元数据都在文件最前面，读 512KB 足够；碰到词表那种大数组就提前收工 */
    private const val HEAD_BYTES = 512 * 1024
    private val cache = HashMap<String, GgufInfo>()

    suspend fun inspect(file: File): GgufInfo = withContext(Dispatchers.IO) { inspectBlocking(file) }

    fun inspectBlocking(file: File): GgufInfo {
        val key = file.absolutePath + "@" + file.length() + "@" + file.lastModified()
        cache[key]?.let { return it }
        val info = runCatching { parse(file) }.getOrDefault(GgufInfo(GgufKind.UNKNOWN))
        if (cache.size > 128) cache.clear()
        cache[key] = info
        return info
    }

    /**
     * 深扫结论：这些键都排在词表那几个大数组之后（本机实测 KV 段约 10.5MB），
     * 512KB 的头读不到，所以单独整段扫一次；结果按「路径+大小+mtime」缓存。
     */
    data class GgufDeep(
        /** 自带 MTP（<arch>.nextn_predict_layers > 0），决定能否 --spec-type draft-mtp */
        val hasMtp: Boolean = false,
        /** general.file_type（llama_ftype 枚举）：模型整体量化的官方声明；-1 = 没读到 */
        val fileType: Int = -1,
        /** token_embd.weight 的张量类型，仅在 fileType 缺失/非标准时兜底；-1 = 没读到 */
        val embedType: Int = -1,
        /** chat_template 里声明了工具调用 */
        val hasTools: Boolean = false,
        /** chat_template 里出现思考相关声明（不细分时用它） */
        val hasThinking: Boolean = false,
        /** 模板认 enable_thinking（开关式：Qwen3 系） */
        val hasEnableThinking: Boolean = false,
        /** 模板认 thinking_budget（预算式） */
        val hasThinkingBudget: Boolean = false,
        /** 模板认 reasoning_effort（等级式：low/medium/high） */
        val hasReasoningEffort: Boolean = false,
        /**
         * PrismML 打包（头部带 prism.hadamard.* 元数据）：三元量化 + 哈达玛变换，
         * 上游 llama.cpp 读不懂。实测这类包的 general.file_type 是缺失的，
         * 所以判据只能用元数据指纹，不能靠量化类型。
         */
        val hasPrismPack: Boolean = false
    )

    private const val DEEP_SCAN_BYTES = 24 * 1024 * 1024
    private val deepCache = HashMap<String, GgufDeep>()

    fun deepInfo(file: File): GgufDeep {
        val key = file.absolutePath + "@" + file.length() + "@" + file.lastModified()
        deepCache[key]?.let { return it }
        val v = scanDeep(file)
        if (deepCache.size > 64) deepCache.clear()
        deepCache[key] = v
        return v
    }

    /** 只要 MTP 判断时用这个（启动前会调） */
    fun hasMtpHead(file: File): Boolean = deepInfo(file).hasMtp

    /**
     * 模型是否是 PrismML 打包（头部带 prism.hadamard.* 元数据）。
     * 这种包只有 prism 引擎认得；上游 llama.cpp 会在加载阶段失败、报错还看不出原因。
     * 判据用元数据指纹而不是量化类型——实测这类包的 general.file_type 是缺失的。
     */
    fun isPrismPacked(file: File): Boolean = deepInfo(file).hasPrismPack

    private val NEXTN = ".nextn_predict_layers".toByteArray(Charsets.US_ASCII)
    private val FILETYPE = "general.file_type".toByteArray(Charsets.US_ASCII)
    private val EMBD = "token_embd.weight".toByteArray(Charsets.US_ASCII)
    private val TPL = "tokenizer.chat_template".toByteArray(Charsets.US_ASCII)

    /** PrismML 打包指纹：这些包必带 prism.hadamard.* 元数据 */
    private val PRISM_MARK = "prism.hadamard.".toByteArray(Charsets.US_ASCII)

    private fun scanDeep(file: File): GgufDeep {
        if (!file.isFile || file.length() < 32) return GgufDeep()
        val head = RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(minOf(DEEP_SCAN_BYTES.toLong(), raf.length()).toInt())
            raf.readFully(buf)
            buf
        }
        if (head.size < 8 || String(head, 0, 4, Charsets.US_ASCII) != "GGUF") return GgufDeep()

        // 不在字节流里逐条走 KV（词表几十万条字符串数组容易踩类型对齐坑），
        // 直接搜键名/张量名，命中位置后面紧跟类型与取值
        var hasMtp = false
        var from = 0
        while (true) {
            val at = indexOf(head, NEXTN, from)
            if (at < 0) break
            val typeAt = at + NEXTN.size
            if (typeAt + 8 <= head.size) {
                val t = readU32(head, typeAt)
                val v = readU32(head, typeAt + 4)          // 层数都是小整数，取低 32 位足够
                if ((t in 4L..5L || t == 10L || t == 11L) && v > 0) hasMtp = true
            }
            from = at + 1
        }

        // 官方量化标签：general.file_type。键名后面紧跟 u32 值类型，再 u32 取值。
        var fileType = -1
        from = 0
        while (true) {
            val at = indexOf(head, FILETYPE, from)
            if (at < 0) break
            val p = at + FILETYPE.size
            if (p + 8 <= head.size) {
                val vt = readU32(head, p)
                if (vt in 0L..12L) {
                    val v = readU32(head, p + 4)
                    if (v in 0L..255L) {
                        fileType = v.toInt()
                        break
                    }
                }
            }
            from = at + 1
        }

        // 量化：张量信息是 [名字][n_dims][dims…][类型][offset]，
        // 元数据里也有同名串（如 prism.hadamard.inverse_weight_names），靠维度数/类型范围排除
        var embedType = -1
        from = 0
        while (true) {
            val at = indexOf(head, EMBD, from)
            if (at < 0) break
            val p = at + EMBD.size
            if (p + 4 <= head.size) {
                val nd = readU32(head, p)
                if (nd in 1L..4L) {
                    val typeAt = p + 4 + (nd * 8).toInt()
                    if (typeAt + 4 <= head.size) {
                        val t = readU32(head, typeAt)
                        // 上游类型 0..43，PrismML 的三元类型在 142 起
                        if (t in 0L..43L || t in 142L..150L) {
                            embedType = t.toInt()
                            break
                        }
                    }
                }
            }
            from = at + 1
        }

        // 模板：字符串值是 [u32 类型][u64 长度][内容]
        var hasTools = false
        var hasThinking = false
        var hasEnableThinking = false
        var hasThinkingBudget = false
        var hasReasoningEffort = false
        val tplAt = indexOf(head, TPL, 0)
        if (tplAt >= 0 && tplAt + TPL.size + 12 <= head.size) {
            val p = tplAt + TPL.size
            if (readU32(head, p) == 8L) {
                val len = readU32(head, p + 4).toInt()
                if (len > 0 && p + 12 + len <= head.size) {
                    val tpl = String(head, p + 12, len, Charsets.UTF_8)
                    hasTools = tpl.contains("tool_call") || tpl.contains("\"tools\"")
                    hasThinking = tpl.contains("thinking")
                    hasEnableThinking = tpl.contains("enable_thinking")
                    hasThinkingBudget = tpl.contains("thinking_budget")
                    hasReasoningEffort = tpl.contains("reasoning_effort")
                }
            }
        }
        val hasPrismPack = indexOf(head, PRISM_MARK, 0) >= 0
        return GgufDeep(
            hasMtp, fileType, embedType, hasTools, hasThinking,
            hasEnableThinking, hasThinkingBudget, hasReasoningEffort, hasPrismPack
        )
    }

    private fun indexOf(b: ByteArray, needle: ByteArray, from: Int): Int {
        val last = b.size - needle.size
        var i = from
        while (i <= last) {
            if (b[i] == needle[0]) {
                var j = 1
                while (j < needle.size && b[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun readU32(b: ByteArray, at: Int): Long =
        if (at + 4 > b.size) -1L
        else (b[at].toLong() and 0xFF) or
                ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or
                ((b[at + 3].toLong() and 0xFF) shl 24)

    private class Buf(private val b: ByteArray) {
        var pos = 0
        fun remaining() = b.size - pos
        fun u32(): Long {
            val v = (b[pos].toLong() and 0xFF) or
                    ((b[pos + 1].toLong() and 0xFF) shl 8) or
                    ((b[pos + 2].toLong() and 0xFF) shl 16) or
                    ((b[pos + 3].toLong() and 0xFF) shl 24)
            pos += 4
            return v
        }
        fun u64(): Long {
            val lo = u32()
            val hi = u32()
            return lo or (hi shl 32)
        }
        fun str(): String {
            val n = u64().toInt()
            val s = String(b, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }
        fun take(n: Int): ByteArray {
            val v = b.copyOfRange(pos, pos + n)
            pos += n
            return v
        }
        fun skip(n: Long) {
            pos += n.toInt()
        }
    }

    private fun parse(file: File): GgufInfo {
        if (!file.isFile || file.length() < 32) return GgufInfo(GgufKind.NOT_GGUF)
        val head = RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(minOf(HEAD_BYTES.toLong(), raf.length()).toInt())
            raf.readFully(buf)
            buf
        }
        if (head.size < 8 || String(head, 0, 4, Charsets.US_ASCII) != "GGUF") {
            return GgufInfo(GgufKind.NOT_GGUF)
        }
        val b = Buf(head)
        b.pos = 4
        b.u32()                    // version
        val nTensors = b.u64()
        val nKv = b.u64()

        var type = ""
        var arch = ""
        var name = ""
        var sizeLabel = ""
        var ctxTrain = 0
        var vocabSize = 0L
        var expertCount = 0
        var seen = 0
        try {
            for (i in 0 until nKv) {
                val key = b.str()
                val t = b.u32().toInt()
                when (t) {
                    8 -> {                                  // string
                        val v = b.str()
                        when (key) {
                            "general.type" -> type = v
                            "general.architecture" -> arch = v
                            "general.name" -> name = v
                            "general.size_label" -> sizeLabel = v
                        }
                    }
                    9 -> {                                  // array
                        val et = b.u32().toInt()
                        val n = b.u64()
                        if (key == "tokenizer.ggml.tokens") vocabSize = n
                        if (et != 8 && et != 9) {
                            b.skip(n * scalarSize(et))
                        } else {
                            // 字符串数组（词表等）可能几十万条：拿到 arch 和词表大小就够了，别读完
                            if (arch.isNotEmpty() || n > 4096) {
                                return classify(
                                    type, arch, name, sizeLabel, nTensors, ctxTrain, vocabSize, expertCount
                                )
                            }
                            repeat(n.toInt()) { b.str() }
                        }
                    }
                    else -> {
                        // 数值键：上下文长度 / 专家数（MoE）
                        if (key.endsWith(".context_length")) {
                            ctxTrain = readNum(b, t).toInt()
                        } else if (key.endsWith(".expert_count")) {
                            expertCount = readNum(b, t).toInt()
                        } else {
                            b.skip(scalarSize(t))
                        }
                    }
                }
                seen++
                // 不再拿到 arch 就停：上下文长度、词表大小排在后面几个键里
                if (b.remaining() < 64) break
            }
        } catch (_: Exception) {
            // 头被截断/格式异常：用已解析到的部分判断
        }
        if (seen == 0) return GgufInfo(GgufKind.UNKNOWN)
        return classify(type, arch, name, sizeLabel, nTensors, ctxTrain, vocabSize, expertCount)
    }

    private fun scalarSize(t: Int): Long = when (t) {
        0, 1, 7 -> 1L
        2, 3 -> 2L
        4, 5, 6 -> 4L
        10, 11, 12 -> 8L
        else -> 0L
    }

    /** 读一个数值型 KV（u8…f64 全覆盖，浮点型只消费字节不取值） */
    private fun readNum(b: Buf, t: Int): Long = when (t) {
        0, 1, 7 -> b.take(1)[0].toLong() and 0xFF
        2, 3 -> {
            val v = b.take(2)
            ((v[1].toLong() and 0xFF) shl 8) or (v[0].toLong() and 0xFF)
        }
        4, 5 -> {
            val v = b.take(4)
            ((v[3].toLong() and 0xFF) shl 24) or ((v[2].toLong() and 0xFF) shl 16) or
                    ((v[1].toLong() and 0xFF) shl 8) or (v[0].toLong() and 0xFF)
        }
        10, 11 -> {
            val v = b.take(8)
            var r = 0L
            for (i in 7 downTo 0) r = (r shl 8) or (v[i].toLong() and 0xFF)
            r
        }
        6 -> { b.take(4); 0L }
        12 -> { b.take(8); 0L }
        else -> 0L
    }

    private fun classify(type: String, arch: String, name: String, sizeLabel: String, nTensors: Long,
                         ctxTrain: Int = 0, vocabSize: Long = 0, expertCount: Int = 0): GgufInfo {
        val t = type.lowercase()
        val a = arch.lowercase()
        val kind = when {
            t == "mmproj" || a == "clip" || a == "mmproj" -> GgufKind.MMPROJ
            t == "adapter" || t == "lora" -> GgufKind.ADAPTER
            t == "imatrix" -> GgufKind.IMATRIX
            a in setOf("dflash", "dspark", "eagle3", "draft", "mtp") -> GgufKind.DRAFT
            a.isNotEmpty() -> GgufKind.MAIN
            else -> GgufKind.UNKNOWN
        }
        return GgufInfo(kind, arch, name, sizeLabel, nTensors, ctxTrain, vocabSize, expertCount)
    }
}
