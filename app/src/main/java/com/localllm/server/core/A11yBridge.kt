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
    /**
     * 诊断：无障碍能看到的每个窗口各自在哪块屏上。
     *
     * 0 = 主屏，后台虚拟屏是另一个编号。这一步决定成败：**如果无障碍能看到虚拟屏上的窗口**，
     * 读屏就该走控件树（有文本、有可点节点、能精确点），而不是截图 + OCR；
     * 如果看不到，才只剩 OCR 一条路。
     */
    fun windowDisplays(): String {
        val svc = A11yService.instance ?: return "无障碍服务没连上"
        return runCatching {
            val ws = svc.windows
            if (ws.isEmpty()) return "（无障碍看不到任何窗口）"
            ws.joinToString("\n") { w ->
                val d = runCatching { w.getDisplayId() }.getOrDefault(-1)
                val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: "-"
                "display=$d active=${w.isActive} focused=${w.isFocused} type=${w.type} pkg=$pkg"
            }
        }.getOrElse { "读取窗口失败：${it.message}" }
    }

    /** 最近一次读屏的节点，按编号索引（点击时直接对节点执行，不用坐标估） */
    private var snapshot: List<android.view.accessibility.AccessibilityNodeInfo> = emptyList()
    private var snapshotDisplay = -1

    /**
     * 读指定屏的控件树，输出带编号的元素（`@e1` 起）。
     *
     * 这是 Mika 那条路：无障碍窗口带 displayId，按它过滤就能读到后台虚拟屏上的界面——
     * 比截图 + OCR 好得多（有真实文本、可点性、资源 ID，且点击可以对节点直接执行）。
     * 之前我拿"空屏"的结果否掉过这条路，实际上窗口是有的（`focused=true`）。
     */
    /**
     * 读指定屏的控件树，输出带编号的元素（`@e1` 起）。
     *
     * [prioritizeInteractive] 只给本地小模型用：它一次读几十个元素就糊涂了，所以要
     * 把输入框/按钮排前面并砍到 40 条。云端模型保持**原始顺序**（从上到下，反映真实布局）
     * 和更大的上限——不要为了迁就小模型削弱它。
     */
    fun treeOnDisplay(
        displayId: Int,
        limit: Int = 80,
        prioritizeInteractive: Boolean = false
    ): String {
        val svc = A11yService.instance ?: return "错误：无障碍服务没连上"
        val wins = runCatching {
            svc.windows.filter {
                runCatching { it.getDisplayId() }.getOrDefault(0) == displayId
            }.sortedByDescending { it.layer }
        }.getOrDefault(emptyList())
        if (wins.isEmpty()) {
            // 留证据：到底是"一块窗口都没有"，还是"有窗口但读不到内容"
            val all: List<android.view.accessibility.AccessibilityWindowInfo> =
                runCatching { svc.windows }.getOrNull() ?: emptyList()
            ServerProcessManager.appLog(
                "读屏（屏 $displayId）：没有该屏的窗口；当前共有 ${all.size} 个窗口，" +
                        all.joinToString(";") {
                            runCatching {
                                "display=${it.getDisplayId()} type=${it.type} " +
                                        "root=${if (it.root == null) "null" else "ok"}"
                            }.getOrDefault("?")
                        }
            )
            return "（这块屏上没有可读的窗口：App 可能还没打开，或界面在加载）"
        }

        val items = mutableListOf<Item>()
        for (w in wins) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            collectItems(root, 0, items, limit * 3)
        }
        val ordered = if (prioritizeInteractive) {
            items.sortedWith(
                compareByDescending<Item> { it.editable }
                    .thenByDescending { it.clickable }
                    .thenBy { it.rect.top }
            )
        } else {
            items.sortedBy { it.rect.top }
        }
        val sorted = ordered.take(limit)
        if (sorted.isEmpty()) {
            ServerProcessManager.appLog(
                "读屏（屏 $displayId）：窗口有 ${wins.size} 个，但没读到文字或可点元素；" +
                        "root 状态=" + wins.joinToString(";") {
                            runCatching { if (it.root == null) "null" else "ok" }.getOrDefault("?")
                        }
            )
            return "（窗口在，但没有读到文字或可点元素：可能整屏是图片、或界面还在加载）"
        }

        snapshot = sorted.map { it.node }
        snapshotDisplay = displayId
        val body = buildString {
            sorted.forEachIndexed { i, it ->
                append("@e").append(i + 1).append(' ')
                append(if (it.editable) "输入框" else if (it.clickable) "按钮" else "文字")
                if (it.text.isNotBlank()) append('「').append(it.text.take(40)).append('」')
                if (it.editable) {
                    it.node.hintText?.toString()?.takeIf { h -> h.isNotBlank() }?.let { h ->
                        append("（提示：").append(h.take(20)).append("）")
                    }
                }
                append(" @").append(it.rect.centerX()).append(',').append(it.rect.centerY())
                it.rid?.let { rid -> append(" id=").append(rid.substringAfterLast('/')) }
                append('\n')
            }
            if (items.size > limit) {
                append("（只列了 ").append(limit).append(" 个，共 ").append(items.size)
                    .append(" 个；输入框和可点的排前面）")
            }
        }.trim()
        // 系统选择器 / 权限弹窗：这些是系统弹出来的，直接点上面的选项就行，
        // 模型很容易把它当成 App 自己的界面，绕半天绕不出来
        val systemDialog = looksLikeSystemDialog(wins, body)
        // 按包名给的用法提示：省掉"底部导航在哪、搜索框在哪"的摸索
        val pkg = wins.firstNotNullOfOrNull {
            runCatching { it.root?.packageName?.toString() }.getOrNull()
        }
        val knowledge = pkg?.let {
            A11yService.instance?.applicationContext?.let { c -> AppKnowledge.forPackage(c, it) }
        }
        return buildString {
            systemDialog?.let { append("【").append(it).append("】\n") }
            knowledge?.let { append("【关于 ").append(pkg).append("】").append(it).append('\n') }
            append(body)
        }
    }

    /** 认出系统弹窗（用哪个 App 打开、权限询问）：返回一句提示，不是系统弹窗返回 null */
    private fun looksLikeSystemDialog(
        wins: List<android.view.accessibility.AccessibilityWindowInfo>,
        body: String
    ): String? {
        val pkgs = wins.mapNotNull { runCatching { it.root?.packageName?.toString() }.getOrNull() }
        val systemWindow = pkgs.any {
            it.contains("android", true) && !it.contains("localllm", true)
        } || pkgs.any { it.contains("permissioncontroller", true) }
        val keywords = listOf(
            "用以下方式打开", "始终", "仅此一次", "打开方式",
            "允许", "拒绝", "仅在使用中允许", "禁止", "取消"
        )
        val hinted = keywords.count { body.contains(it) } >= 2
        return when {
            hinted && systemWindow -> "系统弹窗：这是系统的选择/权限询问，直接点上面的选项（@eN）即可"
            hinted -> "可能是弹窗：先点上面按钮里对应的选项，别去找页面里的控件"
            systemWindow -> "系统界面：当前最上层是系统窗口（选择器/权限框之类），按上面的元素操作"
            else -> null
        }
    }

    /** 在指定屏里按文字找元素并点击（用最近一次读屏的编号，比坐标稳） */
    fun clickTextOnDisplay(displayId: Int, want: String): String {
        if (snapshotDisplay != displayId || snapshot.isEmpty()) return "没找到：请先读一次屏"
        val target = want.trim()
        if (target.isEmpty()) return "错误：要给出目标文字"
        val idx = snapshot.indexOfFirst { nodeText(it).contains(target, ignoreCase = true) }
        if (idx < 0) return "没找到「$target」"
        return clickRef(displayId, "@e${idx + 1}")
    }

    /** 按编号点（@e3）：直接对节点 performAction，界面轻微变化也不会点偏 */
    fun clickRef(displayId: Int, ref: String): String {
        val node = nodeOf(displayId, ref) ?: return lastRefError
        val probe = ref.removePrefix("@").removePrefix("e").toIntOrNull() ?: 0
        val label = nodeText(node).take(20)
        val ok = runCatching {
            if (node.isClickable) node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            else {
                // 可点的常常是父容器：往上找一层再试
                var p = node.parent
                var depth = 0
                var done = false
                while (p != null && depth < 6 && !done) {
                    if (p.isClickable) done = runCatching {
                        p.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                    p = p.parent
                    depth++
                }
                done
            }
        }.getOrDefault(false)
        return if (ok) "已点击「$label」（@e$probe）" else "点击「$label」失败（@e$probe）"
    }

    /** 按编号长按（删除、呼出菜单、拖拽前的常见操作） */
    fun longClickRef(displayId: Int, ref: String): String {
        val node = nodeOf(displayId, ref) ?: return lastRefError
        val label = nodeText(node).take(20)
        val ok = runCatching {
            if (node.isLongClickable) {
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } else {
                var p = node.parent
                var depth = 0
                var done = false
                while (p != null && depth < 6 && !done) {
                    if (p.isLongClickable) done = runCatching {
                        p.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    }.getOrDefault(false)
                    p = p.parent
                    depth++
                }
                done
            }
        }.getOrDefault(false)
        return if (ok) "已长按「$label」" else "长按「$label」失败"
    }

    /**
     * 滚动一屏：优先对可滚动节点执行（[ref] 指定容器，null 就用第一个可滚动元素），
     * 这样滚的是真实列表，不会滚到广告位上。返回 null 表示没有可滚动节点，调用方改用手势。
     */
    fun scrollRef(displayId: Int, ref: String?, forward: Boolean): String? {
        if (snapshotDisplay != displayId || snapshot.isEmpty()) return null
        val node = if (ref.isNullOrBlank()) {
            snapshot.firstOrNull { runCatching { it.isScrollable }.getOrDefault(false) }
        } else {
            nodeOf(displayId, ref)
        } ?: return null
        if (!runCatching { node.isScrollable }.getOrDefault(false)) return null
        val action = if (forward) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = runCatching { node.performAction(action) }.getOrDefault(false)
        return if (ok) "已滚动${if (forward) "向前" else "向后"}一屏" else null
    }

    /**
     * 往「当前聚焦的输入框」直接写文字（不用剪贴板、不碰用户的剪贴板）。
     * 找不到聚焦的输入框返回 null，调用方改走剪贴板粘贴。
     */
    fun setTextOnFocused(displayId: Int, text: String): String? {
        if (snapshotDisplay != displayId || snapshot.isEmpty()) return null
        val node = snapshot.firstOrNull {
            runCatching { it.isFocused && it.isEditable }.getOrDefault(false)
        } ?: return null
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = runCatching {
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        return if (ok) "已输入「$text」（直接写进焦点输入框）" else null
    }

    /** 按编号取节点；失败时把原因写进 [lastRefError]，调用方直接返回它 */
    private var lastRefError: String = "没找到目标"

    /**
     * 当前屏最像输入框的那个元素的编号。
     *
     * 给本地小模型准备的：它经常挑不准编号，所以给一个「不用挑」的入口——
     * 优先已聚焦的、其次是搜索框类（类名或资源 ID 带 search）。
     */
    fun bestEditableRef(displayId: Int): String? {
        if (snapshotDisplay != displayId || snapshot.isEmpty()) return null
        val editables = snapshot.mapIndexedNotNull { i, n ->
            val editable = runCatching { n.isEditable }.getOrDefault(false)
            if (editable) i to n else null
        }
        if (editables.isEmpty()) return null
        val best = editables.maxByOrNull { (_, n) ->
            var score = 0
            if (runCatching { n.isFocused }.getOrDefault(false)) score += 5
            val cls = runCatching { n.className?.toString() ?: "" }.getOrDefault("")
            if (cls.contains("EditText", true)) score += 2
            if (cls.contains("Search", true)) score += 3
            val rid = runCatching { n.viewIdResourceName ?: "" }.getOrDefault("")
            if (rid.contains("search", true)) score += 4
            score
        }!!.first
        return "@e${best + 1}"
    }

    /** 宽容地认编号：`@e3` / `e3` / `3` / `#3` / `第3个` 都当 3（小模型经常只写数字） */
    private fun parseRefNumber(ref: String): Int? {
        val t = ref.trim()
            .removePrefix("@").removePrefix("#")
            .removePrefix("e").removePrefix("E")
            .removePrefix("第").removeSuffix("个")
            .trim()
        return t.toIntOrNull()
    }

    private fun nodeOf(displayId: Int, ref: String): android.view.accessibility.AccessibilityNodeInfo? {
        if (snapshotDisplay != displayId || snapshot.isEmpty()) {
            lastRefError = "没找到目标：请先读一次屏（tree）"
            return null
        }
        val idx = parseRefNumber(ref)?.minus(1)
        if (idx == null) {
            lastRefError = "错误：编号要写成 @e3 这样"
            return null
        }
        val node = snapshot.getOrNull(idx)
        if (node == null) lastRefError = "没有 @e${idx + 1} 这个元素（共 ${snapshot.size} 个）"
        return node
    }

    /** 往编号对应的输入框里写文字（直接设值，比按键模拟稳） */
    fun setTextOnRef(displayId: Int, ref: String, text: String): String {
        val node = nodeOf(displayId, ref) ?: return lastRefError
        if (!node.isEditable) return "「${nodeText(node).take(20)}」不是输入框"
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = runCatching {
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        return if (ok) "已写入「$text」" else "写入失败"
    }

    private class Item(
        val node: android.view.accessibility.AccessibilityNodeInfo,
        val text: String,
        val rect: android.graphics.Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val rid: String?
    )

    private fun collectItems(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<Item>,
        cap: Int
    ) {
        if (depth > 60 || out.size >= cap) return
        val text = nodeText(node)
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (text.isNotBlank() || clickable || editable) {
            val r = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(r) }
            if (r.width() >= 5 && r.height() >= 5) {
                out.add(Item(node, text, r, clickable, editable, node.viewIdResourceName))
            }
        }
        for (i in 0 until node.childCount) {
            val c = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectItems(c, depth + 1, out, cap)
            if (out.size >= cap) return
        }
    }

    private fun collect(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<android.view.accessibility.AccessibilityNodeInfo>,
        sb: StringBuilder,
        limit: Int
    ) {
        if (depth > 60 || out.size >= limit) return
        val text = nodeText(node)
        val clickable = node.isClickable
        val editable = node.isEditable
        if (text.isNotBlank() || clickable || editable) {
            val r = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(r) }
            if (r.width() >= 5 && r.height() >= 5) {
                out.add(node)
                val id = "@e${out.size}"
                sb.append(id).append(' ').append(if (editable) "输入框" else if (clickable) "按钮" else "文字")
                if (text.isNotBlank()) sb.append("「").append(text.take(40)).append("」")
                if (node.isEditable && node.hintText != null) {
                    sb.append("（提示：").append(node.hintText.toString().take(20)).append("）")
                }
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY())
                node.viewIdResourceName?.let { rid ->
                    sb.append(" id=").append(rid.substringAfterLast('/'))
                }
                sb.append('\n')
            }
        }
        for (i in 0 until node.childCount) {
            val c = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collect(c, depth + 1, out, sb, limit)
            if (out.size >= limit) return
        }
    }

    private fun nodeText(n: android.view.accessibility.AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return n.contentDescription?.toString()?.trim().orEmpty()
    }

    fun tree(): String {
        begin("读取屏幕内容")?.let { return it }
        val svc = service() ?: return "错误：无障碍服务未启用（设置 → 无障碍 → 混沌智核）"

        val root = svc.rootInActiveWindow ?: return "错误：读不到当前窗口（可能正处在系统界面或刚切换应用）"
        val out = StringBuilder()
        val dm = svc.resources.displayMetrics
        out.append("屏幕 ${dm.widthPixels}×${dm.heightPixels}\n")
        out.append("包名：${root.packageName ?: "?"}\n")
        // 先收集再排序：**可点 / 可输入**的排前面。
        // 小屏 App 一屏几十个文本节点，按遍历顺序给的话关键按钮会被挤到 80 行之外，
        // 模型就只能瞎猜坐标。同一组内保持屏幕上的先后（阅读顺序不变）。
        val items = mutableListOf<Triple<AccessibilityNodeInfo, Int, Int>>() // node, 分组, 遍历序
        var order = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isEditable) {
                // 0 = 可操作（可点/可输入）、1 = 纯文字
                items.add(Triple(n, if (n.isClickable || n.isEditable) 0 else 1, order++))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        items.sortWith(compareBy({ it.second }, { it.third }))
        var count = 0
        for ((n, _, _) in items) {
            if (count >= MAX_TREE_LINES) break
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val b = Rect().also { n.getBoundsInScreen(it) }
            out.append("· ")
            if (text.isNotEmpty()) out.append("\"").append(text.take(48)).append("\" ")
            if (desc.isNotEmpty() && desc != text) out.append("[").append(desc.take(32)).append("] ")
            out.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            n.viewIdResourceName?.let { out.append(" #").append(it.substringAfterLast('/')) }
            out.append(" @").append(b.centerX()).append(",").append(b.centerY())
            if (n.isClickable) out.append(" 可点")
            if (n.isEditable) out.append(" 可输入")
            if (n.isFocused) out.append(" 焦点")
            out.append('\n')
            count++
        }
        if (count == 0) return out.append("（当前窗口没有可读/可点的控件）").toString()
        return out.toString()
    }

    /** 紧凑版屏幕摘要：只列可点/可输入的前若干个，用于动手之后立刻确认"现在到哪了" */
    fun summary(maxLines: Int = 12): String {
        val svc = service() ?: return ""
        val root = svc.rootInActiveWindow ?: return "（读不到窗口）"
        val out = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count >= maxLines) return
            if (n.isClickable || n.isEditable) {
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() || desc.isNotEmpty()) {
                    val b = Rect().also { n.getBoundsInScreen(it) }
                    out.append(if (count == 0) "" else "、").append("\"")
                        .append((text.ifEmpty { desc }).take(18)).append("\"@")
                        .append(b.centerX()).append(",").append(b.centerY())
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return if (out.isEmpty()) "（没有可点控件）" else "当前可点：" + out
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
        val err = gesture(durationMs = ms) { p ->
            p.moveTo(x1.toFloat(), y1.toFloat())
            p.lineTo(x2.toFloat(), y2.toFloat())
        }
        if (err != null) return err
        // **滑完自动报一次"现在屏幕上有什么"**：真机上出现过"从第二页滑到第三页还没找到"——
        // 它滑完不看，接着瞎找。手势落下需要一点时间，等一下再读屏。
        runCatching { Thread.sleep(350) }
        return "滑动已发出 ($x1,$y1 → $x2,$y2 ${ms}ms) · " + summary()
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
