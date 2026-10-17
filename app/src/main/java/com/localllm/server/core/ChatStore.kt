package com.localllm.server.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 多会话持久化：单文件 JSON，含活动会话指针 */
object ChatStore {
    private const val FILE = "chat_sessions.json"
    private const val MAX_MESSAGES = 100
    private const val MAX_SESSIONS = 50

    /** 执行步骤（思考/搜索/工具），用于消息内的时间线可视化 */
    data class Step(
        val type: String,   // think / search / tool
        val label: String,
        val detail: String? = null,
        val millis: Long = 0
    )

    /** 搜索来源（可点击跳转） */
    data class Source(val title: String, val url: String)

    data class Msg(
        val role: String,
        val content: String,
        val imageB64: String? = null,
        val reasoning: String? = null,
        val steps: List<Step> = emptyList(),
        val sources: List<Source> = emptyList()
    )

    data class Session(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<Msg>,
        /** 本会话的助手指令覆盖；null = 使用全局预设 */
        val systemPrompt: String? = null
    )

    data class Store(val activeId: String, val sessions: List<Session>)

    fun load(context: Context): Store {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) {
            // 一次性迁移：旧版单会话文件
            val legacy = File(context.filesDir, "chat_current.json")
            val msgs = if (legacy.exists()) runCatching {
                val arr = JSONArray(legacy.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Msg(
                        o.optString("role"),
                        o.optString("content"),
                        o.optString("image").takeIf { it.isNotEmpty() },
                        o.optString("reasoning").takeIf { it.isNotEmpty() }
                    )
                }
            }.getOrDefault(emptyList()) else emptyList()
            val title = msgs.firstOrNull { it.role == "user" }?.content?.take(24) ?: "历史会话"
            val s = Session("1", title, System.currentTimeMillis(), msgs)
            val store = Store(s.id, listOf(s))
            save(context, store)
            return store
        }
        return runCatching {
            val root = JSONObject(f.readText())
            val active = root.optString("activeId").ifBlank { "1" }
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            val sessions = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mArr = o.optJSONArray("messages") ?: JSONArray()
                val msgs = (0 until mArr.length()).map { j ->
                    val m = mArr.getJSONObject(j)
                    Msg(
                        m.optString("role"),
                        m.optString("content"),
                        m.optString("image").takeIf { it.isNotEmpty() },
                        m.optString("reasoning").takeIf { it.isNotEmpty() },
                        steps = parseSteps(m.optJSONArray("steps")),
                        sources = parseSources(m.optJSONArray("sources"))
                    )
                }
                Session(
                    o.optString("id"),
                    o.optString("title"),
                    o.optLong("updatedAt"),
                    msgs,
                    o.optString("systemPrompt").takeIf { it.isNotEmpty() }
                )
            }.ifEmpty {
                listOf(Session("1", "新会话", System.currentTimeMillis(), emptyList()))
            }
            Store(if (sessions.any { it.id == active }) active else sessions.first().id, sessions)
        }.getOrDefault(Store("1", listOf(Session("1", "新会话", System.currentTimeMillis(), emptyList()))))
    }

    private fun parseSources(arr: JSONArray?): List<Source> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("url")
            if (u.isBlank()) return@mapNotNull null
            Source(o.optString("title"), u)
        }
    }

    private fun parseSteps(arr: JSONArray?): List<Step> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Step(
                type = o.optString("type"),
                label = o.optString("label"),
                detail = o.optString("detail").takeIf { it.isNotEmpty() && it != "null" },
                millis = o.optLong("millis")
            )
        }
    }

    fun save(context: Context, store: Store) {
        val root = JSONObject().put("activeId", store.activeId)
        val arr = JSONArray()
        store.sessions.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS).forEach { s ->
            val mArr = JSONArray()
            s.messages.takeLast(MAX_MESSAGES).forEach { m ->
                val o = JSONObject().put("role", m.role).put("content", m.content)
                if (m.imageB64 != null) o.put("image", m.imageB64)
                if (m.reasoning != null) o.put("reasoning", m.reasoning)
                if (m.steps.isNotEmpty()) {
                    val sArr = JSONArray()
                    m.steps.forEach { st ->
                        sArr.put(
                            JSONObject().put("type", st.type).put("label", st.label)
                                .put("detail", st.detail ?: JSONObject.NULL)
                                .put("millis", st.millis)
                        )
                    }
                    o.put("steps", sArr)
                }
                if (m.sources.isNotEmpty()) {
                    val uArr = JSONArray()
                    m.sources.forEach { sc ->
                        uArr.put(JSONObject().put("title", sc.title).put("url", sc.url))
                    }
                    o.put("sources", uArr)
                }
                mArr.put(o)
            }
            arr.put(
                (JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", mArr)
                    as JSONObject)
                    .apply { s.systemPrompt?.let { put("systemPrompt", it) } }
            )
        }
        root.put("sessions", arr)
        File(context.filesDir, FILE).writeText(root.toString())
    }
}
