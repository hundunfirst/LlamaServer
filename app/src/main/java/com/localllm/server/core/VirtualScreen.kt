package com.localllm.server.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Agent 专用虚拟屏（后台屏）。
 *
 * 为什么要有它：无障碍和 adb `input` 都只能操作**当前前台**窗口，所以以前每做一次界面任务，
 * 用户的主屏就被抢走几十秒。这里换成另一条路：由 [AgentShell]（以 shell 身份常驻的进程，
 * 见 `helper/AgentShell.java`）建一块独立的公有虚拟屏，目标 App 开在那块屏上，
 * 读屏、点击、输入全部发往那块屏，用户的主屏一点都不动。
 *
 * 分工刻意做得很窄：
 * - helper 只负责"建屏 + 把画面导成 JPEG"，接口面越小越不容易被系统改动打掉；
 * - 启 App、点击、输入、截图取回全部走现成的无线调试通道（[AdbBridge]）；
 * - 进程要常驻，但它不监听任何端口、不接受外部连接，只认 /data/local/tmp 下的几个文件。
 *
 * 前置条件：无线调试已配对（否则 shell 权限拿不到）、Linux 容器就绪（adb 客户端在容器里）。
 */
object VirtualScreen {

    data class Ready(val displayId: Int, val width: Int, val height: Int)

    private const val DEX_REMOTE = "/data/local/tmp/localllm-agent.dex"
    // 画面与停止信号按屏号分开：以前所有 helper 共用一个文件名，多个实例同时活着时
    // 会互相覆盖（真机残留过 6 个，App 读到的是别人那块空屏，OCR 永远读不到字）
    private fun shotPath(id: Int) = "/data/local/tmp/localllm_agent_$id.jpg"
    private fun stopPath(id: Int) = "/data/local/tmp/localllm_agent_$id.stop"
    private fun seqPath(id: Int) = "/data/local/tmp/localllm_agent_$id.seq"
    private fun cmdPath(id: Int) = "/data/local/tmp/localllm_agent_$id.cmd"
    private fun outPath(id: Int) = "/data/local/tmp/localllm_agent_$id.out"
    private const val DISPLAY_FILE = "/data/local/tmp/localllm_agent.display"
    private const val SIZE_FILE = "/data/local/tmp/localllm_agent.size"
    private const val SID = "agent-shell"

    @Volatile
    private var ready: Ready? = null

    /** 上一次失败的原因，界面直接显示，省得每次都要拉日志 */
    @Volatile
    var lastError: String? = null
        private set

    // 自检跑在进程级作用域里：它要二三十秒（连无线调试 → 传 dex → 起进程 → 等屏 → 取图），
    // 用页面作用域的话用户一翻页就被取消，还会抛 "left the composition"。
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 后台屏一次只给一个任务用。
     *
     * 两个任务同时操作同一块屏会互相抢：一个刚点开页面，另一个正好在点上一页的坐标。
     * 拿不到就排队等，等不到再如实告诉模型（别让它改去动主屏）。
     */
    private val screenLock = kotlinx.coroutines.sync.Mutex()

    /** 独占后台屏做一件事；[waitMs] 内没轮到就返回 null */
    suspend fun <T> exclusive(waitMs: Long = 150_000, block: suspend () -> T): T? {
        val got = kotlinx.coroutines.withTimeoutOrNull(waitMs) {
            screenLock.lock()
            true
        } ?: return null
        try {
            return block()
        } finally {
            screenLock.unlock()
        }
    }

    @Volatile
    private var lastUseAt = 0L
    @Volatile
    private var idleJob: kotlinx.coroutines.Job? = null

    /** 每次真正用到后台屏就记一下时间，空闲收屏据此判断 */
    fun touch() {
        lastUseAt = System.currentTimeMillis()
    }

    /**
     * 空闲一段时间后自动收屏：任务完了还留着一块虚拟屏，白占一个合成的层，
     * 用户也会疑惑"它怎么还开着"。
     *
     * 用延迟＋复查而不是立刻收：多轮任务之间往往只隔几十秒，收掉再建要五到十秒。
     */
    fun scheduleIdleRelease(context: Context, idleMs: Long = 0) {
        val app = context.applicationContext
        val wait = if (idleMs > 0) idleMs else AppSettings.virtualScreenIdleMinutes * 60_000L
        idleJob?.cancel()
        idleJob = bg.launch {
            delay(wait)
            if (ready == null) return@launch
            if (System.currentTimeMillis() - lastUseAt < wait) return@launch
            ServerProcessManager.appLog("虚拟屏：空闲 ${wait / 60_000} 分钟，收起这块屏")
            runCatching { release(app) }
        }
    }
    private val _selfTestBusy = MutableStateFlow(false)
    val selfTestBusy: StateFlow<Boolean> = _selfTestBusy.asStateFlow()
    private val _selfTestNote = MutableStateFlow<String?>(null)
    val selfTestNote: StateFlow<String?> = _selfTestNote.asStateFlow()

    /** 自检：建屏 → 在后台屏上开设置 → 截图。全程不碰主屏 */
    fun startSelfTest(context: Context) {
        if (_selfTestBusy.value) return
        _selfTestBusy.value = true
        val app = context.applicationContext
        bg.launch {
            try {
                _selfTestNote.value = "正在连接无线调试…"
                val r = ensure(app) ?: run {
                    _selfTestNote.value = lastError ?: "建屏失败（详情看日志）"
                    return@launch
                }
                _selfTestNote.value = "屏 ${r.displayId} 已就绪（${r.width}x${r.height}），正在打开 App…"
                val launched = startApp(app, "com.android.settings")
                _selfTestNote.value = "App ${if (launched) "已启动" else "启动失败"}，等界面画出来…"
                delay(3_500)
                _selfTestNote.value = "正在取画面…"
                val shot = shot(app)
                if (shot != null) {
                    runCatching { File(app.filesDir, "vd-selftest.jpg").writeBytes(shot) }
                }
                // 关键诊断：无障碍能不能看到虚拟屏上的窗口？看得到就该走控件树，不用 OCR
                val winDump = runCatching { A11yBridge.windowDisplays() }.getOrDefault("（读不到）")
                ServerProcessManager.appLog("自检：无障碍窗口\n$winDump")
                val ocrCount = runCatching { shot?.let { OcrEngine.recognizeLines(it) }?.size }.getOrNull()
                _selfTestNote.value = "屏 ${r.displayId} · ${r.width}x${r.height} · 开App=" +
                        (if (launched) "成功" else "失败") + " · " +
                        (if (shot != null) "截图 ${shot.size / 1024}KB" else "截图失败") +
                        " · OCR ${ocrCount ?: "?"} 行 · 无障碍窗口：\n$winDump"
            } catch (t: Throwable) {
                _selfTestNote.value = "自检出错：${t.message ?: t.javaClass.simpleName}"
            } finally {
                _selfTestBusy.value = false
            }
        }
    }

    fun current(): Ready? = ready

    /** 建好并可用就返回；任何一步失败都返回 null（原因写 [lastError] 与日志） */
    suspend fun ensure(context: Context): Ready? {
        ready?.let { return it }
        lastError = null
        val app = context.applicationContext
        if (!LinuxRuntime.isReady(app)) {
            return fail("容器还没就绪（adb 客户端在里面，没有它拿不到 shell 权限）")
        }
        // 容器里的 adb 必须先连上设备：跳过这一步会出现
        // "failed to get feature set: no devices/emulators found"
        val st = AdbBridge.ensureConnected(app)
        if (!st.connected) {
            return fail("无线调试没连上：${st.message}")
        }
        if (!deploy(app)) return null
        // 先把上一块屏收掉：残留的旧 helper 会继续往盘上写它那块（可能还是空白的）画面
        if (lastDisplayId > 0) {
            runCatching { adbFast(app, "shell touch ${stopPath(lastDisplayId)}", 10_000) }
            ServerProcessManager.appLog("虚拟屏：先收掉上一块屏 ${lastDisplayId}")
            lastDisplayId = 0
            delay(600)
        }
        if (!start(app)) return null
        val id = waitDisplay(app)
        if (id == null) return fail("helper 起来了但没拿到屏号（详情见日志）")
        val (sw, sh) = readSize(app)
        if (sw <= 0 || sh <= 0) {
            ServerProcessManager.appLog("虚拟屏：尺寸读不出来（${sw}x${sh}）")
        }
        val r = Ready(id, sw, sh)
        ready = r
        lastDisplayId = id
        ServerProcessManager.appLog("虚拟屏就绪：display=$id ${sw}x${sh}")
        // 把用户手上的 App 也开到后台屏上一个：否则模型第一步 tree 面对的是一块空屏，
        // 什么都读不到，只能改去动主屏（真机上就是这么把主屏抢走的）
        try {
            val front = foregroundPackage(app)
            ServerProcessManager.appLog("虚拟屏：主屏前台是 ${front ?: "读不到"}")
            if (front != null && front != app.packageName) {
                val ok = startApp(app, front)
                ServerProcessManager.appLog("虚拟屏：复制主屏 App（$front）到后台屏 ${if (ok) "成功" else "失败"}")
            }
        } catch (t: Throwable) {
            ServerProcessManager.appLog("虚拟屏：复制主屏 App 出错：${t.message ?: t.javaClass.simpleName}")
        }
        return r
    }

    /**
     * 用户主屏（display 0）此刻最上面的包名；读不到返回 null。
     *
     * 必须**只看 display=0 那一段**：`dumpsys window displays` 里有多块屏的焦点记录
     * （主屏、副屏、还有我们自己的后台屏），抓第一个匹配会拿到后台屏上的 App，
     * 于是误判成"用户正在主屏用它"，白白拒绝/重开（真机上就是这么假的）。
     */
    private suspend fun foregroundPackage(app: Context): String? {
        val (_, out) = adbFast(
            app, "shell \"dumpsys window displays | grep -E 'Display: mDisplayId=|mCurrentFocus='\"",
            20_000
        )
        var inMain = false
        for (line in out.lineSequence()) {
            if (line.contains("Display: mDisplayId=0")) {
                inMain = true
                continue
            }
            if (line.contains("Display: mDisplayId=")) {
                inMain = false
                continue
            }
            if (!inMain) continue
            Regex("""\su\d+\s+([A-Za-z0-9_.]+)/""").find(line)?.let { return it.groupValues[1] }
            if (line.contains("mCurrentFocus=null")) return null
        }
        return null
    }

    private fun fail(why: String): Ready? {
        lastError = why
        ServerProcessManager.appLog("虚拟屏：$why")
        return null
    }

    /** 收屏（任务结束、或用户关掉后台执行时）。helper 看到 stop 文件会自己释放并退出 */
    suspend fun release(context: Context) {
        val app = context.applicationContext
        val id = ready?.displayId ?: lastDisplayId
        ready = null
        if (id > 0) {
            lastDisplayId = 0
            runCatching { adbFast(app, "shell touch ${stopPath(id)}", 15_000) }
        }
        ServerProcessManager.appLog("虚拟屏：已请求释放（屏 $id）")
    }

    /** 上一块屏的编号：建新屏之前先把这块收掉，别让旧 helper 继续往盘上写画面 */
    @Volatile
    private var lastDisplayId = 0

    /** 把目标 App 开在这块屏上。返回 false 时调用方应退回主屏流程 */
    suspend fun startApp(context: Context, packageName: String): Boolean = startAppDetail(context, packageName).first

    /**
     * 打开 App，并说清楚为什么没打开。
     *
     * 先看用户是不是正在主屏上用它：是的话不在后台屏再开一份——同一个 App 两份实例
     * 会各自收推送、各自持有登录态，用户那边看到的就是"消息怎么变成两份了"。
     * 这一条是 Mika 的做法（`inUseOnMainScreen`）。
     */
    suspend fun startAppDetail(context: Context, packageName: String): Pair<Boolean, String> {
        val r = ready ?: return false to "后台屏没准备好"
        val app = context.applicationContext
        val front = foregroundPackage(app)
        val duplicate = front == packageName
        if (duplicate) {
            // 以前这里直接拒绝，导致「用户主屏正开着这个 App」时整个任务第一步就失败，
            // 后面全跑到主屏上用坐标乱试。效率优先：照样另开一份，只是提醒一句。
            ServerProcessManager.appLog("虚拟屏：$packageName 正在主屏前台，仍另开一份到后台屏")
        }
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false to "找不到 $packageName 的启动入口"
        val component = intent.component ?: return false to "找不到 $packageName 的启动入口"
        // MULTIPLE_TASK：App 已经在别的屏上跑着时，不加这个系统会把 intent 交给那个实例
        // （真机上提示 "delivered to currently running top-most instance"），这块屏就一直是空的。
        //
        // 也试过 Mika 那条正式接口（startActivityAsUser + setLaunchDisplayId），
        // 但这台 MIUI 以 START_BACKGROUND_ACTIVITY_NOT_ALLOWED(-94) 拒绝，即使声明了允许后台启动。
        val (ok, out) = adbFast(
            app,
            "shell am start --activity-multiple-task --display ${r.displayId} " +
                    "-n ${component.flattenToShortString()}", 30_000
        )
        if (ok) {
            lastPackage = packageName
            appStuckOnMain = false
            ServerProcessManager.appLog("虚拟屏：已把 $packageName 开到屏 ${r.displayId}")
            return true to if (duplicate) {
                "已打开 $packageName（主屏那份也在，这是另开的一份实例；操作的是后台这份）"
            } else {
                "已打开 $packageName"
            }
        }
        ServerProcessManager.appLog("虚拟屏：启动 $packageName 失败：${out.take(120)}")
        return false to "打开 $packageName 失败：${out.take(100)}"
    }

    /** 「打开 App」的完整动作：拒绝双实例 + 打开后等窗口并附上元素列表 */
    suspend fun openApp(context: Context, packageName: String): String {
        val (ok, why) = startAppDetail(context, packageName)
        if (!ok) return why
        val screen = readScreenText(context).orEmpty()
        return "$why\n当前屏幕：\n$screen"
    }

    /**
     * 最新画面（JPEG 原字节）。取不到返回 null。
     *
     * 走 base64 文本回传：adb 通道是文本的，二进制直接读会被截断或损坏。
     */
    suspend fun shot(context: Context): ByteArray? {
        val r = ready ?: return null
        val app = context.applicationContext
        // 用 adb pull 把图落进容器目录，App 直接读文件。
        // 之前是 `base64` 当文本回传：一张 128KB 的截图 base64 有 174KB，会被持久 shell 的
        // 输出上限截断，解出来是半张图（上半截还是空白），OCR 自然一个字都认不出。
        val local = File(LinuxRuntime.rootfsDir(app), "root/.agent-screen.jpg")
        runCatching { local.delete() }
        val (ok, out) = adbFast(app, "pull ${shotPath(r.displayId)} /root/.agent-screen.jpg", 30_000)
        if (!ok) {
            ServerProcessManager.appLog("虚拟屏：取画面失败 ${out.take(120)}")
            return null
        }
        return runCatching { local.readBytes() }.getOrNull()
    }

    /** 执行一条注入指令（tap/swipe/key/text），返回给模型看的结果 */
    suspend fun runInjected(context: Context, cmd: String): String {
        val app = context.applicationContext
        val parts = cmd.trim().split(Regex("""\s+"""))
        fun num(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: -1
        return when (parts[0]) {
            "tap" -> if (tap(app, num(1), num(2))) "已点击 (${num(1)},${num(2)})" else "点击失败"
            "swipe" -> if (swipe(app, num(1), num(2), num(3), num(4), num(5).takeIf { it > 0 } ?: 300))
                "已滑动 (${num(1)},${num(2)}) → (${num(3)},${num(4)})" else "滑动失败"
            "key" -> pressKey(app, cmd.removePrefix("key ").trim())
            "text" -> inputText(app, cmd.removePrefix("text ").trim())
            else -> "错误：不支持的输入指令「$cmd」"
        }
    }

    suspend fun tap(context: Context, x: Int, y: Int): Boolean =
        inject(context.applicationContext, "tap $x $y")

    suspend fun longPress(context: Context, x: Int, y: Int, ms: Int = 800): Boolean =
        inject(context.applicationContext, "longpress $x $y ${ms.coerceIn(300, 5000)}")

    suspend fun doubleTap(context: Context, x: Int, y: Int): Boolean =
        inject(context.applicationContext, "doubletap $x $y")

    suspend fun longPressByRef(context: Context, ref: String): String =
        A11yBridge.longClickRef(ready?.displayId ?: -1, ref)

    /** 滚动一屏；无控件可滚时返回 null，让调用方用手势兜底 */
    suspend fun scrollByRef(context: Context, ref: String?, forward: Boolean): String? =
        A11yBridge.scrollRef(ready?.displayId ?: -1, ref, forward)

    suspend fun swipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 300): Boolean =
        inject(context.applicationContext, "swipe $x1 $y1 $x2 $y2 ${ms.coerceIn(50, 3000)}")

    suspend fun key(context: Context, name: String): Boolean =
        inject(context.applicationContext, "key $name")

    /**
     * 让 helper 自己注入输入事件（`setDisplayId` + `injectInputEvent`）。
     *
     * 不能再用 `adb shell input -d <屏号>`：实测在虚拟屏上这些事件会被丢掉
     * （那块屏没有焦点窗口），界面毫无反应，模型就以为没点中、反复重试几十轮。
     */
    private suspend fun inject(app: Context, cmd: String, timeoutMs: Long = 5_000): Boolean {
        val r = ready ?: return false
        val out = outPath(r.displayId)
        adbFast(app, "shell \"rm -f $out; echo '$cmd' > ${cmdPath(r.displayId)}\"", 12_000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            val (_, text) = adbFast(app, "shell cat $out 2>/dev/null", 10_000)
            val line = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: continue
            val ok = line == "ok"
            if (!ok) ServerProcessManager.appLog("虚拟屏：注入失败「$cmd」→ $line")
            return ok
        }
        ServerProcessManager.appLog("虚拟屏：注入超时「$cmd」")
        return false
    }

    /**
     * 把 adb 的界面命令改到后台屏上执行：`input` 加 `-d`，`am start` 加 `--display`。
     *
     * 模型手里的 adb 工具默认落在主屏（`shell input tap x y` 就是点用户眼前那一屏）。
     * 用户要的是"一路后台"，所以这类命令在后台屏就绪时一律改投，不再打扰主屏。
     * 返回改写后的参数；不是界面命令、或已经指定了屏号，返回 null（按原样执行）。
     *
     * 两个真机踩过的口子：
     * - `shell monkey -p 包名 …` 启动 App——monkey 没有指定屏的参数，必须整条换成 `am start --display`，
     *   不然小红书就是这样被开到了主屏；
     * - 用 `;` 连起来的复合命令，只改第一条等于没改，剩下的照样落主屏。
     */
    fun reroute(args: String, displayId: Int): String? {
        // 先按分号拆：整串交给 rerouteOne 只会改到第一条，后面的照样落主屏（单测钉住的）
        val parts = args.split(";")
        if (parts.size > 1) {
            val mapped = parts.map { rerouteOne(it, displayId) ?: it }
            return if (mapped == parts) null else mapped.joinToString("; ")
        }
        return rerouteOne(args, displayId)
    }

    private fun rerouteOne(raw: String, displayId: Int): String? {
        val a = raw.trim()
        if (a.isEmpty()) return null
        // monkey 启动 App：换成语义等价的 am start，才能指定屏号
        val monkey = Regex("""^shell\s+monkey\b.*?-p\s+([A-Za-z0-9_.]+)""").find(a)
        if (monkey != null) {
            return "shell am start --activity-multiple-task --display $displayId " +
                    "-a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER -p ${monkey.groupValues[1]}"
        }
        // am start：既要保证屏号是本屏，也要带 --activity-multiple-task。
        // 少了后者，App 已在别处跑着时系统会把 intent 交给旧实例，
        // 这块屏上依然什么都没有（真机上日志：改投到屏 51、读屏报「没有该屏的窗口」）
        val amStart = Regex("""^(shell\s+am\s+start(?:-activity)?)\s+(.*)$""").find(a)
        if (amStart != null) {
            var rest = amStart.groupValues[2]
            val disp = Regex("""--display[=\s]+(\d+)""").find(rest)
            if (disp == null) {
                rest = "--display $displayId $rest"
            } else if (disp.groupValues[1] != displayId.toString()) {
                rest = rest.replaceRange(disp.range, "--display $displayId")
            }
            if (!rest.contains("--activity-multiple-task")) {
                rest = "--activity-multiple-task $rest"
            }
            val rebuilt = amStart.groupValues[1] + " " + rest
            return if (rebuilt == a) null else rebuilt
        }
        if (Regex("""^shell\s+input\s""").containsMatchIn(a) &&
            !Regex("""\s-d\s""").containsMatchIn(a)
        ) {
            return a.replaceFirst(Regex("""^shell\s+input\s"""), "shell input -d $displayId ")
        }
        if (Regex("""^shell\s+am\s+start(-activity)?\s""").containsMatchIn(a) && !a.contains("--display")) {
            return a.replaceFirst(
                Regex("""^shell\s+am\s+start(-activity)?\s"""),
                "shell am start --display $displayId "
            )
        }
        return null
    }

    /** 这条命令是不是要动界面（用来决定"要不要先把后台屏建起来"） */
    fun touchesScreen(args: String): Boolean {
        val a = args
        return Regex("""\bshell\s+input\s""").containsMatchIn(a) ||
                Regex("""\bshell\s+am\s+start""").containsMatchIn(a) ||
                Regex("""\bshell\s+monkey\b""").containsMatchIn(a)
    }

    /**
     * 把 `shell input …` 翻成注入指令。
     *
     * `input -d 屏号 tap …` 在虚拟屏上事件会被丢掉（真机上模型反复发这条、界面一动不动）。
     * 所以模型不管是自己写了 `-d` 还是没写，这里统一截下来交给 helper 注入。
     * 返回 null 表示这不是 input 命令。
     */
    fun parseInputCommand(args: String): String? {
        val m = Regex(
            """^shell\s+input\s+(?:-d\s+\d+\s+)?(tap|swipe|text|keyevent)\s*(.*)$""",
            RegexOption.IGNORE_CASE
        ).find(args.trim()) ?: return null
        val kind = m.groupValues[1].lowercase()
        val rest = m.groupValues[2].trim()
        return when (kind) {
            "tap" -> {
                val p = rest.split(Regex("""\s+"""))
                if (p.size < 2) null else "tap ${p[0].toIntOrNull() ?: -1} ${p[1].toIntOrNull() ?: -1}"
            }
            "swipe" -> {
                val p = rest.split(Regex("""\s+"""))
                if (p.size < 4) null else "swipe ${p[0]} ${p[1]} ${p[2]} ${p[3]}" +
                        (p.getOrNull(4)?.let { " $it" } ?: "")
            }
            "keyevent" -> "key ${rest.substringAfterLast(' ').ifBlank { rest }}"
            "text" -> "text $rest"
            else -> null
        }
    }

    /**
     * 后台屏上做不到、而且一旦执行就会把用户主屏切走的命令。
     *
     * HOME 和「最近任务」是**系统界面**，它们绑在主屏上：即使按键带了 displayId，
     * 系统也会把 launcher / recents 拉到主屏（真机上模型一键就滑到了最近任务界面）。
     * 这种没有折中办法，只能拦下来并告诉模型换一条路。
     */
    fun blockedOnVirtual(args: String): String? {
        val key = Regex(
            """\bkeyevent\b\s+(KEYCODE_)?(HOME|APP_SWITCH|RECENTS)\b""",
            RegexOption.IGNORE_CASE
        )
        val numeric = Regex("""\bkeyevent\b\s+(3|187)\b""")
        val category = Regex("""category\.(HOME|RECENTS)""", RegexOption.IGNORE_CASE)
        if (key.containsMatchIn(args) || numeric.containsMatchIn(args) || category.containsMatchIn(args)) {
            return "错误：HOME / 最近任务属于系统界面，绑在用户主屏上——后台屏上做不到，" +
                    "执行了会把你眼前的屏幕切走，已拦下。\n" +
                    "要退回上一页用 screen 的 key=back；要换 App 就重新打开目标 App。"
        }
        return null
    }

    /**
     * 读屏：**优先无障碍节点树**（真实文本、可点元素、资源 ID），读不到再退回截图 + OCR。
     *
     * 无障碍这条路是 Mika 的做法。实测我们的虚拟屏上窗口是可见的
     * （`dumpsys accessibility` 里 `displayId=38, focused=true`），
     * 之前我在空屏状态下测过一次就否掉了整条路，那是错的。
     */
    /**
     * 本地小模型模式：读屏只给 40 条、并把输入框/按钮排前面。
     * 由 ChatViewModel 每轮按「本地还是云端」设置——**云端不要开这个**，
     * 它更擅长读完整的、保持界面原始顺序的元素列表。
     */
    @Volatile
    var compactReads: Boolean = false

    /** 这块屏还在系统里吗（缓存里的编号可能已经被收掉了） */
    private suspend fun displayExists(app: Context, id: Int): Boolean {
        val (_, out) = adbFast(app, "shell \"cmd display get-displays | grep -c 'displayId $id'\"", 15_000)
        return out.trim().toIntOrNull()?.let { it > 0 } ?: true
    }

    /**
     * 读屏时发现这块屏上没有窗口时的补救：
     * 屏已经不存在就重建；屏还在但 App 没跟过来，就把上次那个 App 重新开上来。
     */
    /**
     * 目标 App 正开在用户主屏上：这种时候后台屏**打不开它**（系统会把 intent 交给前台实例），
     * 反复重开只会变成死循环（真机日志里刷了一整屏）。此时如实告诉模型改走别的路。
     */
    @Volatile
    private var appStuckOnMain = false

    private suspend fun recoverScreen(app: Context) {
        val r = ready ?: return
        if (!displayExists(app, r.displayId)) {
            ServerProcessManager.appLog("虚拟屏：屏 ${r.displayId} 已经不存在了，重新建一块")
            ready = null
            lastDisplayId = 0
            val again = runCatching { ensure(app) }.getOrNull() ?: return
            runCatching { lastPackage?.let { startAppDetail(app, it) } }
            delay(1_200)
            ServerProcessManager.appLog("虚拟屏：已重建为屏 ${again.displayId}")
            return
        }
        val pkg = lastPackage ?: foregroundPackage(app)
        if (pkg != null && foregroundPackage(app) == pkg) {
            appStuckOnMain = true
            ServerProcessManager.appLog("虚拟屏：$pkg 已在主屏前台，后台屏打不开它；改走主屏/adb")
            return
        }
        if (pkg != null && pkg != app.packageName) {
            ServerProcessManager.appLog("虚拟屏：屏上没有 App 的窗口，把 $pkg 重新开上来")
            startAppDetail(app, pkg)
            delay(1_200)
        }
    }

    /** 上一次开在这块屏上的 App。
     *
     * 用处：屏会被闲置收掉再重建（屏号随之变化），而 App 还挂在旧屏上没跟过来——
     * 这时读屏只会得到「没有该屏的窗口」，模型对着一块空屏反复读、反复点。
     * 记下它是为了在发现空屏时自动重新开上来。
     */
    @Volatile
    private var lastPackage: String? = null

    suspend fun readScreenText(context: Context): String? {
        val r = ready ?: return null
        if (appStuckOnMain) {
            return "（这个 App 正开在用户的主屏上，后台屏打不开第二份。" +
                    "别再用 open 重试——直接告诉用户：要么先把这个 App 从后台清掉，" +
                    "要么这一屏就用 adb 命令处理（不需要界面的活都能干））"
        }
        var triedReopen = false
        // 给窗口一点时间挂上来：刚打开 App 时节点树还是空的，
        // 立刻退 OCR 就会让后面所有点击都退化回"猜坐标"（真机上就是这么绕的）
        repeat(4) { attempt ->
            val a11y = runCatching {
                A11yBridge.treeOnDisplay(
                    r.displayId,
                    limit = if (compactReads) 40 else 80,
                    prioritizeInteractive = compactReads
                )
            }.getOrNull()
            if (a11y != null && !a11y.startsWith("（") && !a11y.startsWith("错误")) return a11y
            // 无障碍看不到这块屏的窗口（有的系统不把它算进 interactive windows）。
            // 这时别再重试四次耗时间：画面可能是好的，直接走截图 OCR。
            // 注意判断要用**返回文案**里的字（以前用了日志文案里的「没有该屏的窗口」，
            // 永远匹配不上，所以下面这段自动恢复从没执行过）。
            if (a11y != null && a11y.contains("没有可读的窗口")) {
                if (!triedReopen) {
                    triedReopen = true
                    recoverScreen(context.applicationContext)
                }
                return readScreen(context)?.let { describeLines(it, 40) }?.also {
                    ServerProcessManager.appLog("读屏（屏 ${r.displayId}）：无障碍看不到窗口，改用截图 OCR")
                }
            }
            if (attempt < 3) delay(500)
        }
        val ocr = readScreen(context)?.let { describeLines(it, 40) }
        if (ocr == null) {
            // 无障碍和 OCR 都没拿到东西：把当时的画面留下来，否则只能靠猜
            val saved = runCatching { saveShot(context) }.getOrNull()
            ServerProcessManager.appLog(
                "读屏（屏 ${r.displayId}）：无障碍没读到、OCR 也没有内容" +
                        (if (saved != null) "；当时的画面已存到 $saved" else "；截图也没取到")
            )
        }
        return ocr ?: "（这块屏目前读不到内容：无障碍没有可读元素、截图里也没有文字。" +
                "如果目标是某个 App，先 open 重新打开它一次；还不行就 back 退回上一页再看）"
    }

    /**
     * 一直往下滚，直到屏幕上出现这段文字（最多 [maxScreens] 屏）。
     *
     * 长列表里翻找的活交给代码做，比让模型「滑一屏 → 读一次 → 再滑」省掉一半轮次。
     */
    suspend fun scrollToText(context: Context, target: String, maxScreens: Int = 6): String {
        val app = context.applicationContext
        if (target.isBlank()) return "错误：scroll_to 需要要查找的文字"
        repeat(maxScreens) { i ->
            val screen = readScreenText(app).orEmpty()
            if (screen.contains(target, ignoreCase = true)) {
                return if (i == 0) "「$target」已经在当前屏幕上"
                else "往下滚了 $i 屏，看到「$target」。当前屏幕：\n$screen"
            }
            val moved = scrollByRef(app, null, forward = true)
                ?: (if (swipe(app, 540, 1900, 540, 700, 300)) "已滚动" else null)
                ?: return "滚动失败：这块界面没有可滚动的地方"
            if (moved.startsWith("滚动失败")) return moved
            delay(450)
        }
        val finalScreen = readScreenText(app).orEmpty()
        return if (finalScreen.contains(target, ignoreCase = true)) {
            "往下滚了 $maxScreens 屏，看到「$target」"
        } else {
            "往下滚了 $maxScreens 屏，没有找到「$target」。当前屏幕：\n$finalScreen"
        }
    }

    /** 已安装应用的「显示名 → 包名」缓存：小模型记不住包名，说「小红书」更自然 */
    @Volatile
    private var labelCache: Map<String, String>? = null

    private suspend fun packageByLabel(app: Context, name: String): String? {
        if (name.contains('.')) return name
        labelCache?.let { return it[name] }
        return withContext(Dispatchers.IO) {
            val pm = app.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val map = HashMap<String, String>()
            runCatching {
                pm.queryIntentActivities(intent, 0).forEach { ri ->
                    val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: return@forEach
                    map[label] = ri.activityInfo.packageName
                    map.putIfAbsent(label.lowercase(), ri.activityInfo.packageName)
                }
            }
            labelCache = map
            map[name]
        }
    }

    /**
     * 极简指令通道：整个命令就是**一个字符串**。
     *
     * 为什么需要它：本地 4B/9B 模型连「打开小红书」都做不到——不是能力不够，是 `screen` 工具
     * 那套「枚举 + 五六个字段 + 嵌套数组」它拼不出来（真机上它只会用单参数的 adb 工具）。
     * 这个入口只有一个字符串，和 adb 一样好学：
     *
     * `tree` / `open 小红书` / `click 3` / `click 搜索` / `type 香氛机` /
     * `swipe 540 1800 540 600` / `scroll` / `scroll_to 文字` / `key back` / `long 3`
     */
    /**
     * 极简指令通道的入口：执行完**会等界面反应并把新屏幕附回去**。
     *
     * 这一步以前漏了：模型点完"搜索"立刻去 type，而搜索页还没出来，于是报"没有找到输入框"，
     * 然后它开始 tree / swipe 乱试（真机上就是这么绕的）。结构化那版一直有等待，简版没有。
     */
    suspend fun runCommand(context: Context, raw: String): String {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return "错误：指令为空"
        val lower = cmd.lowercase()
        val r = ready
        // 只读命令不需要等待和回传
        val readOnly = lower == "tree" || lower == "读屏" || lower == "看屏幕" ||
                lower.startsWith("scroll_to") || lower.startsWith("open ")
        val before = if (!readOnly && r != null) frameSeq(context.applicationContext, r.displayId) else -1
        val result = runCommandInner(context, raw)
        if (readOnly || r == null || result.startsWith("错误")) return result
        if (ready == null) return result // 建屏/重建过，屏幕内容由 Inner 自己带了
        // 等界面反应（最多 2.5 秒），再把新屏幕一起给它
        val changed = awaitFrameChange(context.applicationContext, r.displayId, before, 2_500)
        val screen = readScreenText(context).orEmpty()
        return result +
                (if (!changed) "\n（界面在 2.5 秒内没有明显变化）" else "") +
                "\n当前屏幕：\n" + screen
    }

    private suspend fun runCommandInner(context: Context, raw: String): String {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return "错误：指令为空"
        val app = context.applicationContext
        // 简版入口以前假设屏已经建好，于是第一句 `open ...` 就撞上「后台屏没准备好」直接结束。
        // 现在这里先自己把屏建起来（最多 15 秒），建不起来再说。
        if (ready == null) {
            val built = withTimeoutOrNull(15_000) { ensure(context) }
            if (built == null) {
                val why = lastError ?: "建屏超时"
                return "错误：后台屏没建起来（$why）。\n" +
                        "改用 adb 命令试试（不需要界面的活都能干），或者把这个情况告诉用户：需要检查无线调试是否连着。"
            }
        }
        val lower = cmd.lowercase()
        val rest = { prefix: Int -> cmd.substring(prefix).trim() }
        return when {
            lower == "tree" || lower == "读屏" || lower == "看屏幕" ->
                readScreenText(app) ?: "拿不到画面（后台屏可能没建起来）"

            lower.startsWith("open ") -> {
                val name = rest(5)
                val pkg = packageByLabel(app, name)
                    ?: return "没找到叫「$name」的 App（也可以直接给包名，如 com.xingin.xhs）"
                openApp(app, pkg)
            }

            lower.startsWith("click ") -> {
                val target = rest(6)
                // 模型会把 OCR 认到的坐标直接拿来点：`click 1057,339`
                val coord = Regex("""^(\d{1,5})\s*[,， ]\s*(\d{1,5})$""").find(target)
                when {
                    coord != null -> {
                        val cxx = coord.groupValues[1].toInt()
                        val cyy = coord.groupValues[2].toInt()
                        if (tap(app, cxx, cyy)) "已点击 ($cxx,$cyy)" else "点击失败 ($cxx,$cyy)"
                    }
                    Regex("""^[@#]?[eE]?第?\d+个?$""").matches(target) -> clickByRef(app, target)
                    else -> clickText(app, target)
                }
            }

            lower.startsWith("type ") -> typeIntoBest(app, rest(5))

            // 搜索一条龙：找搜索框 → 点开 → 写进去
            lower.startsWith("search ") -> searchApp(app, rest(7))

            lower.startsWith("swipe ") -> {
                val n = rest(6).split(Regex("""[\s,]+""")).mapNotNull { it.toIntOrNull() }
                if (n.size < 4) "错误：swipe 要写成 `swipe 540 1800 540 600`"
                else if (swipe(app, n[0], n[1], n[2], n[3], n.getOrNull(4) ?: 300)) "已滑动"
                else "滑动失败"
            }

            lower == "scroll" || lower == "down" -> {
                scrollByRef(app, null, forward = true)
                    ?: if (swipe(app, 540, 1900, 540, 700, 300)) "已往下滚一屏" else "滚动失败"
            }

            lower == "up" -> {
                scrollByRef(app, null, forward = false)
                    ?: if (swipe(app, 540, 700, 540, 1900, 300)) "已往上滚一屏" else "滚动失败"
            }

            lower.startsWith("scroll_to ") -> scrollToText(app, rest(10))

            lower.startsWith("key ") -> pressKey(app, rest(4))

            lower.startsWith("long ") -> {
                val target = rest(5)
                if (Regex("""^[@#]?[eE]?第?\d+个?$""").matches(target)) longPressByRef(app, target)
                else "错误：长按请写编号，如 `long 3`"
            }

            else -> "错误：不认识的指令「$cmd」。可用：tree / open 包名 / click 编号或文字 / " +
                    "type 文字 / search 关键词 / swipe x y x2 y2 / scroll / up / scroll_to 文字 / " +
                    "key back / long 编号"
        }
    }

    /** 按编号点击（@e3）：直接对节点执行，不用坐标 */
    /**
     * 主屏上用 uiautomator dump 定位控件。
     *
     * 后台屏上无障碍只能靠截图 + OCR（这台系统上无障碍服务不包含虚拟屏的窗口，实测），
     * 而 uiautomator 能拿到完整控件 XML：resource-id、class、精确 bounds——找搜索框、
     * 输入框这类控件时它最准。代价是操作的是主屏（会切走用户的屏幕）。
     *
     * 返回匹配控件（text / resource-id / class 命中）的中心坐标。
     */
    suspend fun uiFindOnMain(app: Context, word: String): Pair<Int, Int>? {
        adbFast(app, "shell uiautomator dump /sdcard/_ui.xml", 40_000)
        val (_, xml) = adbFast(app, "shell cat /sdcard/_ui.xml", 25_000)
        val nodes = Regex("""<node[^>]*?>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        var fallback: Pair<Int, Int>? = null
        for (n in nodes) {
            val attr = n.value
            val text = Regex("""text="([^"]*)"""").find(attr)?.groupValues?.get(1).orEmpty()
            val rid = Regex("""resource-id="([^"]*)"""").find(attr)?.groupValues?.get(1).orEmpty()
            val cls = Regex("""class="([^"]*)"""").find(attr)?.groupValues?.get(1).orEmpty()
            val bounds = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(attr) ?: continue
            val l = bounds.groupValues[1].toInt()
            val tp = bounds.groupValues[2].toInt()
            val r2 = bounds.groupValues[3].toInt()
            val b = bounds.groupValues[4].toInt()
            val cx = (l + r2) / 2
            val cy = (tp + b) / 2
            val isInput = cls.contains("EditText") || rid.contains("search", true) ||
                    cls.contains("search", true)
            val textHit = word.isNotBlank() && text.contains(word, true)
            if (isInput && text.isBlank()) {
                // 搜索框/输入框：没有更具体的就先记着，继续找精确匹配
                if (fallback == null) fallback = cx to cy
            }
            if (textHit || (isInput && (word.isBlank() || rid.contains("search", true)))) {
                return cx to cy
            }
        }
        return fallback
    }
    @Volatile
    private var lastOcrLines: List<OcrEngine.Line> = emptyList()

    /**
     * 按编号点击：先试无障碍（对节点执行，最准），无障碍没有快照就回落到 OCR 坐标。
     *
     * 这一步是必须的：有的屏无障碍列不出窗口（真机日志里常见），此时 tree 给的是 OCR 行，
     * 如果点击还只查无障碍编号表，`click 3` 必然失败——模型就会开始乱试。
     */
    suspend fun clickByRef(context: Context, ref: String): String {
        val displayId = ready?.displayId ?: -1
        val byA11y = A11yBridge.clickRef(displayId, ref)
        val a11yFailed = byA11y.contains("没找到") || byA11y.contains("没有 @e") ||
                byA11y.contains("错误")
        if (!a11yFailed) return byA11y
        val idx = Regex("""\d+""").find(ref)?.value?.toIntOrNull() ?: return byA11y
        val line = lastOcrLines.getOrNull(idx - 1) ?: return byA11y
        return if (tap(context.applicationContext, line.cx, line.cy)) {
            "已点击「${line.text}」（OCR 坐标 ${line.cx},${line.cy}）"
        } else {
            "点击「${line.text}」失败（OCR 坐标 ${line.cx},${line.cy}）"
        }
    }

    /** 按编号往输入框写文字 */
    suspend fun typeIntoRef(context: Context, ref: String, text: String): String =
        A11yBridge.setTextOnRef(ready?.displayId ?: -1, ref, text)

    /**
     * 读屏：截图 + 本地 OCR，得到「屏幕上写了什么、各自在哪」。
     *
     * 无障碍读不到时的兜底（比如界面全由图片构成、节点树里没有文字）。
     */
    suspend fun readScreen(context: Context): List<OcrEngine.Line>? {
        if (ready == null) return null
        val jpg = shot(context)
        if (jpg == null) {
            ServerProcessManager.appLog("虚拟屏：读屏失败——截图取不回来（pull 或读容器文件失败）")
            return null
        }
        return runCatching { OcrEngine.recognizeLines(jpg) }
            .onSuccess { lastOcrLines = it }
            .onFailure {
                ServerProcessManager.appLog(
                    "虚拟屏：读屏失败——OCR 出错：${it.message ?: it.javaClass.simpleName}" +
                            "（图 ${jpg.size / 1024}KB）"
                )
            }
            .getOrNull()
    }

    /**
     * 等界面达到预期：等某段文字出现（[expect]）或消失（[expectGone]）。
     *
     * 比固定 sleep 靠谱：界面加载快就立刻往下走，慢就多等一会儿，不用模型再问一次屏。
     * 这是 Mika 那套 `expect` 等待条件的最小版本。
     */
    private suspend fun awaitText(
        app: Context,
        expect: String?,
        expectGone: String?,
        timeoutMs: Long
    ): Boolean {
        if (expect.isNullOrBlank() && expectGone.isNullOrBlank()) return true
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500, 20_000)
        while (System.currentTimeMillis() < deadline) {
            val screen = readScreenText(app).orEmpty()
            if (!expect.isNullOrBlank() && screen.contains(expect, ignoreCase = true)) return true
            if (!expectGone.isNullOrBlank() && !screen.contains(expectGone, ignoreCase = true)) return true
            delay(300)
        }
        return false
    }

    /** 连续多少步界面都没有变化——用来发现"走不通了还在硬走" */
    @Volatile
    private var noProgress = 0

    /** 最近做过的动作签名，用来发现「原地打转」 */
    private val recentSig = ArrayDeque<String>()

    /** 记一次动作，返回它此前已经出现过几次（同一动作重复太多次就该叫停） */
    private fun noteAction(sig: String): Int {
        val times = recentSig.count { it == sig }
        recentSig.addLast(sig)
        while (recentSig.size > 12) recentSig.removeFirst()
        return times
    }

    /** 执行一个界面动作（在后台屏上）；返回一句给模型看的结果 */
    suspend fun act(
        context: Context,
        action: String,
        text: String,
        x: Int,
        y: Int,
        x2: Int,
        y2: Int,
        dur: Int,
        withScreen: Boolean = false,
        expect: String? = null,
        expectGone: String? = null,
        timeoutMs: Long = 6_000
    ): String {
        val app = context.applicationContext
        val r = ready ?: return "错误：后台屏没准备好"
        // 守卫：同一个动作反复做还没有效果，就说明卡住了——模型打转几十轮就是这么来的
        if (action != "tree" && action != "shot") {
            val sig = "$action|${text.take(40)}|$x,$y,$x2,$y2"
            val times = noteAction(sig)
            if (times >= 3) {
                return "错误：同一个动作（$action ${text.take(20)}）已经连续做过 $times 次，界面没有按预期变化。" +
                        "别再重复了——先 tree 看当前有哪些元素编号，换个目标或先返回上一页；" +
                        "确实推进不了就如实告诉用户卡在哪一步。"
            }
        }
        val before = if (withScreen) frameSeq(app, r.displayId) else -1
        val res = when (action) {
            "tree" -> readScreenText(app) ?: "错误：拿不到后台屏画面"
            // 编号优先：@e3 直接对节点执行，界面动一动也不会点偏；纯文字才退回 OCR 定位
            "click" -> {
                val t = text.trim()
                if (Regex("""^@e\d+$""").matches(t)) clickByRef(app, t) else clickText(app, t)
            }
            "type" -> {
                val m = Regex("""^(@e\d+)\s+([\s\S]+)$""").find(text.trim())
                if (m == null) "错误：type 的 text 要写成「@e3 要输入的文字」"
                else typeIntoRef(app, m.groupValues[1], m.groupValues[2])
            }
            // 搜索一条龙：找搜索框 → 点开 → 写进去（比模型自己摸三四步快）
            "search" -> searchApp(app, text)
            // 不用挑编号的输入：自动找最像输入框的元素
            "type_any" -> typeIntoBest(app, text)
            // 长列表里翻找：滚动到出现某段文字为止
            "scroll_to" -> scrollToText(app, text.trim())
            // 长按：优先按编号（对节点执行），没给编号就按坐标按住
            "long_press" -> {
                val t = text.trim()
                if (Regex("""^@e\d+$""").matches(t)) longPressByRef(app, t)
                else if (x >= 0 && y >= 0) {
                    if (longPress(app, x, y, dur)) "已长按 ($x,$y)" else "长按失败 ($x,$y)"
                } else "错误：long_press 需要 @eN 或 x,y"
            }
            "double_tap" -> if (x >= 0 && y >= 0) {
                if (doubleTap(app, x, y)) "已双击 ($x,$y)" else "双击失败 ($x,$y)"
            } else "错误：double_tap 需要 x 和 y"
            // 滚动：优先滚可滚动控件（滚的是真列表），没有就用手势滑一屏
            "scroll" -> {
                val back = text.contains("up", true) || text.contains("back", true) ||
                        text.contains("上", true)
                val ref = Regex("""@e\d+""").find(text)?.value
                scrollByRef(app, ref, forward = !back)
                    ?: if (swipe(
                            app, 540, if (back) 800 else 1800,
                            540, if (back) 1800 else 800, 300
                        )
                    ) "已滚动（手势）" else "滚动失败"
            }
            "tap" -> if (x >= 0 && y >= 0) {
                if (tap(app, x, y)) "已点击 ($x,$y)" else "点击失败 ($x,$y)"
            } else "错误：tap 需要 x 和 y"
            "swipe" -> if (x >= 0 && y >= 0 && x2 >= 0 && y2 >= 0) {
                if (swipe(app, x, y, x2, y2, dur)) "已滑动 ${x},${y} → ${x2},${y2}" else "滑动失败"
            } else "错误：swipe 需要 x,y,x2,y2"
            "input" -> inputText(app, text)
            "key" -> pressKey(app, text)
            "open" -> {
                val pkg = text.trim()
                if (pkg.isEmpty()) "错误：open 需要在 text 里给包名" else openApp(app, pkg)
            }
            "shot" -> saveShot(app)?.let { "截图已保存：$it" } ?: "截图失败"
            else -> "错误：未知 action「$action」（可用 tree/click/tap/swipe/input/key/shot）"
        }
        if (!withScreen || action == "tree" || action == "shot") return res
        // 给了等待条件就先等它成立：界面加载快就立刻往下，慢也不会读到半截
        var waitNote = ""
        if (!expect.isNullOrBlank() || !expectGone.isNullOrBlank()) {
            val met = awaitText(app, expect, expectGone, timeoutMs)
            waitNote = if (met) "" else
                "\n（等了 ${timeoutMs / 1000} 秒，" +
                        (if (!expect.isNullOrBlank()) "没等到「$expect」" else "「$expectGone」还在") + "）"
        }
        // 动作之后等界面反应，再把新界面直接附回去。
        // 这一步是照着 Mika 的循环补的：它的执行入口在动作后固定核对效果并附上新界面，
        // 模型从来不用自己再问一次「现在屏幕上是什么」——我们以前每个动作都要多问一轮。
        val changed = if (waitNote.isEmpty()) awaitFrameChange(app, r.displayId, before) else true
        if (changed) noProgress = 0 else noProgress++
        // 连续几步界面一动不动，多半是这条路走不通了（Mika 的守卫也是这么判的）
        val stuckNote = if (noProgress >= 3) {
            "\n（已经连续 $noProgress 步界面没有变化，很可能这条走不通了。" +
                    "别继续重复：先 tree 看现在有哪些元素，换个目标或返回上一页；" +
                    "实在推进不了就如实告诉用户卡在哪一步。)"
        } else ""
        if (noProgress >= 5) {
            noProgress = 0
            return res + stuckNote + "\n（到上限了：连续 5 步没有进展，先停下让用户决定）"
        }
        val screenNow = readScreenText(app).orEmpty()
        return res + waitNote + (if (!changed) "\n（界面在 2.5 秒内没有变化）" else "") +
                stuckNote + "\n当前屏幕：\n" + screenNow
    }

    /** 当前画面序号；读不到返回 -1 */
    private suspend fun frameSeq(app: Context, id: Int): Int {
        val (_, out) = adbFast(app, "shell cat ${seqPath(id)}", 12_000)
        return out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toIntOrNull() ?: -1
    }

    /** 等画面更新：点了之后界面变了才算这一步真的生效，2.5 秒没变就如实说没变 */
    private suspend fun awaitFrameChange(app: Context, id: Int, before: Int, timeoutMs: Long = 2_500): Boolean {
        if (before < 0) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            val now = frameSeq(app, id)
            if (now > 0 && now != before) return true
        }
        return false
    }

    /**
     * 一次做多个动作。
     *
     * 为什么需要：一步一问太慢——读一次屏（截图 + OCR 两三秒）、问一次模型、再做一步，
     * 三十轮也就走了三十步，连「把消息列表翻一翻」都不够。这里让模型一轮给出若干动作，
     * 由代码按顺序执行、步间自动等界面反应，最后再把当前屏幕内容一并给它。
     *
     * 某一步失败就停：后面的坐标多半已经跟着失效，硬点只会越错越远。
     */
    suspend fun actMany(context: Context, steps: org.json.JSONArray, waitMs: Long = 350): String {
        val sb = StringBuilder()
        var done = 0
        for (i in 0 until steps.length()) {
            val o = steps.optJSONObject(i) ?: continue
            val action = o.optString("action").trim().lowercase()
            if (action.isEmpty()) continue
            val text = o.optString("text")
            val x = o.optInt("x", -1)
            val y = o.optInt("y", -1)
            val x2 = o.optInt("x2", -1)
            val y2 = o.optInt("y2", -1)
            val dur = o.optInt("duration", 300).coerceIn(20, 5000)
            val wait = o.optInt("wait", waitMs.toInt()).coerceIn(0, 8000)
            // 每步可以带等待条件：等这段文字出现（expect）或消失（expect_gone）
            val expect = o.optString("expect").takeIf { it.isNotBlank() }
            val expectGone = o.optString("expect_gone").takeIf { it.isNotBlank() }
            val condTimeout = o.optInt("timeout", 6).coerceIn(1, 20) * 1000L
            val r = if (expect != null || expectGone != null) {
                // 这里用 withScreen=true 走「等条件 + 附新界面」，步与步之间就不用固定 sleep 了
                act(
                    context, action, text, x, y, x2, y2, dur,
                    withScreen = true, expect = expect, expectGone = expectGone, timeoutMs = condTimeout
                )
            } else {
                act(context, action, text, x, y, x2, y2, dur)
            }
            sb.append(i + 1).append(". ").append(action)
            if (text.isNotBlank() && (action == "click" || action == "input")) {
                sb.append("（").append(text.take(20)).append("）")
            }
            sb.append(" → ").append(r.take(160)).append('\n')
            done = i + 1
            val failed = r.startsWith("错误") || r.startsWith("没找到") || r.endsWith("失败")
            if (failed) {
                val left = steps.length() - i - 1
                if (left > 0) sb.append("（这一步没成，后面 $left 步已跳过）\n")
                break
            }
            if (wait > 0) delay(wait.toLong())
        }
        // 把最后看到的屏幕一并给出：省掉模型再问一次 tree
        val lines = readScreen(context).orEmpty()
        sb.append("\n当前屏幕（共 ").append(done).append(" 步后的样子）：\n")
        sb.append(describeLines(lines, 30))
        return sb.toString().trim()
    }

    /** 把读屏结果排成给模型的清单（带坐标，按从上到下） */
    fun describeLines(lines: List<OcrEngine.Line>, limit: Int = 60): String {
        if (lines.isEmpty()) return "屏幕上没有识别到文字（可能是纯图片界面，可先 shot 看一眼）"
        // OCR 行也编号：和无障碍那套统一成 @e1 起，模型只学一套编号规则，
        // 点的时候按编号回落到这里的坐标（无障碍读不到的屏就靠这个）
        return lines.take(limit).mapIndexed { i, l ->
            "@e${i + 1} ${l.text}  @(${l.cx},${l.cy})"
        }.joinToString("\n") +
                if (lines.size > limit) "\n…（共 ${lines.size} 行，只列前 $limit 行）" else ""
    }

    /** 按文字点击：先精确匹配，再包含匹配 */
    suspend fun clickText(context: Context, text: String): String {
        val want = text.trim()
        if (want.isEmpty()) return "错误：click 需要 text"
        val lines = readScreen(context) ?: return "错误：拿不到后台屏画面（后台屏可能没建起来）"
        if (lines.isEmpty()) return "没找到可点的文字：屏幕上没有识别到文字"
        val hit = lines.firstOrNull { it.text == want }
            ?: lines.firstOrNull { it.text.contains(want, ignoreCase = true) }
            ?: return "屏幕上没有「$want」。当前可见：\n" + describeLines(lines, 15)
        val ok = tap(context, hit.cx, hit.cy)
        return if (ok) "已点击「${hit.text}」（${hit.cx},${hit.cy}）"
        else "点击「${hit.text}」失败（坐标 ${hit.cx},${hit.cy}）"
    }

    /**
     * 输入文字。
     *
     * 三条路，从稳到将就：
     * 1. 界面上有聚焦的输入框 → 无障碍直接设值（不碰用户剪贴板）；
     * 2. 否则把文字放进剪贴板，让 helper 注入 Ctrl+V 粘贴（中文只能这么打）；
     * 3. 粘贴完把用户原来的剪贴板内容还回去。
     *
     * 注意以前用的是 `adb shell input -d … text`，那条在虚拟屏上事件会被丢掉，等于没输入。
     */
    suspend fun inputText(context: Context, text: String): String {
        val r = ready ?: return "错误：后台屏没准备好"
        val app = context.applicationContext
        if (text.isEmpty()) return "错误：input 需要 text"

        A11yBridge.setTextOnFocused(r.displayId, text)?.let { return it }

        val cm = app.getSystemService(android.content.ClipboardManager::class.java)
        val old = runCatching { cm?.primaryClip }.getOrNull()
        val set = runCatching {
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("chaos-agent", text))
        }.isSuccess
        if (!set) return "输入失败：写不进剪贴板"
        delay(250)
        val ok = inject(app, "paste")
        delay(250)
        // 把用户原来的剪贴板还回去（后台读不到就只能清掉，不留在里面）
        runCatching {
            if (old != null) cm?.setPrimaryClip(old) else cm?.clearPrimaryClip()
        }
        return if (ok) "已输入「$text」（走剪贴板粘贴）" else "输入失败：粘贴没发出去（输入框可能没有焦点）"
    }

    /**
     * 不用挑编号：把文字写进"最像输入框"的那个元素（先点一下聚焦再写）。
     *
     * 给本地小模型的省事入口：它们常常挑不准编号，这一步由代码替它决定。
     */
    suspend fun typeIntoBest(context: Context, text: String): String {
        val r = ready ?: return "错误：后台屏没准备好"
        if (text.isBlank()) return "错误：type_any 需要要输入的文字"
        readScreenText(context) // 刷新一次快照
        val ref = A11yBridge.bestEditableRef(r.displayId)
        if (ref != null) {
            val clicked = A11yBridge.clickRef(r.displayId, ref)
            delay(350)
            A11yBridge.setTextOnFocused(r.displayId, text)?.let { return "$clicked → $it" }
            val typed = A11yBridge.setTextOnRef(r.displayId, ref, text)
            return "$clicked → $typed"
        }
        // 无障碍读不到输入框（很多 App 的首页就是这种）：用 OCR 找搜索入口点进去再写
        val lines = readScreen(context).orEmpty()
        val hit = lines.firstOrNull {
            val t = it.text
            t.contains("搜索") || t.contains("搜一搜") || t.contains("搜商品") || t.contains("查找")
        } ?: return "这块屏上没有找到输入框（无障碍没读到输入框，截图里也没有「搜索」字样）"
        tap(context, hit.cx, hit.cy)
        delay(1_000)
        readScreenText(context)
        A11yBridge.bestEditableRef(r.displayId)?.let { ref2 ->
            A11yBridge.clickRef(r.displayId, ref2)
            delay(250)
            A11yBridge.setTextOnFocused(r.displayId, text)
                ?.let { return "（OCR 找到入口 → $ref2）$it" }
            return "（OCR 找到入口 → $ref2）" + A11yBridge.setTextOnRef(r.displayId, ref2, text)
        }
        return "（OCR 找到「${hit.text}」并点开了）" + inputText(context, text)
    }

    /**
     * 「搜索某个词」一条龙：自己找搜索框 → 点开 → 把词写进去。
     *
     * 为什么做成一个动作：模型找搜索框经常要绕三四步（先找入口、点进去、再找输入框、
     * 再输入），而这几步完全由代码判断更快也更准——输入框优先挑 search 相关的 id，
     * 没有就直接找带「搜索」字样的入口先点进去再来一次。
     */
    suspend fun searchApp(context: Context, keyword: String): String {
        val r = ready ?: return "错误：后台屏没准备好（先 open 一个 App）"
        val k = keyword.trim()
        if (k.isEmpty()) return "错误：search 需要关键词"

        // 用户的真实流程是：点输入框 →（跳到搜索页）→ 输入 → 再点搜索。
        // 以前先找「搜索」字样去点，可很多 App 首页那个「搜索」就是输入框本身，
        // 一点就直接拿默认热搜词搜出去了，后面再输入全是错的。
        var ref = editableRef(context)
        if (ref == null) {
            // 无障碍读不到这一屏：用 OCR 找最像搜索框的那行（顶部、带「搜索」字样）点开
            val lines = readScreen(context).orEmpty()
            val hit = lines
                .filter {
                    val t = it.text
                    t.contains("搜索") || t.contains("搜一搜") || t.contains("搜商品")
                }
                .minByOrNull { it.cy }
            if (hit == null) {
                return "没找到搜索框。当前屏幕：\n" +
                        (readScreenText(context) ?: "（读不到内容）")
            }
            tap(context, hit.cx, hit.cy)
            delay(1_000)
            readScreenText(context)
            ref = editableRef(context)
        }

        if (ref != null) {
            A11yBridge.clickRef(r.displayId, ref) // 点开（可能跳转到搜索页）
            delay(450)
            readScreenText(context)
            ref = editableRef(context) ?: ref
            // 直接设值会**覆盖**已有内容（首页输入框常带默认热搜词，不清掉就会拼在一起）
            val typed = A11yBridge.setTextOnFocused(r.displayId, k)
                ?: A11yBridge.setTextOnRef(r.displayId, ref, k)
            if (typed.contains("已")) {
                delay(400)
                return typed + " → " + triggerSearch(context, r.displayId)
            }
            // 设值不行：清空后走剪贴板粘贴
            A11yBridge.clearRef(r.displayId, ref)
            delay(200)
            val pasted = inputText(context, k)
            delay(500)
            return pasted + " → " + triggerSearch(context, r.displayId)
        }

        // 完全找不到输入框：点开搜索入口后直接粘贴（此时输入框通常已聚焦）
        return inputText(context, k)
    }

    /** 当前屏上最像输入框的元素编号（无障碍快照） */
    private fun editableRef(context: Context): String? =
        A11yBridge.bestEditableRef(ready?.displayId ?: -1)

    /** 触发搜索：优先点「搜索」按钮，其次发回车 */
    private suspend fun triggerSearch(context: Context, displayId: Int): String {
        readScreenText(context)
        A11yBridge.findClickableRef(displayId, "搜索")?.let { ref ->
            val clicked = A11yBridge.clickRef(displayId, ref)
            if (clicked.startsWith("已点击")) return "已触发搜索（$clicked）"
        }
        return if (key(context, "KEYCODE_ENTER")) "已按回车触发搜索" else "没能触发搜索（没找到搜索按钮）"
    }

    /** 系统键；后端统一翻成 keyevent 名字 */
    suspend fun pressKey(context: Context, name: String): String {
        val raw = name.trim()
        val upper = raw.uppercase()
        // HOME / 最近任务属于系统界面，绑在主屏上：后台屏上做不到，执行了会切走用户的屏幕
        if (upper.contains("HOME") || upper.contains("APP_SWITCH") ||
            upper.contains("RECENTS") || raw == "3" || raw == "187"
        ) {
            return "错误：HOME / 最近任务属于系统界面、绑在用户主屏上，后台屏上做不到，" +
                    "执行了会切走用户的屏幕，已拦下。要退回上一页用 key=back；要换 App 就重新打开目标 App。"
        }
        val code = when (upper) {
            "BACK" -> "KEYCODE_BACK"
            "ENTER" -> "KEYCODE_ENTER"
            "DELETE" -> "KEYCODE_DEL"
            else -> raw
        }
        return if (key(context, code)) "已按下 $code" else "按键失败：$code"
    }

    /** 把最新画面存进 App 私有目录，返回路径（模型可以随后用 file_* 查看） */
    suspend fun saveShot(context: Context): String? {
        if (ready == null) return null
        val jpg = shot(context) ?: return null
        val dir = File(context.filesDir, "agent-screen").apply { mkdirs() }
        val f = File(dir, "screen-${System.currentTimeMillis()}.jpg")
        runCatching { f.writeBytes(jpg) }.getOrNull() ?: return null
        return f.absolutePath
    }

    // ---- 内部 ----

    /**
     * assets 里的 dex 跟设备上的一致就跳过，否则写过去。
     *
     * 比对用**文件大小**而不是 md5：md5sum 的输出还要再切一遍管道，之前就是在这一步
     * 静默失败（写成"dex 写入失败"却看不出原因）。dex 只有 8KB，大小一样就当同一份。
     */
    private suspend fun deploy(app: Context): Boolean {
        val bytes = runCatching {
            app.assets.open("agent-shell.dex").use { it.readBytes() }
        }.getOrNull() ?: run {
            ServerProcessManager.appLog("虚拟屏：assets 里没有 agent-shell.dex")
            return false
        }
        val want = bytes.size
        if (remoteFileSize(app, DEX_REMOTE) == want) return true

        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val writeOut = ps(app, "printf %s '$b64' | base64 -d > /root/agent-shell.dex", 90_000)
        if (writeOut.isNotBlank()) {
            ServerProcessManager.appLog("虚拟屏：解 dex 输出 ${writeOut.take(200)}")
        }
        // 复合命令不能塞进 runAdb（它只跑单条 adb 命令），所以文件先落在容器里再 push
        val (pushOk, pushOut) = adbFast(app, "push /root/agent-shell.dex $DEX_REMOTE", 90_000)
        ps(app, "rm -f /root/agent-shell.dex", 10_000)
        ServerProcessManager.appLog(
            "虚拟屏：push ${if (pushOk) "成功" else "失败"} ${pushOut.trim().take(160)}"
        )

        val got = remoteFileSize(app, DEX_REMOTE)
        if (got != want) {
            return false.also {
                fail("helper 写不进去（远端 $got 字节 / 本地 $want 字节）")
            }
        }
        // dex 更新过：正在跑的那个 helper 用的还是**旧代码**（进程里已经加载的类不会变），
        // 光把新 dex 推上去没用。杀掉它，让接下来这次 ensure 用新逻辑重建一块干净的屏。
        if (pushOk) killStaleHelper(app)
        return true
    }

    /** 杀掉遗留的 helper 进程（它是 shell 身份，重装 App 不会带走它） */
    private suspend fun killStaleHelper(app: Context) {
        val (_, out) = adbFast(app, "shell cat /data/local/tmp/localllm_agent.pid", 10_000)
        val pid = out.lineSequence().map { it.trim() }
            .lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return
        adbFast(app, "shell kill $pid", 10_000)
        adbFast(app, "shell rm -f /data/local/tmp/localllm_agent.pid", 10_000)
        ready = null
        lastDisplayId = 0
        ServerProcessManager.appLog("虚拟屏：dex 更新过，清掉遗留的 helper（pid $pid）")
    }

    /** 设备上文件的大小；不存在或读不到返回 -1 */
    private suspend fun remoteFileSize(app: Context, path: String): Int {
        // 重定向必须交给设备端的 shell：不加引号会被容器的 shell 抢先解析，
        // 容器里没有这个文件，整条命令直接失败（踩过一次，表现为"push 成功却校验不过"）
        val (_, out) = adbFast(app, "shell \"wc -c < $path\"")
        return out.trim().toIntOrNull() ?: -1
    }

    /**
     * 快通道：连接已经在 [ensure] 开头建立过，这里直接在容器的持久 shell 里跑 adb。
     *
     * 不能再用 [AdbBridge.runAdb]：它每次都要走一遍 ensureConnected（缓存端点 → 回环 → mDNS 6 秒
     * → 诊断），连接不稳时一次就要十几秒。轮询屏号十几次叠起来就是两三分钟，
     * 用户看到的就是「一直显示正在连接无线调试」。掉线时才补一次重连。
     */
    private suspend fun adbFast(
        app: Context,
        args: String,
        timeoutMs: Long = 20_000
    ): Pair<Boolean, String> {
        touch()
        var out = ps(app, "adb $args 2>&1", timeoutMs)
        if (looksDisconnected(out)) {
            val st = AdbBridge.ensureConnected(app, force = true)
            if (!st.connected) return false to st.message
            out = ps(app, "adb $args 2>&1", timeoutMs)
        }
        return (if (looksDisconnected(out)) false else true) to out
    }

    private fun looksDisconnected(out: String): Boolean =
        out.contains("no devices/emulators", true) ||
                out.contains("failed to connect", true) ||
                out.contains("device offline", true) ||
                out.contains("device not found", true) ||
                out.contains("connection refused", true)

    private suspend fun ps(app: Context, command: String, timeoutMs: Long): String =
        runCatching {
            PersistentShell.run(
                context = app, sid = SID, command = command,
                initialCwd = "/root", cdTo = null, timeoutMs = timeoutMs
            ).output
        }.getOrDefault("")

    private suspend fun start(app: Context): Boolean {
        // stdin 必须接到 /dev/null：命令一交出去 adb shell 会话就结束，pty 关闭后
        // 还挂在 pty 上的 app_process 会卡在初始化里（实测表现为日志只打出第一行就不动了）。
        // stop 文件由 helper 自己按屏号删除，这里不用管。
        val launch = "shell \"CLASSPATH=$DEX_REMOTE nohup app_process /system/bin " +
                "com.localllm.server.helper.AgentShell < /dev/null " +
                "> /data/local/tmp/localllm_agent.log 2>&1 &\""
        val (ok, out) = adbFast(app, launch, 40_000)
        if (!ok && out.contains("app_process", true)) {
            fail("这台设备没有 app_process，无法建后台屏")
            return false
        }
        return true
    }

    /**
     * 等 helper 把屏号写出来。
     *
     * 两点讲究：
     * 1. 冷启动到建屏本来就要 1–3 秒，先等 2.5 秒再查，别拿每秒一次的 adb 往返去磨；
     * 2. 屏号和尺寸**一次**读回来，少一半往返（走的是快通道，不再每次重连）。
     *
     * 解析也不能直接 `toIntOrNull()`：容器 adb 客户端会往输出里掺
     * `* daemon not running…` 这类提示行，整串转数字永远是 null（踩过）。
     */
    private suspend fun waitDisplay(app: Context): Int? {
        repeat(4) { attempt ->
            delay(if (attempt == 0) 2_500 else 1_500)
            // 两个文件之间必须自己补分隔：helper 写的屏号末尾没有换行时，
            // 一次 cat 两个文件会粘成 "191200x2608"，怎么解析都失败（踩过一次）
            val (_, out) = adbFast(app, "shell \"cat $DISPLAY_FILE; echo; cat $SIZE_FILE\"")
            val id = out.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.toIntOrNull()
            if (id != null && id > 0) {
                lastSize = readSizeFrom(out)
                return id
            }
        }
        // 起不来的话把 helper 自己的报错捞出来，省得每次都要手工 adb
        val log = readText(app, "/data/local/tmp/localllm_agent.log", 12_000)
        if (log.isNotBlank()) {
            ServerProcessManager.appLog("虚拟屏：helper 日志尾 ${log.trim().takeLast(400)}")
        }
        return null
    }

    @Volatile
    private var lastSize: Pair<Int, Int>? = null

    private fun readSizeFrom(text: String): Pair<Int, Int>? {
        val m = Regex("""(\d{3,5})\s*x\s*(\d{3,5})""").find(text) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        return w to h
    }

    /** helper 写下的屏幕尺寸 `1200x2608`；同样要绕开 adb 客户端的提示行 */
    private suspend fun readSize(app: Context): Pair<Int, Int> {
        lastSize?.let { return it }
        val text = readText(app, SIZE_FILE)
        return readSizeFrom(text) ?: (0 to 0)
    }

    private suspend fun readText(app: Context, path: String, timeoutMs: Long = 15_000): String {
        val (_, out) = adbFast(app, "shell cat $path", timeoutMs)
        return out
    }
}
