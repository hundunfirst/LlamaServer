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
        get() = prefs.getInt("ctx_size", 4096)
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

    var specType: String
        get() = prefs.getString("spec_type", "none") ?: "none"
        set(value) = prefs.edit().putString("spec_type", value).apply()

    /** 思考模式：true=展示思考卡（默认）；false=提示词抑制+不展示（速度优先） */
    var thinkEnabled: Boolean
        get() = prefs.getBoolean("think_enabled", true)
        set(value) = prefs.edit().putBoolean("think_enabled", value).apply()

    /** MCP 服务器列表 JSON：[{"name","command","args"}]（stdio 型，由 llama-server 拉起） */
    var mcpServersJson: String
        get() = prefs.getString("mcp_servers", "[]") ?: "[]"
        set(value) = prefs.edit().putString("mcp_servers", value).apply()

    /** HTTP 型 MCP 是否启用（app 侧客户端，聊天时注入 tools） */
    var mcpHttpEnabled: Boolean
        get() = prefs.getBoolean("mcp_http_enabled", false)
        set(value) = prefs.edit().putBoolean("mcp_http_enabled", value).apply()

    /** HTTP 型 MCP 端点 URL（Streamable HTTP，如 http://192.168.1.5:8787/mcp） */
    var mcpHttpUrl: String
        get() = prefs.getString("mcp_http_url", "") ?: ""
        set(value) = prefs.edit().putString("mcp_http_url", value).apply()

    /** HTTP 型 MCP 可选鉴权 token */
    var mcpHttpKey: String
        get() = prefs.getString("mcp_http_key", "") ?: ""
        set(value) = prefs.edit().putString("mcp_http_key", value).apply()

    /** HTTP MCP 显示别名（如 "Mt"） */
    var mcpHttpName: String
        get() = prefs.getString("mcp_http_name", "") ?: ""
        set(value) = prefs.edit().putString("mcp_http_name", value).apply()

    /** 工具列表缓存 JSON：[{name,description,inputSchema}]（拉取成功后落盘，聊天直接用，不再每次请求现拉） */
    var mcpToolsJson: String
        get() = prefs.getString("mcp_tools", "[]") ?: "[]"
        set(value) = prefs.edit().putString("mcp_tools", value).apply()

    /** 每个工具的启用状态 JSON：{toolName: bool}（缺省=启用） */
    var mcpToolsEnabledJson: String
        get() = prefs.getString("mcp_tools_enabled", "{}") ?: "{}"
        set(value) = prefs.edit().putString("mcp_tools_enabled", value).apply()

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
