package com.localllm.server.ui

import com.localllm.server.core.AppSettings
import com.localllm.server.core.ServerProcessManager
import com.localllm.server.core.ToolGuard
import com.localllm.server.core.WebSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 审批闸门：工具真正执行前的「要不要问用户」这一层。
 *
 * 从 ChatViewModel 里搬出来的：它只依赖三样东西——会话的权限设置、当前会话 id、一个写状态文案的回调，
 * 和消息、流式生成、工具执行都没有耦合。放在这里以后，调审批策略不用再动那三千多行的 ViewModel。
 *
 * 两类确认：
 * - 危险/外部工具的逐条确认（[confirm]），支持「本会话总是允许」；
 * - 图片转联网搜索的确认（[askSearch]）。
 *
 * 排队与超时：多条会话可能同时要确认，用一把锁串行弹窗；超时一律按拒绝处理——
 * 宁可让模型停下，也不替用户做决定。
 */
class ToolApproval(
    /** sid -> (权限档位, 是否计划模式) */
    private val sessionPermission: (String) -> Pair<String, Boolean>,
    private val activeSessionId: () -> String,
    /** 把「等你确认」这类文案写到状态栏 */
    private val onStatus: (String?) -> Unit,
) {

    data class Ask(
        val title: String,
        val subjectLabel: String,
        val subject: String,
        val reason: String,
        val dangerous: Boolean,
        val canAlways: Boolean,
    )

    private val _toolAsk = MutableStateFlow<Ask?>(null)
    val toolAsk: StateFlow<Ask?> = _toolAsk.asStateFlow()
    private var toolAskDeferred: CompletableDeferred<Int>? = null

    private val _searchApproval = MutableStateFlow<String?>(null)
    val searchApproval: StateFlow<String?> = _searchApproval.asStateFlow()
    private var searchDeferred: CompletableDeferred<Boolean>? = null

    /** 多条会话可能同时要确认：串行排队，否则弹窗互相覆盖 */
    private val lock = Mutex()
    /** 会话 id → 本会话已放行的键（bash / file_write / adb…）；危险命令不在其中 */
    private val sessionAutoAllow = mutableMapOf<String, MutableSet<String>>()

    /** 0=拒绝 1=允许一次 2=本会话总是允许 */
    fun answerToolAsk(decision: Int) {
        toolAskDeferred?.complete(decision)
        toolAskDeferred = null
        _toolAsk.value = null
    }

    fun approveSearch(allow: Boolean) {
        searchDeferred?.complete(allow)
        searchDeferred = null
        _searchApproval.value = null
    }

    /** 停止生成时清理：被取消的协程不会走 [confirm] 的收尾，确认框留着会挡住下一次操作 */
    fun cancelPending() {
        _toolAsk.value = null
        toolAskDeferred = null
    }

    /** 图片转联网搜索：弹窗等用户决定，120 秒超时按拒绝 */
    suspend fun askSearch(query: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        searchDeferred = d
        _searchApproval.value = WebSearch.sanitizeQuery(query).first
        ServerProcessManager.appLog("图片转联网搜索待用户确认：「${query.take(30)}」")
        val result = withTimeoutOrNull(120_000L) { d.await() }
        if (result == null) {
            searchDeferred = null
            _searchApproval.value = null
            ServerProcessManager.appLog("确认超时（120s），按拒绝处理")
            return false
        }
        return result
    }

    /** 危险操作确认：返回 true 才真的执行；超时（10 分钟）按拒绝 */
    suspend fun confirm(verdict: ToolGuard.Verdict?, sid: String): Boolean {
        if (verdict == null) return true
        if (!AppSettings.toolApprovalEnabled) return true
        // 完全权限：会话里把权限开到 full 就不问（计划模式除外——那时候工具本来也没给）
        val (permission, planMode) = sessionPermission(sid)
        if (permission == "full" && !planMode) return true
        val allowed = sessionAutoAllow.getOrPut(sid) { mutableSetOf() }
        if (verdict.autoKey != null && allowed.contains(verdict.autoKey)) return true
        // adb 免确认开关：自动化（尤其是操作屏幕）时不再逐条打断——
        // App 在后台时弹窗根本看不见，表现就是「卡了几百秒」。DANGER 档 autoKey 为 null，仍然每次都问。
        if (AppSettings.adbAutoAllow && verdict.autoKey?.startsWith("adb") == true) {
            ServerProcessManager.appLog("adb 免确认已开启，直接放行：${verdict.subject.take(60)}")
            return true
        }
        return lock.withLock {
            // 排队期间别的会话可能已经放行了同类操作
            if (verdict.autoKey != null && allowed.contains(verdict.autoKey)) return@withLock true
            val d = CompletableDeferred<Int>()
            toolAskDeferred = d
            _toolAsk.value = Ask(
                title = verdict.title,
                subjectLabel = verdict.subjectLabel,
                subject = verdict.subject,
                reason = verdict.reason,
                dangerous = verdict.risk == ToolGuard.Risk.DANGER,
                canAlways = verdict.autoKey != null,
            )
            ServerProcessManager.appLog("等待确认：${verdict.title.removeSuffix("？")}")
            // 用户可能正在别的 App 里（agent 自己切走的）：弹窗看不见就以为卡住。
            // 把话说在状态栏上，切回来一眼能看懂。
            if (activeSessionId() == sid) {
                onStatus(
                    "等你确认：${verdict.title.removeSuffix("？")}" +
                            "（如果没看到弹窗，说明本 App 在后台，切回来即可）"
                )
            }
            val decision = withTimeoutOrNull(600_000L) { d.await() }
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
}
