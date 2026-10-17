package com.localllm.server.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长期记忆：纯文本文件 files/memory.md（用户可读可编辑）。
 * - 自动注入：每次对话把记忆（截断）并入 system 提示
 * - memory_write 工具：模型在用户说"记住…"时追加条目
 */
object MemoryStore {

    const val TOOL_NAME = "memory_write"
    private const val FILE = "memory.md"
    private const val INJECT_MAX_CHARS = 1500
    private const val MAX_ENTRIES = 200

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): List<String> =
        runCatching {
            file(context).takeIf { it.exists() }?.readLines()
                ?.filter { it.isNotBlank() } ?: emptyList()
        }.getOrDefault(emptyList())

    fun append(context: Context, content: String): Int {
        val text = content.trim()
        if (text.isBlank()) return load(context).size
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val entry = "- [$stamp] " + text.replace('\n', ' ')
        val list = (load(context) + entry).takeLast(MAX_ENTRIES)
        runCatching { file(context).writeText(list.joinToString("\n") + "\n") }
        return list.size
    }

    fun clear(context: Context) {
        runCatching { file(context).writeText("") }
    }

    /** 注入 system 提示的文本（空串表示没有记忆） */
    fun injectText(context: Context): String {
        val list = load(context)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in list.indices.reversed()) {
            val line = list[i]
            if (sb.length + line.length > INJECT_MAX_CHARS) break
            sb.insert(0, line + "\n")
        }
        return sb.toString().trim()
    }

    fun toolJson(): org.json.JSONObject = org.json.JSONObject()
        .put("type", "function")
        .put(
            "function", org.json.JSONObject()
                .put("name", TOOL_NAME)
                .put(
                    "description",
                    "把一条关于用户的重要信息写入长期记忆（跨会话生效）：偏好、长期目标、项目背景、称呼等。" +
                            "仅当用户明确要求记住，或信息长期有用时调用；不要记录一次性内容。"
                )
                .put(
                    "parameters", org.json.JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", org.json.JSONObject().put(
                                "content", org.json.JSONObject()
                                    .put("type", "string")
                                    .put("description", "一句话记忆，包含主语与具体内容")
                            )
                        )
                        .put("required", org.json.JSONArray().put("content"))
                )
        )
}
