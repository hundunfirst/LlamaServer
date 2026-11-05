package com.localllm.server.core

import android.content.Context

/**
 * 用无线调试把无障碍服务自己登记上。
 *
 * 为什么需要这一套：
 * 1. **覆盖安装之后系统会把服务停掉**：设置里开关还显示「开」，底下小字写「此服务出现故障」，
 *    用户完全看不出问题在哪，只会觉得 App 坏了；
 * 2. 从浏览器/GitHub 装的包，在系统设置里手动勾无障碍经常被「受限设置」（Restricted setting）挡住，
 *    根本点不进去。
 *
 * 无线调试通道就是 shell 权限，可以直接改 secure 设置：
 * `enabled_accessibility_services` 登记组件 + `accessibility_enabled 1` 打开总开关。
 *
 * 注意这个列表是**全系统共用**的：必须读出来追加，不能整串覆盖——否则用户自己的
 * TalkBack、其他无障碍应用会被我们一起顶掉。
 */
object A11ySelfEnable {

    /** 尝试节流：失败一次别每次操作、每次启动都去连一遍 adb */
    private const val RETRY_GAP_MS = 5 * 60_000L

    @Volatile
    private var lastAttemptAt = 0L

    /** 系统里登记的组件名：包名/类全名 */
    fun component(context: Context): String =
        "${context.packageName}/${A11yService::class.java.name}"

    /**
     * 算出要执行的命令（纯逻辑，单测盯着）。
     *
     * [existing] 是 `settings get secure enabled_accessibility_services` 的原样输出
     * （可能是 "null"、空、或冒号分隔的一串）。
     */
    fun plan(existing: String?, component: String): List<String> {
        val raw = existing.orEmpty().trim()
        val cur = if (raw.equals("null", true)) emptyList()
        else raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        val merged = if (cur.contains(component)) cur else cur + component
        val cmds = mutableListOf<String>()
        if (merged != cur) {
            cmds += "shell settings put secure enabled_accessibility_services '" +
                    merged.joinToString(":") + "'"
        }
        cmds += "shell settings put secure accessibility_enabled 1"
        return cmds
    }

    /**
     * 尽量把服务打开。返回 true 表示调用结束时服务已启用且进程里连上了。
     *
     * 失败一律静默（无线调试没开、没配对、系统拒绝），只写日志——这是「顺手修好」的
     * 兜底路径，不是必须成功的流程。
     */
    suspend fun ensure(context: Context, force: Boolean = false): Boolean {
        if (A11yService.isEnabled(context) && A11yBridge.available()) return true
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < RETRY_GAP_MS) return false
        lastAttemptAt = now

        val status = runCatching { AdbBridge.ensureConnected(context) }.getOrNull()
        if (status == null || !status.connected) {
            ServerProcessManager.appLog("无障碍自启跳过：无线调试没连上（${status?.message ?: "未知"}）")
            return false
        }
        val (readOk, existing) = AdbBridge.runAdb(
            context, "shell settings get secure enabled_accessibility_services", 15_000
        )
        val cmds = plan(if (readOk) existing else null, component(context))
        var ok = true
        for (c in cmds) {
            val (cOk, out) = AdbBridge.runAdb(context, c, 15_000)
            if (!cOk) {
                ok = false
                ServerProcessManager.appLog("无障碍自启失败：$c → ${out.take(120)}")
            }
        }
        if (!ok) return false
        // 系统接受设置后要一两秒才把服务拉起来
        repeat(12) {
            if (A11yService.isEnabled(context) && A11yBridge.available()) {
                ServerProcessManager.appLog("无障碍已通过无线调试自动打开")
                AppSettings.a11yWasEnabled = true
                return true
            }
            kotlinx.coroutines.delay(300)
        }
        ServerProcessManager.appLog(
            "无障碍已写入系统设置，但服务还没连上（启用=${A11yService.isEnabled(context)}，" +
                    "连接=${A11yBridge.available()}）"
        )
        return false
    }
}
