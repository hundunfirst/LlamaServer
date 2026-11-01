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
    /**
     * args / ok 是给「工具调用可观测性」用的：原来只存名字和结果前 120 字，
     * 排查「为什么没调对工具」时看不到参数，也不知道这次算不算失败。
     */
    data class Step(
        val type: String,   // think / search / tool
        val label: String,
        val detail: String? = null,
        val millis: Long = 0,
        /** 调用参数（已截断），仅 tool 类型有值 */
        val args: String? = null,
        /** 该步是否成功；false 时界面标红 */
        val ok: Boolean = true
    )

    /**
     * 一体过程时间线的收起摘要（DSH / MiMo Desktop 风格）：
     * 有工具调用 → "✓ 已完成 · N 次工具调用 · M 次搜索"；
     * 中间正文（text 步）→ 摘要加 "· M 条消息"；
     * 只有思考 → "✓ 已完成 · N 段思考"；什么都没有 → "✓ 已完成"。
     * hasReasoning 用于思考还在流式、尚未落成 think 步（或老数据只有 reasoning）的场景；
     * 已有 think 步时忽略，避免重复计数。
     */
    internal fun turnSummaryLabel(steps: List<Step>, hasReasoning: Boolean = false): String {
        val tools = steps.count { it.type == "tool" }
        val searches = steps.count { it.type == "search" }
        val messages = steps.count { it.type == "text" }
        val thinkSteps = steps.count { it.type == "think" }
        val thinks = thinkSteps + if (hasReasoning && thinkSteps == 0) 1 else 0
        val parts = mutableListOf("✓ 已完成")
        if (tools > 0) parts += "$tools 次工具调用"
        if (searches > 0) parts += "$searches 次搜索"
        if (messages > 0) parts += "$messages 条消息"
        if (parts.size == 1 && thinks > 0) parts += "$thinks 段思考"
        return parts.joinToString(" · ")
    }


    /**
     * DSH ThinkingPill 的预览规则：思考中只取「已写完首行的最近一段」（不跟着半句话抖），
     * 完成后取全文第一行；都去掉 `**`。返回的预览用于思考行的紧凑一行。
     */
    internal fun thinkingPreview(text: String, active: Boolean): String {
        val line = if (active) latestParagraphFirstLine(text) else firstLine(text)
        return line.replace("**", "").trim()
    }

    private fun firstLine(text: String): String {
        val trimmed = text.trimStart()
        val nl = trimmed.indexOf('\n')
        return if (nl == -1) trimmed else trimmed.substring(0, nl)
    }

    /** 最近一段里已经写完（后面跟着换行）的首行；还没有就空。 */
    private fun latestParagraphFirstLine(text: String): String {
        var summary = ""
        var start = 0
        val matches = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)+").findAll(text).toList()
        for (i in 0..matches.size) {
            val sep = if (i < matches.size) matches[i] else null
            val end = if (sep == null) text.length else sep.range.first + sep.value.indexOf('\n')
            val nl = text.indexOf('\n', start)
            if (nl != -1 && nl <= end) {
                val candidate = text.substring(start, nl).trim()
                if (candidate.isNotEmpty()) summary = candidate
            }
            if (sep == null) break
            start = sep.range.last + 1
        }
        return summary
    }
    /** 搜索来源（可点击跳转） */
    data class Source(val title: String, val url: String)

    /** 任务清单项（status: pending / in_progress / completed） */
    data class Todo(val title: String, val status: String)

    /** 由文件工具生成的文件（内容截断保存，供聊天内预览） */
    /**
     * 生成/改动过的文件。[prev] 是这次写入**之前**的旧内容（null = 新建），
     * 有了它才能算「+N 行 / -M 行」并给出改动对比。
     */
    data class GenFile(val path: String, val content: String, val prev: String? = null)

    /**
     * 中断后续跑用的任务快照。
     * 只存「现状」不存「过程」——模型看历史也记不住自己上一轮怎么想的，
     * 与其重放几十 k token，不如告诉它目标、当前报错、已做到哪一步。
     */
    data class TaskState(
        val goal: String = "",
        /** 最后一次工具/自检的错误摘要；null = 没有报错 */
        val lastError: String? = null,
        val rounds: Int = 0,
        /** 被手动停止或进程中断（false = 正常跑完，不显示「继续」） */
        val stopped: Boolean = false
    )

    data class Msg(
        val role: String,
        val content: String,
        val imageB64: String? = null,
        val reasoning: String? = null,
        val steps: List<Step> = emptyList(),
        val sources: List<Source> = emptyList(),
        val todos: List<Todo> = emptyList(),
        val genFiles: List<GenFile> = emptyList(),
        val task: TaskState? = null,
        /**
         * 随图片一起产生的本地 OCR 文字。挂在消息上而不是只留在当轮：
         * 后续追问（「第二行的金额是多少」）仍要逐字原文核对，而图会一直被重发。
         * 放在最后，别插到中间——有好几处按位置构造函数参数。
         */
        val ocrText: String? = null,
        /** 这一轮从发出到结束的耗时（毫秒）；0 = 老数据没有记录 */
        val elapsedMs: Long = 0,
        /** 这一轮的实际用 token：输入（含缓存命中）与输出；0 = 老数据 */
        val promptTokens: Int = 0,
        val outputTokens: Int = 0
    )

    /**
     * 会话级端点覆盖：null = 跟随全局设置。
     * local=true 走本机回环；否则按 baseUrl/model/apiKey 走 OpenAI 兼容端点
     * （云端 API，或电脑上跑的 llama.cpp 等）。多个会话各自绑不同端点即可并行跑。
     */
    data class Endpoint(
        val local: Boolean = false,
        val baseUrl: String = "",
        val model: String = "",
        val apiKey: String = ""
    )

    data class Session(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<Msg>,
        /** 本会话的助手指令覆盖；null = 使用全局预设 */
        val systemPrompt: String? = null,
        /** 生效来源：单个技能或技能链的 id（互斥）；null = 手写指令或未设置 */
        val appliedSkillId: String? = null,
        val appliedChainId: String? = null,
        /** 本会话端点覆盖：null = 跟随全局设置 */
        val endpoint: Endpoint? = null,
        /** 文件夹（工作区）分组名；null = 未分组 */
        val folder: String? = null,
        /**
         * 计划模式：只读文件、不出手，先把计划说清楚等用户点头。
         * 开启后不注入任何写/执行类工具，提示词里也要求先给计划。
         */
        val planMode: Boolean = false,
        /**
         * 本会话的执行权限：read（仅可查看）/ workspace（工作区内修改）/ full（完全权限）。
         * null = 跟随全局默认（AppSettings.defaultPermission）。
         */
        val permission: String? = null
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
                        sources = parseSources(m.optJSONArray("sources")),
                        todos = parseTodos(m.optJSONArray("todos")),
                        genFiles = parseGenFiles(m.optJSONArray("genFiles")),
                        ocrText = m.optString("ocrText").takeIf { it.isNotEmpty() },
                    elapsedMs = m.optLong("elapsedMs"),
                    promptTokens = m.optInt("promptTokens"),
                    outputTokens = m.optInt("outputTokens"),
                        task = m.optJSONObject("task")?.let { t ->
                            TaskState(
                                goal = t.optString("goal"),
                                lastError = t.optString("lastError")
                                    .takeIf { it.isNotEmpty() && it != "null" },
                                rounds = t.optInt("rounds"),
                                stopped = t.optBoolean("stopped")
                            )
                        }
                    )
                }
                Session(
                    o.optString("id"),
                    o.optString("title"),
                    o.optLong("updatedAt"),
                    msgs,
                    o.optString("systemPrompt").takeIf { it.isNotEmpty() },
                    o.optString("appliedSkillId").takeIf { it.isNotEmpty() },
                    o.optString("appliedChainId").takeIf { it.isNotEmpty() },
                    o.optJSONObject("endpoint")?.let {
                        Endpoint(
                            local = it.optBoolean("local"),
                            baseUrl = it.optString("baseUrl"),
                            model = it.optString("model"),
                            apiKey = it.optString("apiKey")
                        )
                    },
                    o.optString("folder").takeIf { it.isNotEmpty() },
                    o.optBoolean("planMode"),
                    o.optString("permission").takeIf { it.isNotEmpty() }
                )
            }.ifEmpty {
                listOf(Session("1", "新会话", System.currentTimeMillis(), emptyList()))
            }
            Store(if (sessions.any { it.id == active }) active else sessions.first().id, sessions)
        }.getOrDefault(Store("1", listOf(Session("1", "新会话", System.currentTimeMillis(), emptyList()))))
    }

    private fun parseGenFiles(arr: JSONArray?): List<GenFile> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path")
            if (path.isBlank()) return@mapNotNull null
            GenFile(
                path,
                o.optString("content"),
                if (o.isNull("prev")) null else o.optString("prev")
            )
        }
    }

    private fun parseTodos(arr: JSONArray?): List<Todo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title")
            if (title.isBlank()) return@mapNotNull null
            Todo(title, o.optString("status").ifBlank { "pending" })
        }
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
                millis = o.optLong("millis"),
                args = o.optString("args").takeIf { it.isNotEmpty() && it != "null" },
                ok = if (o.has("ok")) o.optBoolean("ok", true) else true
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
                if (m.ocrText != null) o.put("ocrText", m.ocrText)
        if (m.elapsedMs > 0) o.put("elapsedMs", m.elapsedMs)
        if (m.promptTokens > 0) o.put("promptTokens", m.promptTokens)
        if (m.outputTokens > 0) o.put("outputTokens", m.outputTokens)
                if (m.reasoning != null) o.put("reasoning", m.reasoning)
                if (m.steps.isNotEmpty()) {
                    val sArr = JSONArray()
                    m.steps.forEach { st ->
                        sArr.put(
                            JSONObject().put("type", st.type).put("label", st.label)
                                .put("detail", st.detail ?: JSONObject.NULL)
                                .put("millis", st.millis)
                                .put("args", st.args ?: JSONObject.NULL)
                                .put("ok", st.ok)
                        )
                    }
                    o.put("steps", sArr)
                }
                if (m.task != null) {
                    o.put(
                        "task",
                        JSONObject().put("goal", m.task.goal)
                            .put("lastError", m.task.lastError ?: JSONObject.NULL)
                            .put("rounds", m.task.rounds)
                            .put("stopped", m.task.stopped)
                    )
                }
                if (m.genFiles.isNotEmpty()) {
                    val gArr = JSONArray()
                    m.genFiles.forEach { gf ->
                        gArr.put(
                            JSONObject().put("path", gf.path)
                                .put("content", gf.content.take(20000))
                                .apply {
                                    // 旧内容只留前 8000 字：算增删行够用，也不让会话文件膨胀
                                    gf.prev?.let { put("prev", it.take(8000)) }
                                }
                        )
                    }
                    o.put("genFiles", gArr)
                }
                if (m.todos.isNotEmpty()) {
                    val tArr = JSONArray()
                    m.todos.forEach { td ->
                        tArr.put(JSONObject().put("title", td.title).put("status", td.status))
                    }
                    o.put("todos", tArr)
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
                    .apply {
                        s.systemPrompt?.let { put("systemPrompt", it) }
                        s.appliedSkillId?.let { put("appliedSkillId", it) }
                        s.appliedChainId?.let { put("appliedChainId", it) }
                        s.folder?.let { put("folder", it) }
                        if (s.planMode) put("planMode", true)
                        s.permission?.let { put("permission", it) }
                        s.endpoint?.let { e ->
                            put(
                                "endpoint",
                                JSONObject().put("local", e.local)
                                    .put("baseUrl", e.baseUrl)
                                    .put("model", e.model)
                                    .put("apiKey", e.apiKey)
                            )
                        }
                    }
            )
        }
        root.put("sessions", arr)
        File(context.filesDir, FILE).writeText(root.toString())
    }
}
