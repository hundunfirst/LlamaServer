package com.localllm.server.core

import org.json.JSONObject

/**
 * MCP 工具返回的结构化错误。
 *
 * 不少 MCP 服务（MT 那套就是）把错误塞在 content[].text 的 JSON 里：
 * `{"ok":false,"error":{"code":"WORKSPACE_NOT_FOUND","message":"Workspace not found.",
 *   "recoverable":false,"retrySameArguments":false,"allowedValues":[]},"nextActions":[]}`
 *
 * 原样丢给模型，它面对的是一坨几百字、大半字段是 null 的 JSON，「重试没用」和「缺的是工作区」
 * 这两条关键信息都读不出来——实测就是拿着同样的调用重试到把我设的失败上限撞满。
 * 这里只挑有用的字段，压成一句人话。
 */
object McpToolError {

    data class Info(
        val code: String,
        val message: String,
        /** 出错的是哪个参数（服务端给的时候才有） */
        val argument: String?,
        val badValue: String?,
        val allowedValues: List<String>,
        /** 服务端说这个错误是否值得再试；null = 没说 */
        val recoverable: Boolean?,
        /** 服务端说原参数重试是否有意义；null = 没说 */
        val retrySameArguments: Boolean?
    )

    private const val MAX_TEXT = 600

    private val OPEN_VERB = Regex(
        "(open|create|init|begin|mount|load|start|new)", RegexOption.IGNORE_CASE
    )

    /** 解析工具返回文本里的结构化错误；解析不出结构就返回 null，调用方保持原文行为 */
    fun parse(text: String): Info? {
        val obj = firstJsonObject(text) ?: return null
        // 有的服务包一层 error，有的直接把字段放顶层
        val err = obj.optJSONObject("error") ?: obj
        val code = err.optString("code").trim()
        val message = err.optString("message").trim()
        if (code.isEmpty() && message.isEmpty()) return null
        return Info(
            code = code,
            message = message,
            argument = err.optString("argument").trim().takeIf { it.isNotEmpty() && it != "null" },
            badValue = err.opt("badValue")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
            allowedValues = err.optJSONArray("allowedValues")?.let { a ->
                (0 until a.length()).mapNotNull { a.opt(it)?.toString() }
            } ?: emptyList(),
            recoverable = err.optBooleanOrNull("recoverable"),
            retrySameArguments = err.optBooleanOrNull("retrySameArguments")
        )
    }

    /** 一行摘要：参数错说清哪个值越界，其余说清错误码和原因 */
    fun summarize(info: Info, fallback: String): String {
        val head = when {
            info.argument != null && info.badValue != null -> {
                val range = allowedText(info.allowedValues)
                if (range != null) "参数 ${info.argument}=${info.badValue} 超出允许范围（$range）"
                else "参数 ${info.argument}=${info.badValue} 不被接受"
            }
            info.code.isNotEmpty() ->
                info.code + if (info.message.isNotEmpty()) "：${info.message}" else ""
            else -> info.message
        }
        val tail = when (info.retrySameArguments) {
            false -> "；服务端明确说原参数重试不会有不同结果"
            true -> "；服务端表示修正参数后可以再试"
            null -> ""
        }
        return (head + tail).take(MAX_TEXT).ifBlank { fallback.take(MAX_TEXT) }
    }

    /**
     * 下一步该怎么做的建议；没有更好的说法时返回 null，让调用方用通用文案。
     *
     * 两类值得单独说：
     *  - 参数越界：给出允许范围，让它改参数重试（这类改了就成）；
     *  - 缺前置上下文（工作区/会话/工程没打开）：直接点名该先调哪个工具，
     *    否则模型只会换着名字调同类工具，全被同一个原因打回。
     */
    fun advice(info: Info, toolName: String, peers: List<String>): String? {
        if (info.argument != null && info.badValue != null) {
            return if (info.allowedValues.isNotEmpty())
                "把 ${info.argument} 改到允许范围内再调一次，其余参数不用动。"
            else "这个取值不被接受，换一个值再试；同一个值不要再发第二次。"
        }
        val code = info.code.uppercase()
        if (!needsPrerequisite(code)) return null
        val candidates = prerequisiteTools(toolName, code, peers)
        return if (candidates.isEmpty())
            "这类错误说明它依赖的上下文（工作区/会话/工程）没打开或已失效：先建立起来再重试本工具，" +
                    "原参数重试不会成功。"
        else
            "这类错误说明它依赖的上下文还没建立：先调用 ${candidates.joinToString(" 或 ")} 建立起来，" +
                    "再重试本工具；原参数重试不会成功。"
    }

    /** 「不存在 / 没打开 / 已过期」这类错误，靠重试解决不了，得先建上下文 */
    private fun needsPrerequisite(code: String): Boolean = listOf(
        "WORKSPACE", "SESSION", "NOT_FOUND", "NOT_OPEN", "NOT_INIT", "NOT_LOADED",
        "NO_PROJECT", "EXPIRED", "UNINITIALIZED"
    ).any { code.contains(it) }

    /** 同一族（mt_apk_list → mt_apk_*）里负责「打开/建会话」的工具 */
    private fun prerequisiteTools(toolName: String, code: String, peers: List<String>): List<String> {
        val family = toolName.substringBeforeLast('_', "")
        if (family.isNotEmpty()) {
            val same = peers.filter {
                it != toolName && it.startsWith(family + "_") && OPEN_VERB.containsMatchIn(it)
            }
            if (same.isNotEmpty()) return same.take(3)
        }
        val key = when {
            code.contains("WORKSPACE") -> "workspace"
            code.contains("SESSION") -> "session"
            code.contains("PROJECT") -> "project"
            else -> return emptyList()
        }
        return peers.filter { it != toolName && it.contains(key, true) && OPEN_VERB.containsMatchIn(it) }
            .take(3)
    }

    /** allowedValues 恰好两个数值时按范围读（1 与 2000 → 1–2000），否则按枚举列 */
    private fun allowedText(values: List<String>): String? = when {
        values.isEmpty() -> null
        values.size == 2 && values.all(::validNumber) -> "${values[0]}–${values[1]}"
        else -> values.joinToString("、")
    }

    private fun validNumber(s: String) = s.trim().toDoubleOrNull() != null

    private fun firstJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
}
