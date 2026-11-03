package com.localllm.server.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：给 agent 一双"眼睛和手"，**不需要 adb、不需要配对码、不需要网络**。
 *
 * 为什么必须有它：无线调试那条路要求 WLAN 真的连上网络（只是拨开开关不行），
 * 出门在外就废了；而"看屏幕、点按钮、输入文字"恰恰是最常用的操作。
 * 权限在系统设置里授权一次，重启后仍然有效。
 *
 * 能力边界（老实说）：只能做 UI 层的事——读控件树与文本、点击/长按/滑动、输入文本、
 * 返回/主页/最近任务、截图。装 APK（静默）、`pm`/`am`/`dumpsys` 这些还得靠 adb。
 */
class A11yService : AccessibilityService() {

    private var hudView: android.view.View? = null
    private var hudText: android.widget.TextView? = null
    private var flashView: android.view.View? = null
    private val hudHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 每秒重绘状态条：让"已过多少秒"是真的在动，用户才知道它没死 */
    private val ticker = object : Runnable {
        override fun run() {
            refreshHud()
            if (AgentHud.isActive()) hudHandler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServerProcessManager.appLog("无障碍服务已连接（可以读屏、点击、滑动、输入）")
    }

    /** 状态条内容变了（阶段切换/动作更新）时调用：没有就创建，Idle 就隐藏 */
    fun refreshHud() {
        hudHandler.post {
            val text = AgentHud.text()
            if (text == null) {
                hideHudNow()
                return@post
            }
            runCatching {
                val wm = getSystemService(android.view.WindowManager::class.java) ?: return@runCatching
                if (hudView == null) {
                    val tv = android.widget.TextView(this).apply {
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 12f
                        setPadding(30, 18, 30, 18)
                        maxLines = 2
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 44f
                            setColor(0xF0202124.toInt())
                        }
                        setOnClickListener {
                            AgentHud.requestStop()
                            refreshHud()
                        }
                    }
                    hudText = tv
                    hudView = tv
                }
                hudText?.text = text
                // 卡住时标红，正常时白字
                hudText?.setTextColor(
                    if (text.contains("可能卡住")) 0xFFFF6B6B.toInt() else android.graphics.Color.WHITE
                )
                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    y = 110
                }
                if (hudView?.isAttachedToWindow != true) wm.addView(hudView, params)
            }
            // 每秒刷一次
            hudHandler.removeCallbacks(ticker)
            if (AgentHud.isActive()) hudHandler.postDelayed(ticker, 1000)
        }
    }

    private fun hideHudNow() {
        runCatching {
            val wm = getSystemService(android.view.WindowManager::class.java)
            if (hudView?.isAttachedToWindow == true) wm?.removeView(hudView)
        }
        hudHandler.removeCallbacks(ticker)
    }

    fun hideHud() = hudHandler.post { hideHudNow() }

    /**
     * 在 (x,y) 位置闪一个圈，告诉用户「它点了这里」。
     * 不需要系统开关、不需要 adb——比系统级 `show_touches` 更可控，两者可以并存。
     */
    fun flashAt(x: Int, y: Int) {
        hudHandler.post {
            runCatching {
                val wm = getSystemService(android.view.WindowManager::class.java) ?: return@runCatching
                val size = 96
                val view = android.view.View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke(6, 0xFF2D7FF9.toInt())
                        setColor(0x332D7FF9)
                    }
                }
                val params = android.view.WindowManager.LayoutParams(
                    size, size,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    this.x = x - size / 2
                    this.y = y - size / 2
                }
                wm.addView(view, params)
                flashView = view
                hudHandler.postDelayed({
                    runCatching { if (view.isAttachedToWindow) wm.removeView(view) }
                }, 450)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 目前用不到事件流：模型按需主动读屏（pull），比被动监听省电也更可控
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideHud()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideHud()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: A11yService? = null
            private set

        /** 服务是否已在系统中启用（用户在设置里打开过） */
        fun isEnabled(context: Context): Boolean = runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as? android.view.accessibility.AccessibilityManager ?: return false
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
        }.getOrDefault(false)

        /** 已启用但进程里还没连上（少见：用户刚打开服务） */
        fun isConnected(): Boolean = instance != null
    }
}
