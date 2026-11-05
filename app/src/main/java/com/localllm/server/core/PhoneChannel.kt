package com.localllm.server.core

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * 屏幕操作的通道闸门。
 *
 * 无障碍（[A11yBridge]）和无线调试（[AdbBridge]）都能点屏幕、都能输入文字。问题是
 * 模型手里同时有这两个工具，常常「两条都试一遍」，或者一条已经点成功了还用另一条再点一次：
 * 用户看到的就是同时亮着两条通道、App 被反复拉起、一个动作做两遍。
 *
 * 两条规则把它按住：
 * 1. **一次对话只认一条通道**：某条通道成功做过界面动作后，另一条通道的界面命令直接拒绝，
 *    并把该走哪条告诉模型。**失败不占通道**，所以「无障碍没连上 → 换 adb」这条降级路仍然通。
 * 2. **锁屏/灭屏时不碰界面**：后台定时任务多半就是这个状态。这时硬点只会亮屏、拉起 App、
 *    在锁屏界面上点空，白耗电还影响用户。直接回一句「锁屏中，界面步骤已跳过」。
 */
object PhoneChannel {

    enum class Lane { NONE, A11Y, ADB }

    @Volatile
    private var lane = Lane.NONE

    fun reset() {
        lane = Lane.NONE
        wakeTried = false
    }

    /** 放行返回 null；拒绝返回一段给模型看的说明 */
    fun reject(asking: Lane): String? {
        val cur = lane
        if (cur == Lane.NONE || cur == asking) return null
        return if (cur == Lane.A11Y) {
            "错误：本轮已经在用无障碍通道操作屏幕（前面 screen 的动作成功了），" +
                "这条 adb 界面命令不再执行——两条通道同时上手会互相打架，" +
                "而且同一个动作做两遍会让界面错乱。\n" +
                "继续用 screen 工具（tap/click/input/tree/shot）。adb 只用于它独有的非界面命令：" +
                "install、pm、dumpsys、am start 之外别再点屏。"
        } else {
            "错误：本轮已经在用无线调试通道操作屏幕（前面的 adb input/uiautomator/screencap 成功了），" +
                "这次 screen 的界面动作不再执行，避免同一屏两个指针打架。\n" +
                "继续用 adb（shell input / uiautomator dump / screencap）。"
        }
    }

    fun mark(used: Lane) {
        if (lane == Lane.NONE) lane = used
    }

    /** 模型是不是在**主动亮屏**（这种不算乱点，锁屏检查也放行） */
    fun isWakeIntent(args: String): Boolean {
        val a = args.lowercase()
        return a.contains("keyevent 224") || a.contains("keyevent 26") ||
            a.contains("wakeup") || a.contains("wm dismiss-keyguard")
    }

    /** adb 参数里是否有「改界面 / 读界面」的动作 */
    fun adbTouchesUi(args: String): Boolean {
        if (isWakeIntent(args)) return false
        return Regex("""(^|\s)(input\s+(tap|swipe|text|motionevent)|uiautomator|screencap)""")
            .containsMatchIn(args.trim())
    }

    /** screen 的哪些动作会改界面 */
    fun a11yTouchesUi(action: String): Boolean =
        action in setOf("click", "tap", "swipe", "input", "key")

    /** 灭屏或锁屏：这种状态下点屏没有意义 */
    fun screenOffOrLocked(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(PowerManager::class.java)
            val km = context.getSystemService(KeyguardManager::class.java)
            val off = pm != null && !pm.isInteractive
            val locked = km != null && km.isKeyguardLocked
            off || locked
        } catch (e: Throwable) {
            false
        }
    }

    @Volatile
    private var wakeTried = false

    /**
     * 本对话第一次发现屏幕锁着时返回 true——用来触发一次唤醒/解屏。
     * 只尝试一次：每条命令都按一次电源键既慢又会让屏幕一闪一闪。
     */
    fun claimWakeAttempt(context: Context): Boolean {
        if (wakeTried) return false
        if (!screenOffOrLocked(context)) return false
        wakeTried = true
        return true
    }
}
