package com.localllm.server.core

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS = "llama_server_settings"

    private lateinit var prefs: SharedPreferences

    /** 供应商下的模型条目：id=请求用的上游模型名；label/ctx/out/modalities 为本地展示元数据 */
    data class CloudModel(
        val id: String,
        val label: String = "",
        val ctx: Int = 32768,
        val out: Int = 8192,
        val modalities: List<String> = listOf("文本")
    )

    /** 云端渠道预设：一组可切换的 OpenAI 兼容 API 配置（老配置缺 id/models 时自动迁移） */
    data class CloudPreset(
        val id: String = "",
        val name: String,
        val url: String,
        val key: String,
        val model: String,
        val protocol: String = "openai",
        val models: List<CloudModel> = emptyList()
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 迁移：老的单渠道配置 → 预设列表（只跑一次）
        if (cloudPresetsJson == "[]" && chatCloudUrl.isNotBlank()) {
            saveCloudPresets(
                listOf(CloudPreset(name = "当前配置", url = chatCloudUrl, key = chatCloudKey, model = chatCloudModel))
            )
        }
    }

    /** 云端渠道预设列表 JSON：[{"name","url","key","model"}] */
    var cloudPresetsJson: String
        get() = prefs.getString("cloud_presets", "[]") ?: "[]"
        set(value) = prefs.edit().putString("cloud_presets", value).apply()

    fun loadCloudPresets(): List<CloudPreset> = runCatching {
        val arr = org.json.JSONArray(cloudPresetsJson)
        val used = mutableSetOf<String>()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            // 老数据无 id → 生成稳定 id；撞名兜底
            var id = o.optString("id").ifBlank {
                "sup${i + 1}_" + (name.ifBlank { "p$i" }).hashCode().toUInt().toString(16).take(6)
            }
            while (!used.add(id)) id = "${id}_$i"

            val models = mutableListOf<CloudModel>()
            o.optJSONArray("models")?.let { ma ->
                for (j in 0 until ma.length()) {
                    val mo = ma.optJSONObject(j) ?: continue
                    val mid = mo.optString("id").takeIf { it.isNotBlank() } ?: continue
                    models += CloudModel(
                        id = mid,
                        label = mo.optString("label"),
                        ctx = mo.optInt("ctx", 32768),
                        out = mo.optInt("out", 8192),
                        modalities = mo.optJSONArray("modalities")?.let { pa ->
                            (0 until pa.length()).mapNotNull { k -> pa.optString(k).takeIf { s -> s.isNotBlank() } }
                        }?.takeIf { it.isNotEmpty() } ?: listOf("文本")
                    )
                }
            }
            // 老配置：只有单个 model 字段 → 补成列表项
            val legacyModel = o.optString("model")
            if (legacyModel.isNotBlank() && models.none { it.id == legacyModel }) {
                models += CloudModel(id = legacyModel)
            }
            CloudPreset(
                id = id,
                name = name,
                url = o.optString("url"),
                key = o.optString("key"),
                model = legacyModel,
                protocol = o.optString("protocol").ifBlank { "openai" },
                models = models
            )
        }
    }.getOrDefault(emptyList())

    fun saveCloudPresets(list: List<CloudPreset>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(
                org.json.JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("url", p.url)
                    .put("key", p.key)
                    .put("model", p.model)
                    .put("protocol", p.protocol)
                    .put(
                        "models",
                        org.json.JSONArray().also { ma ->
                            p.models.forEach { m ->
                                ma.put(
                                    org.json.JSONObject()
                                        .put("id", m.id)
                                        .put("label", m.label)
                                        .put("ctx", m.ctx)
                                        .put("out", m.out)
                                        .put("modalities", org.json.JSONArray().also { pa -> m.modalities.forEach(pa::put) })
                                )
                            }
                        }
                    )
            )
        }
        cloudPresetsJson = arr.toString()
    }

    /** 激活某个预设：写入当前渠道字段（即改即存，聊天/体检/拉取都读这里） */
    fun activateCloudPreset(p: CloudPreset) {
        chatCloudEnabled = true
        chatCloudUrl = p.url.trim()
        chatCloudKey = p.key.trim()
        chatCloudModel = p.model.trim()
    }

    var port: Int
        get() = prefs.getInt("port", 8080)
        set(value) = prefs.edit().putInt("port", value).apply()

    var ctxSize: Int
        // 默认 8192：4096 装不下「一个完整的单文件小游戏」，输出会被硬截断在半行代码上
        get() = prefs.getInt("ctx_size", 8192)
        set(value) = prefs.edit().putInt("ctx_size", value).apply()

    var threads: Int
        get() = prefs.getInt("threads", 0)
        set(value) = prefs.edit().putInt("threads", value).apply()

    var ngl: Int
        get() = prefs.getInt("ngl", 0)
        set(value) = prefs.edit().putInt("ngl", value).apply()

    var lanEnabled: Boolean
        get() = prefs.getBoolean("lan_enabled", false)
        set(value) = prefs.edit().putBoolean("lan_enabled", value).apply()

    var apiToken: String
        get() = prefs.getString("api_token", "") ?: ""
        set(value) = prefs.edit().putString("api_token", value).apply()

    /** 令牌开关：默认关闭——局域网模式默认无鉴权，显式打开才校验 */
    var apiTokenEnabled: Boolean
        get() = prefs.getBoolean("api_token_enabled", false)
        set(value) = prefs.edit().putBoolean("api_token_enabled", value).apply()

    var cacheTypeK: String
        get() = prefs.getString("cache_k", "") ?: ""
        set(value) = prefs.edit().putString("cache_k", value).apply()

    var cacheTypeV: String
        get() = prefs.getString("cache_v", "") ?: ""
        set(value) = prefs.edit().putString("cache_v", value).apply()

    /** 模型下载免责声明是否已确认：确认过就不再弹窗（下载页仍常驻展示该声明） */
    var modelDisclaimerAccepted: Boolean
        get() = prefs.getBoolean("model_disclaimer_accepted", false)
        set(value) = prefs.edit().putBoolean("model_disclaimer_accepted", value).apply()

    /**
     * 是否用 PrismML fork 引擎（纯 CPU 模式下的"特别版"）：
     * 支持 PQ2_0 / PTQ1_0 打包的 Bonsai 模型，且 PQ2_0 kernel 在 ARM 上是优化过的。
     * ngl>0（GPU）时忽略此开关，仍用上游 opencl 引擎——该 fork 没有 OpenCL 后端。
     */
    var prismEngineEnabled: Boolean
        get() = prefs.getBoolean("prism_engine", false)
        set(value) = prefs.edit().putBoolean("prism_engine", value).apply()

    /**
     * 是否用 Hexagon NPU 引擎（高通 SoC 专用）。
     * 打开后强制 ngl=99 并走 `--device HTP0`：在这套参数里 NPU 相当于 GPU，靠 -ngl 决定卸载范围。
     * 优先级最高，会盖掉 GPU 与 prism 两个开关（NPU 引擎包里不带 OpenCL 后端）。
     * 8 Elite Gen 5 走 HTP v81（实测），老一些的芯片是 v73/v75/v79，包里四档 skel 都带。
     */
    var npuEnabled: Boolean
        get() = prefs.getBoolean("npu_engine", false)
        set(value) = prefs.edit().putBoolean("npu_engine", value).apply()

    /**
     * 模型加载模式（llama.cpp 的 `-lm`）："" = auto（默认，能用 mmap 就用 mmap）、
     * "mmap"、"dio"（DirectIO）、"mlock"、"mmap+mlock"。
     *
     * 机制（`llama-model-loader.cpp:560`）：只有 MMAP/MMAP_MLOCK/AUTO 才 `use_mmap = true`；
     * `dio` 会关掉 mmap 并把权重读进已分配内存。所以：
     * - 模型**大于**内存时只有 auto/mmap 能跑（靠按需换页），选 dio 会直接内存不足；
     * - 模型**装得下**但 mmap 抖动严重时，dio 可能更快。
     * 竞品那种「35B 在 12GB 上跑」的专家流式是自研 native 加载器，不是这个开关。
     */
    var loadMode: String
        get() = prefs.getString("load_mode", "") ?: ""
        set(value) = prefs.edit().putString("load_mode", value).apply()

    /**
     * 允许启动超过物理内存 85% 的模型（默认关）。
     * 内存闸的意义是挡住「必然把系统吃穿 → OOM 重启」的启动，但它不该替用户否决实验：
     * 想试 35B 在 NPU 上的预填充速度，就得让他试。真出事是用户自己选的。
     */
    var allowOverMemory: Boolean
        get() = prefs.getBoolean("allow_over_memory", false)
        set(value) = prefs.edit().putBoolean("allow_over_memory", value).apply()

    var specType: String
        get() = prefs.getString("spec_type", "none") ?: "none"
        set(value) = prefs.edit().putString("spec_type", value).apply()

    /** 思考模式：true=展示思考卡（默认）；false=提示词抑制+不展示（速度优先） */
    /** 思考等级（仅对模板认 reasoning_effort 的模型有意义）：low / medium / high；空 = 用模型默认 */
    var reasoningEffort: String
        get() = prefs.getString("reasoning_effort", "") ?: ""
        set(value) = prefs.edit().putString("reasoning_effort", value).apply()

    var thinkEnabled: Boolean
        get() = prefs.getBoolean("think_enabled", true)
        set(value) = prefs.edit().putBoolean("think_enabled", value).apply()

    /** HTTP MCP 多服务器列表 JSON：[{id,name,url,enabled,tools:[{name,description,inputSchema,enabled}]}] */
    var mcpHttpServersJson: String
        get() = prefs.getString("mcp_http_servers", "[]") ?: "[]"
        set(value) = prefs.edit().putString("mcp_http_servers", value).apply()

    // ---- 以下为旧「单服务器」配置，仅迁移使用，勿再新增引用 ----
    var mcpHttpEnabled: Boolean
        get() = prefs.getBoolean("mcp_http_enabled", false)
        set(value) = prefs.edit().putBoolean("mcp_http_enabled", value).apply()
    var mcpHttpUrl: String
        get() = prefs.getString("mcp_http_url", "") ?: ""
        set(value) = prefs.edit().putString("mcp_http_url", value).apply()
    var mcpHttpKey: String
        get() = prefs.getString("mcp_http_key", "") ?: ""
        set(value) = prefs.edit().putString("mcp_http_key", value).apply()
    var mcpHttpName: String
        get() = prefs.getString("mcp_http_name", "") ?: ""
        set(value) = prefs.edit().putString("mcp_http_name", value).apply()
    var mcpToolsJson: String
        get() = prefs.getString("mcp_tools", "[]") ?: "[]"
        set(value) = prefs.edit().putString("mcp_tools", value).apply()
    var mcpToolsEnabledJson: String
        get() = prefs.getString("mcp_tools_enabled", "{}") ?: "{}"
        set(value) = prefs.edit().putString("mcp_tools_enabled", value).apply()

    fun loadMcpServers(): List<McpHttpServer> = runCatching {
        val arr = org.json.JSONArray(mcpHttpServersJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ta = o.optJSONArray("tools") ?: org.json.JSONArray()
            McpHttpServer(
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { "MCP" },
                url = o.optString("url"),
                enabled = o.optBoolean("enabled", true),
                tools = (0 until ta.length()).mapNotNull { j ->
                    val t = ta.optJSONObject(j) ?: return@mapNotNull null
                    val n = t.optString("name")
                    if (n.isBlank()) return@mapNotNull null
                    McpHttpTool(
                        name = n,
                        description = t.optString("description"),
                        inputSchema = t.optJSONObject("inputSchema")
                            ?: org.json.JSONObject().put("type", "object"),
                        enabled = t.optBoolean("enabled", true)
                    )
                }
            )
        }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    fun saveMcpServers(list: List<McpHttpServer>) {
        val arr = org.json.JSONArray()
        list.forEach { s ->
            val o = org.json.JSONObject()
                .put("id", s.id).put("name", s.name).put("url", s.url).put("enabled", s.enabled)
            val ta = org.json.JSONArray()
            s.tools.forEach { t ->
                ta.put(
                    org.json.JSONObject()
                        .put("name", t.name).put("description", t.description)
                        .put("inputSchema", t.inputSchema).put("enabled", t.enabled)
                )
            }
            o.put("tools", ta)
            arr.put(o)
        }
        mcpHttpServersJson = arr.toString()
    }

    /** 旧单服务器配置一次性迁移为多服务器列表（App 启动时调用） */
    fun migrateMcpSingleToMulti() {
        if (mcpHttpServersJson.trim() != "[]") return
        val url = mcpHttpUrl.trim()
        if (url.isBlank()) return
        val toolsArr = runCatching { org.json.JSONArray(mcpToolsJson) }
            .getOrDefault(org.json.JSONArray())
        val en = runCatching { org.json.JSONObject(mcpToolsEnabledJson) }
            .getOrDefault(org.json.JSONObject())
        val tools = (0 until toolsArr.length()).mapNotNull { i ->
            val t = toolsArr.optJSONObject(i) ?: return@mapNotNull null
            val n = t.optString("name")
            if (n.isBlank()) return@mapNotNull null
            McpHttpTool(
                name = n,
                description = t.optString("description"),
                inputSchema = t.optJSONObject("inputSchema")
                    ?: org.json.JSONObject().put("type", "object"),
                enabled = en.optBoolean(n, true)
            )
        }
        saveMcpServers(
            listOf(
                McpHttpServer(
                    id = java.util.UUID.randomUUID().toString(),
                    name = mcpHttpName.ifBlank { "MCP" },
                    url = url,
                    enabled = mcpHttpEnabled,
                    tools = tools
                )
            )
        )
    }

    /** 主题："system"（跟随系统）/ "light" / "dark" */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) {
            prefs.edit().putString("theme_mode", value).apply()
            _themeMode.value = value
        }

    /**
     * 主题变化流：Compose 侧靠它立即重组。
     * 初始值必须在 prefs 初始化之后才能读，所以先留空、首次取用时兜底。
     */
    private val _themeMode = kotlinx.coroutines.flow.MutableStateFlow("")
    val themeModeFlow: kotlinx.coroutines.flow.StateFlow<String>
        get() {
            if (_themeMode.value.isEmpty()) _themeMode.value = themeMode
            return _themeMode
        }

    /** 内置网页搜索开关（聊天顶栏 🔍）：开启后向模型注入 web_search 工具 */
    var webSearchEnabled: Boolean
        get() = prefs.getBoolean("web_search_enabled", false)
        set(value) = prefs.edit().putBoolean("web_search_enabled", value).apply()

    /**
     * MCP 工具注入策略："auto"（默认，工具多时按关键词/模型挑选几个）/"all"（全量注入，不裁剪）。
     *
     * 原来限制的理由是「工具多把 prompt 撑到几万 token、CPU 预填充要几分钟」——NPU 引擎之后
     * 这条基本不成立（12B 实测预填充 3 秒 vs CPU 180 秒），所以给出关闭限制的选项。
     * 默认仍是 auto：工具太多会降低小模型的选择准确率，这跟速度无关，是另一个问题。
     */
    var mcpToolMode: String
        get() = prefs.getString("mcp_tool_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("mcp_tool_mode", value).apply()

    /**
     * 工具调用的最大轮次（每轮 = 一次完整生成 + 一次工具回传）。
     * 原来是写死的 8 轮，多步任务（写文件 → 自检 → 改错 → 再自检）经常撞上限，
     * 日志里表现为「轮次用尽，任务未收敛」。NPU 之后每轮更便宜，放宽更有意义。
     * 上限不高仍有意义：模型陷进工具循环时，每多一轮就是一次完整生成。
     */
    var toolRoundLimit: Int
        get() = prefs.getInt("tool_round_limit", 16).coerceIn(4, 40)
        set(value) = prefs.edit().putInt("tool_round_limit", value.coerceIn(4, 40)).apply()

    /** 含图片轮次的联网搜索策略："block"（默认，禁止）/ "ask"（先征求同意）/ "allow" */
    var imageSearchPolicy: String
        get() = prefs.getString("image_search_policy", "block") ?: "block"
        set(value) = prefs.edit().putString("image_search_policy", value).apply()

    /** 每条消息的联网搜索次数上限；0 = 不限制 */
    var searchMaxCalls: Int
        get() = prefs.getInt("search_max_calls", 3)
        set(value) = prefs.edit().putInt("search_max_calls", value).apply()

    /** 助手指令（system prompt）预设：{id,name,text} 列表 */
    var sysPresetsJson: String
        get() = prefs.getString("sys_prompt_presets", "") ?: ""
        set(value) = prefs.edit().putString("sys_prompt_presets", value).apply()

    /** 当前全局启用的预设 id（空 = 使用默认预设） */
    var activeSysPresetId: String
        get() = prefs.getString("sys_prompt_active", "") ?: ""
        set(value) = prefs.edit().putString("sys_prompt_active", value).apply()

    fun loadSysPresets(): List<SysPreset> {
        val raw = sysPresetsJson
        val parsed = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                SysPreset(id, o.optString("name").ifBlank { "预设" }, o.optString("text"))
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { defaultSysPresets() }
    }

    fun saveSysPresets(list: List<SysPreset>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(org.json.JSONObject().put("id", p.id).put("name", p.name).put("text", p.text))
        }
        sysPresetsJson = arr.toString()
    }

    /** 当前生效的全局系统提示词（空串 = 不加任何指令） */
    fun activeSysPrompt(): String {
        val list = loadSysPresets()
        val id = activeSysPresetId
        return (list.find { it.id == id } ?: list.firstOrNull())?.text.orEmpty()
    }

    private fun defaultSysPresets(): List<SysPreset> = listOf(
        SysPreset("default", "默认（不额外指令）", ""),
        SysPreset("concise", "简洁直接", "回答尽量简短直接，先说结论，必要时才展开。"),
        SysPreset("rigorous", "严谨求证", "严谨作答：不确定的内容要明确标注不确定，避免编造；必要时说明依据。"),
        SysPreset("translate", "翻译助手", "你是翻译助手：默认把用户输入翻译成中文（若输入是中文则翻译成英文），只输出译文，不加解释。"),
        SysPreset("coding", "编程助手", "你是编程助手：给可运行的代码与必要的说明，代码用 Markdown 代码块并标注语言；优先指出潜在 bug 与边界条件。")
    )

    /** 技能模板：{id,name,desc,prompt} 列表（null = 内置默认） */
    var skillsJson: String
        get() = prefs.getString("skills", "") ?: ""
        set(value) = prefs.edit().putString("skills", value).apply()

    fun loadSkills(): List<Skill> {
        val raw = skillsJson
        val parsed = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Skill(
                    id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = o.optString("name").ifBlank { "技能" },
                    desc = o.optString("desc"),
                    prompt = o.optString("prompt")
                )
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { defaultSkills() }
    }

    fun saveSkills(list: List<Skill>) {
        val arr = org.json.JSONArray()
        list.forEach { s ->
            arr.put(
                org.json.JSONObject()
                    .put("id", s.id).put("name", s.name)
                    .put("desc", s.desc).put("prompt", s.prompt)
            )
        }
        skillsJson = arr.toString()
    }

    // ---- 技能链（按顺序组合多个技能）----
    var skillChainsJson: String
        get() = prefs.getString("skill_chains", "") ?: ""
        set(value) = prefs.edit().putString("skill_chains", value).apply()

    fun loadSkillChains(): List<SkillChain> {
        val raw = skillChainsJson
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val stepsArr = o.optJSONArray("steps") ?: return@mapNotNull null
                val steps = (0 until stepsArr.length()).map { stepsArr.optString(it) }
                    .filter { it.isNotBlank() }
                if (steps.isEmpty()) return@mapNotNull null
                SkillChain(
                    o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    o.optString("name").ifBlank { "技能链" },
                    steps,
                    // 失败时改用的备用技能；老数据没有这个字段，留空即可
                    o.optString("fallbackSkillId").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveSkillChains(list: List<SkillChain>) {
        val arr = org.json.JSONArray()
        list.forEach { c ->
            val steps = org.json.JSONArray()
            c.skillIds.forEach { steps.put(it) }
            arr.put(
                org.json.JSONObject()
                    .put("id", c.id).put("name", c.name).put("steps", steps)
                    .put("fallbackSkillId", c.fallbackSkillId ?: org.json.JSONObject.NULL)
            )
        }
        skillChainsJson = arr.toString()
    }

    /** 把技能链拼成一段可执行的 system 指令 */
    fun chainPrompt(chain: SkillChain): String {
        val skills = loadSkills().associateBy { it.id }
        val steps = chain.skillIds.mapNotNull { skills[it] }
        if (steps.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【技能链：").append(steps.joinToString(" → ") { it.name }).append("】\n")
        sb.append("请按顺序完成以下步骤，最终只输出最后一步的成果（中间步骤的结论作为下一步输入）：\n")
        steps.forEachIndexed { i, sk ->
            sb.append("第 ${i + 1} 步（").append(sk.name).append("）：").append(sk.prompt).append('\n')
        }
        return sb.toString().trim()
    }

    private fun defaultSkills(): List<Skill> = listOf(
        Skill(
            "sk_summary", "摘要总结", "长文/链接 → 结论+要点",
            "阅读用户提供的内容并总结：先给一句话结论，再列 3~5 条要点，最后列出关键数据；" +
                    "不要复述原文，不要加客套。"
        ),
        Skill(
            "sk_translate", "翻译", "中英互译，只出译文",
            "你是翻译助手：把用户输入翻译成中文（若输入是中文则翻译成英文），只输出译文，不加解释。"
        ),
        Skill(
            "sk_extract", "信息提取", "从文本/图片里抽字段成表格",
            "从用户提供的内容中提取关键实体与字段，输出 Markdown 表格；" +
                    "字段缺失时留空并标注「未提及」，不要编造。"
        ),
        Skill(
            "sk_review", "代码审查", "按严重/建议分级列问题",
            "审查用户提供的代码：按「严重问题 / 改进建议」分级列出，每条给出原因与修复示例；" +
                    "特别指出边界条件与潜在异常。"
        ),
        Skill(
            "sk_csv", "表格生成", "需求 → CSV 代码块",
            "把用户需求整理成表格：输出一个 ```csv 代码块（第一行表头，其后数据行），" +
                    "不要额外解释；如需多表分别输出。"
        ),
        Skill(
            "sk_todo", "任务拆解", "把目标拆成可执行清单",
            "把用户的目标拆解为可执行步骤清单：每条不超过 20 字，标注优先级（高/中/低）与依赖关系，" +
                    "输出 Markdown 任务列表（- [ ] 形式）；若用户要的是产出物（代码/文件/表格），" +
                    "列完清单后必须直接给出完整产出，不能只报进度。"
        )
    )

    /** 任务清单工具（todo_write）开关：多步任务可先列计划再逐步执行 */
    var todoToolEnabled: Boolean
        get() = prefs.getBoolean("todo_tool_enabled", true)
        set(value) = prefs.edit().putBoolean("todo_tool_enabled", value).apply()

    /** 长期记忆开关（注入 + memory_write 工具） */
    var memoryEnabled: Boolean
        get() = prefs.getBoolean("memory_enabled", true)
        set(value) = prefs.edit().putBoolean("memory_enabled", value).apply()

    /** 文件工具开关与授权的根目录（SAF tree uri） */
    var fileToolsEnabled: Boolean
        get() = prefs.getBoolean("file_tools_enabled", false)
        set(value) = prefs.edit().putBoolean("file_tools_enabled", value).apply()

    var workDirTreeUri: String
        get() = prefs.getString("work_dir_tree", "") ?: ""
        set(value) = prefs.edit().putString("work_dir_tree", value).apply()

    /**
     * 代码自检开关：允许模型把生成的 HTML 丢进内置 WebView 沙箱跑一次，拿到报错后自己修。
     * 没有这条回路，弱模型写的代码就只能一次成型、错了也不知道。
     */
    var webSandboxEnabled: Boolean
        get() = prefs.getBoolean("web_sandbox_enabled", true)
        set(value) = prefs.edit().putBoolean("web_sandbox_enabled", value).apply()

    /**
     * 纯净模式：不注入任何工具，纯对话。
     * 工具 schema 是 prompt 体积的大头（几十个 MCP 工具可达 9K token），
     * 只是聊天时把工具全关掉，prefill 能快一个量级。
     */
    var pureMode: Boolean
        get() = prefs.getBoolean("pure_mode", false)
        set(value) = prefs.edit().putBoolean("pure_mode", value).apply()

    /** 已弹窗提示过的更新版本（避免每次启动重复提示） */
    var lastPromptedUpdateVersion: String
        get() = prefs.getString("last_prompted_update", "") ?: ""
        set(value) = prefs.edit().putString("last_prompted_update", value).apply()

    /** 搜索时是否抓取结果页正文（提升回答质量，代价是每次搜索多 1~3 秒） */
    var searchFetchFullText: Boolean
        get() = prefs.getBoolean("search_fetch_full", true)
        set(value) = prefs.edit().putBoolean("search_fetch_full", value).apply()

    // ---- 本地模型的按模型参数覆盖（精简版：上下文 / 输出上限 / mmproj / LoRA 绑定）----
    /** {模型绝对路径: {"ctx":Int?, "out":Int?, "mmproj":String?, "lora":String?, "loraScale":Float?}} */
    var localModelParamsJson: String
        get() = prefs.getString("local_model_params", "{}") ?: "{}"
        set(value) = prefs.edit().putString("local_model_params", value).apply()

    fun localModelParam(path: String?): LocalModelParam? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val o = org.json.JSONObject(localModelParamsJson).optJSONObject(path)
                ?: return null
            LocalModelParam(
                ctx = if (o.has("ctx")) o.optInt("ctx") else null,
                out = if (o.has("out")) o.optInt("out") else null,
                mmproj = if (o.has("mmproj")) o.optString("mmproj") else null,
                lora = if (o.has("lora")) o.optString("lora") else null,
                loraScale = if (o.has("loraScale")) o.optDouble("loraScale").toFloat() else null
            )
        }.getOrNull()
    }

    /** p 全空则删除该模型条目 */
    fun setLocalModelParam(path: String, p: LocalModelParam?) {
        val root = runCatching { org.json.JSONObject(localModelParamsJson) }
            .getOrDefault(org.json.JSONObject())
        if (p == null || (p.ctx == null && p.out == null && p.mmproj == null &&
                    p.lora == null && p.loraScale == null)) {
            root.remove(path)
        } else {
            val o = org.json.JSONObject()
            p.ctx?.let { o.put("ctx", it) }
            p.out?.let { o.put("out", it) }
            p.mmproj?.let { o.put("mmproj", it) }
            p.lora?.let { o.put("lora", it) }
            p.loraScale?.let { o.put("loraScale", it) }
            root.put(path, o)
        }
        localModelParamsJson = root.toString()
    }

    /** 生效上下文长度：模型级 → 全局 */
    fun effectiveCtx(modelPath: String?): Int = localModelParam(modelPath)?.ctx ?: ctxSize

    /** 生效输出上限（本地 max_tokens）：模型级 → 默认 8192 */
    fun effectiveOut(modelPath: String?): Int = localModelParam(modelPath)?.out ?: 8192

    /** 生效 mmproj：模型级（空串=跟全局，"none"=明确不用）→ 全局 */
    fun effectiveMmproj(modelPath: String?): String? =
        when (val v = localModelParam(modelPath)?.mmproj) {
            null -> selectedMmprojPath
            "none" -> null
            else -> v.ifBlank { selectedMmprojPath }
        }

    /** 模型级是否覆盖了 mmproj（用于 UI 标注来源） */
    fun isMmprojOverridden(modelPath: String?): Boolean =
        localModelParam(modelPath)?.mmproj != null

    /** 生效的 LoRA 适配器路径（模型级绑定，不存在就返回 null）；scale 用 [effectiveLoraScale] */
    fun effectiveLora(modelPath: String?): String? =
        localModelParam(modelPath)?.lora?.takeIf { it.isNotBlank() && java.io.File(it).exists() }

    /** LoRA 强度，默认 1.0；0 = 等效关闭 */
    fun effectiveLoraScale(modelPath: String?): Float =
        localModelParam(modelPath)?.loraScale ?: 1.0f

    /** 适配器文件被删/移走后清掉绑定，避免一直拿旧路径去启动 */
    fun dropLoraIfMissing(modelPath: String?) {
        val p = localModelParam(modelPath) ?: return
        if (p.lora != null && !java.io.File(p.lora).exists()) {
            setLocalModelParam(modelPath ?: return, p.copy(lora = null, loraScale = null))
        }
    }

    /** 聊天云端渠道 */
    var chatCloudEnabled: Boolean
        get() = prefs.getBoolean("chat_cloud", false)
        set(value) = prefs.edit().putBoolean("chat_cloud", value).apply()

    var chatCloudUrl: String
        get() = prefs.getString("chat_cloud_url", "") ?: ""
        set(value) = prefs.edit().putString("chat_cloud_url", value).apply()

    var chatCloudKey: String
        get() = prefs.getString("chat_cloud_key", "") ?: ""
        set(value) = prefs.edit().putString("chat_cloud_key", value).apply()

    var chatCloudModel: String
        get() = prefs.getString("chat_cloud_model", "") ?: ""
        set(value) = prefs.edit().putString("chat_cloud_model", value).apply()

    /** 进行中的下载任务（JSON 数组），进程重启后自动恢复 */
    var downloadTasksJson: String
        get() = prefs.getString("download_tasks", "[]") ?: "[]"
        set(value) = prefs.edit().putString("download_tasks", value).apply()

    /** flash attention: "" = 服务器默认(auto) / "on" / "off" */
    var faMode: String
        get() = prefs.getString("fa_mode", "") ?: ""
        set(value) = prefs.edit().putString("fa_mode", value).apply()

    var selectedModelPath: String?
        get() = prefs.getString("selected_model", null)
        set(value) = prefs.edit().putString("selected_model", value).apply()

    /** 手动选配的 mmproj；null = 不启用多模态（即使 projectors 目录有文件） */
    var selectedMmprojPath: String?
        get() = prefs.getString("selected_mmproj", null)
        set(value) = prefs.edit().putString("selected_mmproj", value).apply()

    /** 保活：服务在位时持有 PARTIAL_WAKE_LOCK，屏幕关闭后不被 Doze 冻结。默认开 */
    var keepAwakeEnabled: Boolean
        get() = prefs.getBoolean("keep_awake_enabled", true)
        set(value) = prefs.edit().putBoolean("keep_awake_enabled", value).apply()

    /**
     * 服务是否处于「应该在跑」的状态。
     * 进程被系统杀掉后 Service 会被重建（此时 intent 为 null），靠这个标记区分
     * 「用户主动启动过」和「用户已经主动停止」——后者不该自己复活。
     */
    var serverShouldRun: Boolean
        get() = prefs.getBoolean("server_should_run", false)
        set(value) = prefs.edit().putBoolean("server_should_run", value).apply()

    /**
     * 上次下发给服务的模型路径，复活时用。
     * 不复用 selectedModelPath：用户可以启动一个并非当前选中的模型。
     */
    var serverModelPath: String?
        get() = prefs.getString("server_model_path", null)
        set(value) = prefs.edit().putString("server_model_path", value).apply()

    /** 模型别名表：绝对路径 → 用户起的显示名（JSON 对象） */
    private var aliasMap: Map<String, String>
        get() = runCatching {
            val o = org.json.JSONObject(prefs.getString("model_aliases", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
        set(value) {
            prefs.edit().putString("model_aliases", org.json.JSONObject(value).toString()).apply()
        }

    fun aliasFor(path: String?): String? = path?.let { aliasMap[it]?.takeIf { a -> a.isNotBlank() } }

    fun setAlias(path: String, alias: String) {
        val m = aliasMap.toMutableMap()
        if (alias.isBlank()) m.remove(path) else m[path] = alias.trim()
        aliasMap = m
    }
}

/** HTTP MCP 服务器（多服务器列表中的一条） */
data class McpHttpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val tools: List<McpHttpTool>
)

/** 一个 MCP 工具（含拉取缓存的 schema 与单独开关） */
data class McpHttpTool(
    val name: String,
    val description: String,
    val inputSchema: org.json.JSONObject,
    val enabled: Boolean
)

/** 本地模型的按模型参数覆盖（null = 跟随全局） */
data class LocalModelParam(
    val ctx: Int? = null,
    val out: Int? = null,
    /** 投影文件绝对路径；空串=跟全局；"none"=明确不用 */
    val mmproj: String? = null,
    /** LoRA 适配器绝对路径；null/空 = 不使用 */
    val lora: String? = null,
    /** LoRA 强度，默认 1.0（0 = 等效关闭） */
    val loraScale: Float? = null
)

/** 助手指令预设 */
data class SysPreset(val id: String, val name: String, val text: String)

/** 技能模板：一段可复用的助手指令 */
data class Skill(val id: String, val name: String, val desc: String, val prompt: String)

/** 技能链：按顺序执行多个技能 */
/**
 * 技能链。原来的用法是把各步提示词拼成一段「第 1 步…第 2 步…」的提示词，
 * 让模型在一次生成里按顺序做完——中间结果看不到，也无法真正分支。
 * fallbackSkillId 用于新的执行器：某一步被判定为失败时，改用这个技能重跑该步。
 */
data class SkillChain(
    val id: String,
    val name: String,
    val skillIds: List<String>,
    /** 失败时改用的备用技能；null = 失败就中止并说明卡在哪一步 */
    val fallbackSkillId: String? = null
)
