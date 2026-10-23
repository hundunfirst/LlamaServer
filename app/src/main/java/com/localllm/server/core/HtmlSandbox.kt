package com.localllm.server.core

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * HTML/JS 沙箱执行器：把模型生成的单文件网页真的跑一次，回报「错在哪 / 是不是死了 / 画面有没有内容」。
 *
 * 为什么必须是 UI 托管的一个真实 WebView：requestAnimationFrame 只在视图挂到窗口且可见时才推进，
 * 离屏 WebView 里游戏循环永远不启动，验证就失去意义。
 *
 * 沙箱边界（WebView 自 Android 8 起跑在独立 renderer 进程，页面死循环不会卡住 App 主线程）：
 * - loadDataWithBaseURL(null, …) → 不透明源，拿不到 file:// 与 App 私有目录
 * - 关掉 file/content 访问与跨源，blockNetworkLoads → 生成物禁止联网
 * - 不注册 addJavascriptInterface → 生成代码拿不到任何 native 桥
 *
 * 可抛弃：WebView 的 JS 一旦死循环就不可中断（stopLoading 也救不了），
 * 所以超时后一律 recycle()，由 UI 用新实例重建。
 */
object HtmlSandbox {

    private const val SETTLE_MS = 250L
    private const val ALIVE_TIMEOUT_MS = 2500L
    private const val MAX_ERRORS = 12
    private const val MAX_LOGS = 8

    // 虚拟时钟推进：90 帧 ≈ 1.5 秒游戏时间，30 帧用来比对画面是否还在变
    private const val TICK_90 = "typeof window.__hubTick==='function'?window.__hubTick(90):-999"
    private const val TICK_30 = "typeof window.__hubTick==='function'?window.__hubTick(30):-999"

    // 交互探测：收集可交互元素 + 提供点击/指纹比较。只做这件事，不改页面本身。
    private const val INTERACT_INIT = """
(function(){
  window.__pkCand = Array.prototype.slice.call(document.querySelectorAll(
    'button,[onclick],input[type=button],input[type=submit],input[type=range],input[type=checkbox],select,a[href=\""#\""]'
  )).slice(0,4);
  window.__probeCount = window.__pkCand.length;
  window.__fp = function(){
    var t = document.body ? (document.body.innerText || '') : '';
    var n = document.querySelectorAll('*').length;
    var s = 0;
    document.querySelectorAll('[style]').forEach(function(e){ s += (e.getAttribute('style')||'').length; });
    return t + '|' + n + '|' + s;
  };
  window.__probeDesc = function(i){
    var e = window.__pkCand[i]; if(!e) return '#' + i;
    var d = (e.innerText || e.value || e.id || e.tagName || '').toString().trim();
    return d.slice(0,16) || e.tagName;
  };
  window.__probeClick = function(i){
    var e = window.__pkCand[i]; if(!e) return 0;
    window.__before = window.__fp();
    try { e.click(); } catch(err) {}
    return 1;
  };
  window.__probeChanged = function(){ return window.__fp() === window.__before ? 0 : 1; };
  return 1;
})()
"""

    data class Report(
        val alive: Boolean,
        val errors: List<String>,
        val logs: List<String>,
        val canvases: List<String>,
        val animated: Boolean,
        val nodeCount: Int,
        val textPreview: String,
        /** 静态/交互检查发现的可疑点：不一定是错误，但提示「能用」存疑 */
        val warnings: List<String> = emptyList()
    ) {
        /** 给模型看的纯文本结论：错误优先，其次画面，最后 DOM。 */
        fun toToolText(): String {
            val sb = StringBuilder()
            if (!alive) {
                sb.append("【运行结果】页面卡死：超过 ").append(ALIVE_TIMEOUT_MS)
                    .append(" 毫秒没有任何响应。\n")
                sb.append("最常见原因：while/for 死循环（缺 break 或条件永远为真）、")
                sb.append("或首屏同步执行了极重的计算。\n")
                if (errors.isNotEmpty()) {
                    sb.append("卡死前捕获到的错误：\n")
                    errors.forEach { sb.append("  ✗ ").append(it).append('\n') }
                }
                sb.append("请定位死循环并修掉；若确实需要长循环，必须加入退出条件。")
                return sb.toString()
            }
            sb.append("【运行结果】页面存活")
            if (errors.isEmpty()) sb.append("，无报错。\n") else sb.append("，捕获 ").append(errors.size).append(" 个错误：\n")
            errors.forEach { sb.append("  ✗ ").append(it).append('\n') }
            if (logs.isNotEmpty()) {
                sb.append("控制台输出：\n")
                logs.forEach { sb.append("  · ").append(it).append('\n') }
            }
            if (canvases.isEmpty()) {
                sb.append("画布：页面里没有 <canvas>（纯 DOM 实现）。\n")
            } else {
                sb.append("画布：\n")
                canvases.forEach { sb.append("  · ").append(it).append('\n') }
            }
            sb.append("画面：").append(if (animated) "在变化（循环在跑）" else "静止（0.4 秒内没有任何变化）").append('\n')
            sb.append("DOM 元素：").append(nodeCount).append(" 个\n")
            if (warnings.isNotEmpty()) {
                sb.append("可疑点（页面没崩，但这些地方可能不工作）：\n")
                warnings.forEach { sb.append("  ⚠ ").append(it).append('\n') }
            }
            if (textPreview.isNotBlank()) sb.append("可见文本：").append(textPreview).append('\n')
            sb.append('\n')
            // 解析期错误会让整段 script 一行都不执行，此时运行期错误还没机会出现
            if (errors.any { it.contains("SyntaxError") || it.contains("Unexpected") || it.contains("Missing }") }) {
                sb.append("⚠ 存在语法错误。浏览器解析失败时整个 <script> 一行都不会执行，")
                sb.append("所以现在看不到运行期错误——先把语法修完，再跑一次。")
            } else if (errors.isNotEmpty()) {
                sb.append("请按上面的行号逐个修掉错误，改完再调用一次本工具验证。")
            } else if (!animated && canvases.isNotEmpty()) {
                sb.append("画面静止说明绘制循环没跑起来（检查 requestAnimationFrame / setInterval 是否真的调用了绘制函数）。")
            } else if (canvases.any { it.contains("已绘制 0") || it.contains("已绘制 -") }) {
                sb.append("画布几乎空白：检查绘制坐标是否落在画布外、以及 canvas 的宽高是否设置了。")
            } else if (warnings.isNotEmpty()) {
                sb.append("页面能加载、没有报错，但上面的可疑点要逐个核实——")
                sb.append("「没崩」不等于「能用」：按钮没反应、数字不刷新这类问题不会报错。")
            } else {
                sb.append("看起来可以运行了。")
            }
            return sb.toString()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    @Volatile private var view: WebView? = null
    @Volatile private var onPageDone: ((Boolean) -> Unit)? = null

    private val errors = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private val _generation = MutableStateFlow(0)
    /** 每次 +1 都要求 UI 换一个全新的 WebView（死循环后旧实例不可复用） */
    val generation: StateFlow<Int> = _generation

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _lastHtml = MutableStateFlow<String?>(null)
    /** 最近一次交给沙箱的页面，供 UI 预览/试玩 */
    val lastHtml: StateFlow<String?> = _lastHtml

    private val _lastLabel = MutableStateFlow("")
    /** 最近一次自检的文件名，显示在摘要行上 */
    val lastLabel: StateFlow<String> = _lastLabel

    private val _lastSummary = MutableStateFlow<String?>(null)
    /** 最近一次自检的结论，收起状态下直接显示，不用展开就知道过没过 */
    val lastSummary: StateFlow<String?> = _lastSummary

    fun attach(w: WebView) {
        configure(w)
        view = w
    }

    fun detach(w: WebView) {
        if (view === w) view = null
        onPageDone = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        @Suppress("DEPRECATION")
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
        }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        // 刻意不调用 addJavascriptInterface：生成代码不该拿到任何 native 能力
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                onPageDone?.invoke(true)
                onPageDone = null
            }

            override fun onReceivedError(
                v: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                // blockNetworkLoads 下外部资源必然失败，只有主文档失败才值得记
                if (request?.isForMainFrame == true) {
                    errors.add("页面加载失败：${error?.description ?: "未知原因"}")
                }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val text = "${m.message()}（第 ${m.lineNumber()} 行）"
                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> if (errors.size < MAX_ERRORS) errors.add(text)
                    else -> if (logs.size < MAX_LOGS) logs.add(text)
                }
                return true
            }
        }
    }

    /** 沙箱是否可用（UI 里那个宿主视图挂上了没有） */
    val available: Boolean get() = view != null

    fun recycle() {
        view = null
        onPageDone = null
        _lastSummary.value = "卡死（超时）"
        _generation.value += 1
    }

    private fun summarize(r: Report): String = when {
        r.errors.isNotEmpty() -> "${r.errors.size} 个错误"
        r.canvases.isNotEmpty() && !r.animated -> "画面静止"
        r.canvases.isEmpty() -> "无画布，通过"
        else -> "通过"
    }

    /** 用户关掉预览面板：下次自检会自动重新弹出 */
    fun clearPreview() {
        _lastHtml.value = null
    }

    suspend fun run(html: String, label: String = "", settleMs: Long = SETTLE_MS): Report = mutex.withLock {
        // 先挂出来：UI 看到 lastHtml 才会把沙箱视图装进界面
        _lastHtml.value = html
        _lastLabel.value = label
        _lastSummary.value = null
        _running.value = true
        try {
            // 等 UI 把宿主视图挂上（一次重组的时间），否则拿不到可见 WebView，rAF 不会跑
            var waited = 0L
            while (view == null && waited < 4000) {
                delay(60); waited += 60
            }
            val w = view
            if (w == null) {
                ServerProcessManager.appLog(
                    "沙箱：等待宿主视图超时（${waited}ms）——预览面板没有挂载，通常是没有停留在聊天页"
                )
                return@withLock Report(
                    alive = true,
                    errors = listOf("沙箱宿主视图未挂载（等待 ${waited}ms 超时）"),
                    logs = emptyList(), canvases = emptyList(),
                    animated = false, nodeCount = 0, textPreview = ""
                )
            }
            ServerProcessManager.appLog("沙箱：宿主就绪（等待 ${waited}ms），开始加载页面")
            synchronized(errors) { errors.clear() }
            synchronized(logs) { logs.clear() }
            val ok = loadAndWait(w, injectHarness(html))
            if (!ok) {
                recycle()
                return@withLock Report(
                    alive = false, errors = snapshotErrors(), logs = snapshotLogs(),
                    canvases = emptyList(), animated = false, nodeCount = 0, textPreview = ""
                )
            }
            delay(settleMs)
            // 用虚拟时钟主动推进 90 帧（≈1.5 秒游戏时间）。不走真实 rAF：
            // rAF 只在视图可见并参与合成时才推进，切标签/退到后台就停摆；
            // 而这里的回调是同步跑完的，不依赖可见性，还能稳定复现「第 N 帧才出错」的问题。
            if (!evalOk(w, TICK_90)) {
                recycle()
                return@withLock Report(
                    alive = false, errors = snapshotErrors(), logs = snapshotLogs(),
                    canvases = emptyList(), animated = false, nodeCount = 0, textPreview = ""
                )
            }
            val p1 = probe(w) ?: run {
                recycle()
                return@withLock Report(
                    alive = false, errors = snapshotErrors(), logs = snapshotLogs(),
                    canvases = emptyList(), animated = false, nodeCount = 0, textPreview = ""
                )
            }
            val ticked2 = evalOk(w, TICK_30)
            val p2 = if (ticked2) probe(w) else null // 第二次失败不算死，只影响动画判据
            // 静态检查看「元素取到没用」，交互探测看「点了没反应」——两者互补，
            // 因为「点了有局部变化但功能不对」只有静态检查能抓到。
            val warns = staticWarnings(html) + interactionWarnings(w)
            buildReport(p1, p2, warns).also { _lastSummary.value = summarize(it) }
        } finally {
            _running.value = false
        }
    }

    private fun snapshotErrors(): List<String> = synchronized(errors) { errors.toList() }
    private fun snapshotLogs(): List<String> = synchronized(logs) { logs.toList() }

    private suspend fun loadAndWait(w: WebView, html: String): Boolean = withTimeoutOrNull(ALIVE_TIMEOUT_MS) {
        suspendCancellableCoroutine<Boolean> { cont ->
            onPageDone = { ok -> if (cont.isActive) cont.resume(ok) }
            main.post {
                try {
                    // baseUrl=null → 不透明源：页面拿不到 file:// 与任何 App 私有路径
                    w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                } catch (e: Throwable) {
                    onPageDone = null
                    if (cont.isActive) cont.resume(false)
                }
            }
            cont.invokeOnCancellation { onPageDone = null }
        }
    } ?: false

    /** 返回 null = JS 完全没有响应（死循环或渲染进程死了） */
    /** 通用求值：返回 null = JS 完全没有响应（死循环或渲染进程死了） */
    private suspend fun evalJs(w: WebView, js: String): String? = withTimeoutOrNull(ALIVE_TIMEOUT_MS) {
        suspendCancellableCoroutine<String?> { cont ->
            main.post {
                try {
                    w.evaluateJavascript(js) { r ->
                        if (cont.isActive) cont.resume(r)
                    }
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { }
        }
    }

    /** 只要「执行完了」这个事实；超时即判定卡死 */
    private suspend fun evalOk(w: WebView, js: String): Boolean = evalJs(w, js) != null

    private fun jsStr(raw: String?): String? =
        raw?.let { runCatching { (JSONTokener(it).nextValue() as? String) ?: it }.getOrNull() }

    /**
     * 静态检查：「取到了元素却再也没用过这个变量」。
     * 这是最典型的「页面没报错但功能不工作」——实测番茄钟就是 `const timerEl = getElementById('timer')`
     * 之后从没往 timerEl 写过，倒计时数字永远停在初始值，而所有报错检测都是干净的。
     */
    private fun staticWarnings(html: String): List<String> {
        val out = mutableListOf<String>()
        val decl = Regex(
            """(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*document\.getElementById\(\s*['"]([^'"]+)['"]\s*\)"""
        )
        for (m in decl.findAll(html)) {
            val name = m.groupValues[1]
            val id = m.groupValues[2]
            val rest = html.substring(m.range.last + 1)
            val used = Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(rest)
            if (!used) {
                out += "取到了元素 #$id，但之后再也没用过变量 $name —— 这块界面上可能永远是初始值" +
                        "（例如倒计时数字不刷新、状态文字不变）"
            }
        }
        return out.take(3)
    }

    /**
     * 交互探测：逐个点可交互元素，比对点击前后的 DOM 指纹。
     * 只能抓「点了完全没反应」这一类；点了有局部变化但功能不对（比如只改了状态文字、
     * 数字仍不更新）它看不出来，那种要靠上面的静态检查。
     */
    private suspend fun interactionWarnings(w: WebView): List<String> {
        val out = mutableListOf<String>()
        evalJs(w, INTERACT_INIT) ?: return out
        val n = jsStr(evalJs(w, "window.__probeCount||0"))?.toIntOrNull() ?: 0
        var idx = 0
        var checked = 0
        while (idx < n && checked < 4) {
            idx++; checked++
            if (evalJs(w, "window.__probeClick(${idx - 1})") == null) break
            delay(320)
            val changed = jsStr(evalJs(w, "window.__probeChanged()")) == "1"
            if (!changed) {
                val desc = jsStr(evalJs(w, "window.__probeDesc(${idx - 1})")).orEmpty().ifBlank { "#$idx" }
                out += "点击「$desc」后页面没有任何变化 —— 这个按钮/控件可能没接上处理逻辑"
            }
        }
        return out.take(3)
    }

    private suspend fun probe(w: WebView): JSONObject? {
        val raw = evalJs(w, PROBE_JS) ?: return null
        val text = runCatching {
            (JSONTokener(raw).nextValue() as? String) ?: raw
        }.getOrNull() ?: raw
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun buildReport(p1: JSONObject, p2: JSONObject?, warnings: List<String> = emptyList()): Report {
        // window.onerror 报的行号比 console 精确，优先用它；console 里那些非 JS 错误（资源加载）补在后面
        val merged = mutableListOf<String>()
        val fromJs = p1.optJSONArray("errs")
        if (fromJs != null) for (i in 0 until fromJs.length()) {
            val s = fromJs.optString(i)
            if (s.isNotBlank()) merged.add(s)
        }
        val keys = merged.map { normalizeErr(it) }.toMutableSet()
        synchronized(errors) {
            errors.forEach { e ->
                val k = normalizeErr(e)
                if (keys.none { it.isNotBlank() && k.contains(it) }) {
                    keys.add(k); merged.add(e)
                }
            }
        }
        val mergedLogs = mutableListOf<String>()
        val logsJs = p1.optJSONArray("logs")
        if (logsJs != null) for (i in 0 until logsJs.length()) mergedLogs.add(logsJs.optString(i))
        if (mergedLogs.isEmpty()) mergedLogs.addAll(snapshotLogs())

        val c1 = p1.optJSONArray("canvases")
        val c2 = p2?.optJSONArray("canvases")
        val desc = mutableListOf<String>()
        var animated = false
        if (c1 != null) {
            for (i in 0 until c1.length()) {
                val o = c1.optJSONObject(i) ?: continue
                val wd = o.optInt("w")
                val ht = o.optInt("h")
                val painted = o.optDouble("painted", -1.0)
                val paintText = when {
                    painted < -1.5 -> "无法读取像素（用了 WebGL 或已被跨源污染）"
                    else -> "已绘制 " + String.format("%.1f", painted * 100) + "%"
                }
                desc.add("${wd}×${ht}，$paintText")
                val sig1 = o.optLong("sig")
                val sig2 = c2?.optJSONObject(i)?.optLong("sig", sig1) ?: sig1
                if (sig1 != sig2) animated = true
            }
        }
        if (!animated) {
            val t1 = p1.optString("text")
            val t2 = p2?.optString("text") ?: t1
            if (t1 != t2) animated = true
            val n1 = p1.optInt("nodes")
            val n2 = p2?.optInt("nodes", n1) ?: n1
            if (n1 != n2) animated = true
        }
        return Report(
            alive = true,
            errors = merged.take(MAX_ERRORS),
            logs = mergedLogs.take(MAX_LOGS),
            canvases = desc,
            animated = animated,
            nodeCount = p1.optInt("nodes"),
            textPreview = p1.optString("text").replace("\n", " ").take(120),
            warnings = warnings
        )
    }

    /** 只留消息主体，用来在两路来源之间去重 */
    private fun normalizeErr(s: String): String = s
        .replace(Regex("^第\\s*\\d+\\s*行[：:]\\s*"), "")
        .replace(Regex("（第\\s*\\d+\\s*行）$"), "")
        .trim()
        .take(60)

    /**
     * 把采集器插到 <head> 之后——必须保持**单行**，否则会顶掉后续所有脚本的行号，
     * 回给模型的行号就和文件对不上了。
     */
    private fun injectHarness(html: String): String {
        val tag = "<script>$HARNESS</script>"
        val head = html.indexOf("<head>", ignoreCase = true)
        if (head >= 0) {
            val at = head + 6
            return html.substring(0, at) + tag + html.substring(at)
        }
        val htmlIdx = html.indexOf("<html", ignoreCase = true)
        if (htmlIdx >= 0) {
            val close = html.indexOf('>', htmlIdx)
            if (close > 0) return html.substring(0, close + 1) + tag + html.substring(close + 1)
        }
        return tag + html
    }

    private val HARNESS = """
window.__hubErrs=[];window.__hubLogs=[];
window.onerror=function(m,s,l,c){window.__hubErrs.push({line:l||0,col:c||0,msg:String(m)});return true;};
window.addEventListener('unhandledrejection',function(e){window.__hubErrs.push({line:0,col:0,msg:'Promise 未捕获: '+e.reason});});
(function(){var oe=console.error,ow=console.warn;console.error=function(){window.__hubLogs.push('[error] '+Array.prototype.join.call(arguments,' '));oe.apply(console,arguments);};console.warn=function(){window.__hubLogs.push('[warn] '+Array.prototype.join.call(arguments,' '));ow.apply(console,arguments);};})();
window.__hubQ=[];window.__hubSeq=1;window.__hubNow=0;window.__hubCalls=0;
window.requestAnimationFrame=function(cb){var id=window.__hubSeq++;window.__hubQ.push({id:id,at:window.__hubNow+16,fn:cb,raf:true});return id;};
window.cancelAnimationFrame=function(id){window.__hubQ=window.__hubQ.filter(function(x){return x.id!==id;});};
window.setTimeout=function(fn,ms){var id=window.__hubSeq++;window.__hubQ.push({id:id,at:window.__hubNow+(ms||0),fn:fn,rep:0});return id;};
window.setInterval=function(fn,ms){var id=window.__hubSeq++;window.__hubQ.push({id:id,at:window.__hubNow+(ms||16),fn:fn,rep:ms||16});return id;};
window.clearTimeout=function(id){window.__hubQ=window.__hubQ.filter(function(x){return x.id!==id;});};window.clearInterval=window.clearTimeout;
window.__hubTick=function(n){for(var f=0;f<n;f++){window.__hubNow+=16;var due=[],rest=[],i;for(i=0;i<window.__hubQ.length;i++){(window.__hubQ[i].at<=window.__hubNow?due:rest).push(window.__hubQ[i]);}window.__hubQ=rest;for(i=0;i<due.length;i++){var t=due[i];window.__hubCalls++;if(window.__hubCalls>20000){return -1;}try{if(t.raf){t.fn(window.__hubNow);}else if(t.rep){t.fn();t.at=window.__hubNow+t.rep;window.__hubQ.push(t);}else{t.fn();}}catch(e){window.__hubErrs.push({line:0,col:0,msg:'帧回调异常: '+e});}}}return window.__hubNow;};
""".trim().replace("\n", "")

    private val PROBE_JS = """
(function(){
 try{
  var cs=[],i;
  var list=document.querySelectorAll('canvas');
  for(i=0;i<list.length;i++){
   var c=list[i],r=-1,sig=0;
   try{
    var g=c.getContext('2d');
    if(g&&c.width>0&&c.height>0){
     var d=g.getImageData(0,0,c.width,c.height).data,n=0,tot=c.width*c.height,k;
     for(k=3;k<d.length;k+=4){if(d[k]>8)n++;}
     r=tot?n/tot:0;
     for(k=0;k<d.length;k+=997){sig=(sig*31+d[k])|0;}
    }
   }catch(e){r=-2;}
   cs.push({w:c.width,h:c.height,painted:r,sig:sig});
  }
  var e1=[],l1=[],he=window.__hubErrs||[],hl=window.__hubLogs||[];
  for(i=0;i<he.length&&i<12;i++){e1.push((he[i].line?'第 '+he[i].line+' 行：':'')+he[i].msg);}
  for(i=0;i<hl.length&&i<8;i++){l1.push(hl[i]);}
  var body=document.body;
  var txt=body&&body.innerText?body.innerText.replace(/\s+/g,' ').slice(0,160):'';
  return JSON.stringify({canvases:cs,nodes:document.querySelectorAll('*').length,text:txt,errs:e1,logs:l1});
 }catch(e){return JSON.stringify({canvases:[],nodes:0,text:'',errs:['探针异常: '+e],logs:[]});}
})()
""".trim().replace("\n", "")
}
