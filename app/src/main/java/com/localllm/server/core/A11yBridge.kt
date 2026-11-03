package com.localllm.server.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Application
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 无障碍能力的实现层：读屏、点击、长按、滑动、输入、系统按键、截图。
 *
 * 这些都是"看"和"点"的动作，不需要 adb、不需要网络；装 APK / `pm` / `am` / `dumpsys`
 * 仍然只能走 adb（见 [AdbBridge]）。两者互补：出门没 Wi-Fi 时，至少"看和点"还在。
 */
object A11yBridge {

    private const val MAX_TREE_LINES = 80

    data class UiNode(
        val text: String,
        val desc: String,
        val cls: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
    )

    fun available(): Boolean = A11yService.instance != null

    private fun service(): AccessibilityService? = A11yService.instance

    /**
     * 每个动作的入口：先看用户有没有在悬浮提示上点停止，再对外报告「正在做什么」。
     * 返回非空 = 直接中止（把这句话当工具结果交给模型）。
     */
    private fun begin(action: String): String? {
        if (AgentHud.consumeStop()) return "用户点了停止：已中止后续屏幕操作"
        AgentHud.update(AgentHud.Stage.RunningTool, action)
        return null
    }

    /** 当前窗口的控件清单（只挑有文字/可点/可编辑的，避免把上千个无意义节点灌给模型） */
    fun tree(): String {
        begin("读取屏幕内容")?.let { return it }
        val svc = service() ?: return "错误：无障碍服务未启用（设置 → 无障碍 → 混沌智核）"

        val root = svc.rootInActiveWindow ?: return "错误：读不到当前窗口（可能正处在系统界面或刚切换应用）"
        val out = StringBuilder()
        val dm = svc.resources.displayMetrics
        out.append("屏幕 ${dm.widthPixels}×${dm.heightPixels}\n")
        out.append("包名：${root.packageName ?: "?"}\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= MAX_TREE_LINES) return
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val clickable = n.isClickable
            val editable = n.isEditable
            if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                out.append("· ")
                if (text.isNotEmpty()) out.append("\"").append(text.take(48)).append("\" ")
                if (desc.isNotEmpty() && desc != text) out.append("[").append(desc.take(32)).append("] ")
                out.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
                n.viewIdResourceName?.let { out.append(" #").append(it.substringAfterLast('/')) }
                out.append(" @").append(b.centerX()).append(",").append(b.centerY())
                if (clickable) out.append(" 可点")
                if (editable) out.append(" 可输入")
                if (n.isFocused) out.append(" 焦点")
                out.append('\n')
                count++
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        if (count == 0) return out.append("（当前窗口没有可读/可点的控件）").toString()
        return out.toString()
    }

    /** 按文字点击：优先找可点的祖先，找不到就在节点中心补一次手势点击 */
    fun clickText(text: String): String {
        begin("点击「${text.take(24)}」")?.let { return it }
        val svc = service() ?: return "错误：无障碍服务未启用"
        val target = text.trim()
        if (target.isEmpty()) return "错误：要点的文字不能为空"
        val root = svc.rootInActiveWindow ?: return "错误：读不到当前窗口"
        val hits = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            if (t.contains(target, ignoreCase = true) || d.contains(target, ignoreCase = true)) hits.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        if (hits.isEmpty()) return "没找到包含「$target」的控件（先用读屏看看当前屏幕上有什么）"
        val node = hits.first()
        var cur: AccessibilityNodeInfo? = node
        while (cur != null && !cur.isClickable) cur = cur.parent
        if (cur != null && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "已点击「$target」"
        }
        val b = Rect().also { node.getBoundsInScreen(it) }
        return tap(b.centerX(), b.centerY()).let { "控件本身不可点，已在 (${b.centerX()},${b.centerY()}) 补点一次：$it" }
    }

    fun tap(x: Int, y: Int): String {
        begin("点击 ($x,$y)")?.let { return it }
        A11yService.instance?.flashAt(x, y)   // 让用户看到"点了这里"
        return gesture { p -> p.moveTo(x.toFloat(), y.toFloat()) } ?: "点击已发出 ($x,$y)"
    }

    fun longPress(x: Int, y: Int, ms: Long = 700): String {
        begin("长按 ($x,$y)")?.let { return it }
        return gesture(durationMs = ms) { p -> p.moveTo(x.toFloat(), y.toFloat()) } ?: "长按已发出 ($x,$y ${ms}ms)"
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 300): String {
        begin("滑动 ($x1,$y1 → $x2,$y2)")?.let { return it }
        A11yService.instance?.flashAt(x2, y2)   // 高亮终点：滑动方向一目了然
        return gesture(durationMs = ms) { p ->
            p.moveTo(x1.toFloat(), y1.toFloat())
            p.lineTo(x2.toFloat(), y2.toFloat())
        } ?: "滑动已发出 ($x1,$y1 → $x2,$y2 ${ms}ms)"
    }

    private fun gesture(
        durationMs: Long = 60,
        build: (Path) -> Unit,
    ): String? {
        val svc = service() ?: return "错误：无障碍服务未启用"
        val path = Path().also(build)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(20))
        val gd = GestureDescription.Builder().addStroke(stroke).build()
        return if (svc.dispatchGesture(gd, null, null)) null else "手势发送失败"
    }

    /** 往当前焦点输入框写字；没有焦点框就找屏幕上第一个可编辑控件 */
    fun inputText(text: String): String {
        begin("输入文字")?.let { return it }
        val svc = service() ?: return "错误：无障碍服务未启用"
        val root = svc.rootInActiveWindow ?: return "错误：读不到当前窗口"
        var target: AccessibilityNodeInfo? = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) {
            val editables = mutableListOf<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null) return
                if (n.isEditable) editables.add(n)
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(root)
            target = editables.firstOrNull()
        }
        val node = target ?: return "没找到输入框：先点一下要输入的位置（或让用户手动点）"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "已输入 ${text.length} 个字"
        else "输入失败：这个输入框不接受直接设值（可以改成逐字输入试试）"
    }

    /** 系统按键：back / home / recents / notifications */
    fun global(action: String): String {
        begin("按键 $action")?.let { return it }
        val svc = service() ?: return "错误：无障碍服务未启用"
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "shade" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return "错误：未知按键 $action（可用 back/home/recents/notifications）"
        }
        return if (svc.performGlobalAction(code)) "已发送 $action" else "发送失败：$action"
    }

    /**
     * 截图存到工作目录，返回文件路径（模型可以用 file_* 打开看，也可以给用户看）。
     * 走 AccessibilityService.takeScreenshot（API 30+），不需要 adb。
     */
    suspend fun screenshot(app: Application): String {
        begin("截图")?.let { return it }
        return suspendCancellableCoroutine { cont ->
        val svc = service()
        if (svc == null) {
            cont.resume("错误：无障碍服务未启用")
            return@suspendCancellableCoroutine
        }
        val dir = LinuxRuntime.workdirAbsPath(app)?.let { File(it) }
            ?: File(app.filesDir, "screens")
        val file = File(dir, "shot-${System.currentTimeMillis()}.png")
        runCatching {
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                app.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val wrapped = android.graphics.Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace
                        )
                        if (wrapped == null) {
                            result.hardwareBuffer.close()
                            cont.resume("截图失败：无法从硬件缓冲创建位图")
                            return
                        }
                        val ok = runCatching {
                            file.parentFile?.mkdirs()
                            java.io.FileOutputStream(file).use { out ->
                                wrapped.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            }
                            true
                        }.getOrDefault(false)
                        wrapped.recycle()
                        result.hardwareBuffer.close()
                        cont.resume(if (ok) "已截图：${file.absolutePath}" else "截图失败：写文件出错")
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume("截图失败（errorCode=$errorCode）：系统可能限制了这个界面的截图")
                    }
                }
            )
        }.onFailure { cont.resume("截图调用失败：${it.message}") }
        }
    }
}
