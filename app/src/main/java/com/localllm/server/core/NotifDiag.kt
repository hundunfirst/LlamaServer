package com.localllm.server.core

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * 发通知的唯一入口：**失败或被关掉时把原因写进日志**。
 *
 * 起因：定时任务跑完了，结果通知在这台小米上一直不出现。权限查过是 granted、
 * 通道 importance=3 也正常，但 `nm.notify()` 被 `runCatching` 包着——如果系统层面
 * 关掉了这个 App 的通知开关，notify 是**静默无效**的，代码看不出任何异常。
 * 所以这里显式判一遍并记账：日志里直接能看到「被谁关的」。
 */
internal object NotifDiag {

    /** @return true = 认为发出去了 */
    fun post(
        context: Context,
        nm: NotificationManager,
        id: Int,
        n: Notification,
        tag: String,
    ): Boolean {
        return try {
            if (!nm.areNotificationsEnabled()) {
                ServerProcessManager.appLog(
                    "通知没发出（$tag）：系统里这个 App 的通知开关是关的——" +
                            "去 设置 → 通知管理 把「混沌智核」的通知打开"
                )
                return false
            }
            val chId = runCatching { n.channelId }.getOrNull()
            val ch = chId?.let { runCatching { nm.getNotificationChannel(it) }.getOrNull() }
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) {
                ServerProcessManager.appLog(
                    "通知没发出（$tag）：通道「${ch.id}」被关掉了（importance=NONE）——去通知设置里打开这个通道"
                )
                return false
            }
            nm.notify(id, n)
            true
        } catch (e: SecurityException) {
            ServerProcessManager.appLog("通知被拒绝（$tag）：${e.message ?: "SecurityException"}")
            false
        } catch (e: Exception) {
            ServerProcessManager.appLog("通知发送失败（$tag）：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }
}
