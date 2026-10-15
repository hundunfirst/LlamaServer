package com.localllm.server.core

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS = "llama_server_settings"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
