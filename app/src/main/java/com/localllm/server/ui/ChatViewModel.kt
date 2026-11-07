package com.localllm.server.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.server.core.AdbBridge
import com.localllm.server.core.AdbTool
import com.localllm.server.core.AppSettings
import com.localllm.server.core.BashTool
import com.localllm.server.core.ChatClient
import com.localllm.server.core.ChatStore
import com.localllm.server.core.DocExtract
import com.localllm.server.core.DocMaker
import com.localllm.server.core.FileOpener
import com.localllm.server.core.FileSnapshots
import com.localllm.server.core.FileTools
import com.localllm.server.core.GgufInspector
import com.localllm.server.core.InstallTool
import com.localllm.server.core.LinuxRuntime
import com.localllm.server.core.McpArgRepair
import com.localllm.server.core.McpHttpClient
import com.localllm.server.core.OcrIntent
import com.localllm.server.core.OcrTool
import com.localllm.server.core.RepeatGuard
import com.localllm.server.core.ServerProcessManager
import com.localllm.server.core.Skill
import com.localllm.server.core.SkillChain
import com.localllm.server.core.SkillChainFlow
import com.localllm.server.core.ServerUiState
import com.localllm.server.core.ToolGuard
import com.localllm.server.core.ToolLoopPolicy
import com.localllm.server.core.ToolResultBudget
import com.localllm.server.core.UrlFetch
import com.localllm.server.core.WebRunTool
import com.localllm.server.core.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private var store = ChatStore.load(app)

    /**
     * 每台 MCP 服务器上一次 open 成功返回的工作区 id（url → workspaceId）。
     * 模型手里的 id 会漂移（把路径当 id、或拿旧 id），这里留一份服务端自己给过的，
     * 出错时用来改参数重试（见 [McpArgRepair]）。
     */
    private val mcpWorkspaceIds = mutableMapOf<String, String>()

    private val _sessions = MutableStateFlow(store.sessions)
    val sessions: StateFlow<List<ChatStore.Session>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow(store.activeId)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _messages = MutableStateFlow(
        store.sessions.find { it.id == store.activeId }?.messages ?: emptyList()
    )
    val messages: StateFlow<List<ChatStore.Msg>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /**
     * 正在生成回答的会话 id。生成归属于发起它的会话：切走不会中断，
     * 增量与收尾都写回那个会话，而不是「当前正在看的会话」。
     * 同一时间只允许一个生成（单模型手机上并发只会互相拖慢）。
     */
    private val _generating = MutableStateFlow<Set<String>>(emptySet())
    val generating: StateFlow<Set<String>> = _generating.asStateFlow()

    /** 当前会话是否在生成：界面据此显示「停止」还是「发送」 */
    val streaming: StateFlow<Boolean> =
        combine(_activeId, _generating) { id, gen -> id in gen }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 一次性提示（生成完成等），界面消费后清空 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 排队中的指令：本会话在生成时发来的下一条，跑完自动开始 */
    data class QueuedTask(val sid: String, val text: String)

    private val _taskQueue = MutableStateFlow<List<QueuedTask>>(emptyList())
    val taskQueue: StateFlow<List<QueuedTask>> = _taskQueue.asStateFlow()

    private fun enqueue(sid: String, text: String) {
        _taskQueue.update { it + QueuedTask(sid, text) }
        saveQueue()
        ServerProcessManager.appLog("指令已排队（共 ${_taskQueue.value.size} 条）：${text.take(40)}")
    }

    /** 队列落盘：进程被系统杀掉时排队的指令不该跟着消失 */
    private fun queueFile() = java.io.File(getApplication<Application>().filesDir, "task_queue.json")

    private fun saveQueue() {
        runCatching {
            val arr = org.json.JSONArray()
            _taskQueue.value.forEach { q ->
                arr.put(org.json.JSONObject().put("sid", q.sid).put("text", q.text))
            }
            queueFile().writeText(arr.toString())
        }
    }

    private fun loadQueue() {
        runCatching {
            val f = queueFile()
            if (!f.isFile) return
            val arr = org.json.JSONArray(f.readText())
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val sid = o.optString("sid")
                val text = o.optString("text")
                // 定时任务写进来的条目没有 sid：落到当前会话
                if (text.isBlank()) null else QueuedTask(sid.ifBlank { _activeId.value }, text)
            }
            if (list.isNotEmpty()) {
                _taskQueue.value = list
                ServerProcessManager.appLog("恢复排队指令 ${list.size} 条（上次进程被结束时留下的）")
            }
        }
    }

    /** 引擎就绪、本会话空闲时把排队的下一条接上（开机/进程被杀后补跑） */
    private fun kickQueue() {
        val sid = _activeId.value
        if (sid in _generating.value) return
        if (_taskQueue.value.none { it.sid == sid }) return
        // 输入框里有用户正在写的东西就不动它：drainQueue 会覆盖输入框
        if (_input.value.isNotBlank()) return
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            drainQueue(sid)
        }
    }

    /**
     * 队列里有指令、但引擎还没跑：把它拉起来，就绪后由状态监听自动接上。
     *
     * 补的是「定时任务只排队、点开通知却没反应」这个洞：原来 kickQueue 只在引擎已经 RUNNING
     * 时才会被调，没人负责启动引擎，于是队列永远躺着（提示说「点开立即执行」其实什么都不会发生）。
     */
    fun ensureEngineForQueued() {
        if (_taskQueue.value.isEmpty()) return
        val st = ServerProcessManager.state.value.status
        if (st == ServerUiState.Status.RUNNING) {   // 已经在跑：直接接上
            kickQueue()
            return
        }
        if (st == ServerUiState.Status.STARTING) return  // 正在起，等状态监听触发
        val model = AppSettings.selectedModelPath ?: AppSettings.serverModelPath ?: run {
            _toast.value = "队列里有指令，但还没选模型，无法自动启动服务"
            return
        }
        ServerProcessManager.appLog(
            "队列里还有 ${_taskQueue.value.size} 条待跑指令：自动启动本地引擎（${model.substringAfterLast('/')}）"
        )
        _lastStats.value = "队列里有 ${_taskQueue.value.size} 条指令，正在启动服务…"
        runCatching { com.localllm.server.core.ServerService.start(getApplication(), model) }
            .onFailure { _toast.value = "启动服务失败：${it.message}" }
    }

    /** 一轮跑完后取出本会话的下一条排队指令；没有就什么都不做 */
    private fun drainQueue(sid: String) {
        if (sid in _generating.value) return
        val next = _taskQueue.value.firstOrNull { it.sid == sid } ?: return
        _taskQueue.update { it - next }
        saveQueue()
        if (_activeId.value != sid) {
            // 用户已经切走了：留一条提示，切回来再说，不擅自换会话
            _toast.value = "「${next.text.take(20)}」还在队列里，切回该会话后会自动继续"
            _taskQueue.update { it + next }
            return
        }
        ServerProcessManager.appLog("开始执行排队指令：${next.text.take(40)}")
        _input.value = next.text
        send()
    }

    /** 清空排队（用户主动丢弃时用） */
    fun clearQueue() {
        ServerProcessManager.appLog("已清空排队指令（${_taskQueue.value.size} 条）")
        _taskQueue.value = emptyList()
        saveQueue()
    }

    /** 各会话的生成进度（“342 token · 28.1 tok/s”），用于全局运行中指示 */
    private val _progress = MutableStateFlow<Map<String, String>>(emptyMap())
    val progress: StateFlow<Map<String, String>> = _progress.asStateFlow()

    /** 当前本地模型的模板是否认 enable_thinking；认才下发开关，云端不干预 */
    private fun thinkSwitch(cloud: Boolean): Boolean? {
        if (cloud) return null
        val p = AppSettings.selectedModelPath ?: return null
        val d = runCatching { GgufInspector.deepInfo(java.io.File(p)) }.getOrNull() ?: return null
        return if (d.hasEnableThinking) AppSettings.thinkEnabled else null
    }

    /** 模板认 reasoning_effort 才下发等级（设置里留空则用模型默认） */
    private fun thinkEffort(cloud: Boolean): String? {
        if (cloud) return null
        val p = AppSettings.selectedModelPath ?: return null
        val d = runCatching { GgufInspector.deepInfo(java.io.File(p)) }.getOrNull() ?: return null
        return if (d.hasReasoningEffort) AppSettings.reasoningEffort else null
    }

    private fun setProgress(sid: String, text: String?) {
        _progress.value =
            if (text == null) _progress.value - sid else _progress.value + (sid to text)
    }

    fun clearToast() {
        _toast.value = null
    }

    private fun isGenerating(sid: String) = sid in _generating.value

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 待用户确认的联网搜索（图片轮 + "先问我"策略）：值为将要发送给搜索引擎的内容 */
    private val _searchApproval = MutableStateFlow<String?>(null)
    val searchApproval: StateFlow<String?> = _searchApproval.asStateFlow()
    private var approvalDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** 链接抓取缓存：url → (抓取时间, 内容)，10 分钟内复用（避免重发时重复抓取） */
    private val linkCache = mutableMapOf<String, Pair<Long, String>>()

    /** 用户对「图片轮联网搜索」的确认结果 */
    fun approveSearch(allow: Boolean) {
        approvalDeferred?.complete(allow)
        approvalDeferred = null
        _searchApproval.value = null
    }

    /** 图片轮搜索确认：弹窗等待用户决定，超时（120s）视为拒绝 */
    private suspend fun askSearchApproval(query: String): Boolean {
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        approvalDeferred = d
        _searchApproval.value = WebSearch.sanitizeQuery(query).first
        ServerProcessManager.appLog("图片轮联网搜索待用户确认：「${query.take(30)}」")
        val result = kotlinx.coroutines.withTimeoutOrNull(120_000L) { d.await() }
        if (result == null) {
            approvalDeferred = null
            _searchApproval.value = null
            ServerProcessManager.appLog("确认超时（120s），按拒绝处理")
            return false
        }
        return result
    }

    /** 待用户确认的危险操作（模型已发起、还没执行）；界面据此弹确认框 */
    data class ToolAsk(
        val title: String,
        val subjectLabel: String,
        val subject: String,
        val reason: String,
        val dangerous: Boolean,
        val canAlways: Boolean,
    )

    private val _toolAsk = MutableStateFlow<ToolAsk?>(null)
    val toolAsk: StateFlow<ToolAsk?> = _toolAsk.asStateFlow()
    private var toolAskDeferred: kotlinx.coroutines.CompletableDeferred<Int>? = null
    /** 三条会话可能同时要确认：串行排队，否则弹窗互相覆盖 */
    private val approvalLock = kotlinx.coroutines.sync.Mutex()
    /** 会话 id → 本会话已放行的键（bash / file_write / mcp:xxx）；危险命令不在其中 */
    private val sessionAutoAllow = mutableMapOf<String, MutableSet<String>>()

    /** 用户对确认框的回答：0=拒绝 1=允许一次 2=本会话总是允许 */
    fun answerToolAsk(decision: Int) {
        toolAskDeferred?.complete(decision)
        toolAskDeferred = null
        _toolAsk.value = null
    }

    /**
     * 危险操作确认：返回 true 才真的执行。
     * 超时（10 分钟没人理）按拒绝处理——宁可让模型停住，也不替用户做决定。
     */
    private suspend fun confirmTool(verdict: ToolGuard.Verdict?, sid: String): Boolean {
        if (verdict == null) return true
        if (!AppSettings.toolApprovalEnabled) return true
        // 完全权限：会话里把权限开到 full 就是不问（计划模式除外——那时候工具本来也没给）
        val sess = _sessions.value.find { it.id == sid }
        if ((sess?.permission ?: AppSettings.defaultPermission) == "full" && sess?.planMode != true) {
            return true
        }
        val allowed = sessionAutoAllow.getOrPut(sid) { mutableSetOf() }
        if (verdict.autoKey != null && allowed.contains(verdict.autoKey)) return true
        // adb 免确认开关：自动化（尤其是操作屏幕）时不再逐条打断——
        // App 在后台时弹窗根本看不见，表现就是「卡了几百秒」。DANGER 档 autoKey 为 null，仍然每次都问。
        if (AppSettings.adbAutoAllow && verdict.autoKey?.startsWith("adb") == true) {
            ServerProcessManager.appLog("adb 免确认已开启，直接放行：${verdict.subject.take(60)}")
            return true
        }
        return approvalLock.withLock {
            // 排队期间别的会话可能已经放行同类操作
            if (verdict.autoKey != null && allowed.contains(verdict.autoKey)) return@withLock true
            val d = kotlinx.coroutines.CompletableDeferred<Int>()
            toolAskDeferred = d
            _toolAsk.value = ToolAsk(
                title = verdict.title,
                subjectLabel = verdict.subjectLabel,
                subject = verdict.subject,
                reason = verdict.reason,
                dangerous = verdict.risk == ToolGuard.Risk.DANGER,
                canAlways = verdict.autoKey != null,
            )
            ServerProcessManager.appLog("等待确认：${verdict.title.removeSuffix("？")}")
            // 用户可能正在别的 App 里（agent 自己切走的）：弹窗看不见就以为卡死。
            // 把话说在状态行上，切回来一眼能看懂；也给「本会话总是允许 / 免确认」指路。
            if (_activeId.value == sid) {
                _lastStats.value = "等你确认：${verdict.title.removeSuffix("？")}" +
                        "（如果没看到弹窗，说明本 App 在后台，切回来即可）"
            }
            val decision = kotlinx.coroutines.withTimeoutOrNull(600_000L) { d.await() }
            toolAskDeferred = null
            _toolAsk.value = null
            when (decision) {
                1 -> {
                    ServerProcessManager.appLog("用户允许了一次")
                    true
                }

                2 -> {
                    ServerProcessManager.appLog("用户允许本会话同类操作：${verdict.autoKey}")
                    verdict.autoKey?.let { allowed.add(it) }
                    true
                }

                null -> {
                    ServerProcessManager.appLog("确认超时（10 分钟），按拒绝处理")
                    false
                }

                else -> {
                    ServerProcessManager.appLog("用户拒绝了")
                    false
                }
            }
        }
    }

    private val _lastStats = MutableStateFlow<String?>(null)
    val lastStats: StateFlow<String?> = _lastStats.asStateFlow()

    /**
     * 上一次请求的上下文占用（used, limit）；null = 还没有数据。
     * 按会话分开存：输入框右边圆环读的是「当前会话」那一份。
     */
    private val _ctxBySession = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val contextUsage: StateFlow<Pair<Int, Int>?> =
        combine(_activeId, _ctxBySession) { id, m -> m[id] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 上一次请求的用量明细（输入框右边圆环点开的那张卡）。
     * 系统/工具/消息是按本轮实际下发的 prompt 估的，误差在几十分之一，用来看「谁在吃上下文」够了。
     */
    data class Usage(
        val prompt: Int,
        val output: Int,
        val cacheHit: Int,
        val rounds: Int,
        val systemTokens: Int,
        val toolTokens: Int,
        val messageTokens: Int,
    )

    private val _usageBySession = MutableStateFlow<Map<String, Usage>>(emptyMap())
    /** 当前会话的用量明细；切会话跟着切，后台会话跑完不会把屏幕上的数字换掉 */
    val usage: StateFlow<Usage?> =
        combine(_activeId, _usageBySession) { id, m -> m[id] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pendingImageB64 = MutableStateFlow<String?>(null)
    /** 这次图片实际怎么处理的（原样直传 / 转码保分辨率 / 降到多大）：界面照着显示，别一律写「已压缩」 */
    private val _pendingImageNote = MutableStateFlow<String?>(null)
    val pendingImageNote: StateFlow<String?> = _pendingImageNote.asStateFlow()
    /** 原图 base64（仅用于 OCR，不传模型、不落盘） */
    private val _pendingImageFullB64 = MutableStateFlow<String?>(null)

    /** 附件时用原图跑一次的本地 OCR 结果；随消息落盘，后续追问也能核对数字 */
    private val _pendingOcrText = MutableStateFlow<String?>(null)
    val pendingImageB64: StateFlow<String?> = _pendingImageB64.asStateFlow()

    /**
     * 每个会话一条生成任务：不同会话可以并行跑（云端 API / 本地服务 / 电脑上的模型各一条），
     * 键是会话 id。同一条会话仍然一次只跑一个（重复发送没有意义）。
     */
    private val streamJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** 同时最多几条会话在生成：再多设备扛不住，也没人看得过来 */
    private val MAX_PARALLEL_GENERATIONS = 3

    /** 自驱模式最多自动续跑几次（再多就是模型跑偏了，停下来交给人看） */
    private val MAX_AUTODRIVE = 5

    /**
     * 熔断阈值：**工具名 + 参数完全一致**的调用连续失败几次就不再给它这个工具。
     * 刻意定高（5 次）——换个路径、改个参数的重试是正常行为，不能误伤。
     */
    private val SAME_CALL_FAIL_LIMIT = 5

    /** 宽松硬上限：轮次设为「不限」时的兜底，防止单任务把机器跑到底 */
    private val HARD_ROUND_CAP = 200
    private val HARD_TIME_MS = 20 * 60 * 1000L
    // MCP 工具注入阈值由设置控制（0 = 不限，全给）；超过阈值才按关键词筛
    private val maxToolsFull: Int
        get() = AppSettings.mcpToolFullLimit

    /**
     * 原图小于这个大小就直接原样送模型，不重编码。
     * 之前所有图都重编码成 JPEG 85、长边压到 1024–2047：截图里的小字、小数点先糊，
     * 模型读不出来（同一张图别的客户端不压就能认）。
     */
    private val IMAGE_PASSTHROUGH_BYTES = 1536 * 1024

    /** 超大图才降采样，长边上限（原来的 1024–2047 太小） */
    private val MAX_IMAGE_SIDE = 3072

    /**
     * 清单更新次数的**跑飞护栏**（不是步数上限）：十步的任务光打勾就要二十来次更新，
     * 所以这个值必须远高于真实任务的用量；它只在模型只刷清单、别的不干时兜底。
     * （曾经是 3，撞上就拒绝更新，长任务的清单于是停在半路。）
     */

    /** 纯文本附件的内存上限（不是上下文上限：装不装得下由后面的预算截断决定） */
    private val PLAIN_ATTACH_MAX_BYTES = 32 * 1024 * 1024

    /**
     * 错误提示的显示长度上限（可在设置里调，0 = 不限）。
     * 为什么要限：引擎会把整段工具调用参数回显进 500 响应（实测到 30 KB+），
     * 而这条文案是直接铺在聊天页上的 Text——超长文本会把 Compose 的文本测量拖死，
     * 整个界面点不动、连新会话都切不过去，只能杀进程恢复。完整内容仍然写进日志。
     */
    private val maxErrorChars: Int
        get() = AppSettings.errorMaxChars

    /** 技能链步骤的失败标志：只看行首，避免「没有错误」这类否定句误判 */
    private val CHAIN_FAIL_MARKERS = listOf(
        "错误：", "错误:", "失败：", "失败:", "无法完成", "无法继续", "执行失败", "超时"
    )
    // 实测 26 个工具 ≈ 9.1K token（每个约 350），对 0.5B 来说 prefill 就是几分钟。
    // 小模型本来也用不好二三十个工具，收到 8 个是「首字时间 vs 可用性」的折中。
    private val MAX_TOOLS_PICKED = 8
    /** 本轮结束时上下文占用过高 → 打完这一轮自动压缩一次 */
    private var autoCompressPending = false

    fun onInput(v: String) { _input.value = v }

    init {
        // 上次进程被结束时还剩的排队指令：读回来，接着排队
        runCatching { loadQueue() }
        // 进程在后台被杀时，那一轮的 stopped 还是 false，正文也可能为空：
        // 重开时把它们标成「被打断」，于是「继续这个任务」会出现，而不是看起来什么都没发生。
        runCatching {
            val fixed = _sessions.value.map { s ->
                val last = s.messages.lastOrNull()
                val t = last?.task
                if (last != null && last.role == "assistant" && t != null &&
                    !t.stopped && last.content.isBlank()
                ) {
                    s.copy(
                        messages = s.messages.dropLast(1) +
                                last.copy(task = t.copy(stopped = true))
                    )
                } else s
            }
            if (fixed != _sessions.value) {
                _sessions.value = fixed
                if (_activeId.value == store.activeId) {
                    _messages.value = fixed.find { it.id == _activeId.value }?.messages
                        ?: _messages.value
                }
                saveStore(fixed, _activeId.value)
                ServerProcessManager.appLog("检测到被中断的任务，已标记为可续跑")
            }
        }
        // prefill 进度接到状态行：这个阶段服务器不吐 token，不显示进度看着就像卡死
        viewModelScope.launch {
            ServerProcessManager.prefill.collect { p ->
                val sid = _generating.value.firstOrNull() ?: return@collect
                if (p != null) {
                    setProgress(sid, "正在处理上下文 ${(p * 100).toInt()}%")
                    if (_activeId.value == sid) {
                        _lastStats.value = "正在处理上下文 ${(p * 100).toInt()}%"
                    }
                }
            }
        }
        // 服务就绪后自动清掉"请先启动服务"之类的连接错误（避免回到会话页仍显示红字）
        viewModelScope.launch {
            ServerProcessManager.state.collect { st ->
                if (st.status == ServerUiState.Status.RUNNING) {
                    val cur = _error.value ?: return@collect
                    if (cur.contains("启动服务") || cur.contains("连接失败")) {
                        _error.value = null
                    }
                }
            }
        }
        // 服务就绪后把上次剩下的排队指令接上。原来只把队列读回内存，没人触发就永远躺着——
        // 通知上写着「点开立即执行」，实际点开什么都不会发生。
        viewModelScope.launch {
            ServerProcessManager.state.collect { st ->
                if (st.status == ServerUiState.Status.RUNNING) kickQueue()
            }
        }
        // 打开 App 时如果队列里还有东西（定时任务只排了队、或上次进程被杀），
        // 自己把引擎拉起来，就绪后接着执行——这才是「点开通知就开始跑」
        viewModelScope.launch {
            kotlinx.coroutines.delay(800)
            runCatching { ensureEngineForQueued() }
        }
        // 接收外部分享内容：填进输入框并提示
        viewModelScope.launch {
            com.localllm.server.core.ShareInbox.text.collect { shared ->
                val v = com.localllm.server.core.ShareInbox.consume() ?: return@collect
                _input.value = v
                _lastStats.value = "已接收分享内容（${v.length} 字），确认后点发送"
                ServerProcessManager.appLog("已接收分享内容：${v.take(60)}")
            }
        }
    }

    fun attachImage(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val origBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("图片读取失败")
                // 压缩档位来自设置：默认不压缩——重编码是截图小字糊掉的主因。
                // 但"不压缩"也有上限（6MB）：再大的图 base64 之后请求体会大到拖死 prefill。
                val mode = AppSettings.imageCompressMode
                val native = com.localllm.server.core.ImageFormats.serverReadable(origBytes)
                val origFormat = com.localllm.server.core.ImageFormats.magic(origBytes) ?: "未知"
                // 不压缩档位的兜底上限：再往上 base64 之后请求体会把预填充拖死
                val ORIGINAL_LIMIT = 50 * 1024 * 1024
                val passthrough = when (mode) {
                    "small" -> false
                    "auto" -> native && origBytes.size <= IMAGE_PASSTHROUGH_BYTES
                    else -> native && origBytes.size <= ORIGINAL_LIMIT
                }
                val b64: String = if (passthrough) {
                    val mb = origBytes.size / 1024.0 / 1024.0
                    _pendingImageNote.value = "原图直传 ${String.format(java.util.Locale.CHINA, "%.1f", mb)}MB（未压缩）"
                    ServerProcessManager.appLog(
                        "图片：${origBytes.size / 1024}KB 原样直传（未压缩，档位 $mode，格式 $origFormat）"
                    )
                    android.util.Base64.encodeToString(origBytes, android.util.Base64.NO_WRAP)
                } else if (mode == "original") {
                    // 不压缩档走到这里只有两种原因：格式模型读不了（手机拍照常是 HEIC），
                    // 或者文件超过 20MB。两种都**不降分辨率**：能换容器就换容器，
                    // 实在太大才逐步缩边长——用户选的是「别偷偷压小」。
                    encodeKeepingResolution(origBytes, ORIGINAL_LIMIT)
                } else {
                    // 自动 / 省上下文：按档位降采样
                    val maxSide = if (mode == "small") 1600 else MAX_IMAGE_SIDE
                    val quality = if (mode == "small") 85 else 95
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(origBytes, 0, origBytes.size, bounds)
                    var sample = 1
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                    while (maxDim / sample > maxSide) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(origBytes, 0, origBytes.size, opts)
                        ?: throw IllegalStateException("图片解码失败")
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
                    val outW = bmp.width
                    val outH = bmp.height
                    bmp.recycle()
                    _pendingImageNote.value =
                        "已压到 ${outW}×${outH}（${baos.size() / 1024}KB，质量 $quality）"
                    ServerProcessManager.appLog(
                        "图片：原图 ${origBytes.size / 1024}KB → 压缩 ${baos.size() / 1024}KB" +
                                "（档位 $mode，长边 ≤ $maxSide，质量 $quality）"
                    )
                    android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                }
                _pendingImageB64.value = b64
                // OCR 用原图（压缩图会让小字/小数点糊掉，数字容易错）
                runCatching {
                    if (origBytes.size <= 8 * 1024 * 1024) {
                        val fullB64 = android.util.Base64.encodeToString(origBytes, android.util.Base64.NO_WRAP)
                        _pendingImageFullB64.value = fullB64
                        // 附件时就跑一次本地 OCR：视觉模型读数字会错、读不清会编，
                        // 逐字原文跟图一起给模型核对（版面仍以图为准）。
                        // 图里字太少（照片/插图）就不附带，免得白占上下文。
                        val text = runCatching { OcrTool.recognize(fullB64) }.getOrNull()
                        _pendingOcrText.value = text
                        if (OcrTool.promptBlock(text) != null) {
                            ServerProcessManager.appLog(
                                "图片：本地 OCR 预识别 ${text!!.length} 字，将与图一起送给模型"
                            )
                        }
                    }
                }
            }.onFailure {
                _error.value = "图片处理失败：${it.message}"
            }
        }
    }

    fun clearPendingImage() {
        _pendingImageB64.value = null
        _pendingImageFullB64.value = null
        _pendingOcrText.value = null
    }

    /**
     * 不压缩档的转码：优先**保持原分辨率**，只在结果超过 [limit] 时才逐步缩边长。
     * 用于两种情况——模型读不了的格式（手机拍照多是 HEIC）、以及超过上限的大图。
     * 质量从 100 起，只在仍超标时降一档，绝不为了体积先偷偷缩小。
     */
    private fun encodeKeepingResolution(origBytes: ByteArray, limit: Int): String {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(origBytes, 0, origBytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) throw IllegalStateException("图片解码失败")

        var maxSide = maxOf(srcW, srcH)
        var quality = 100
        var attempt = 0
        while (true) {
            attempt++
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = if (maxSide >= maxOf(srcW, srcH)) 1 else scaleSample(srcW, srcH, maxSide)
            }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(origBytes, 0, origBytes.size, opts)
                ?: throw IllegalStateException("图片解码失败")
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
            val outW = bmp.width
            val outH = bmp.height
            bmp.recycle()
            val outBytes = baos.size()
            if (outBytes <= limit || attempt >= 4) {
                _pendingImageNote.value =
                    (if (outW == srcW && outH == srcH) "转 JPEG 保持原分辨率 ${outW}×${outH}"
                    else "转 JPEG ${outW}×${outH}（原 ${srcW}×${srcH}，超 ${limit / 1024 / 1024}MB 才缩）") +
                            "，质量 $quality"
                ServerProcessManager.appLog(
                    "图片：${origBytes.size / 1024}KB → ${outBytes / 1024}KB（转 JPEG，" +
                            if (outW == srcW && outH == srcH) "保持原分辨率 $outW×$outH"
                            else "长边 $outW×$outH（原 $srcW×$srcH，超 ${limit / 1024}KB 才缩）" +
                                    "，质量 $quality）"
                )
                return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            }
            // 还太大：先降质量，再缩边长
            if (quality > 92) quality -= 4
            else maxSide = (maxSide * 3) / 4
        }
    }

    private fun scaleSample(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        val dim = maxOf(w, h)
        while (dim / sample > maxSide) sample *= 2
        return sample
    }

    /**
     * 历史消息里可能躺着本地引擎读不了的图（旧版本把 WebP 当成可直传格式存了下来）：
     * 发之前转一道 JPEG。云端也收得下，代价只是重新编码一次。
     *
     * 返回 (base64, mime)。
     */
    private fun ensureReadableForServer(b64: String): Pair<String, String> {
        val fmt = com.localllm.server.core.ImageFormats.formatOfB64(b64)
        if (fmt == null || fmt in setOf("jpeg", "png", "gif", "bmp")) {
            return b64 to com.localllm.server.core.ImageFormats.mimeOf(fmt)
        }
        return runCatching {
            val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: throw IllegalStateException("解码失败")
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, baos)
            bmp.recycle()
            ServerProcessManager.appLog("图片：$fmt 本地引擎读不了，已转 JPEG 后发送")
            android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP) to "image/jpeg"
        }.getOrDefault(b64 to com.localllm.server.core.ImageFormats.mimeOf(fmt))
    }

    // ---- 文本类附件（文档/代码等）：读成文本随消息一起发送 ----
    private val _pendingFile = MutableStateFlow<Pair<String, String>?>(null) // (文件名, 文本)
    // 音视频附件：原样(base64)发送，不抽文本。只挂在当前这一轮，不落盘（体积太大）
    private val _pendingMediaB64 = MutableStateFlow<String?>(null)
    private val _pendingMediaMime = MutableStateFlow<String?>(null)
    private val _pendingMediaName = MutableStateFlow<String?>(null)
    private val _pendingMediaNote = MutableStateFlow<String?>(null)
    val pendingMediaNote: StateFlow<String?> = _pendingMediaNote.asStateFlow()

    /** 音视频上限：base64 之后请求体会膨胀 1/3，再大就别指望一次传上去 */
    private val MEDIA_MAX_BYTES = 25 * 1024 * 1024
    val pendingFile: StateFlow<Pair<String, String>?> = _pendingFile.asStateFlow()

    fun clearPendingFile() { _pendingFile.value = null }

    /** 清掉音视频附件 */
    fun clearPendingMedia() {
        _pendingMediaB64.value = null
        _pendingMediaMime.value = null
        _pendingMediaName.value = null
        _pendingMediaNote.value = null
    }


    fun attachFile(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val name = runCatching {
                    cr.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment ?: "文件"
                val mime = cr.getType(uri) ?: ""
                val bytes = runCatching {
                    cr.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    _error.value = "读取文件失败或文件为空"
                    return@launch
                }
                // 文档走本地结构化抽取（pdf/docx/xlsx/pptx/rtf）；其它按纯文本读取
                val doc = DocExtract.extract(bytes, name, mime)
                val text = if (doc != null) {
                    if (doc.text.isBlank()) {
                        _error.value = doc.note ?: when (doc.kind) {
                            "pdf" -> "PDF 没有可提取的文字层（可能是扫描件）：可截图后走 OCR，或使用视觉模型"
                            "docx" -> "Word 文档没有可提取的文字"
                            else -> "文件里没有可提取的文字"
                        }
                        return@launch
                    }
                    ServerProcessManager.appLog(
                        "文档抽取：$name → ${doc.kind.uppercase()} ${doc.text.length} 字" +
                                (doc.note?.let { "（$it）" } ?: "")
                    )
                    "（${doc.kind.uppercase()} 已抽取文本）\n" + doc.text +
                            (doc.note?.let { "\n[$it]" } ?: "")
                } else {
                    // 二进制文件不能按 UTF-8 读：那样得到的是一堆乱码，模型还以为这就是文件内容
                    val binMime = mime.ifBlank { FileOpener.mimeOf(name) }
                    val isMedia = binMime.startsWith("audio/") || binMime.startsWith("video/")
                    if (!isMedia) {
                        _error.value = "「$name」（$binMime）不是文本，当前协议没有直接传输它的通道。" +
                                "可以：①图片直接发（原样上传）；②音视频直接发（走原样传输）；" +
                                "③PDF/Office 会被抽取成文本；④其它二进制请先用 run_bash 处理成文本/图片再发。"
                        return@launch
                    }
                    if (bytes.size > MEDIA_MAX_BYTES) {
                        _error.value = "音视频超过 ${MEDIA_MAX_BYTES / 1024 / 1024}MB，请先压缩或截取片段"
                        return@launch
                    }
                    // 音视频：**原样**（base64）交给支持它们的端点，不抽文本、不乱码
                    _pendingMediaB64.value = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    _pendingMediaMime.value = binMime
                    _pendingMediaName.value = name
                    _pendingMediaNote.value =
                        "原样发送 $binMime（${String.format(java.util.Locale.CHINA, "%.1f", bytes.size / 1024.0 / 1024.0)}MB）"
                    ServerProcessManager.appLog("音视频附件：$name（$binMime，${bytes.size / 1024}KB，原样发送）")
                    return@launch
                }
                if (text.isBlank()) {
                    _error.value = "文件为空或格式不支持（支持：图片、文本、PDF、docx、xlsx）"
                    return@launch
                }
                _pendingFile.value = name to text
                ServerProcessManager.appLog("已附加文件：$name（${text.length} 字）")
            } catch (e: Exception) {
                _error.value = "读取文件失败：${e.message}"
            }
        }
    }

    fun send() {
        val text = _input.value.trim()
        val image = _pendingImageB64.value
        val file = _pendingFile.value
        // 音视频要提前拦：本地引擎没有多模态投影时会直接回 500（video input is not supported），
        // 报错还夹在「你好」这种无关的问题后面，用户根本不知道是附件的问题
        if (_pendingMediaB64.value != null) {
            val ep = endpointFor(_activeId.value)
            val mmproj = AppSettings.effectiveMmproj(AppSettings.selectedModelPath)
            if (ep.local && mmproj.isNullOrBlank()) {
                _error.value = "音视频需要多模态投影：本地模型没配 mmproj，引擎会拒绝（video/audio input is not supported）。" +
                        "两个办法：①到「模型」页给这个模型选配套的 mmproj 文件；②把本会话端点切到支持音视频的云端渠道。"
                return
            }
        }
        if ((text.isEmpty() && image == null && file == null && _pendingMediaB64.value == null) || blockIfBusy()) return
        _input.value = ""
        _pendingImageB64.value = null
        _pendingFile.value = null
        val ocrSource = _pendingImageFullB64.value
        _pendingImageFullB64.value = null
        val ocrText = _pendingOcrText.value
        _pendingOcrText.value = null
        val content = if (file != null) {
            // 附件截断比例可配：0 = 不按比例截断，交给模型自己的上下文上限去报错
            val pct = AppSettings.attachmentBudgetPct
            val raw = file.second
            val kept = if (pct <= 0) raw else {
                val ctx = AppSettings.effectiveCtx(AppSettings.selectedModelPath)
                val historyEst = _messages.value.sumOf { estimateTokens(it.content) }
                val budgetTokens = (ctx * (pct / 100.0)).toInt() - historyEst - 1024
                val maxChars = maxOf(500, (budgetTokens * 1.6).toInt())
                if (raw.length > maxChars) raw.take(maxChars) else raw
            }
            val note = if (kept.length < raw.length)
                "\n[附件过长已截断：原文 " + raw.length + " 字，本次仅发送前 " + kept.length +
                        " 字（可在设置里把「附件占上下文」调到不限）]"
            else ""
            ServerProcessManager.appLog(
                "附件 " + file.first + "：" + raw.length + " 字，估算 ~" + estimateTokens(raw) +
                        " tokens；占上下文上限 $pct%，实际发送 " + kept.length + " 字" +
                        if (note.isEmpty()) "" else "（已截断）"
            )
            "【附件：" + file.first + "】\n```\n" + kept + "\n```" + note + "\n\n" + text
        } else {
            text
        }
        // 只上传图/文件、没打字时不再自动补「请描述这张图片」这类指令：
        // 用户要的是把东西丢给模型，不是让模型按我们预设的句式回答。
        // 内容保持空串，图片本身随消息发出；任务快照里用占位名只是为了界面上有标题。
        val goalPlaceholder = when {
            image != null -> "（图片）"
            file != null -> "（附件）"
            else -> ""
        }
        append(ChatStore.Msg("user", content, image, ocrText = ocrText))
        append(
            ChatStore.Msg(
                "assistant", "",
                task = ChatStore.TaskState(goal = content.ifBlank { goalPlaceholder }.take(300))
            )
        )
        doStream(ocrSource)
    }

    /**
     * 并行限制：同一条会话一次只跑一个（重复发送没有意义）；
     * 不同会话可以同时生成，总数不超过 MAX_PARALLEL_GENERATIONS。
     */
    private fun blockIfBusy(): Boolean {
        val sid = _activeId.value
        if (sid in _generating.value) {
            // 本会话在生成：把这条指令排队，跑完自动接着做，而不是让它丢掉
            val pending = _input.value.trim()
            if (pending.isNotEmpty()) {
                enqueue(sid, pending)
                _input.value = ""
                _lastStats.value = "已排队（第 ${_taskQueue.value.size} 个），当前任务结束会自动开始"
                _toast.value = "已排队：当前任务结束后自动开始"
            } else {
                _lastStats.value = "本会话正在生成，稍等或先停止"
                _toast.value = "本会话正在生成，稍等或先停止"
            }
            return true
        }
        if (_generating.value.size >= MAX_PARALLEL_GENERATIONS) {
            val hint = "已有 ${_generating.value.size} 条会话在生成（上限 $MAX_PARALLEL_GENERATIONS），" +
                    "等一条跑完再发"
            _lastStats.value = hint
            _toast.value = hint
            return true
        }
        return false
    }

    /** 本会话实际使用的端点：会话级覆盖优先，否则跟随全局（云端开关 + 当前渠道） */
    private fun endpointFor(sid: String): ChatStore.Endpoint {
        _sessions.value.find { it.id == sid }?.endpoint?.let { return it }
        val globalCloud = AppSettings.chatCloudEnabled && AppSettings.chatCloudUrl.isNotBlank()
        return if (globalCloud) {
            ChatStore.Endpoint(
                local = false,
                baseUrl = AppSettings.chatCloudUrl,
                model = AppSettings.chatCloudModel.ifBlank { "default" },
                apiKey = AppSettings.chatCloudKey
            )
        } else {
            ChatStore.Endpoint(local = true)
        }
    }

    fun resendAt(index: Int) {
        if (blockIfBusy()) return
        val list = _messages.value.toMutableList()
        if (index !in list.indices || list[index].role != "user") return
        _messages.value = list.subList(0, index + 1).toMutableList()
        append(ChatStore.Msg("assistant", ""))
        doStream()
    }

    fun editAndResend(index: Int, newText: String) {
        val text = newText.trim()
        if (text.isEmpty() || blockIfBusy()) return
        val list = _messages.value.toMutableList()
        if (index !in list.indices || list[index].role != "user") return
        list[index] = list[index].copy(content = text, reasoning = null)
        _messages.value = list.subList(0, index + 1).toMutableList()
        // 改的是第一条：会话标题也跟着重来。否则标题还是旧请求（比如「打开小红书…」），
        // 用户看着就像上下文串了——虽然模型其实拿不到标题
        if (index == 0) {
            val id = _activeId.value
            _sessions.value = _sessions.value.map {
                if (it.id == id) it.copy(title = "") else it
            }
        }
        append(ChatStore.Msg("assistant", ""))
        doStream()
    }

    private fun doStream(ocrSource: String? = null) {
        // 生成归属于发起它的会话：后续所有增量/收尾都按 sid 找会话，不再看当前在哪个会话
        val sid = _activeId.value
        // 已经发出新指令 = 放弃之前被中断的任务：收掉旧续跑入口，
        // 否则之后误点会把续跑指令追加到末尾，和新指令混成两条任务线。
        clearStoppedTasks(sid)
        // send / 重新发送 / 编辑后重发 都会走到这里，统一补任务快照。
        // 只在 send() 里建的话，走另外两个入口的消息就没有快照，停止后也不会出现续跑按钮。
        val goalText = messagesOf(sid).lastOrNull { it.role == "user" }?.content ?: ""
        if (goalText.isNotBlank()) {
            appendToLastInline(sid) { m ->
                if (m.role == "assistant" && m.task == null) {
                    m.copy(task = ChatStore.TaskState(goal = goalText.take(300)))
                } else m
            }
        }
        streamJobs[sid] = viewModelScope.launch {
            _generating.update { it + sid }
            _error.value = null
            _lastStats.value = null
            // 技能链走执行器：每步一次生成、失败可换方案。
            // 旧实现是把各步提示词拼成一段「第 1 步…第 2 步…」让模型一口气做完，
            // 中间结果看不到，也没有真正的条件分支。
            val chain = _sessions.value.firstOrNull { it.id == sid }?.appliedChainId
                ?.let { cid -> AppSettings.loadSkillChains().firstOrNull { it.id == cid } }
            if (chain != null && goalText.isNotBlank()) {
                runSkillChain(sid, chain, goalText)
                return@launch
            }
            // 复读中断标记（正文里同一段反复出现；实测会把 file_write 的 JSON 撑爆）
            var repetitionAborted = false
            // 工具调用参数被写崩时的降级重试：catch 里拿不到 try 内的局部变量，这里先留出口。
            // 一旦服务端因工具参数 JSON 不闭合返回 500，去掉 tools 重发一次，让用户至少拿到文字答案。
            var turnSystem: String? = null
            var turnHistory: List<ChatStore.Msg> = emptyList()
            var turnHadTools = false
            var toolJsonRetried = false
            // 心跳任务：每轮重置，必须先取消上一轮的，否则多个心跳同时写状态行，秒数会来回跳
            var heartbeat: kotlinx.coroutines.Job? = null
            var abortDeltas = 0
            val sendStartedAt = System.currentTimeMillis()
            // 这一轮的标识：文件快照按它分组，「撤销整轮」靠它找回动手之前的样子
            val turnId = sendStartedAt
            activeTurnId = turnId
            appendToLastInline(sid) { m ->
                if (m.role == "assistant" && m.turnId == 0L) m.copy(turnId = turnId) else m
            }
            try {
                // 提示词保持最小化：过多硬性约束会让小模型在思考里反复“检查规则”而失控。
                // LaTeX 已有客户端转换器兜底，不再要求模型禁用。
                val searchOn = AppSettings.webSearchEnabled
                // 任务清单（todo_write）已移除：本地小模型基本只是在反复刷清单，
                // 每次更新还占一次工具调用与一轮上下文，收益抵不上成本。
                val todoOn = false
                val memoryOn = false

                // 本会话的执行权限与计划模式：权限决定「能给哪些工具」，计划模式只留读类工具。
                // 默认值取自设置，会话可以单独覆盖（面板里切换就改这一条会话）。
                val selfSession = _sessions.value.find { it.id == sid }
                val permission = selfSession?.permission ?: AppSettings.defaultPermission
                val planMode = selfSession?.planMode == true
                // 自驱：模型想收尾时先按客观判据查一遍，没达成就有上限地继续
                val autoDrive = selfSession?.autoDrive == true && !planMode
                var autoDriveCount = 0
                var lastStallKey: String? = null
                val readOnly = permission == "read" || planMode
                val fullAccess = permission == "full" && !planMode
                // 授权过工作目录就注入文件工具；读类（列/读/搜）在只读与计划模式下都要留着——
                // 制定计划前总得能看现状，砍光了模型只能瞎猜。
                val fileToolsOn = AppSettings.fileToolsEnabled && AppSettings.workDirTreeUri.isNotBlank()
                // 写类（写入/编辑/生成文档）和跑命令只在非只读时给
                val fileOn = !readOnly && fileToolsOn
                val sandboxOn = !readOnly && AppSettings.webSandboxEnabled
                // 隐私护栏：本轮用户消息含图片（可能是身份证/证件等敏感件）→ 不注入任何联网搜索工具，
                // 并把模型幻觉出的 web_search 调用靠白名单挡掉（OCR 文字不会流向搜索引擎）。
                val turnHasImage = _messages.value.dropLast(1).lastOrNull()?.imageB64 != null
                // 含图片轮：仅「禁止」策略时不注入搜索工具；「先问我」由执行前弹窗把关；「允许」照常
                val searchBlockedByImage =
                    turnHasImage && AppSettings.imageSearchPolicy == "block"
                val searchAvailable = searchOn && !searchBlockedByImage
                if (searchOn && searchBlockedByImage) {
                    ServerProcessManager.appLog("本轮含图片：出于隐私保护未注入联网搜索工具")
                }
                val today = java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA)
                    .format(java.util.Date())
                // 助手指令：会话级覆盖优先。全局预设那套（sysPrompt 预设列表）已删——
                // UI 早就没有了，留着只会让人以为「某份预设还在悄悄生效」
                val sessionPrompt = _sessions.value.find { it.id == _activeId.value }?.systemPrompt
                val basePrompt = sessionPrompt.orEmpty()
                // 长期记忆已停用：小模型不会主动写，写了也未必在后续轮次用上，
                // 而写错的条目会一直跟着每一轮提示词走。memory.md 文件保留，不删数据。
                val memoryText = ""
                val memoryHint = if (memoryText.isNotBlank()) {
                    "\n\n【长期记忆（用户偏好/背景，供参考）】\n" + memoryText
                } else ""
                // 意图判别：本轮是否真的要「产出可运行/可打开的东西」。
                // 只看关键词会在多轮迭代里失灵（"颜色改成蓝色"一个关键词都不含，但仍在造物），
                // 所以叠加历史判据：上一轮已动过 web_run / 文件工具，就继续算造物任务。
                // 判错的代价不对称——误判成闲聊只是这一轮少了个自检工具，用户再说一次即可；
                // 误判成造物则会让「介绍一下茶文化」这种问题被改成写 HTML 页面。
                val history = _messages.value.dropLast(1)
                val lastUserText = history.lastOrNull { it.role == "user" }?.content ?: ""
                val artifactWordPattern = Regex(
                    // 强信号：「做个X」「写个X」式的造物动词 + 量词，X 是什么都算。
                    // 但「做个自我介绍」「帮我做个总结」要的是文字，用后视排除掉这批宾语。
                    "(写|做|生成|实现|画|搭|撸|弄|整|出|来)(个|一个|一款|一份)" +
                            "(?!自我介绍|总结|决定|选择|判断|保证|解释|说明|比较|对比|分析|介绍|回答|评价|推荐|计划|概述|概括)" +
                            // 次强信号：造物动词 + 产出物名词（允许中间夹"介绍茶文化的"这类定语）。
                            // 「整理/汇总/转成/导出」专管「把这段整理成 markdown 文档」这类改写需求。
                            "|(写|做|生成|实现|改|来个|帮我做|弄个|整个|出个|画个|搭个|撸|整理|汇总|转成|导出).{0,12}" +
                            "(页面|网页|html|程序|工具|小工具|app|应用|游戏|可视化|图表|脚本|demo|原型|界面|组件" +
                            "|markdown|\\bmd\\b|文档|报告)" +
                            // 倒装跟进：「把这个页面改成深色」「把首页调一下」——名词在前动词在后
                            "|(首页|页面|网页|界面|样式|布局|配色|组件|表格|图表|字体|字号|颜色|背景|按钮|图片).{0,10}(改|调|换|加|删|修|优化)" +
                            "|单文件|生成文件|完整代码",
                    RegexOption.IGNORE_CASE
                )
                val wantsArtifact = artifactWordPattern.containsMatchIn(lastUserText) ||
                        history.any { m ->
                            m.role == "assistant" && m.steps.any { st ->
                                st.type == "tool" &&
                                        (st.label.contains(WebRunTool.NAME) || st.label.startsWith("文件："))
                            }
                        }
                val fileHint = if (fileOn) {
                    // 路径约定要短、要分句：实测小模型会把「工作目录」和「Linux 容器的 /root」混着用，
                    // 一会读 /root、一会读 /，全都不存在，然后反复重试到轮次耗尽。
                    "文件工具用相对路径，直接写文件名或子目录名（如 report.xlsx、demo/a.html），" +
                            "它们都在用户的工作目录里。不要用 /、/root、/home、/sdcard 去猜文件在哪。" +
                            "run_bash 是另一个 Linux 环境，里面没有用户的文件，不要用 bash 找文件。" +
                            "如需生成文件（HTML/代码/CSV）用 file_write；需要真正的 Excel 表格用 make_xlsx（rows 二维数组）；" +
                            "需要 PDF 用 make_pdf。都写入工作目录，并在回答里告知文件名；" +
                            "已经存在的文件只改一两处时，用 file_patch（给 old/new 片段），" +
                            "不要用 file_write 重吐全文——那样每轮都要重新输出整份文件，慢且容易把已对的部分改坏；" +
                            "查看已有文件用 file_list / file_read / file_grep；" +
                            "**关于设备/系统的状态类问题（版本号、文件是否在、命令能否跑、目录内容），" +
                            "必须先用 run_bash 实测再回答，禁止凭记忆给答案——你记忆里的版本号不是这台机器上的版本号。**"
                } else ""
                val todoHint = if (todoOn && wantsArtifact) {
                    "多步任务可先用 todo_write 列出计划（同一消息最多 3 次），随后必须在正文中直接给出实际产出（代码/文件内容），" +
                            "绝不能用「已完成…」之类的进度描述代替真正的内容。"
                } else ""
                // 自检回路：弱模型看不到运行结果就永远不会自我修正
                val sandboxHint = if (sandboxOn && wantsArtifact) {
                    "本轮要的是能直接打开运行的成品。禁止用方案、架构、步骤清单、伪代码来替代代码——" +
                            "直接写出完整可运行的单文件代码，这是唯一合格的产出。" +
                            "写完必须调用 " + WebRunTool.NAME +
                            " 自检：它会把页面放进真实浏览器环境跑一次，返回语法错误与运行期错误（含行号）、" +
                            "是否死循环、canvas 是否真的画出了内容、画面是否在动。" +
                            "按返回的错误逐条修完，再自检一次；没有自检通过前不要声称已完成。" +
                            "修改已有文件时用 file_patch 只替换出错的那一小段，" +
                            "不要用 file_write 整份重写——每次重写都会把已经正确的部分改坏，也会引入新的错误。" +
                            "单文件程序控制在 500 行以内；确实超出这个规模时，先写出能跑的最小版本，" +
                            "再用后续轮次迭代补齐，不要用「接下来可以怎么做」来代替代码。"
                } else ""
                // 路由句放最前面：小模型只认开头和短句，放后面会被后面的强制句盖过。
                // 之前没有这句时，sandboxHint 的「唯一合格的产出是代码」被当成全局断言，
                // 「介绍一下茶文化」会被理解成「写一个介绍茶文化的 HTML 页面并自检」。
                // 注：曾试过把它和另外几条 hint 挪到提示末尾来「延长可复用的稳定前缀」，
                // 实测**无效**——本引擎只吃「完全相同」或「末尾微调」的缓存，中途分叉就整段重算
                // （1539 token 同提示重发 0.18s、尾部换问题 0.47s、中途分叉 10-11s），
                // 所以顺序对缓存没有收益，按指令遵循效果排序即可。
                val chatHint = if (wantsArtifact) "" else
                    "用户只是提问、求解释或求介绍时，直接用中文回答，不要建文件、不要调用任何工具。" +
                            "但关于设备/系统当前状态的提问（软件版本号、文件是否在、命令能否执行、目录里有什么）例外：" +
                            "那类必须先调用 run_bash 实测，凭记忆回答会报错或撒谎。"
                val systemText = chatHint + (if (basePrompt.isNotBlank())
                    "【本会话角色设定，优先遵守】" + basePrompt + " "
                else "") + todoHint + fileHint + sandboxHint + memoryHint +
                        (if (AppSettings.thinkEnabled) {
                    // 不能只说“简洁回答”：实测弱模型会把它理解成“别用工具、直接答”，
                    // 于是把多轮验证流程提前掐断。
                    // 但也不能放任成“不要跳过动手验证”——那句会抵消上面的 chatHint，
                    // 所以这里一律带「要产出成品时」的作用域。
                    "用中文回答，简洁但保留必要步骤；要产出成品时照常调用工具，不要为了简洁跳过自检。"
                } else {
                    "不要输出思考过程，直接用中文回答；要产出成品时照常调用工具。"
                }) + if (searchAvailable) {
                    "今天是${today}。涉及最新信息时，先用 web_search 工具联网查询再回答，不要臆造日期。"
                } else "" + if (planMode) {
                    // 计划模式的要点是「别给成品」。上一版只说「先给计划」，实测模型会理解成
                    // 「把 HTML 完整写出来给你看」，等于绕过了计划。
                    "【计划模式，必须遵守】这一轮只输出计划，不要产出成品：\n" +
                            "1. 不要输出 HTML / CSS / JS / Python 等代码，不要贴任何文件的完整内容；\n" +
                            "2. 只写分步计划：每步做什么、要动哪个文件（路径）、需要用户确认的取舍；\n" +
                            "3. 需要先了解现状时，可以用读取类工具（列目录 / 读文件 / 搜索）；\n" +
                            "4. 最后用一句话问用户是否按这个计划执行。\n" +
                            "用户确认后会退出计划模式，那时才真正创建文件。"
                } else if (permission == "read") {
                    "【仅可查看】这一轮只能读取信息，不要写文件、不要执行会改变系统状态的命令。"
                } else ""
                // 已沉淀的技能：按本条输入做正文级检索——命中就把**正文**一起给模型，
                // 否则「存为技能」只是个名字，模型不知道里面写了什么（等于没用）。
                // 没命中就退回老行为，只报几条名字，别白占上下文。
                val skillHint = run {
                    val all = AppSettings.loadSkills()
                    if (all.isEmpty()) return@run ""
                    val hits = com.localllm.server.core.SkillRetrieval.rank(all, lastUserText)
                    if (hits.isNotEmpty()) {
                        ServerProcessManager.appLog(
                            "技能检索命中：" + hits.joinToString("、") { "${it.skill.name}(${it.score})" }
                        )
                        return@run com.localllm.server.core.SkillRetrieval.hintText(hits)
                    }
                    val picked = if (all.size <= 5) all else all.take(5)
                    "【以前做成功过的流程（仅列名字，细节问用户）】\n" +
                            picked.joinToString("\n") { "- ${it.name}：${it.desc.take(40)}" } +
                            "\n如果这次和其中某条相似，就说明「可以照 XXX 的做法」，别凭空编步骤。"
                }
                // 用户最后一条消息里若含链接 → 抓取内容（GitHub 走 README 特判）
                // 注意：内容必须并入首条 system 文案——部分模板（Qwen）要求 system 只能在开头
                val linkText: String? = run {
                    val url = UrlFetch.firstUrl(lastUserText) ?: return@run null
                    val cached = linkCache[url]
                    val now = System.currentTimeMillis()
                    if (cached != null && now - cached.first < 10 * 60_000L) {
                        cached.second
                    } else {
                        val fetched = UrlFetch.fetch(url)
                        if (fetched != null) {
                            linkCache[url] = now to fetched
                            ServerProcessManager.appLog(
                                "链接预取：${url.take(60)} → ${fetched.length} 字" +
                                        if (url.contains("github.com")) "（GitHub 特判）" else ""
                            )
                            fetched
                        } else {
                            ServerProcessManager.appLog("链接预取失败：${url.take(60)}")
                            null
                        }
                    }
                }

                // 图片只有一条规则，不再有「自动 OCR / 多模态 / 按需」的设置档位：
                //   挂了 mmproj（视觉模型）→ 直接把图发过去，它自己看；
                //   没挂 mmproj（纯文本模型）→ 图发不了（它看不了），换成一句可操作的提示，
                //     模型需要文字时自己调 ocr_image 工具做本地识别。
                // 这样「用户明确要求识别文字」就成了明确触发，而不会被自动 OCR 抢在前面白跑一遍。
                //
                // 判据看「服务实际在跑什么」而不是「设置里绑了什么」：投影器不匹配时服务会
                // 自动去掉 --mmproj 重启，但设置里那条绑定还在——按设置判会把图片继续发过去，
                // 服务端直接 500（image input is not supported - you may need to provide the mmproj）。
                val svcState = ServerProcessManager.state.value
                val visionReady = if (svcState.status == ServerUiState.Status.RUNNING) {
                    svcState.runningMmproj?.let { java.io.File(it).exists() } == true
                } else {
                    AppSettings.selectedMmprojPath?.let { java.io.File(it).exists() } == true
                }
                // 显式指令优先：用户说了 OCR 相关的话，即使挂着视觉模型也**不传图**，
                // 让 ocr_image 成为唯一通路。不然视觉模型会直接读图，把「用 OCR 识别」顶掉——
                // 而用户要的往往正是 OCR 的逐字结果（表格数字、报错原文），不是视觉模型的概括。
                val ocrRequested = OcrIntent.requested(lastUserText)
                val imageVisible = visionReady && !ocrRequested
                var imageLogged = false
                // 对话里有没有图：决定是否注入 ocr_image 工具
                val hasImage = history.any { it.imageB64 != null }
                // 规划用云端：本地小模型最弱的一环是「把目标拆成步骤」，
                // 这一环借云端，文件与命令仍然在本地执行（数据不出设备）。
                val cloudPlan: String?
                var cloudPlanSkip: String? = null
                if (selfSession?.planWithCloud == true) {
                    // 一次性：拿到计划就把开关关掉。用户不用再想「这一轮算不算追问」，
                    // 也不会在不知情的情况下被多扣几次云端调用。
                    setProgress(sid, "正在让云端出计划…")
                    when (val r = requestCloudPlan(lastUserText)) {
                        is PlanResult.Ok -> {
                            cloudPlan = r.text
                            setSessionPlanWithCloud(sid, false)
                            _toast.value = "云端计划已拿到，开关已自动关闭"
                            ServerProcessManager.appLog("规划用云端已完成，开关自动关闭")
                        }

                        is PlanResult.Skip -> {
                            // 没成功就不关：关掉等于悄悄改了用户的设置，他下次还会以为开着
                            ServerProcessManager.appLog("规划用云端未生效：${r.reason}")
                            _toast.value = "规划用云端未生效：${r.reason}"
                            cloudPlan = null
                            cloudPlanSkip = r.reason
                        }
                    }
                } else {
                    cloudPlan = null
                }
                val planHint = if (cloudPlan.isNullOrBlank()) "" else
                    "\n\n【云端给出的执行计划，照此执行，不要重新规划】\n" + cloudPlan

                val systemTextFull = (if (linkText == null) systemText
                else systemText + "\n\n【用户消息中链接的内容（供回答参考，可能不完整）】\n" + linkText) +
                        (if (skillHint.isBlank()) "" else "\n\n" + skillHint) + planHint
                // Qwen3.5 等模板硬性要求 system 只能是第一条，否则 raise_exception：
                // 「System message must be at the beginning」。而上下文压缩产生的「历史摘要」
                // 是以 system 角色插在历史中间的——直接让整个请求 500，且是在 prefill
                // 跑完（可能好几分钟）之后才炸。这里统一折进开头那条。
                val midSystems = history.filter { it.role == "system" && it.content.isNotBlank() }
                val systemMerged = if (midSystems.isEmpty()) systemTextFull
                else systemTextFull + "\n\n" + midSystems.joinToString("\n\n") { it.content }
                turnSystem = systemMerged
                turnHistory = history
                // 音视频附件：**原样**（base64）交给支持它们的端点。
                // 以前二进制被当 UTF-8 读成乱码发过去，模型等于收到一堆垃圾。
                val mediaB64 = _pendingMediaB64.value
                val mediaMime = _pendingMediaMime.value.orEmpty()
                val noSys = history.filter { it.role != "system" }
                val lastUserIdx = noSys.indexOfLast { it.role == "user" }
                val mediaPart: org.json.JSONObject? = when {
                    mediaB64 == null -> null
                    mediaMime.startsWith("audio/") -> org.json.JSONObject()
                        .put("type", "input_audio")
                        .put(
                            "input_audio", org.json.JSONObject()
                                .put("data", mediaB64)
                                .put("format", mediaMime.substringAfter('/').ifBlank { "wav" })
                        )
                    else -> org.json.JSONObject()
                        .put("type", "video_url")
                        .put("video_url", org.json.JSONObject().put("url", "data:$mediaMime;base64,$mediaB64"))
                }
                val payloadMsgs = listOf(ChatClient.ChatMessage("system", systemMerged)) +
                        noSys.mapIndexed { idx, m ->
                            // 有图就有本地 OCR 原文：视觉模型读数字常错、读不清会编，
                            // 逐字结果给它核对；版面、图表、手写仍靠图。
                            val ocrBlock = OcrTool.promptBlock(m.ocrText)
                            when {
                                mediaPart != null && idx == lastUserIdx -> {
                                    val parts = org.json.JSONArray()
                                    parts.put(
                                        org.json.JSONObject().put("type", "text")
                                            .put("text", m.content.ifBlank { "（见附件）" })
                                    )
                                    parts.put(mediaPart)
                                    ChatClient.ChatMessage(m.role, parts)
                                }
                                m.imageB64 == null -> ChatClient.ChatMessage(m.role, m.content)
                                imageVisible -> {
                                    if (!imageLogged) {
                                        imageLogged = true
                                        ServerProcessManager.appLog(
                                            "图片：多模态直读（传图给视觉模型）" +
                                                    if (ocrBlock != null)
                                                        "，并附上本地 OCR 原文供核对数字" else ""
                                        )
                                    }
                                    val parts = org.json.JSONArray()
                                    val textPart = if (ocrBlock == null) m.content
                                    else (if (m.content.isNotBlank()) m.content + "\n\n" else "") + ocrBlock
                                    parts.put(org.json.JSONObject().put("type", "text").put("text", textPart))
                                    // 本地引擎只认 jpg/png（WebP 会整个请求 400），发之前统一兜一道
                                    val (sendB64, sendMime) = ensureReadableForServer(m.imageB64!!)
                                    parts.put(
                                        org.json.JSONObject().put("type", "image_url").put(
                                            "image_url",
                                            org.json.JSONObject().put(
                                                "url", "data:$sendMime;base64,$sendB64"
                                            )
                                        )
                                    )
                                    ChatClient.ChatMessage(m.role, parts)
                                }
                                else -> {
                                    // 看不到图：本地 OCR 原文就是唯一的信息来源，直接给出，
                                    // 省掉「先调一次工具才拿到字」的来回。
                                    if (!imageLogged) {
                                        imageLogged = true
                                        ServerProcessManager.appLog(
                                            if (ocrBlock != null)
                                                "图片：当前模型没有视觉投影（mmproj），已直接附上本地 OCR 原文"
                                            else if (ocrRequested)
                                                "图片：用户要求 OCR，按指令走 ocr_image（不传图）"
                                            else
                                                "图片：当前模型没有视觉投影（mmproj），改由 ocr_image 工具按需识别"
                                        )
                                    }
                                    val hint = if (ocrBlock != null)
                                        "【用户上传了一张图片，你看不到图片本身。" +
                                                "上面是本机 OCR 识别出的文字，可直接依据它作答；" +
                                                "需要完整原文或换重点再识别时，可调用 ${OcrTool.NAME}。】"
                                    else if (ocrRequested)
                                        "【用户上传了一张图片，并要求用 OCR 识别其中的文字】" +
                                                "你看不到图片本身，必须调用 ocr_image 工具取得文字后再作答，不要凭空猜测内容。"
                                    else
                                        "【用户上传了一张图片】你看不到图片本身。若回答需要图上的文字" +
                                                "（截图内容、表格、文档、报错信息等），先调用 ocr_image 工具识别，再作答。"
                                    val body = listOfNotNull(
                                        m.content.takeIf { it.isNotBlank() },
                                        ocrBlock,
                                        hint
                                    ).joinToString("\n\n")
                                    ChatClient.ChatMessage(m.role, body)
                                }
                            }
                        }
                // 端点按会话取：会话级覆盖优先，否则跟随全局
                val ep = endpointFor(sid)
                val cloud = !ep.local
                // 代码/工具类任务用低温采样：量化模型在语法层抽风的概率明显下降。
                // 关键词判据是启发式的，误判影响很小（只是温度低一点），不必做成开关。
                val codeWordPattern = Regex(
                    "html|css|javascript|typescript|\\bjs\\b|\\bpython\\b|代码|脚本|程序|游戏|函数|接口|" +
                            "单文件|生成文件|写一个|实现一个|编译|报错|重构",
                    RegexOption.IGNORE_CASE
                )
                val codeTask = codeWordPattern.containsMatchIn(lastUserText)

                // HTTP MCP 多服务器：聚合已启用服务器的工具（mcp__服务器__工具 前缀防冲突），并记录路由
                val mcpRoute = mutableMapOf<String, Pair<String, String>>() // 完整名 → (url, 原始工具名)
                val mcpAgg = org.json.JSONArray()
                AppSettings.loadMcpServers().filter { it.enabled && !AppSettings.pureMode }.forEach { s ->
                    val prefix = "mcp__" + s.name.map { c -> if (c.isLetterOrDigit()) c else '_' }
                        .joinToString("") + "__"
                    s.tools.filter { it.enabled }.forEach { t ->
                        val full = prefix + t.name
                        mcpRoute[full] = s.url to t.name
                        mcpAgg.put(
                            org.json.JSONObject()
                                .put("type", "function")
                                .put(
                                    "function", org.json.JSONObject()
                                        .put("name", full)
                                        // MCP 工具描述常常是一整段规则说明（几百到两千字），
                                        // 几十个工具累加能把 prompt 撑到几万 token，prefill 变成几分钟、
                                        // 小模型还会被压垮。截断到一句话——名字和参数结构才是模型真正需要的。
                                        .put("description", t.description.take(200))
                                        // 参数 schema 才是体积大头：MCP 工具的 inputSchema 里
                                        // 每个属性都带一段说明，几十个工具累加就是几千 token。
                                        // 这里递归剥掉所有 description，只留类型/必填/枚举。
                                        .put("parameters", trimSchema(t.inputSchema))
                                )
                        )
                    }
                }

                // 工具合并：内置 web_search + 全部 MCP 服务器工具
                // MCP 工具按需注入：工具多时（实测接到 67 个）全量注入会把 prompt 撑到几万 token，
                // prefill 变成几分钟，小模型还会选错。按当前消息匹配名字/描述，命中的才注入；
                // 一个都没命中时退回全量，保证不会因为猜不到而失去能力。
                //
                // 但「预填充慢」这个理由在 NPU 上基本不成立了（实测 12B 预填充 3 秒 vs CPU 180 秒），
                // 所以限制改成可关：设置里选「全量」就一个不裁，代价是工具选择准确率可能下降
                // （这一条跟速度无关，小模型在几十个工具里挑错是另一个问题）。
                val mcpAll = AppSettings.mcpToolMode == "all"
                val mcpSel = if (mcpAll) {
                    (0 until mcpAgg.length()).mapNotNull { mcpAgg.optJSONObject(it) }
                } else if (maxToolsFull > 0 && mcpAgg.length() > maxToolsFull) {
                    // 两阶段：先让模型只从「名字+一句话描述」的清单里挑工具，
                    // 再把这些工具的完整 schema 注入主请求。清单约 1/8 体积，
                    // 换掉动辄 9K token 的全量注入。挑不出东西时退回关键词筛。
                    pickMcpByModel(mcpAgg, lastUserText, cloud)
                        .ifEmpty { filterMcpForText(mcpAgg, lastUserText) }
                } else filterMcpForText(mcpAgg, lastUserText)
                val toolsJson = org.json.JSONArray().also { arr ->
                    if (searchAvailable) arr.put(WebSearch.toolJson())
                    if (fileToolsOn) FileTools.toolsJson().let { fa ->
                        for (i in 0 until fa.length()) {
                            val fn = fa.getJSONObject(i).optJSONObject("function")
                            val name = fn?.optString("name") ?: ""
                            // 只读/计划模式下不给写类工具：模型看不到就不会去试
                            if (readOnly && (name == FileTools.TOOL_WRITE || name == FileTools.TOOL_PATCH)) {
                                continue
                            }
                            arr.put(fa.getJSONObject(i))
                        }
                    }
                    // 内置 Linux 环境（PRoot + Ubuntu）：跑测试/git/node 的入口，跟着文件工具一起给
                    if (fileOn) arr.put(BashTool.toolJson())
                    // 装 APK 用系统安装器，别让模型再去折腾 adb 无线调试（配对码 + 每次变的端口）
                    if (fileOn) arr.put(InstallTool.toolJson())
                    // 无线调试通道：免点击装 APK、截屏、input tap、uiautomator dump…
                    // （App 会自动发现端口并连接；配对只需一次）
                    if (fileOn) arr.put(AdbTool.toolJson())
                    // 无障碍通道：看和点屏幕，不依赖 adb 与网络（没连 Wi-Fi 时也能用）
                    // OCR 工具：只有对话里真的出现过图片才注入，平时不白占 schema
                    if (hasImage) arr.put(OcrTool.toolJson())
                    // web_run 只在造物轮注入：纯提问轮模型看不到这个工具，
                    // 也就不会顺着「唯一合格的产出是代码」把问答改造成页面。
                    if (sandboxOn && wantsArtifact) arr.put(WebRunTool.toolJson())
                    if (fileOn) DocMaker.toolsJson().let { da ->
                        for (i in 0 until da.length()) arr.put(da.getJSONObject(i))
                    }
                    mcpSel.forEach { arr.put(it) }
                }.takeIf { !AppSettings.pureMode && it.length() > 0 }
                turnHadTools = toolsJson != null
                // 搜索配额用尽后本轮请求不带 web_search：模型无工具可调，只能产出文本回答
                val toolsNoSearch = org.json.JSONArray().also { arr ->
                    mcpSel.forEach { arr.put(it) }
                }.takeIf { !AppSettings.pureMode && it.length() > 0 }

                ServerProcessManager.appLog(
                    "采样：" + (if (codeTask) "代码任务 → temp 0.3" else "普通对话 → temp 0.7") +
                            " · top_p 0.9 · top_k 20 · repeat_penalty 1.10" +
                            (if (codeTask) "（命中代码关键词）" else "")
                )
                ServerProcessManager.appLog(
                    "聊天：发送（" +
                            (if (cloud) "云端 ${ep.model.ifBlank { "default" }}" else "本地") +
                            " · 历史 ${payloadMsgs.size - 1} 条" +
                            (toolsJson?.let { " · 工具 ${it.length()} 个" } ?: "") +
                            (if (searchAvailable) " · 搜索开" else "") +
                            (if (fileOn) " · 文件工具" else "") + "）"
                )

                // 真实注入过的工具名白名单：模型幻觉出来的工具调用一律不执行（直接结束本轮）
                val injectedNames = toolsJson?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optJSONObject("function")?.optString("name")
                            ?.takeIf { it.isNotBlank() }
                    }?.toSet()
                } ?: emptySet()
                var searchCount = 0
                var sandboxCalls = 0
                val searchCap = AppSettings.searchMaxCalls.let { if (it <= 0) Int.MAX_VALUE else it }
                var loggedNoSearch = false
                // 执行步骤时间线（思考/搜索/工具），落到消息上供 UI 展示
                val steps = mutableListOf<ChatStore.Step>()
                var thinkStartedAt = 0L
                // 本轮思考正文：落到 step.detail 里，时间线才能看出「想到哪一步去调的工具」
                val roundThink = StringBuilder()
                fun syncSteps() {
                    appendToLastInline(sid) { it.copy(steps = steps.toList()) }
                    persistProgress(sid)
                }
                fun settleThink() {
                    if (thinkStartedAt > 0) {
                        val ms = System.currentTimeMillis() - thinkStartedAt
                        val body = roundThink.toString().trim().replace(Regex("\\s+"), " ")
                        val liveIdx = steps.indexOfLast { it.type == "think" && it.label == "思考中" }
                        if (liveIdx >= 0) {
                            // 思考开始时已占好时间线位置：就地收尾，不重排不跳位
                            steps[liveIdx] = steps[liveIdx].copy(
                                label = "思考了 ${String.format("%.1f", ms / 1000.0)} 秒",
                                detail = body.take(600).ifBlank { null },
                                millis = ms
                            )
                        } else {
                            steps.add(
                                ChatStore.Step(
                                    "think",
                                    "思考了 ${String.format("%.1f", ms / 1000.0)} 秒",
                                    detail = body.take(600).ifBlank { null },
                                    millis = ms
                                )
                            )
                        }
                        roundThink.setLength(0)
                        thinkStartedAt = 0L
                        syncSteps()
                    }
                }
                val searchedQueries = mutableMapOf<String, String>() // 规范化查询 → 结果文本（相同查询复用）

                var apiMsgs = payloadMsgs
                val usedTools = mutableListOf<String>()
                // OCR 工具的数据源与次数限制：优先用本轮原图（分辨率更高），否则回落到历史里最近一张；
                // 一轮最多识别两次，否则模型可能在工具循环里反复识别同一张图。
                val ocrImageB64: String? =
                    ocrSource ?: history.lastOrNull { it.imageB64 != null }?.imageB64
                var ocrCalls = 0
                var truncFix = 0
                // 「只宣告下一步、不调工具」不再催办：模型写到哪就停在哪，由用户决定是否继续
                val searchSources = mutableListOf<WebSearch.Result>()
                var timingNote: String? = null

                // 熔断状态：只统计「工具名 + 参数一字不差」的连续失败；换参数即重置
                var lastCallKey: String? = null
                var sameCallFails = 0
                val mutedTools = mutableSetOf<String>()
                var hardStop = false

                /** 本轮真正下发给模型的工具：被熔断摘掉的不再出现 */
                fun toolsForRound(exhausted: Boolean): org.json.JSONArray? {
                    val base = (if (exhausted) toolsNoSearch else toolsJson) ?: return null
                    if (mutedTools.isEmpty()) return base
                    return org.json.JSONArray().also { arr ->
                        for (i in 0 until base.length()) {
                            val o = base.getJSONObject(i)
                            val name = o.optJSONObject("function")?.optString("name") ?: ""
                            if (name !in mutedTools) arr.put(o)
                        }
                    }
                }

                // 注意：必须用 for + break——repeat 里的 return@repeat 是 continue 语义，
                // 会导致「回答完成」后仍继续下一轮（历史 bug：同一问题最多被连答 5 遍）。
                // 默认不限（设置里可设成有限轮次）：NPU 上每轮成本低一个数量级，次数上限反而成了任务做不完的主因。
                // 正常结束靠模型自己不再调工具（下面的 break），中途想停靠停止按钮。
                val roundCap = ToolLoopPolicy.roundCap(AppSettings.toolRoundLimit)
                // 这一轮是模型自己收尾（不再调工具）还是撞上限被打断——两者收尾方式完全不同
                var answeredNaturally = false
                for (round in 0 until roundCap) {
                    ensureActive() // 停止/切换后不再进入下一轮

                    // 云端计划单独占时间线的一步：否则用户根本看不出「哪部分是云端规划的、
                    // 哪部分是本地执行的」，也看不到云端到底给了什么。
                    if (round == 0 && !cloudPlan.isNullOrBlank()) {
                        steps.add(
                            ChatStore.Step(
                                "cloud",
                                "云端规划（${cloudPlan.length} 字）",
                                cloudPlan.take(3000)
                            )
                        )
                        syncSteps()
                    }
                    // 硬上限（宽松）：轮次设成「不限」也挡一下极端情况——跑了几百轮或二十分钟还没完，
                    // 大概率是模型在原地打转，收掉工具让它用文字总结，比继续空转有用。
                    if (!hardStop && (round >= HARD_ROUND_CAP ||
                                System.currentTimeMillis() - sendStartedAt > HARD_TIME_MS)
                    ) {
                        hardStop = true
                        mutedTools.addAll(usedTools)
                        ServerProcessManager.appLog("达到硬上限（第 $round 轮 / 24 分钟量级），转为收尾")
                        steps.add(
                            ChatStore.Step("tool", "达到硬上限，收尾", "第 $round 轮，不再提供工具")
                        )
                        syncSteps()
                        apiMsgs = apiMsgs + ChatClient.ChatMessage(
                            "user",
                            "（系统提示：已经跑了 $round 轮，现在停止调用工具。请用一段话总结：" +
                                    "完成了什么、卡在哪里、建议用户下一步怎么做。）"
                        )
                    }
                    // 只按用户设的配额收口；「连续多轮没正文就逼模型作答」那条围栏已去掉：
                    // 多步任务本来就常常连跑几轮工具不带正文，它会把正常的流程掐断。
                    val searchExhausted = searchAvailable && searchCount >= searchCap
                    if (searchExhausted && !loggedNoSearch) {
                        loggedNoSearch = true
                        ServerProcessManager.appLog("搜索配额已用尽，本轮起移除搜索工具（强制模型产出回答）")
                    }
                    if (round > 0) {
                        ServerProcessManager.appLog("工具回传完成，第 ${round + 1} 轮继续生成…")
                    }
                    var deltaCount = 0
                    var roundChars = 0
                    // 本轮正文：工具轮要把它随 assistant 消息一起回传给模型，
                    // 否则模型看不到自己上一轮说了什么，等于每轮失忆重来
                    val roundText = StringBuilder()
                    var lastRepCheck = 0
                    var firstDeltaMs = 0L
                    var serverStats: String? = null
                    var toolCalls: List<ChatClient.ToolCall> = emptyList()
                    var finishReason = ""
                    // 正文开始后冻结思考卡：部分模型会在正文期间/之后仍吐 reasoning，
                    // 导致“回答完了思考还在滚”。首个内容 delta 到达即停止追加。
                    var contentStarted = false
                    // 心跳：prefill 阶段服务器只在处理完一段后才打一次进度，中间可能几十秒没动静，
                    // 光看界面像死了。这里每秒刷一次「已等待 N 秒 + 进度」，让状态一直有变化。
                    val roundStartAt = System.currentTimeMillis()
                    heartbeat?.cancel()
                    heartbeat = viewModelScope.launch {
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            if (contentStarted || !isGenerating(sid)) break
                            val secs = (System.currentTimeMillis() - roundStartAt) / 1000
                            val p = ServerProcessManager.prefill.value
                            val txt = if (p != null) {
                                "处理上下文 ${(p * 100).toInt()}% · 已等待 ${secs}s"
                            } else {
                                "等待模型响应 · 已等待 ${secs}s"
                            }
                            setProgress(sid, txt)
                            if (_activeId.value == sid) _lastStats.value = txt
                        }
                    }

                    ChatClient.chatStream(
                        port = AppSettings.port,
                        messages = apiMsgs,
                        apiToken = if (cloud) ep.apiKey.ifBlank { null }
                                   else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                        baseUrl = if (cloud) ep.baseUrl else null,
                        model = if (cloud) ep.model.ifBlank { "default" } else "local",
                        // chat 请求按会话标识登记，停止时只断这一条（多会话并行）
                        callKey = sid,
                        // 本地走 jinja 模板真开关；云端模板自管不干预。
                        // 只下发模型模板认的参数（检测见 GgufInspector.deepInfo）
                        thinking = thinkSwitch(cloud),
                        effort = thinkEffort(cloud),
                        // 本地生成上限：退化复读时不至于跑到撑满上下文
                        maxTokens = if (cloud) null else AppSettings.effectiveOut(AppSettings.selectedModelPath),
                        codeTask = codeTask,
                        // 搜索配额用尽 → 不再提供搜索工具（防止弱模型空转到轮数上限、永远不回答）
                        tools = if (hardStop) null else toolsForRound(searchExhausted),
                        onToolCalls = { toolCalls = it },
                        onFinish = { finishReason = it },
                        onTiming = { ttfb, firstToken ->
                            // 云端慢排查：ttfb vs 首token 分离，判断慢在网络还是在推理
                            if (cloud) {
                                val tok = firstToken?.let { " · 首token ${it / 1000.0}s" } ?: ""
                                timingNote = "连接${ttfb / 1000.0}s$tok"
                            }
                        },
                        onDelta = { delta ->
                            // 停止后一律不再上屏（abort 断连前的在途分片也丢弃）
                            if (isGenerating(sid)) {
                                if (!contentStarted) settleThink()
                                contentStarted = true
                                deltaCount++
                                abortDeltas++
                                roundChars += delta.length
                                roundText.append(delta)
                                if (firstDeltaMs == 0L) firstDeltaMs = System.currentTimeMillis()
                                val elapsed = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                                if (elapsed > 0.3) {
                                    val tps = deltaCount / elapsed
                                    val stats = "$deltaCount token · ${String.format("%.1f", tps)} tok/s" +
                                            " · ${String.format("%.0f", elapsed)}s"
                                    setProgress(sid, stats)
                                    if (_activeId.value == sid) _lastStats.value = "生成中 · $stats"
                                }
                                appendToLastInline(sid) { it.copy(content = it.content + delta) }
                                // 正文也随时间落盘：被后台清掉时至少保留已生成的部分
                                persistProgress(sid)
                                // 复读检测：每新增 800 字查一次尾部是否周期性重复。
                                // 弱模型在长字符串里会陷入复读，把工具调用的 JSON 撑到不闭合，
                                // 白跑十几分钟才报错——这里直接掐断。
                                if (roundChars - lastRepCheck >= 800) {
                                    lastRepCheck = roundChars
                                    if (RepeatGuard.isRepetitive(roundText)) {
                                        repetitionAborted = true
                                        ServerProcessManager.appLog(
                                            "检测到输出复读，已中断本轮（已生成 $roundChars 字）"
                                        )
                                        ChatClient.abort(sid)
                                    }
                                }
                            }
                        },
                        onReasoning = { r ->
                            when {
                                !AppSettings.thinkEnabled || !isGenerating(sid) -> Unit
                                // 正文开始后模型再思考也照单全收（不再截断本轮）：思考行跟着时间线继续长
                                else -> {
                                    if (thinkStartedAt == 0L) {
                                        thinkStartedAt = System.currentTimeMillis()
                                        // 思考一开始就占好时间线位置（label 思考中），边想边更新正文——
                                        // 而不是等思考完才补一行（那样等于多一步）
                                        steps.add(ChatStore.Step("think", "思考中", detail = ""))
                                    }
                                    roundThink.append(r)
                                    // 实时更新 think 步正文 + reasoning：时间线里的思考行边想边长
                                    val liveBody = roundThink.toString()
                                    val liveIdx = steps.indexOfLast { it.type == "think" && it.label == "思考中" }
                                    if (liveIdx >= 0) steps[liveIdx] = steps[liveIdx].copy(detail = liveBody.take(600))
                                    appendToLastInline(sid) {
                                        it.copy(
                                            reasoning = (it.reasoning ?: "") + r,
                                            steps = steps.toList()
                                        )
                                    }
                                }
                            }
                        },
                        onFinal = { tokens, tps, prompt ->
                            val tpsPart = if (tps > 0) " · ${String.format("%.1f", tps)} tok/s" else ""
                            // 上下文占用 = 本次输入 + 刚生成的回复（下一轮会话的实际起点）
                            serverStats = "共 $tokens token$tpsPart" +
                                    ctxNote(prompt + tokens)
                            val ctxLimit = AppSettings.effectiveCtx(AppSettings.selectedModelPath)
                            if (ctxLimit > 0) {
                                // 按会话存：三条会话并行时，后台那条跑完不该改掉屏幕上这条的数字
                                _ctxBySession.update { it + (sid to ((prompt + tokens) to ctxLimit)) }
                            }
                            // 用量明细：系统提示词与工具 schema 是本轮真正下发的那两份，
                            // 剩下的差额全算消息——比按历史重算接近真实 prefill。
                            val sysTok = estimateTokens(systemMerged)
                            val toolTok = estimateTokens(
                                (if (searchExhausted) toolsNoSearch else toolsJson).toString()
                            )
                            _usageBySession.update {
                                it + (sid to Usage(
                                    prompt = prompt,
                                    output = tokens,
                                    cacheHit = ChatClient.lastCacheHitFor(sid),
                                    rounds = round + 1,
                                    systemTokens = sysTok,
                                    toolTokens = toolTok,
                                    messageTokens = (prompt + tokens - sysTok - toolTok).coerceAtLeast(0),
                                ))
                            }
                            // 超过 85% 就标记，让这一轮结束后自动压缩——再往后很容易在
                            // 下一轮中途撞顶（表现为莫名其妙的截断或答非所问）
                            val ctx = AppSettings.effectiveCtx(AppSettings.selectedModelPath)
                            if (ctx > 0 && (prompt + tokens).toDouble() / ctx > 0.85) {
                                autoCompressPending = true
                                ServerProcessManager.appLog(
                                    "上下文占用 ${prompt + tokens}/$ctx 超过 85%，本轮结束后自动压缩"
                                )
                            }
                        }
                    )

                    // 工具调用过滤：**只按「这个工具本轮是否真的注入过」判**。
                    //
                    // 以前还有一条「正文 ≥60 字且不像进度说明 → 答后调用当幻觉丢掉」，初衷是挡
                    // 弱模型答完再补一次假调用；实际代价更大：真机上模型写完一句
                    // 「让我重新从 CSV 文件里准确提取…生成正确的 Excel 文件。」（句号结尾）后跟了一个
                    // file_read，被判成幻觉丢掉，那正是当时唯一该做的动作，整轮就停在那句上。
                    // 判断「模型是不是已经答完了」这件事不该由启发式猜：调用了就执行，
                    // 真跑错由工具结果自己说话（错误会回传给模型）。
                    val validCalls = run {
                        val ok = toolCalls.filter { it.name in injectedNames }
                        val ignored = toolCalls.map { it.name }.toSet() - ok.map { it.name }.toSet()
                        if (ignored.isNotEmpty()) {
                            ServerProcessManager.appLog("忽略未注入的工具调用：${ignored.joinToString("、")}")
                        }
                        ok
                    }

                    if (validCalls.isEmpty()) {
                        // 撞到 max_tokens（finish_reason=length）→ 自动续写，而不是把半截代码当成品交付。
                        // 截断正是「打开只有背景」的元凶：文件断在半行 JS 上。
                        val partial = _messages.value.lastOrNull()?.content ?: ""
                        if ((finishReason == "length" || finishReason == "max_tokens") &&
                            truncFix < 8 && partial.isNotBlank()
                        ) {
                            truncFix++
                            apiMsgs = apiMsgs +
                                    ChatClient.ChatMessage("assistant", partial) +
                                    ChatClient.ChatMessage(
                                        "user",
                                        "（上一条回答因输出长度上限被截断，停在半句上。请直接从中断处继续输出剩余内容，" +
                                                "不要重复已经输出的部分，不要重新开头。）"
                                    )
                            ServerProcessManager.appLog("输出撞到 max_tokens，自动续写第 $truncFix 次")
                            steps.add(ChatStore.Step("tool", "输出被截断，自动续写（第 $truncFix 次）"))
                            syncSteps()
                            continue
                        }
                        // 自驱：模型这一轮不调工具了，先看客观判据再决定收不收尾。
                        // 判据不问模型（小模型自评不可靠），只看：有没有失败、该产出的产出了没、是否原地打转。
                        if (autoDrive) {
                            val failed = steps.count { st -> !st.ok }
                            val deliverable = _messages.value.lastOrNull()?.genFiles?.isNotEmpty() == true
                            val stallKey = steps.filter { !it.ok }
                                .joinToString("|") { (it.label + ":" + (it.detail ?: "")).take(80) }
                                .ifBlank { null }
                            val sameStall = stallKey != null && stallKey == lastStallKey
                            val verdict = ToolLoopPolicy.driveVerdict(
                                wantsArtifact = wantsArtifact,
                                hasDeliverable = deliverable,
                                failedTools = failed,
                                sameStall = sameStall,
                                continued = autoDriveCount,
                                maxContinue = MAX_AUTODRIVE,
                            )
                            if (verdict.shouldContinue && round < roundCap - 1) {
                                autoDriveCount++
                                lastStallKey = stallKey
                                val failedNotes = steps.filter { !it.ok }
                                    .takeLast(3)
                                    .joinToString("\n") { "- " + it.label.take(60) + "：" + (it.detail ?: "").take(160) }
                                val nudge = buildString {
                                    append("【自动续跑 $autoDriveCount/$MAX_AUTODRIVE】")
                                    append(verdict.reason ?: "目标还没完成")
                                    append("。\n")
                                    if (failedNotes.isNotBlank()) {
                                        append("最近失败的步骤：\n").append(failedNotes).append('\n')
                                    }
                                    append("请针对上面的问题继续做完，不要重复已经完成的部分，也不要重新开头。")
                                }
                                ServerProcessManager.appLog(
                                    "自驱续跑第 $autoDriveCount 次：${verdict.reason}"
                                )
                                steps.add(
                                    ChatStore.Step("tool", "目标未达成，自动续跑（第 $autoDriveCount 次）", verdict.reason)
                                )
                                syncSteps()
                                apiMsgs = apiMsgs + ChatClient.ChatMessage("user", nudge)
                                continue
                            }
                            if (!verdict.shouldContinue && verdict.reason != null) {
                                ServerProcessManager.appLog("自驱收尾：${verdict.reason}")
                                steps.add(ChatStore.Step("tool", "自驱收尾", verdict.reason))
                                syncSteps()
                            }
                        }
                        // 最终回复：先把可能还在流式的思考行收尾（正文开始后模型也可能再思考）
                        settleThink()
                        // 「嘴上说生成了」：小模型会把「打算做」当成「已经做了」，
                        // 而用户会真的去目录里找。这里按事实核对一次：说了写文件却没有写文件调用，就标出来。
                        run {
                            val finalMsg = _messages.value.lastOrNull()
                            val text = finalMsg?.content ?: ""
                            val claims = com.localllm.server.core.ReplyClaims.claimsFile(text)
                            // 回答里点名的文件，只要现在真的存在、且还没登记过，就补登记。
                            // 不能因为「已经有别的产物」就跳过：模型常用 python3 脚本改文件，
                            // 那种写入不进 FileTools，产物卡上就只剩脚本、看不到真正的交付物。
                            // 候选文件名不只看正文：`run_bash` 里编出来的 APK，名字往往只出现在
                            // 工具输出（gradle 的 "Built …/app-debug.apk"）里，正文只写"构建好了"。
                            val stepsHaveBash = finalMsg?.steps?.any {
                                it.type == "tool" && it.label.startsWith("执行命令")
                            } == true
                            val scanText = buildString {
                                append(text)
                                if (stepsHaveBash) {
                                    finalMsg?.steps?.forEach { st -> st.detail?.let { append('\n').append(it) } }
                                }
                            }
                            val known = finalMsg?.genFiles?.map { it.path }?.toSet().orEmpty()
                            val existing = if (claims || stepsHaveBash) {
                                com.localllm.server.core.ReplyClaims.mentionedNames(scanText, limit = 4)
                                    .filter { it !in known && fileExistsNow(it) }
                            } else emptyList()
                            if (existing.isNotEmpty()) {
                                existing.forEach { recordGeneratedFile(it) }
                                ServerProcessManager.appLog(
                                    "本轮产物核对：回答点名的文件确实存在（${existing.joinToString("、")}），已登记"
                                )
                            }
                            val wrote = (finalMsg?.genFiles?.isNotEmpty() == true) || existing.isNotEmpty()
                            if (claims && !wrote) {
                                ServerProcessManager.appLog("回答声称生成了文件，但本轮没有写文件调用")
                                steps.add(
                                    ChatStore.Step(
                                        "tool",
                                        "注意：本轮没有真的写出文件",
                                        "回答里提到了生成/保存文件，但本次既没调用 file_write / make_xlsx，" +
                                                "点名的文件也不在工作目录里。可以再要求它「真的写出来」。"
                                    )
                                )
                                syncSteps()
                            }
                        }
                        val usedSec = (System.currentTimeMillis() - sendStartedAt) / 1000.0
                        val timing = timingNote?.let { "$it · " } ?: ""
                        val secPart = " · 用时 ${String.format("%.1f", usedSec)}s"
                        // 缓存命中：本地引擎每次请求都会报「复用了多少 prompt token」，
                        // 多轮对话里这个数越高说明前缀复用越好（不用反复预填充）
                        val cachePart = ChatClient.lastCacheHit.let {
                            if (it > 0) " · 缓存命中 $it" else ""
                        }
                        val finalStats = if (serverStats != null) {
                            timing + serverStats + cachePart + secPart
                        } else if (deltaCount > 0 && firstDeltaMs > 0) {
                            val genSecs = (System.currentTimeMillis() - firstDeltaMs) / 1000.0
                            if (genSecs > 0.05) {
                                val tps = deltaCount / genSecs
                                timing + "共 $deltaCount token · ${String.format("%.1f", tps)} tok/s" + secPart
                            } else {
                                timing + "共 $deltaCount token" + secPart
                            }
                        } else {
                            (timing + "用时 ${String.format("%.1f", usedSec)}s").trim('·', ' ')
                        }
                        // 统计行只写给当前会话：后台会话跑完别把屏幕上的数字换掉
                        if (_activeId.value == sid) _lastStats.value = finalStats
                        // 这一轮的耗时与 token 落到消息上：会话详情页的轨迹/上下文 tab 靠它
                        val turnMs = System.currentTimeMillis() - sendStartedAt
                        val turnUsage = _usageBySession.value[sid]
                        appendToLastInline(sid) { m ->
                            m.copy(
                                elapsedMs = turnMs,
                                promptTokens = turnUsage?.prompt ?: deltaCount,
                                outputTokens = turnUsage?.output ?: deltaCount
                            )
                        }
                        // 搜索来源 → 消息 sources 字段（UI 渲染为可点击列表，不再塞进正文文本）
                        if (searchSources.isNotEmpty()) {
                            val answerText = _messages.value.lastOrNull()?.content ?: ""
                            val cited = searchSources.filter { s ->
                                val host = runCatching {
                                    java.net.URI(s.url).host?.removePrefix("www.")
                                }.getOrNull() ?: ""
                                (host.isNotBlank() && answerText.contains(host)) ||
                                        (s.title.length >= 6 && answerText.contains(s.title.take(6)))
                            }
                            val picked = (if (cited.isNotEmpty()) cited else searchSources)
                                .distinctBy { it.url }.take(5)
                            val list = _messages.value.toMutableList()
                            list[list.lastIndex] = list.last().copy(
                                sources = picked.map { ChatStore.Source(it.title, it.url) }
                            )
                            _messages.value = list
                        }
                        // 正常跑完：清掉 stopped，界面不再显示「继续这个任务」
                        appendToLastInline(sid) { m ->
                            m.copy(task = m.task?.copy(stopped = false, rounds = round + 1))
                        }
                        ServerProcessManager.appLog("聊天：完成 · ${_lastStats.value ?: "（无统计）"}")
                        // 首轮回答完成 → 自动生成会话标题
                        val assistantCount = _messages.value.count {
                            it.role == "assistant" && it.content.isNotBlank()
                        }
                        if (assistantCount == 1) {
                            val lastUser = _messages.value.lastOrNull { it.role == "user" }?.content ?: ""
                            maybeAutoTitle(lastUser, _messages.value.lastOrNull()?.content ?: "")
                        }
                        answeredNaturally = true
                        break
                    }

                    // 执行工具调用并回传结果（仅 validCalls）
                    settleThink()
                    // 本轮正文落成「消息」步：DSH 时间线里中间正文与工具调用交错。
                    // 正文区只留最终答案——中间轮的文字从正文清走，避免时间线和正文重复。
                    val roundProse = roundText.toString().trim()
                    if (roundProse.isNotEmpty()) {
                        steps.add(
                            ChatStore.Step(
                                "text",
                                roundProse.lineSequence().firstOrNull()?.take(40) ?: "消息",
                                detail = roundProse
                            )
                        )
                        appendToLastInline(sid) { it.copy(content = "") }
                        syncSteps()
                    }
                    val asstCalls = org.json.JSONArray()
                    val newMsgs = apiMsgs.toMutableList()
                    validCalls.forEach { tc ->
                        usedTools.add(tc.name)
                        _lastStats.value = "🔧 调用 ${tc.name}…"
                        asstCalls.put(
                            org.json.JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function", org.json.JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.arguments)
                                )
                        )
                    }
                    newMsgs.add(
                        ChatClient.ChatMessage(
                            "assistant",
                            roundText.toString().trim().take(1500).ifBlank { null },
                            toolCalls = asstCalls
                        )
                    )
                        validCalls.forEach { tc ->
                        // 危险动作先要用户点头：命令、写/改文件、带写语义的 MCP 工具都在这里拦下。
                        // 读类动作不打扰——否则每轮都要点好几次「允许」，人就会闭眼乱点。
                        val verdict = when {
                            fileOn && tc.name == BashTool.NAME -> ToolGuard.assessBash(
                                org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                    .optString("command")
                            )

                            fileOn && tc.name in FileTools.NAMES -> ToolGuard.assessFile(
                                tc.name, org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                            )

                            fileOn && tc.name == AdbTool.NAME -> ToolGuard.assessAdb(
                                org.json.JSONObject(tc.arguments.ifBlank { "{}" }).optString("args")
                            )

                            mcpRoute.containsKey(tc.name) -> ToolGuard.assessMcp(
                                tc.name, org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                            )

                            else -> null
                        }
                        val result = if (verdict != null && !confirmTool(verdict, sid)) {
                            ServerProcessManager.appLog("用户拒绝执行工具：${tc.name}")
                            steps.add(
                                ChatStore.Step(
                                    "tool", "已拒绝：${tc.name}", verdict.subject.take(200), ok = false
                                )
                            )
                            syncSteps()
                            "用户拒绝了这次操作，它没有执行。不要重复同样的调用：" +
                                    "换个做法，或先把打算做什么告诉用户。"
                        } else runCatching {
                            if (fileOn && tc.name in DocMaker.NAMES) {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val docPath = args.optString("path")
                                ServerProcessManager.appLog("文档生成：${tc.name}")
                                val r = runCatching { DocMaker.execute(getApplication(), tc.name, args) }
                                    .getOrElse {
                                        "错误：生成失败（${it.message ?: it.javaClass.simpleName}）"
                                    }
                                val ok = !r.startsWith("错误：")
                                steps.add(
                                    ChatStore.Step(
                                        "tool",
                                        "生成文档：${tc.name}",
                                        (if (docPath.isNotBlank()) docPath else r).take(80),
                                        ok = ok
                                    )
                                )
                                syncSteps()
                                ServerProcessManager.appLog("文档生成完成：$r")
                                // 生成出来的文档必须登记进产物列表：产物卡、会话详情「文件」、
                                // 以及「本轮有没有真的写出文件」的判据，全都看这个列表。
                                // （曾经漏了这一步，于是一次真生成 xlsx 的轮次被判成"只说不做"）
                                if (ok && docPath.isNotBlank()) recordGeneratedFile(docPath)
                                r
                            } else if (tc.name == AdbTool.NAME) {
                                val adbArgs = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                    .optString("args").trim()
                                val timeout = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                    .optInt("timeout", 60).coerceIn(5, 600)
                                ServerProcessManager.appLog("adb：${adbArgs.take(80)}")
                                val (ok, out) = AdbBridge.runAdb(
                                    getApplication(), adbArgs, timeout * 1000L
                                )
                                val body = if (out.isBlank()) "（无输出）" else out.take(4000)
                                if (ok) {
                                    Regex("""[\w\u4e00-\u9fa5./\-]+\.(?:apk|png|jpe?g|webp|csv|txt|log|json|xml|zip)""",
                                        RegexOption.IGNORE_CASE)
                                        .findAll(adbArgs).map { it.value }.distinct().take(3)
                                        .filter { fileExistsNow(it) }
                                        .forEach { recordGeneratedFile(it) }
                                }
                                steps.add(
                                    ChatStore.Step(
                                        "tool", "adb ${adbArgs.take(50)}",
                                        body.take(400), args = adbArgs.take(300), ok = ok
                                    )
                                )
                                syncSteps()
                                if (ok) body else "adb 执行失败：\n$body"
                            } else if (tc.name == InstallTool.NAME) {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val apkPath = args.optString("path").trim()
                                ServerProcessManager.appLog("安装 APK：$apkPath")
                                // 先试**免点击**的 adb 安装（无线调试配对过一次就行）；不行再退回
                                // 系统安装器（用户点一下）。把 adb 的失败原因一并给模型，免得它反复猜。
                                val adbErr = if (apkPath.endsWith(".apk", true)) {
                                    AdbBridge.install(getApplication(), apkPath)
                                } else "路径不是 .apk"
                                val r = if (adbErr == null) {
                                    // 装成功也登记产物：不然「装一下」这一轮的消息里没有产物卡，
                                    // 用户看着就像"会话里没显示生成的文件"（实际在上一轮消息里）
                                    if (apkPath.isNotBlank()) recordGeneratedFile(apkPath)
                                    "已通过无线调试静默安装成功（用户不用点确认）。可以用 adb 的 " +
                                            "`shell am start` 或 `shell pm list packages` 继续验证。"
                                } else {
                                    InstallTool.execute(getApplication(), args) +
                                            "\n（说明：adb 免点击安装没成功：${adbErr.take(200)}）"
                                }
                                val ok = !r.startsWith("错误：")
                                steps.add(
                                    ChatStore.Step(
                                        "tool", "安装 APK：${apkPath.take(50)}",
                                        r.take(200), args = apkPath.take(200), ok = ok
                                    )
                                )
                                syncSteps()
                                r
                            } else if (tc.name == OcrTool.NAME) {
                                // 图片文字识别：本地 ML Kit，不上传。模型按需调用，不是每张图都自动跑。
                                val src = ocrImageB64
                                when {
                                    src == null -> "错误：当前对话里没有可识别的图片"
                                    ocrCalls >= 2 -> "错误：本轮已识别过两次，请基于已拿到的文字作答"
                                    else -> {
                                        ocrCalls++
                                        ServerProcessManager.appLog("OCR 工具：识别用户上传的图片（第 $ocrCalls 次）")
                                        runCatching { OcrTool.recognize(src) }.getOrElse { e ->
                                            "错误：${e.message ?: "图片识别失败"}"
                                        }
                                    }
                                }
                            } else if (fileOn && tc.name == BashTool.NAME) {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val cmd = args.optString("command").trim()
                                if (!LinuxRuntime.isReady(getApplication())) {
                                    ServerProcessManager.appLog(
                                        "Linux 环境首次使用：解压 rootfs + 安装 git/curl/python3，可能需要几分钟…"
                                    )
                                }
                                ServerProcessManager.appLog("Linux 命令：${cmd.take(80)}")
                                if (args.optBoolean("reset")) {
                                    ServerProcessManager.appLog("按模型要求清空持久 shell 会话状态")
                                }
                                // 长命令放到 IO 线程，别卡主线程
                                val r = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    BashTool.execute(getApplication(), sid, args)
                                }
                                // 返回文本第一行是「当前目录」，所以判失败只能用 contains。
                                // 原来写的是 startsWith("【命令退出码")——那句永远为假，失败的命令
                                // 一直被标成成功，自驱/熔断的判据也就看不到这类失败。
                                val failed = r.contains("【命令退出码") || r.contains("【命令超时") ||
                                        r.contains("Linux 环境未就绪")
                                steps.add(
                                    ChatStore.Step(
                                        "tool", "执行命令：${cmd.take(60)}",
                                        r.take(800), args = cmd.take(600), ok = !failed
                                    )
                                )
                                syncSteps()
                                ServerProcessManager.appLog(
                                    "Linux 命令完成：${if (failed) "失败" else "成功"}（${r.length} 字符回传）"
                                )
                                r
                            } else if (fileToolsOn && tc.name in FileTools.NAMES) {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                // 只读/计划模式兜底：写类工具没注入，但历史里可能残留调用
                                if (readOnly &&
                                    (tc.name == FileTools.TOOL_WRITE || tc.name == FileTools.TOOL_PATCH)
                                ) {
                                    "当前是只读/计划模式，不能写文件。请把计划或完整内容直接说给用户，" +
                                            "等用户确认后再执行。"
                                } else {
                                ServerProcessManager.appLog("文件工具：${tc.name}")
                                // 写入前留一份现状：产物卡上的「撤销」靠它退回本次写入之前，
                                // 「撤销本轮」靠 turnId 把同一条消息里的多次写入归成一组
                                if (tc.name == FileTools.TOOL_WRITE || tc.name == FileTools.TOOL_PATCH) {
                                    FileSnapshots.capture(
                                        getApplication(),
                                        getApplication<Application>().filesDir,
                                        sid,
                                        args.optString("path"),
                                        turn = activeTurnId
                                    )
                                }
                                val r = FileTools.execute(
                                    getApplication(),
                                    tc.name,
                                    args
                                )
                                val detail = when (tc.name) {
                                    FileTools.TOOL_WRITE ->
                                        "写入 " + args.optString("path") + "（" + r.length + "）"
                                    FileTools.TOOL_PATCH ->
                                        "局部修改 " + args.optString("path") + "（" +
                                                args.optString("old").length + " → " +
                                                args.optString("new").length + " 字）"
                                    FileTools.TOOL_READ ->
                                        "读取 " + args.optString("path")
                                    FileTools.TOOL_LIST -> "列出目录"
                                    else -> "搜索「" + args.optString("pattern") + "」"
                                }
                                if (tc.name == FileTools.TOOL_PATCH) {
                                    // 卡片预览要跟着更新，否则还显示替换前的旧内容
                                    val p0 = args.optString("path")
                                    runCatching {
                                        val body = FileTools.execute(
                                            getApplication(), FileTools.TOOL_READ,
                                            org.json.JSONObject().put("path", p0)
                                        )
                                        if (body.isNotBlank()) {
                                            val list = _messages.value.toMutableList()
                                            if (list.isNotEmpty()) {
                                                val last = list.last()
                                                val merged = (last.genFiles.filter { it.path != p0 } +
                                                        ChatStore.GenFile(
                                                            p0, body,
                                                            last.genFiles.firstOrNull { it.path == p0 }?.content
                                                        )).takeLast(5)
                                                list[list.lastIndex] = last.copy(genFiles = merged)
                                                _messages.value = list
                                            }
                                        }
                                    }
                                }
                                if (tc.name == FileTools.TOOL_WRITE) {
                                    val p0 = args.optString("path")
                                    val body = args.optString("content")
                                    if (p0.isNotBlank() && body.isNotBlank()) {
                                        val list = _messages.value.toMutableList()
                                        if (list.isNotEmpty()) {
                                            val last = list.last()
                                            val merged = (last.genFiles.filter { it.path != p0 } +
                                                    ChatStore.GenFile(
                                                        p0, body,
                                                        last.genFiles.firstOrNull { it.path == p0 }?.content
                                                    )).takeLast(5)
                                            list[list.lastIndex] = last.copy(genFiles = merged)
                                            _messages.value = list
                                        }
                                    }
                                }
                                // 时间线标题按动作走：「写入 番茄钟.html」比「文件：file_write」好读，
                                // 也让每一行能按种类配图标（见 ChatScreen.toolStyle）
                                val fileVerb = when (tc.name) {
                                    FileTools.TOOL_WRITE -> "写入"
                                    FileTools.TOOL_PATCH -> "编辑"
                                    FileTools.TOOL_READ -> "读取"
                                    FileTools.TOOL_LIST -> "列出"
                                    else -> "搜索文件"
                                }
                                val fileTarget = if (tc.name == FileTools.TOOL_GREP) {
                                    args.optString("pattern")
                                } else {
                                    args.optString("path").substringAfterLast('/')
                                        .ifBlank { args.optString("path") }
                                }
                                steps.add(
                                    ChatStore.Step(
                                        "tool", "$fileVerb $fileTarget", detail.take(800),
                                        args = args.toString().take(600),
                                        ok = !r.trimStart().startsWith("错误")
                                    )
                                )
                                syncSteps()
                                ServerProcessManager.appLog("文件工具完成：$detail")
                                r
                                }
                            } else if (sandboxOn && tc.name == WebRunTool.NAME) {
                                sandboxCalls++
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val label = args.optString("path").ifBlank { "内联代码" }
                                ServerProcessManager.appLog("沙箱自检 ${sandboxCalls}：$label")
                                steps.add(
                                    ChatStore.Step(
                                        "tool", "运行自检（第 ${sandboxCalls} 次）", label.take(80)
                                    )
                                )
                                syncSteps()
                                val r = WebRunTool.execute(getApplication(), args)
                                // 自检跑多少次都行：模型自己决定什么时候停（不再用「两次同样报错 = 无进展」判据）
                                // 只记真正的错误：自检干净通过时报告第一行是「页面存活，无报错」，
                                // 把它当错误存下来会让续跑误以为上次没过
                                appendToLastInline(sid) { m ->
                                    val errs = r.lineSequence()
                                        .firstOrNull { it.trimStart().startsWith("✗") }
                                    val dead = if (!r.contains("【运行结果】页面存活")) {
                                        r.lineSequence().firstOrNull { it.isNotBlank() }
                                    } else null
                                    m.copy(
                                        task = m.task?.copy(
                                            lastError = (errs ?: dead)?.trim()?.take(200),
                                            rounds = round + 1
                                        )
                                    )
                                }
                                ServerProcessManager.appLog(
                                    "沙箱自检完成：" + (r.lineSequence().firstOrNull() ?: "")
                                )
                                r
                            } else if (tc.name == "web_search") {
                                val query = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                    .optString("query")
                                val norm = query.trim().lowercase()
                                // 隐私策略：含图片轮次可"禁止/先问我/允许"
                                val turnPolicy =
                                    if (turnHasImage) AppSettings.imageSearchPolicy else "allow"
                                when {
                                    turnPolicy == "block" -> {
                                        ServerProcessManager.appLog("图片轮已阻止联网搜索（策略=禁止）")
                                        "本轮消息包含图片，出于隐私保护禁止联网搜索，请基于已有信息回答。"
                                    }
                                    turnPolicy == "ask" && !askSearchApproval(query) -> {
                                        ServerProcessManager.appLog("用户拒绝了本次图片轮搜索")
                                        "用户拒绝了本次联网搜索，请基于已有信息直接回答。"
                                    }
                                    norm.isNotBlank() && searchedQueries.containsKey(norm) -> {
                                        ServerProcessManager.appLog("内置搜索：相同查询「${query.take(30)}」复用已有结果（不占用配额）")
                                        steps.add(ChatStore.Step("search", "搜索网页：${query.take(60)}", "相同查询，复用已有结果"))
                                        syncSteps()
                                        searchedQueries[norm]!!
                                    }
                                    searchCount >= searchCap -> {
                                        ServerProcessManager.appLog("搜索已达本消息上限（$searchCap 次），返回提示")
                                        steps.add(ChatStore.Step("search", "搜索网页：${query.take(60)}", "已达上限，未执行"))
                                        syncSteps()
                                        "本轮搜索次数已达上限，请基于已有搜索结果直接回答。"
                                    }
                                    else -> {
                                        searchCount++
                                        if (WebSearch.sanitizeQuery(query).second) {
                                            ServerProcessManager.appLog("搜索词含身份证/手机号/卡号，已脱敏后再出网")
                                        }
                                        ServerProcessManager.appLog("内置搜索：「${query.take(40)}」…")
                                        val rs = WebSearch.search(
                                            query, fetchFullText = AppSettings.searchFetchFullText
                                        )
                                        searchSources.addAll(rs)
                                        ServerProcessManager.appLog("内置搜索：返回 ${rs.size} 条结果")
                                        val text = WebSearch.formatResults(rs)
                                        searchedQueries[norm] = text
                                        val fetched = rs.count { it.content != null }
                                        steps.add(
                                            ChatStore.Step(
                                                "search",
                                                "搜索网页：${query.take(60)}",
                                                "共 ${rs.size} 条结果" +
                                                        if (AppSettings.searchFetchFullText && fetched > 0)
                                                            "（已抓取 $fetched 篇正文）" else ""
                                            )
                                        )
                                        syncSteps()
                                        text
                                    }
                                }
                            } else {
                                val args = org.json.JSONObject(tc.arguments.ifBlank { "{}" })
                                val route = mcpRoute[tc.name]
                                    ?: throw IllegalStateException("未路由的工具：${tc.name}")
                                // 同一组参数失败多少次都照常调用：错误会回传给模型，由它决定改参数还是换路
                                run {
                                    ServerProcessManager.appLog("MCP 调用：${tc.name}")
                                    var firstTry =
                                        runCatching { McpHttpClient.callTool(route.first, null, route.second, args) }
                                    // 上下文类错误（workspaceId 传错 / 编辑会话过期）先自己改参数重试一次：
                                    // 这两类改完就能成，不值得再耗一整轮让模型猜（真机上它连撞四次都没猜对）
                                    val repair = firstTry.exceptionOrNull()?.let { e ->
                                        (e as? McpHttpClient.McpException)?.toolError?.let {
                                            McpArgRepair.repair(it, args, mcpWorkspaceIds[route.first])
                                        }
                                    }
                                    if (repair != null) {
                                        ServerProcessManager.appLog("MCP：${repair.note}")
                                        val retried = runCatching {
                                            McpHttpClient.callTool(route.first, null, route.second, repair.args)
                                        }
                                        if (retried.isSuccess) {
                                            steps.add(
                                                ChatStore.Step(
                                                    "tool", "自动修正参数后重试：${tc.name}",
                                                    repair.note,
                                                    args = repair.args.toString().take(600),
                                                    ok = true
                                                )
                                            )
                                            syncSteps()
                                        }
                                        firstTry = retried
                                    }
                                    firstTry.fold(
                                            onSuccess = { r ->
                                                McpArgRepair.rememberedWorkspaceId(r)?.let {
                                                    mcpWorkspaceIds[route.first] = it
                                                }
                                                steps.add(
                                                    ChatStore.Step(
                                                        "tool", "调用工具：${tc.name}",
                                                        r.take(800),
                                                        args = args.toString().take(600),
                                                        ok = true
                                                    )
                                                )
                                                syncSteps()
                                                ServerProcessManager.appLog("MCP 完成：${tc.name}")
                                                r
                                            },
                                            onFailure = { e ->
                                                if (e is kotlinx.coroutines.CancellationException) throw e
                                                // 失败也要落成可见的一步，否则界面上一片正常、实际什么都没发生
                                                // 同批工具名传进去：缺工作区/会话这类错要能点名「先调哪个」
                                                val msg = McpHttpClient.explain(
                                                    route.second, e, mcpRoute.values.map { it.second }
                                                )
                                                ServerProcessManager.appLog(
                                                    "MCP 失败：${tc.name} — ${msg.lineSequence().first()}"
                                                )
                                                steps.add(
                                                    ChatStore.Step(
                                                        "tool", "调用工具：${tc.name}",
                                                        msg.take(800),
                                                        args = args.toString().take(600),
                                                        ok = false
                                                    )
                                                )
                                                syncSteps()
                                                msg
                                            }
                                        )
                                }
                            }
                        }.getOrElse { e ->
                            ServerProcessManager.appLog("工具失败：${tc.name} — ${e.message}")
                            "工具执行失败：${e.message}"
                        }
                        // 回传前按窗口预算压一次：一次读取吐回 9 万字曾把上下文从 15.5k 顶到 108.6k，
                        // 下一轮直接 201837 token 超窗 400，整轮白跑。宽松裁剪，正常大小完全不受影响。
                        val budget = ToolResultBudget.budgetChars(
                            AppSettings.effectiveCtx(AppSettings.selectedModelPath),
                            AppSettings.toolResultBudgetPct
                        )
                        val fitted = ToolResultBudget.trim(result, budget)
                        if (fitted.truncated) {
                            ServerProcessManager.appLog(
                                "工具结果过大（${fitted.originalChars} 字符），回传模型时压到 " +
                                        "${fitted.text.length} 字符（预算 $budget）"
                            )
                        }
                        newMsgs.add(ChatClient.ChatMessage("tool", fitted.text, toolCallId = tc.id))

                        // 熔断计数：工具名 + 参数完全一致才算「同一次失败」；
                        // 只要换了参数或成功了，计数清零——正常重试不会被误伤。
                        val callKey = tc.name + "|" + tc.arguments
                        val isFailure = fitted.text.trimStart().startsWith("错误") ||
                                fitted.text.startsWith("工具执行失败") ||
                                fitted.text.startsWith("【命令退出码") ||
                                fitted.text.startsWith("搜索失败") ||
                                fitted.text.startsWith("Linux 环境未就绪")
                        if (isFailure) {
                            if (callKey == lastCallKey) sameCallFails++
                            else {
                                lastCallKey = callKey
                                sameCallFails = 1
                            }
                        } else {
                            lastCallKey = null
                            sameCallFails = 0
                        }
                    }

                    // 同一调用连续失败到阈值 → 摘掉这个工具，并明确告诉模型别再用它
                    if (sameCallFails >= SAME_CALL_FAIL_LIMIT) {
                        val toolName = lastCallKey?.substringBefore('|')
                        if (toolName != null && mutedTools.add(toolName)) {
                            ServerProcessManager.appLog(
                                "熔断：$toolName 连续失败 $sameCallFails 次，本轮起不再提供该工具"
                            )
                            steps.add(
                                ChatStore.Step(
                                    "tool", "熔断：暂时移除 $toolName",
                                    "同样的调用连续失败 $sameCallFails 次"
                                )
                            )
                            syncSteps()
                            newMsgs.add(
                                ChatClient.ChatMessage(
                                    "user",
                                    "（系统提示：$toolName 已经用完全相同的参数连续失败 $sameCallFails 次，" +
                                            "这个工具暂时不给你用了。请换一种做法：改路径、换工具、或用 run_bash；" +
                                            "如果确实做不到，就直接告诉用户卡在哪里。）"
                                )
                            )
                        }
                        sameCallFails = 0
                        lastCallKey = null
                    }
                    apiMsgs = newMsgs
                }

                // 不再「无正文就强行再发一次无工具请求」：模型不写正文就是不写，
                // 由用户决定追问或继续（2026-10-30 用户决定）。

                if (usedTools.isNotEmpty()) {
                    val base = _lastStats.value ?: ""
                    _lastStats.value = "🔧 ${usedTools.joinToString("、")}${if (base.isNotEmpty()) " · $base" else ""}"
                }
                // 只有「循环是被上限耗尽的」才算被打断。
                // 这里以前只判 isGenerating(sid)——生成中当然是 true，于是只要这条消息用过工具，
                // 就算模型自己答完收尾了也会打「轮次用尽」并挂上「继续这个任务」。
                if (!answeredNaturally && isGenerating(sid)) {
                    appendToLastInline(sid) { m ->
                        if (m.role == "assistant" && m.task?.lastError != null) {
                            m.copy(task = m.task.copy(stopped = true))
                        } else m
                    }
                    ServerProcessManager.appLog(
                        "工具轮次用尽（设置里是 ${AppSettings.toolRoundLimit} 轮），任务未收敛，" +
                                "已标记为可续跑；想让它一次跑完可在设置里把上限改为「不限」"
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (repetitionAborted) {
                    // 复读中断：模型在长字符串里打转，与其等它把预算烧完（实测十几分钟）
                    // 不如立刻停，让用户点「继续」换分段写法
                    _lastStats.value = "输出陷入复读，已中断（$abortDeltas token）"
                    _toast.value = "输出陷入重复已中断，可点「继续这个任务」分段重试"
                    appendToLastInline(sid) { m ->
                        if (m.role == "assistant" && m.task != null) {
                            m.copy(task = m.task.copy(stopped = true))
                        } else m
                    }
                } else {
                    val toolJsonBroke = e.message?.contains("Failed to parse tool call arguments as JSON") == true
                    if (toolJsonBroke && turnHadTools && turnSystem != null && !toolJsonRetried) {
                        toolJsonRetried = true
                        ServerProcessManager.appLog("工具调用参数格式出错，去掉工具重发一次（降级为纯文字回答）")
                        // 上一次写进消息里的是崩掉工具调用的碎片，清空后再重填，免得拼出乱七八糟的内容
                        appendToLastInline(sid) { m ->
                            if (m.role == "assistant") m.copy(content = "", steps = emptyList()) else m
                        }
                        val ok = runCatching { retryWithoutTools(sid, turnSystem!!, turnHistory) }
                        if (ok.getOrDefault(false)) {
                            _toast.value = "工具调用格式出错，已自动改用纯文字回答"
                        } else {
                            _error.value = friendlyError(e)
                        }
                    } else {
                        // 日志保留原文（appendLog 内部按 1200 字截断），界面只显示截断后的短提示
                        ServerProcessManager.appLog("聊天失败：${e.message ?: "请求失败"}")
                        _error.value = friendlyError(e)
                    }
                }
            } finally {
                // 被停止/出错时可能还停在「思考中」：就地收尾，别让时间线留一行永远转圈
                appendToLastInline(sid) { m ->
                    if (m.steps.none { it.type == "think" && it.label == "思考中" }) m
                    else m.copy(
                        steps = m.steps.map {
                            if (it.type == "think" && it.label == "思考中") {
                                it.copy(label = "思考（已中断）")
                            } else it
                        }
                    )
                }
                val msgs = messagesOf(sid)
                val last = msgs.lastOrNull()
                // 只有「彻底空」才删。之前只判正文为空，导致点停止时
                // 连步骤、思考、生成的文件、续跑入口一起被抹掉——用户看到的就是什么都没了。
                val empty = last != null &&
                        last.content.isNullOrEmpty() &&
                        last.steps.isEmpty() &&
                        last.reasoning.isNullOrBlank() &&
                        last.genFiles.isEmpty() &&
                        last.todos.isEmpty()
                if (empty) {
                    writeMessages(sid, msgs.dropLast(1))
                    // 用户主动停止不算错误
                    if (_error.value == null && last?.task?.stopped != true) {
                        _error.value = "无回复（服务未运行？请先在状态页启动）"
                    }
                }
                heartbeat?.cancel()
                persistSession(sid)
                _generating.update { it - sid }
                streamJobs.remove(sid)
                setProgress(sid, null)
                // 音视频附件只在当轮有效（体积太大，不落盘、不进历史）
                _pendingMediaB64.value = null
                _pendingMediaMime.value = null
                _pendingMediaName.value = null
                _pendingMediaNote.value = null
                runCatching {
                    if (_generating.value.isEmpty()) {
                        com.localllm.server.core.AdbBridge.setTouchMarker(getApplication(), false)
                    }
                }
                // 上一轮把上下文吃得太满 → 自动压一次。放在 generating 清掉之后，
                // 否则 compressContext 会自检「正在生成中」直接返回。
                if (autoCompressPending) {
                    autoCompressPending = false
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(400)
                        if (_activeId.value == sid && _generating.value.isEmpty()) {
                            compressContext()
                        }
                    }
                }
                // 在别的会话里完成时给个提示，否则用户不知道后台跑完了
                if (_activeId.value != sid) {
                    val title = _sessions.value.find { it.id == sid }?.title ?: "另一条会话"
                    val ok = _error.value == null
                    _toast.value = if (ok) "「$title」已生成完成" else "「$title」生成失败：${_error.value}"
                }
                // 这一轮结束了：还有排队的指令就接着做（延后一点，让收尾落盘先完成）
                viewModelScope.launch {
                    kotlinx.coroutines.delay(300)
                    drainQueue(sid)
                }
            }
        }
    }

    /** 停掉指定会话的生成（不动别的会话） */
    private fun stopSession(sid: String) {
        // 立刻复位按钮状态，不等协程真正感知取消（阻塞读可能要等到下一行 SSE）
        _generating.update { it - sid }
        setProgress(sid, null)
        // 被取消的协程不会走 confirmTool 的收尾：确认框留在这儿会挡住下一次操作
        _toolAsk.value = null
        toolAskDeferred = null
        // **先断流、不强杀协程**：`doStream` 是个几千行的巨型挂起函数，
        // 直接 cancel 会让 CancellationException 从挂起点穿过它——真机上两次因此把进程带走
        // （tombstone：'No static field versionName … with unexpected pending exception:
        //  JobCancellationException'，以及 'Unexpected instruction: unused-e3'，都停在
        //  doStream$2.invokeSuspend）。abort 掉 HTTP 连接后，读循环会自然抛 Socket closed 走到收尾。
        ChatClient.abort(sid)
        val job = streamJobs[sid]
        if (job != null) {
            // 兜底：5 秒还没收尾（比如正卡在工具执行里）才强杀，避免它挂着占会话
            viewModelScope.launch {
                kotlinx.coroutines.delay(5_000)
                if (job.isActive) {
                    ServerProcessManager.appLog("停止后 5 秒仍未收尾，强制取消协程（会话 $sid）")
                    runCatching { job.cancel() }
                }
            }
        }
        // 标记为「被打断」：界面于是显示「继续这个任务」，且状态已落盘，重开 App 也能续
        appendToLastInline(sid) { m ->
            if (m.role == "assistant" && m.task != null) m.copy(task = m.task.copy(stopped = true))
            else m
        }
        persistSession(sid)
    }

    /** 停止当前会话：先关门（回调失效）→ 立即断连（思考流也停）→ 取消协程 → 落盘已生成部分。
     *  只停当前会话，别的会话照常并行跑。 */
    fun stop() {
        val sid = _activeId.value
        ServerProcessManager.appLog("用户手动停止生成（会话 $sid）")
        // 停止是用户随时会点的动作，任何一步出错都不能把 App 带走（真机反馈过点停止闪退）
        runCatching { stopSession(sid) }.onFailure {
            ServerProcessManager.appLog("停止生成时出错：${it.message ?: it.javaClass.simpleName}")
            _error.value = "停止时出错：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /**
     * 续跑被中断的那一轮。
     * 关键点：**不重放历史**，只用任务快照重建一条紧凑指令——
     * 目标、已生成的文件、计划、最后一次报错。模型看历史也记不住自己怎么想的，
     * 给它「现状」才接得上；顺带省掉几十 k token 的上下文。
     */
    fun resumeStoppedTask(index: Int) {
        if (blockIfBusy()) return
        val sid = _activeId.value ?: return
        val msgs = messagesOf(sid)
        val m = msgs.getOrNull(index) ?: return
        if (m.role != "assistant") return
        val t = m.task ?: return
        // 续跑后旧入口作废：否则每中断一次就多一个按钮，点哪个都是续跑最后一个
        writeMessages(
            sid,
            msgs.toMutableList().also { it[index] = m.copy(task = t.copy(stopped = false)) }
        )
        val files = m.genFiles.joinToString("、") { "${it.path}（${it.content.length} 字）" }
        val plan = m.todos.joinToString("；") {
            (if (it.status == "completed") "✓" else "□") + " " + it.title
        }
        val prompt = buildString {
            append("继续上一个被中断的任务。\n")
            append("目标：").append(t.goal.ifBlank { "见上面的用户消息" }).append('\n')
            if (files.isNotBlank()) {
                append("已生成的文件：").append(files).append('\n')
                append("请先用 file_read 读回最新内容，不要在假设的基础上改。\n")
            }
            if (plan.isNotBlank()) append("计划：").append(plan).append('\n')
            // 三种情况要求不同：有错才谈「只改这一处」；自检已过只是没做完，就别让它重写；
            // 不是代码任务的话根本不该提自检
            val codeLike = m.genFiles.any {
                val p = it.path.lowercase()
                p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".js") ||
                        p.endsWith(".css") || p.endsWith(".svg")
            }
            when {
                t.lastError != null -> {
                    append("最后一次检查未通过：").append(t.lastError).append('\n')
                    append("要求：只动出错的那一处及其直接相关代码，不要整份重写；改完调用 ")
                    append(WebRunTool.NAME).append(" 自检，通过前不要声称完成。")
                }
                codeLike -> {
                    append("上一次自检已通过，继续把还没做完的部分补上。\n")
                    append("要求：不要重写已经能跑的部分；每补完一块再调用一次 ")
                    append(WebRunTool.NAME).append(" 自检。")
                }
                else -> append("接着把没做完的部分做完，不要重复已经产出的内容。")
            }
        }
        ServerProcessManager.appLog("续跑被中断的任务：${t.goal.take(40)}")
        append(ChatStore.Msg("user", prompt))
        append(
            ChatStore.Msg(
                "assistant", "",
                // 清单必须带过来：界面上的进度卡片靠它，工具循环判「任务还没做完」也靠它
                // （丢了就只能看正文尾部猜，续跑轮里答后调用会被误当幻觉丢掉）
                todos = m.todos,
                task = ChatStore.TaskState(goal = t.goal, rounds = t.rounds)
            )
        )
        doStream()
    }

    /** 新建会话并切换（保留当前会话内容） */
    fun newChat() {
        persist()
        val id = System.nanoTime().toString()
        val s = ChatStore.Session(id, "新会话", System.currentTimeMillis(), emptyList())
        _sessions.value = _sessions.value + s
        _activeId.value = id
        _messages.value = emptyList()
        _lastStats.value = null
        clearPendingAttachments()
        saveStore()
    }

    /**
     * 切换会话：不中断正在跑的生成。生成结果的增量与收尾都按发起时会话写回，
     * 切回来就能看到它继续出字（或已经写完）。
     */
    fun switchSession(id: String) {
        if (id == _activeId.value) return
        persist()
        _activeId.value = id
        _messages.value = _sessions.value.find { it.id == id }?.messages ?: emptyList()
        _lastStats.value = null
        // 附件属于「这条会话正要发的那条消息」：切会话必须清掉，
        // 否则上一条会话选的图会跟着进到新会话里（看起来就像记忆串了）
        clearPendingAttachments()
        saveStore()
    }

    /** 清掉待发送的图片/文件附件（切会话、新建会话时用） */
    private fun clearPendingAttachments() {
        _pendingImageB64.value = null
        _pendingImageFullB64.value = null
        _pendingOcrText.value = null
        _pendingFile.value = null
        _pendingImageNote.value = null
    }

    /** 把会话放进某个文件夹（工作区）；null = 未分组 */
    fun setSessionFolder(sid: String, folder: String?) {
        val name = folder?.trim()?.takeIf { it.isNotEmpty() }
        _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(folder = name) else it
        }
        saveStore()
    }

    /** 本会话的执行权限：read / workspace / full；null = 跟随全局默认 */
    fun setSessionPermission(sid: String, level: String?) {
        _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(permission = level) else it
        }
        saveStore()
    }

    /** 计划模式：只读 + 先给计划，等用户点头再执行 */
    fun setSessionPlanMode(sid: String, on: Boolean) {
        _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(planMode = on) else it
        }
        saveStore()
    }

    /** 自驱模式：目标没达成时自动继续（有次数上限） */
    fun setSessionAutoDrive(sid: String, on: Boolean) {
        _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(autoDrive = on) else it
        }
        saveStore()
    }

    /** 规划用云端：先让云端出计划，本地照着执行 */
    fun setSessionPlanWithCloud(sid: String, on: Boolean) {
        _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(planWithCloud = on) else it
        }
        saveStore()
    }

    /**
     * 请云端出一份分步计划。失败或没配置就返回 null，这一轮退回纯本地——
     * 不能因为"云端不可用"把整轮卡死。
     */
    /** 云端规划的结果：成功给出计划，或者说明为什么没生效（不能静默退回本地） */
    private sealed interface PlanResult {
        data class Ok(val text: String) : PlanResult
        data class Skip(val reason: String) : PlanResult
    }

    /**
     * 挑一个端点来做规划：
     * 1) 设置里明确指定的「规划端点」（按 URL 认）；
     * 2) 没指定就用当前聊天渠道那个；
     * 3) 再没有就用预设里第一个有地址的。
     * 注意**不看 chatCloudEnabled**——那是"聊天走不走云端"，而这里要的恰恰是
     * "聊天在本地、规划借云端"，用它会把这个功能直接掐死。
     */
    private fun planEndpoint(): Triple<String, String, String?>? {
        val presets = AppSettings.loadCloudPresets().filter { it.url.isNotBlank() }
        if (presets.isEmpty()) return null
        val norm = { s: String -> s.trim().removeSuffix("/") }
        val pinned = AppSettings.planPresetUrl
        val preset = (if (pinned.isNotBlank()) presets.firstOrNull { norm(it.url) == norm(pinned) } else null)
            ?: presets.firstOrNull { norm(it.url) == norm(AppSettings.chatCloudUrl) }
            ?: presets.first()
        return Triple(norm(preset.url), preset.model.ifBlank { "default" }, preset.key.ifBlank { null })
    }

    private suspend fun requestCloudPlan(goal: String): PlanResult {
        val ep = planEndpoint()
            ?: return PlanResult.Skip("「模型渠道」里还没有可用的端点（需要在「配置模型」里加一条带地址的接口）")
        val url = ep.first
        val model = ep.second
        val key = ep.third
        val system = "你是任务规划者。根据用户目标，输出一份可执行的分步计划：\n" +
                "1. 只写步骤（每步做什么、要动哪个文件或路径、需要什么取舍）；\n" +
                "2. 不要输出代码，不要写完整文件内容；\n" +
                "3. 3-8 步为宜，每步一句话。"
        // 云端看不到手机上的目录，只能凭目标猜路径。把工作目录顶层清单一起发过去，
        // 计划里的路径才落得住（否则它会规划出 /path/to/your/file.csv 这类东西）。
        val dirHint = runCatching {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                FileTools.execute(getApplication(), FileTools.TOOL_LIST, org.json.JSONObject())
            }
        }.getOrNull()?.takeIf { !it.startsWith("错误") }?.take(1500)

        val userMsg = buildString {
            append(goal.take(1500))
            if (!dirHint.isNullOrBlank()) {
                append("\n\n【当前工作目录的顶层内容（规划时按这些真实路径来，不要编造路径）】\n")
                append(dirHint)
            }
        }
        val messages = listOf(
            ChatClient.ChatMessage("system", system),
            ChatClient.ChatMessage("user", userMsg)
        )
        val sb = StringBuilder()
        val error = runCatching {
            ChatClient.chatStream(
                port = AppSettings.port,
                messages = messages,
                apiToken = key,
                baseUrl = url,
                model = model,
                callKey = "plan-" + System.currentTimeMillis(),
                thinking = null,
                effort = null,
                maxTokens = 1200,
                codeTask = false,
                tools = null,
                onToolCalls = {},
                onFinish = {},
                onTiming = { _, _ -> },
                onDelta = { d -> sb.append(d) },
                onReasoning = {},
                onFinal = { _, _, _ -> }
            )
        }.exceptionOrNull()
        if (error != null) {
            return PlanResult.Skip("云端请求失败：${error.message ?: error.javaClass.simpleName}")
        }
        val text = sb.toString().trim()
        if (text.isBlank()) return PlanResult.Skip("云端返回了空计划")
        ServerProcessManager.appLog("云端规划完成：${text.length} 字")
        return PlanResult.Ok(text)
    }

    /**
     * 把这一轮跑通的流程沉淀成技能：以后遇到类似目标可以直接套用。
     * 只收「做成了」的流程（有工具步骤、没有失败步骤），不把闲聊和失败的尝试变成技能。
     */
    fun saveFlowAsSkill(sid: String, msgIndex: Int): String {
        val msg = messagesOf(sid).getOrNull(msgIndex) ?: return "找不到这条消息"
        val flow = msg.steps.filter { it.type == "tool" || it.type == "search" }
        if (flow.isEmpty()) return "这一轮没有可沉淀的步骤"
        if (flow.any { !it.ok }) return "这一轮有失败的步骤，先做成再存更可靠"
        val goal = msg.task?.goal?.takeIf { it.isNotBlank() }
            ?: messagesOf(sid).lastOrNull { it.role == "user" }?.content?.take(80)
            ?: "未命名流程"
        val name = goal.lineSequence().first().trim().take(20).ifBlank { "未命名流程" }
        val prompt = buildString {
            append("（以下是从一次成功执行里沉淀的做法，遇到类似目标可以照做；场景不同就调整。）\n")
            append("目标示例：").append(goal.take(120)).append("\n\n")
            append("已验证的步骤：\n")
            flow.forEachIndexed { idx, st ->
                append(idx + 1).append(". ").append(st.label.take(80))
                st.args?.takeIf { it.isNotBlank() }?.let {
                    append("\n   参数：").append(it.take(160))
                }
                append('\n')
            }
        }
        val skill = Skill(
            id = "auto-" + System.currentTimeMillis(),
            name = name,
            desc = "自动沉淀：${flow.size} 步流程",
            prompt = prompt
        )
        AppSettings.saveSkills(AppSettings.loadSkills() + skill)
        ServerProcessManager.appLog("流程已存为技能：$name（${flow.size} 步）")
        return "已存为技能「$name」"
    }

    /** 撤销某个文件最近一次写入：恢复到写入前的状态（本次新建的就删掉），
     * 并把产物卡回退。返回一句能直接弹给用户的话。
     */
    fun undoFileWrite(sid: String, path: String): String {
        val app = getApplication<Application>()
        val base = app.filesDir
        val snap = FileSnapshots.latest(base, sid, path)
        val result = FileSnapshots.restore(app, base, sid, path)
        if (result.startsWith("已撤销")) {
            FileSnapshots.forget(base, sid, path)
            writeMessages(sid, messagesOf(sid).map { m ->
                if (m.genFiles.none { it.path == path }) m
                else m.copy(
                    genFiles = m.genFiles.mapNotNull { gf ->
                        when {
                            gf.path != path -> gf
                            // 原本存在 → 卡片内容换回旧版（不再有 prev，什么都不用撤销了）
                            snap != null && snap.existed -> ChatStore.GenFile(path, snap.content ?: "", null)
                            // 本次新建又被撤销 → 文件已不存在，卡片跟着撤掉
                            else -> null
                        }
                    }
                )
            })
        }
        ServerProcessManager.appLog("撤销写入：$path → $result")
        return result
    }

    /**
     * 撤销一条消息（一轮生成）里的**全部**文件改动。
     *
     * 逐文件撤销对用户不成立：一轮里模型常常同时改 4、5 个文件，点五次还得记住是哪些。
     * 这里按 turnId 找回这一轮动手之前的样子，一次退回去。
     */
    fun undoRound(sid: String, msgIndex: Int): String {
        val app = getApplication<Application>()
        val base = app.filesDir
        val msg = messagesOf(sid).getOrNull(msgIndex)
            ?: return "找不到这条消息"
        val turn = msg.turnId
        if (turn == 0L) return "这条消息没有写入记录（老数据），只能逐个撤销"
        val result = FileSnapshots.restoreTurn(app, base, sid, turn)
        if (result.startsWith("已撤销本轮")) {
            // 卡片跟着回退：这一轮改过的文件重新按「不知道现状」显示（内容改成空、prev 清掉），
            // 避免界面上还留着已经被撤销的 diff
            writeMessages(sid, messagesOf(sid).mapIndexed { i, m ->
                if (i != msgIndex) m
                else m.copy(genFiles = m.genFiles.map { ChatStore.GenFile(it.path, "", null) })
            })
        }
        ServerProcessManager.appLog("撤销整轮（turn=$turn）：$result")
        return result
    }

    /** 文件名 → 真实路径：绝对路径原样用，相对路径按工作目录拼（工作目录就是 SAF 授权根） */
    private fun realPathFor(name: String): String? =
        if (name.startsWith("/")) name
        else LinuxRuntime.workdirAbsPath(getApplication())?.let { "$it/$name" }

    private fun fileExistsNow(name: String): Boolean =
        realPathFor(name)?.let { java.io.File(it).isFile } == true

    /** 文本类产出物读一份内容供预览/改动对比；二进制（xlsx/pdf/docx）只登记路径 */
    private fun previewOf(name: String): String {
        val abs = realPathFor(name) ?: return ""
        val f = java.io.File(abs)
        if (!f.isFile || f.length() > 256 * 1024) return ""
        val textLike = setOf("csv", "md", "txt", "html", "htm", "json", "py", "js", "sh", "log")
        if (f.extension.lowercase() !in textLike) return ""
        return runCatching { f.readText(Charsets.UTF_8).take(20_000) }.getOrDefault("")
    }

    /**
     * 把「真的产出了这个文件」登记进当前消息的产物列表。
     *
     * 产物卡、会话详情「文件」tab、以及「本轮有没有真的写出文件」的判据，全都只看这个列表——
     * 所以任何真的写了文件的路径都必须经过这里，否则就会出现「文件在、界面上没有、
     * 还被告知没写文件」这种自相矛盾的状态。
     */
    private fun recordGeneratedFile(path: String) {
        if (path.isBlank()) return
        val list = _messages.value.toMutableList()
        if (list.isEmpty()) return
        val last = list.last()
        if (last.role != "assistant") return
        val merged = (last.genFiles.filter { it.path != path } +
                ChatStore.GenFile(
                    path,
                    previewOf(path),
                    last.genFiles.firstOrNull { it.path == path }?.content
                )).takeLast(5)
        list[list.lastIndex] = last.copy(genFiles = merged)
        _messages.value = list
    }

    fun deleteSession(id: String) {
        if (isGenerating(id)) stopSession(id)
        // 会话没了，它那条持久 shell 也别留着占内存
        runCatching { com.localllm.server.core.PersistentShell.reset(id) }
        val remaining = _sessions.value.filterNot { it.id == id }.ifEmpty {
            listOf(ChatStore.Session("1", "新会话", System.currentTimeMillis(), emptyList()))
        }
        _sessions.value = remaining
        if (_activeId.value == id || remaining.none { it.id == _activeId.value }) {
            _activeId.value = remaining.first().id
            _messages.value = remaining.first().messages
        }
        saveStore()
    }

    /**
     * 给会话绑定端点：null = 跟随全局。多会话并行时各绑各的（本地 / 某个云端渠道 / 电脑上的模型）。
     * 只影响之后的发送，正在跑的这一轮不受影响。
     */
    fun setSessionEndpoint(sid: String, endpoint: ChatStore.Endpoint?) {
        _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(endpoint = endpoint) else it
        }
        saveStore()
        ServerProcessManager.appLog(
            "会话端点：" + (endpoint?.let {
                if (it.local) "本地服务" else "${it.baseUrl}（${it.model.ifBlank { "default" }}）"
            } ?: "跟随全局")
        )
    }

    /** 粗略 token 估算：汉字约 1 token，其它字符约 1/4 token */
    private fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F) cjk++ else other++
        }
        return cjk + other / 4 + 1
    }

    /**
     * 设置/清除本会话的助手指令覆盖（null/空 = 回落到全局预设）。
     * [skillId] / [chainId] 记录这份指令是"哪个技能/技能链"带来的，界面据此显示生效标记；
     * 手写指令时两者都传 null，会清掉旧标记。
     */
    fun setSessionSystemPrompt(text: String?, skillId: String? = null, chainId: String? = null) {
        val id = _activeId.value
        val v = text?.trim()?.takeIf { it.isNotBlank() }
        _sessions.value = _sessions.value.map {
            if (it.id == id) it.copy(
                systemPrompt = v,
                appliedSkillId = if (v == null) null else skillId,
                appliedChainId = if (v == null) null else chainId,
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        saveStore()
        ServerProcessManager.appLog(
            when {
                v == null -> "本会话指令已清除（使用全局预设）"
                chainId != null -> "已应用技能链（${v.length} 字）"
                skillId != null -> "已应用技能（${v.length} 字）"
                else -> "本会话指令已更新（${v.length} 字）"
            }
        )
    }

    fun currentSessionSystemPrompt(): String? =
        _sessions.value.find { it.id == _activeId.value }?.systemPrompt

    /** 首轮回答后自动生成会话标题（仅当标题仍是默认时） */
    private suspend fun maybeAutoTitle(userText: String, assistantText: String) {
        val id = _activeId.value
        val session = _sessions.value.find { it.id == id } ?: return
        val isDefaultTitle = session.title == "新会话" ||
                session.title == userText.take(24) ||
                session.title.isBlank()
        if (!isDefaultTitle) return
        val buf = StringBuilder()
        runCatching {
            val ep = endpointFor(id)
            val cloud = !ep.local
            ChatClient.chatStream(
                port = AppSettings.port,
                messages = listOf(
                    ChatClient.ChatMessage(
                        "system",
                        "为这段对话起一个简短标题：不超过 12 个字，不加标点与引号，直接输出标题本身。"
                    ),
                    ChatClient.ChatMessage(
                        "user",
                        "用户：" + userText.take(300) + "\n助手：" + assistantText.take(300)
                    )
                ),
                apiToken = if (cloud) ep.apiKey.ifBlank { null }
                           else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                baseUrl = if (cloud) ep.baseUrl else null,
                model = if (cloud) ep.model.ifBlank { "default" } else "local",
                thinking = if (cloud) null else false,
                maxTokens = 60,
                tools = null,
                onDelta = { d -> buf.append(d) },
                onReasoning = {},
                onFinal = { _, _, _ -> },
                callKey = "title:$id"
            )
        }.onFailure { ServerProcessManager.appLog("自动标题失败：${it.message}") }
        val title = buf.toString().trim().trim('"', '“', '”', '。', ' ')
            .replace(Regex("\\s+"), "")
        if (title.isNotBlank()) {
            _sessions.value = _sessions.value.map {
                if (it.id == id) it.copy(title = title.take(20)) else it
            }
            saveStore()
            ServerProcessManager.appLog("已自动生成会话标题：$title")
        }
    }

    fun clearError() { _error.value = null }

    /** 删除一条消息；若删的是正在生成的那条，先停止生成再删 */
    fun deleteAt(index: Int) {
        val list = _messages.value
        if (index !in list.indices) return
        if (index == list.lastIndex && isGenerating(_activeId.value)) {
            stop()
        }
        val m = list[index]
        _messages.value = list.toMutableList().also { it.removeAt(index) }
        ServerProcessManager.appLog(
            "已删除一条${if (m.role == "user") "用户" else "助手"}消息（${m.content.length} 字）"
        )
        persist()
    }

    /** 读某会话的消息：当前会话走 _messages（实时），其它会话从 _sessions 取 */
    private fun messagesOf(sid: String): List<ChatStore.Msg> =
        if (_activeId.value == sid) _messages.value
        else _sessions.value.find { it.id == sid }?.messages ?: emptyList()

    /** 写某会话的消息（只动内存：流式期间高频调用，不落盘） */
    private fun writeMessages(sid: String, list: List<ChatStore.Msg>) {
        if (_activeId.value == sid) _messages.value = list
        else _sessions.value = _sessions.value.map {
            if (it.id == sid) it.copy(messages = list) else it
        }
    }

    /** 就地改写某会话最后一条消息（没有消息时忽略） */
    /**
     * 按当前消息筛选 MCP 工具：工具少时全给；多时按名字/描述关键词匹配，
     * 命中为空则退回全量（宁可慢，也不要因为猜不到而让模型没有可用的能力）。
     */
    private fun filterMcpForText(
        arr: org.json.JSONArray, text: String
    ): List<org.json.JSONObject> {
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (maxToolsFull <= 0 || all.size <= maxToolsFull) return all
        val keys = Regex("[A-Za-z_]{3,}|[\\u4e00-\\u9fa5]{2,}")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .distinct()
            .take(40)
            .toList()
        val scored = all.map { o ->
            val fn = o.optJSONObject("function")
            val hay = ((fn?.optString("name") ?: "") + " " + (fn?.optString("description") ?: ""))
                .lowercase()
            o to keys.count { hay.contains(it) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val hits = scored.map { it.first }
        // 未命中时**不要退回全量**：57 个工具就是 9K token、几分钟 prefill，等于优化全废。
        // 工具名/描述是英文、用户消息常是中文，硬关键词本来就常常碰不上。
        // 退而求其次：按名字排序保底给几个，保证不是彻底没能力，同时不把 prompt 撑爆。
        val picked = if (hits.isEmpty()) all.sortedBy { it.optJSONObject("function")?.optString("name") ?: "" }
            .take(4)
        else hits.take(MAX_TOOLS_PICKED)
        ServerProcessManager.appLog(
            "MCP 工具注入：全部 ${all.size} 个 → " +
                    (if (hits.isEmpty()) "关键词未命中，保底注入" else "命中 ${hits.size} 个") +
                    "，实际注入 ${picked.size} 个"
        )
        return picked
    }

    /**
     * 工具路由（两阶段调用的第一阶段）：只把「工具名 + 一句话描述」的清单发给模型，
     * 让它挑出这次要用的名字；返回选中工具的完整定义。
     * 比全量注入省一个数量级——57 个工具的完整 schema ≈ 9K token，清单只要 ~1K。
     */
    private suspend fun pickMcpByModel(
        mcpAgg: org.json.JSONArray, userText: String, cloud: Boolean
    ): List<org.json.JSONObject> {
        val all = (0 until mcpAgg.length()).mapNotNull { mcpAgg.optJSONObject(it) }
        if (all.isEmpty()) return emptyList()
        val catalog = all.joinToString("\n") { o ->
            val fn = o.optJSONObject("function")
            (fn?.optString("name") ?: "") + ": " + (fn?.optString("description")?.take(60) ?: "")
        }
        val sb = StringBuilder()
        runCatching {
            ChatClient.chatStream(
                port = AppSettings.port,
                messages = listOf(
                    ChatClient.ChatMessage(
                        "system",
                        "你是工具路由器。从候选工具中挑出完成用户请求需要的工具名，" +
                                "只输出工具名、逗号分隔、最多 5 个；不需要工具就只输出 NONE。不要解释。"
                    ),
                    ChatClient.ChatMessage("user", "用户请求：$userText\n\n候选工具：\n$catalog")
                ),
                apiToken = if (cloud) AppSettings.chatCloudKey.ifBlank { null }
                else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                baseUrl = if (cloud) AppSettings.chatCloudUrl else null,
                model = if (cloud) AppSettings.chatCloudModel.ifBlank { "default" } else "local",
                maxTokens = 120,
                onDelta = { sb.append(it) }
            )
        }.onFailure {
            ServerProcessManager.appLog("工具路由失败：${it.message}")
        }
        val raw = sb.toString()
        val names = raw.split(',', '，', '、', ';', '；', '\n', ' ', '\t')
            .map { it.trim().trim('`', '"', '\'', '.', '。', ':', '：', '-', '*') }
            .filter { it.isNotBlank() && !it.equals("NONE", true) }
        // 双保险：不管模型用什么分隔符，只要工具名出现在输出里就算命中
        val sel = all.filter { o ->
            val n = o.optJSONObject("function")?.optString("name") ?: return@filter false
            n.isNotBlank() && (raw.contains(n) || names.any { it.equals(n, true) })
        }
        ServerProcessManager.appLog(
            "工具路由：候选 ${all.size} 个，模型点名 ${names.take(6)}，匹配到 ${sel.size} 个"
        )
        return sel
    }

    /** 递归剥掉 schema 里的 description（保留 type/required/enum/items 等结构信息） */
    private fun trimSchema(o: org.json.JSONObject): org.json.JSONObject {
        val out = org.json.JSONObject()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k == "description" || k == "title" || k == "examples") continue
            when (val v = o.opt(k)) {
                is org.json.JSONObject -> out.put(k, trimSchema(v))
                is org.json.JSONArray -> {
                    val a = org.json.JSONArray()
                    for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        a.put(if (e is org.json.JSONObject) trimSchema(e) else e)
                    }
                    out.put(k, a)
                }
                else -> if (v != null) out.put(k, v)
            }
        }
        return out
    }

    private fun appendToLastInline(sid: String, transform: (ChatStore.Msg) -> ChatStore.Msg) {
        val list = messagesOf(sid).toMutableList()
        if (list.isEmpty()) return
        list[list.lastIndex] = transform(list.last())
        writeMessages(sid, list)
    }

    private var lastPersistAt = 0L

    /**
     * 生成过程中也要落盘。以前只在整轮结束/手动停止时写磁盘，
     * 进程被后台清掉时这一轮（用户消息、步骤、生成的文件记录）整个消失。
     * 节流到 1.5 秒一次，避免每 token 都写文件。
     */
    /** 收掉所有「被打断」标记：新的指令意味着旧任务不再续跑 */
    private fun clearStoppedTasks(sid: String) {
        val msgs = messagesOf(sid)
        if (msgs.none { it.task?.stopped == true }) return
        runCatching {
            writeMessages(
                sid,
                msgs.map { m ->
                    if (m.task?.stopped == true) m.copy(task = m.task.copy(stopped = false)) else m
                }
            )
        }
    }

    /**
     * 生成过程中也要落盘。以前只在整轮结束/手动停止时写磁盘，
     * 进程被后台清掉时这一轮（用户消息、步骤、生成的文件记录）整个消失。
     * 节流到 1.5 秒一次。
     *
     * 注意：**磁盘 IO 必须放到 IO 线程**。整份 chat_sessions.json 含生成文件正文，
     * 可能好几 MB；在主线程（onDelta 回调）上同步写会把界面卡住——
     * 表现为切会话/新建会话没有反应，看着像 UI 坏了。
     */
    private fun persistProgress(sid: String) {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < 1500) return
        lastPersistAt = now
        // 主线程只更新内存快照（便宜）
        val msgs = messagesOf(sid)
        val updated = _sessions.value.map {
            if (it.id != sid) it
            else it.copy(messages = msgs, updatedAt = System.currentTimeMillis())
        }
        _sessions.value = updated
        val snapshot = updated
        val active = _activeId.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { saveStore(snapshot, active) }
        }
    }

    /** 把某会话的内存状态落盘（含「首条用户消息 → 标题」） */
    private fun persistSession(sid: String) {
        val msgs = messagesOf(sid)
        _sessions.value = _sessions.value.map {
            if (it.id != sid) it else it.copy(
                messages = msgs,
                title = it.title.takeIf { t -> t != "新会话" }
                    ?: (msgs.firstOrNull { m -> m.role == "user" }?.content?.take(24) ?: "新会话"),
                updatedAt = System.currentTimeMillis()
            )
        }
        saveStore()
    }

    /** 当前这一轮生成的标识（毫秒）；文件快照按它分组，实现「撤销整轮」 */
    @Volatile
    private var activeTurnId: Long = 0

    private fun append(msg: ChatStore.Msg) {
        // 时间戳在这里统一盖章：所有新消息都走 append，
        // 就不必让每个构造点各记一次「现在」（漏一个就少一条时间）
        val stamped = if (msg.at == 0L) msg.copy(at = System.currentTimeMillis()) else msg
        _messages.value = _messages.value + stamped
        persist()
    }

    private fun persist() {
        val activeId = _activeId.value
        val sessions = _sessions.value.map {
            if (it.id == activeId) it.copy(
                messages = _messages.value,
                title = it.title.takeIf { t -> t != "新会话" }
                    ?: (_messages.value.firstOrNull { m -> m.role == "user" }
                        ?.content?.take(24) ?: "新会话"),
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        _sessions.value = sessions
        saveStore(sessions, activeId)
    }

    /** 多会话并行时会有多条生成同时收尾/落盘：串行化整份存档写入，避免两份内容互相覆盖踩坏 JSON */
    private val storeWriteLock = Any()

    private fun saveStore(
        sessions: List<ChatStore.Session> = _sessions.value,
        activeId: String = _activeId.value
    ) {
        synchronized(storeWriteLock) {
            ChatStore.save(getApplication(), ChatStore.Store(activeId, sessions))
        }
    }


    /**
     * 上下文压缩：把较早的消息交给当前渠道模型总结成一条 system 摘要，仅保留最近若干条。
     * 触发后上下文占用大幅下降，早期的关键信息以摘要形式保留。
     */
    fun compressContext(keepRecent: Int = 4) {
        if (blockIfBusy()) {
            _lastStats.value = "正在生成中，稍后再压缩"
            return
        }
        val list = _messages.value
        val old = list.dropLast(keepRecent)
        val recent = list.takeLast(keepRecent)
        if (old.size < 2) {
            _lastStats.value = "消息太少，无需压缩"
            return
        }
        val sid = _activeId.value
        streamJobs[sid] = viewModelScope.launch {
            _generating.update { it + sid }
            _error.value = null
            _lastStats.value = "正在压缩上下文…"
            ServerProcessManager.appLog("上下文压缩：将前 ${old.size} 条消息总结为摘要…")
            try {
                val ep = endpointFor(sid)
                val cloud = !ep.local
                val sb = StringBuilder()
                old.forEach { m ->
                    val who = when (m.role) {
                        "user" -> "用户"
                        "assistant" -> "助手"
                        else -> "系统"
                    }
                    sb.append(who).append('：').append(m.content.take(1200)).append('\n')
                }
                val summary = StringBuilder()
                ChatClient.chatStream(
                    port = AppSettings.port,
                    messages = listOf(
                        ChatClient.ChatMessage(
                            "system",
                            "把下面的对话压缩成要点摘要：保留关键事实、结论、待办事项与用户偏好；" +
                                    "用中文；不超过 400 字；直接输出摘要正文，不要客套。"
                        ),
                        ChatClient.ChatMessage("user", sb.toString())
                    ),
                    apiToken = if (cloud) ep.apiKey.ifBlank { null }
                               else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                    baseUrl = if (cloud) ep.baseUrl else null,
                    model = if (cloud) ep.model.ifBlank { "default" } else "local",
                    thinking = if (cloud) null else false,
                    maxTokens = if (cloud) null else 1024,
                    tools = null,
                    onDelta = { d -> if (isGenerating(sid)) summary.append(d) },
                    onReasoning = {},
                    onFinal = { _, _, _ -> },
                    callKey = sid
                )
                val text = summary.toString().trim()
                if (text.isBlank()) {
                    _error.value = "压缩失败：模型未返回摘要"
                    ServerProcessManager.appLog("上下文压缩失败：空摘要")
                } else {
                    val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date())
                    val sysMsg = ChatStore.Msg(
                        "system",
                        "📝 历史摘要（${old.size} 条消息，压缩于 $stamp）：\n$text",
                        at = System.currentTimeMillis()
                    )
                    _messages.value = listOf(sysMsg) + recent
                    persist()
                    _lastStats.value = "已压缩：${old.size} 条 + 最近 ${recent.size} 条 → 摘要 + 最近 ${recent.size} 条"
                    ServerProcessManager.appLog("上下文压缩完成：${old.size} 条 → 摘要（${text.length} 字）")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "压缩失败：${e.message}"
                ServerProcessManager.appLog("上下文压缩失败：${e.message}")
            } finally {
                _generating.update { it - sid }
                setProgress(sid, null)
            }
        }
    }

    /**
     * 工具调用参数被模型写崩（复读把 JSON 撑到不闭合）时服务端直接 500，整轮就废了。
     * 这里去掉 tools 重发一次：模型通常能给纯文字答案，用户至少拿到结果。只试一次，失败仍报原错。
     */
    private suspend fun retryWithoutTools(
        sid: String,
        systemText: String,
        history: List<ChatStore.Msg>
    ): Boolean {
        val ep = endpointFor(sid)
        val cloud = !ep.local
        val msgs = listOf(
            ChatClient.ChatMessage(
                "system",
                systemText + "\n\n【注意】上一次尝试里工具调用的参数格式出错，本次请直接用文字回答，不要调用任何工具。"
            )
        ) + history.filter { it.role != "system" && it.imageB64 == null }
            .map { ChatClient.ChatMessage(it.role, it.content ?: "") }
        var wrote = false
        val ok = runCatching {
            ChatClient.chatStream(
                port = AppSettings.port,
                messages = msgs,
                apiToken = if (cloud) ep.apiKey.ifBlank { null }
                           else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
                onDelta = { d ->
                    if (d.isNotEmpty()) {
                        wrote = true
                        appendToLastInline(sid) { it.copy(content = (it.content ?: "") + d) }
                    }
                },
                baseUrl = if (cloud) ep.baseUrl else null,
                model = if (cloud) ep.model.ifBlank { "local" } else "local",
                tools = null,
                callKey = sid
            )
        }
        return ok.isSuccess && wrote
    }

    /**
     * 技能链执行器：每步独立生成一次，上一步的输出作为下一步的输入，每步结果都可见。
     *
     * 与旧实现的根本区别：旧的是「一段提示词里的步骤清单」，模型必须在一口气生成里自己记住所有步骤，
     * 中间结果既不展示也无法判断成败，所谓条件分支只能靠它自觉。这里每步都能判定成败，
     * 失败时按 `fallbackSkillId` 换一个技能重跑这一步——这才是真正的分支。
     *
     * 失败判据刻意保守：只认行首的「错误：/失败：」这类明确声明，避免「没有错误」被误判成失败。
     * 没有配备用技能时，失败就中止并把卡在哪一步说出来，不假装完成。
     */
    private suspend fun runSkillChain(sid: String, chain: SkillChain, goal: String) {
        val skills = AppSettings.loadSkills().associateBy { it.id }
        // 保留链条里的原始位置（含已删除技能留下的空洞），否则分支里的步号会整体错位
        val stepSkills = chain.skillIds.map { skills[it] }
        val fallback = chain.fallbackSkillId?.let { skills[it] }
        if (stepSkills.all { it == null }) {
            _error.value = "这条技能链里的技能都被删掉了，请重新配置"
            _generating.update { it - sid }
            return
        }
        // 技能链每步自己带指令，这里只需要一句兜底的角色设定（全局预设已删）
        val sys = "你是一个严谨的中文助手，回答用中文。"
        val out = StringBuilder()
        val limit = SkillChainFlow.visitLimit(stepSkills.size)
        var executed = 0
        try {
            var input = goal
            var index = 0
            while (index != SkillChainFlow.END) {
                val sk = stepSkills.getOrNull(index)
                if (sk == null) {
                    index = SkillChainFlow.followingIndex(index, stepSkills.size)
                    continue
                }
                executed++
                if (executed > limit) {
                    _error.value = "技能链跳转次数超过上限（$limit 次），已中止——可能在几步之间来回跳，请检查条件设置"
                    ServerProcessManager.appLog("技能链：跳转次数超限（$limit），中止")
                    break
                }
                ServerProcessManager.appLog("技能链 第 ${index + 1} 步：${sk.name}")
                val prompt = sk.prompt + "\n\n【输入】\n" + input
                var text = runChainStep(sid, sys, prompt)
                var failed = looksLikeFailure(text)
                if (failed && fallback != null) {
                    ServerProcessManager.appLog("第 ${index + 1} 步判定失败，改用备用技能：${fallback.name}")
                    val alt = runChainStep(
                        sid,
                        sys,
                        fallback.prompt + "\n\n【上一次尝试没能完成本步，请换一种可行做法重做】\n【输入】\n" + input
                    )
                    if (alt.isNotBlank() && !looksLikeFailure(alt)) {
                        text = alt
                        failed = false
                    } else {
                        appendToLastInline(sid) { m -> m.copy(content = out.toString().trim()) }
                        out.append("### 第 ${index + 1} 步 · ").append(sk.name).append("（已尝试备用技能仍失败）\n")
                        out.append(text.trim()).append("\n\n")
                        appendToLastInline(sid) { m -> m.copy(content = out.toString().trim()) }
                        _error.value = "第 ${index + 1} 步（${sk.name}）没能完成，技能链已中止"

                        break
                    }
                }
                val branch = SkillChainFlow.matchedBranch(index, text, chain)
                val nextIndex = SkillChainFlow.nextIndex(index, text, chain)
                out.append("### 第 ${index + 1} 步 · ").append(sk.name).append('\n')
                out.append(text.trim()).append("\n\n")
                if (branch != null) {
                    val where = branch.gotoStep?.let { "第 ${it + 1} 步" } ?: "结束整条链"
                    out.append("（输出命中「").append(branch.contains).append("」→ 跳转到").append(where).append("）\n\n")
                    ServerProcessManager.appLog("第 ${index + 1} 步命中「${branch.contains}」→ $where")
                }
                appendToLastInline(sid) { m ->
                    m.copy(
                        content = out.toString().trim(),
                        steps = m.steps + ChatStore.Step(
                            "tool", "技能链第 ${index + 1} 步：${sk.name}",
                            text.trim().take(400), 0, args = prompt.take(300), ok = !failed
                        )
                    )
                }
                persistProgress(sid)
                if (failed) {
                    _error.value = "第 ${index + 1} 步（${sk.name}）没能完成，技能链已中止"
                    break
                }
                input = text.trim()
                index = nextIndex
            }
            _lastStats.value = "技能链完成（执行 $executed 步）"
        } catch (e: Exception) {
            ServerProcessManager.appLog("技能链失败：${e.message}")
            _error.value = friendlyError(e)
        } finally {
            _generating.update { it - sid }
            setProgress(sid, null)
            persistSession(sid)
        }
    }

    /** 跑一步：一次不带动工具的生成，返回完整文本 */
    private suspend fun runChainStep(sid: String, system: String, prompt: String): String {
        val sb = StringBuilder()
        val ep = endpointFor(sid)
        val cloud = !ep.local
        ChatClient.chatStream(
            port = AppSettings.port,
            messages = listOf(
                ChatClient.ChatMessage("system", system),
                ChatClient.ChatMessage("user", prompt)
            ),
            apiToken = if (cloud) ep.apiKey.ifBlank { null }
                       else if (AppSettings.apiTokenEnabled) AppSettings.apiToken else null,
            onDelta = { d -> sb.append(d) },
            baseUrl = if (cloud) ep.baseUrl else null,
            model = if (cloud) ep.model.ifBlank { "local" } else "local",
            callKey = sid
        )
        return sb.toString()
    }

    /** 步骤失败判据：只认明确声明，避免把「没有错误」这类否定句判成失败 */
    private fun looksLikeFailure(t: String): Boolean {
        val s = t.trim()
        if (s.isEmpty()) return true
        val head = s.take(40)
        if (CHAIN_FAIL_MARKERS.any { head.startsWith(it) }) return true
        return s.contains("无法完成本步") || s.contains("无法继续执行本步")
    }

    /**
     * 应用/清除技能链。链由执行器逐步驱动，所以不再把各步提示词写成会话的 system prompt。
     */
    fun setSessionChain(chainId: String?) {
        val id = _activeId.value
        _sessions.value = _sessions.value.map {
            if (it.id == id) it.copy(
                systemPrompt = null,
                appliedSkillId = null,
                appliedChainId = chainId,
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        saveStore()
        ServerProcessManager.appLog(if (chainId == null) "已清除技能链" else "已应用技能链（执行器模式）")
    }

    private fun friendlyError(e: Exception): String = when {
        e is java.net.ConnectException || e.message?.contains("Connection refused") == true ->
            "连接失败：请先在「状态」页启动服务"
        e.message?.contains("401") == true -> "鉴权失败：请在设置中检查访问令牌/云端 Key"
        e.message?.contains("input is not supported") == true ->
            "这个端点不支持这类输入（常见于本地引擎收到音频/视频）。" +
                    "本地模型要么配好配套的 mmproj（模型 → 多模态投影），要么把会话切到支持音视频的云端渠道。"
        // 服务端把工具调用参数当 JSON 解析失败：10 次有 9 次是模型在长字符串里复读，
        // 把参数撑到不闭合。这种情况重试通常就好，说得直白点，别让用户以为是自己操作错了。
        e.message?.contains("Failed to parse tool call arguments as JSON") == true ->
            "模型把工具调用的参数写成了不合法的 JSON（通常是陷入复读），这一轮已中断。" +
                    "再发一次、或点「继续这个任务」分段重试通常就好了。"
        // 上下文被工具返回值撑爆：本地引擎会直接 400，这一轮连结果都拿不回来。
        // 原文只有一行 JSON，用户看不出该做什么，这里把该做的两步说清楚。
        e.message?.contains("exceeds the available context size") == true -> {
            val nums = Regex("\"n_prompt_tokens\":\\s*(\\d+)|\\\"n_ctx\\\":\\s*(\\d+)")
                .findAll(e.message ?: "").mapNotNull { it.groupValues.drop(1).firstOrNull { v -> v.isNotEmpty() } }
                .toList()
            val used = nums.getOrNull(0)?.toLongOrNull()
            val cap = nums.getOrNull(1)?.toLongOrNull()
            "上下文被撑满了" + (if (used != null && cap != null) "：这次请求 $used token，窗口只有 $cap" else "") +
                    "。多数情况是某次工具调用一次读回了太大的内容——让它分小段读（指定行数/范围），" +
                    "或点输入框上方的「压缩上下文」把历史压一压再重试。"
        }
        // 兜底分支可能是几十 KB 的原始响应体，必须截断（原因见 maxErrorChars）
        else -> (e.message ?: "请求失败").let {
            if (maxErrorChars <= 0 || it.length <= maxErrorChars) it
            else it.take(maxErrorChars) + "…（完整内容见日志）"
        }
    }

    /** 上下文占用（本次输入+本轮回复 = 下一轮的实际起点，服务端权威计数，含模板开销）；≥90% 加 ⚠️ */
    private fun ctxNote(usedTokens: Int): String {
        if (usedTokens <= 0) return ""
        val ctx = AppSettings.effectiveCtx(AppSettings.selectedModelPath)
        if (ctx <= 0) return ""
        val cur = if (usedTokens >= 1000) String.format("%.1fk", usedTokens / 1000.0) else "$usedTokens"
        val max = if (ctx >= 1000) String.format("%.1fk", ctx / 1000.0) else "$ctx"
        val near = usedTokens.toDouble() / ctx >= 0.9
        return " · 上下文 $cur/$max" + if (near) " ⚠️" else ""
    }

    // ---- 后台执行（定时任务无人值守）----

    /** 一轮后台执行的结果：ok=false 时 text 是失败原因 */
    data class BackgroundTurn(val sid: String, val ok: Boolean, val text: String, val elapsedMs: Long)

    /**
     * 后台直接跑一轮，不经过输入框。
     *
     * 为什么必须共享同一个实例：[ChatStore] 是单文件存档，两个 ViewModel 各持一份内存快照
     * 会互相覆盖——后台写进去的会话，界面再存一次就没了。所以这个类必须进程内单例
     * （见 [shared]），后台与界面读写的是同一份状态。
     *
     * 跑在当前会话里（和手动排队一致）：定时任务的结果因此出现在用户最后待的会话里，
     * 而不是悄悄另开一条谁也找不到的会话。
     */
    suspend fun runBackgroundTurn(
        prompt: String,
        newSessionTitle: String? = null,
        endpoint: ChatStore.Endpoint? = null,
    ): BackgroundTurn {
        val text = prompt.trim()
        if (text.isEmpty()) return BackgroundTurn(_activeId.value, false, "指令为空", 0)
        // 定时任务默认每次新开一条会话：任务的历史与手动聊天分开，回头能单独翻。
        // 新会话会切成当前会话（后台跑时用户看不见；前台时会看到他跳到这条任务会话，
        // 这正是"它在跑，我去看看"的效果）。
        if (newSessionTitle != null) {
            persist()
            val id = System.nanoTime().toString()
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date())
            val s = ChatStore.Session(id, "$newSessionTitle · $stamp", System.currentTimeMillis(), emptyList())
            _sessions.value = _sessions.value + s
            _activeId.value = id
            _messages.value = emptyList()
            _lastStats.value = null
            clearPendingAttachments()
            saveStore()
            ServerProcessManager.appLog("定时任务新开会话：${s.title}")
        }
        // 任务指定的端点（云端渠道）：写在这条会话上，整轮都走它
        if (endpoint != null) {
            val id = _activeId.value
            _sessions.value = _sessions.value.map { if (it.id == id) it.copy(endpoint = endpoint) else it }
            saveStore()
            ServerProcessManager.appLog(
                "定时任务使用指定端点：${if (endpoint.local) "本地" else endpoint.baseUrl} / ${endpoint.model}"
            )
        }
        val sid = _activeId.value
        val startedAt = System.currentTimeMillis()
        // 本会话还在生成：先等它跑完（同一会话不并发，和手动发送的排队语义一致）
        _generating.first { sid !in it }
        clearStoppedTasks(sid)
        append(ChatStore.Msg("user", text))
        append(ChatStore.Msg("assistant", "", task = ChatStore.TaskState(goal = text.take(300))))
        doStream()
        val job = streamJobs[sid]
        if (job == null) return BackgroundTurn(sid, false, "没能启动生成（可能已达并行上限）", 0)
        job.join()
        val last = messagesOf(sid).lastOrNull { it.role == "assistant" }
        val body = last?.content.orEmpty()
        return BackgroundTurn(
            sid = sid,
            ok = body.isNotBlank(),
            text = body.ifBlank { _error.value ?: "模型没有返回任何内容" },
            elapsedMs = if ((last?.elapsedMs ?: 0L) > 0) last!!.elapsedMs
                        else System.currentTimeMillis() - startedAt
        )
    }

    companion object {
        @Volatile
        private var sharedInstance: ChatViewModel? = null

        /**
         * 进程内唯一实例。
         *
         * 原来界面用 `viewModel()`（Activity 作用域）：闹钟到点时进程里还没有界面，
         * 后台只能另造一份会话状态，两边各写各的 chat_sessions.json——后写的覆盖先写的。
         * 改成进程级单例后，后台跑完的那一轮，用户打开界面看到的就是同一份，无需重新加载。
         * 这个实例不随 Activity 销毁而清理，跟着进程活。
         */
        fun shared(app: Application): ChatViewModel =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: ChatViewModel(app).also { sharedInstance = it }
            }

        /** 已经建过实例就返回它（后台线程不必为了看一眼状态去创建） */
        fun peek(): ChatViewModel? = sharedInstance
    }
}
