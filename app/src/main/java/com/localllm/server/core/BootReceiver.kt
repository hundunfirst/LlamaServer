package com.localllm.server.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后把定时任务重新登记一遍。
 *
 * 为什么需要它：AlarmManager 的闹钟存在系统里，**重启就没了**。以前只在 App 启动时
 * rescheduleAll，所以用户设了「每天早上 8 点」之后只要没手动打开 App，第二天就不响——
 * 而且不会有任何提示，看起来像是任务"没生效"。
 *
 * 注意：MIUI 上还要允许本应用「自启动」，否则系统根本不会把这条广播发过来。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val ours = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!ours) return
        val count = runCatching { ScheduledTasks.load(context).count { it.enabled } }.getOrDefault(0)
        ServerProcessManager.appLog("开机完成：重新登记定时任务（$count 个在用）")
        runCatching { ScheduledTasks.rescheduleAll(context) }
    }
}
