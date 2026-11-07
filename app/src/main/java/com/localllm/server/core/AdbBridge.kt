package com.localllm.server.core

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 无线调试（adb）的全局连接管理：**配对一次，之后自动连**。
 *
 * 为什么需要它：容器里装着一套 arm64 的 Android SDK 工具，`adb` 能免点击装 APK、截屏、
 * `input tap`、`uiautomator dump` —— 这是不 root、不用电脑就能拿到 shell 级权限的唯一通道。
 * 但无线调试的**端口每次开启都会变**，所以「上次配对好了、这次又连不上」。
 *
 * 三个坑各对应一个处理：
 *  1. 端口会变 → 用系统 [NsdManager] 做 mDNS 发现 `_adb-tls-connect._tcp`
 *     （容器里的 `adb mdns services` 拿不到广播，App 自己能，见 HANDOFF 的实测结论）。
 *  2. 授权会丢 → adb 私钥在容器 `/root/.android/adbkey`，rootfs 重新解压会清掉。
 *     连上后备份到 `files/android/adb/`，用前再补回去。
 *  3. 会话无关 → adb 跑在**独立的持久 shell**（固定 sid）里，切会话/开新会话都不受影响。
 */
object AdbBridge {

    /** adb 专用的持久 shell：与聊天会话解耦 */
    const val SID = "__adb__"

    data class Status(val connected: Boolean, val endpoint: String?, val message: String)

    // ---- 端点发现 ----

    /** 用系统的 mDNS 找无线调试端口；找不到返回 null */
    suspend fun discoverEndpoint(context: Context, timeoutMs: Long = 6000): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val nsd = context.getSystemService(NsdManager::class.java)
                    if (nsd == null) {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun finish(v: String?) {
                        if (done.compareAndSet(false, true)) cont.resume(v)
                    }
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            finish(null)
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onServiceLost(info: NsdServiceInfo) {}

                        override fun onServiceFound(info: NsdServiceInfo) {
                            if (done.get()) return
                            if (!info.serviceType.contains("_adb-tls-connect")) return
                            val rl = object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    val host = serviceInfo.host?.hostAddress ?: return
                                    if (serviceInfo.port <= 0) return
                                    finish("$host:${serviceInfo.port}")
                                }
                            }
                            runCatching { nsd.resolveService(info, rl) }
                        }
                    }
                    cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
                    runCatching {
                        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
                    }.onFailure { finish(null) }
                }
            }
        }

    // ---- 连接 ----

    /**
     * 确保连上。顺序据此设计（每一步失败才走下一步）：
     *  1. 缓存端点直接连（同一轮里最快）；
     *  2. **回环 127.0.0.1:缓存端口** —— adb 客户端和 adbd 在同一台设备上，
     *     所以不开 Wi-Fi、没有局域网也能连（adbd 绑 0.0.0.0 时有效）；
     *  3. mDNS 发现新端口（要求无线调试开着且 WLAN 在线）；
     *  4. 都不行就讲清楚**为什么**：Wi-Fi 关了 / 无线调试关了 / 需要重新配对。
     */
    suspend fun ensureConnected(context: Context, force: Boolean = false): Status {
        val app = context.applicationContext as? Application ?: return Status(false, null, "上下文无效")
        restoreKeyToRootfs(app)

        val cached = AppSettings.adbEndpoint
        val cachedPort = cached?.substringAfterLast(':')?.toIntOrNull()

        if (!force && !cached.isNullOrBlank()) {
            shell(app, "adb connect $cached 2>&1 | tail -1", 30_000)
            if (devicesOnline(app)) {
                backupKey(app)
                return Status(true, cached, "已连接（缓存端点 $cached）")
            }
        }
        // 回环兜底：无线调试的端口在「不重启无线调试」期间是不变的，
        // 所以 Wi-Fi 掉线时用 127.0.0.1 + 上次的端口往往还能连上
        if (!force && cachedPort != null) {
            val loop = "127.0.0.1:$cachedPort"
            shell(app, "adb connect $loop 2>&1 | tail -1", 30_000)
            if (devicesOnline(app)) {
                AppSettings.adbEndpoint = loop
                backupKey(app)
                return Status(true, loop, "已连接（回环 $loop，不需要 Wi-Fi）")
            }
        }

        val found = discoverEndpoint(app)
        if (found != null) {
            shell(app, "adb connect $found 2>&1 | tail -1", 30_000)
            val ok = devicesOnline(app)
            AppSettings.adbEndpoint = found
            if (ok) backupKey(app)
            if (ok) return Status(true, found, "已连接（mDNS 发现 $found）")
            return Status(
                false, found,
                "发现了 $found 但连不上：多半需要在手机上重新「使用配对码配对设备」一次" +
                        "（配对码一次性、端口每次开启都会变）"
            )
        }
        return Status(false, null, whyNotConnected(app))
    }

    /** 连不上时说人话：区分「没连 WLAN」「无线调试关了」「需要重新配对」 */
    private fun whyNotConnected(context: Context): String {
        // 关键区分：Wi-Fi 开关拨开 ≠ 无线调试可用。
        // adbd 的监听端口和 mDNS 广播都绑在 wlan0 的地址上，没拿到 IP 就等于没有端口。
        val wlanIp = wlanIpv4()
        val wirelessOn = runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver, "adb_wifi_enabled", 0
            ) == 1
        }.getOrDefault(false)
        return when {
            wlanIp == null ->
                "现在 WLAN 没有拿到 IP 地址，所以连不上。无线调试的端口是靠 wlan0 的地址监听和广播的——" +
                        "只是把 Wi-Fi 开关拨开不算，必须**连上某个网络**（可以是没外网的热点/路由器）。" +
                        "连上之后再试；不想开 Wi-Fi 的话，装应用可以走 install_apk（系统安装器，不需要 adb）。"
            !wirelessOn ->
                "「开发者选项 → 无线调试」现在是关的：打开它（一次就好），然后重试。" +
                        "端口每次开启都会变，App 会自动用 mDNS 找，不用你抄端口。"
            else ->
                "WLAN 有地址（$wlanIp）、无线调试也开着，但连不上：多半是这台手机把之前配对的密钥清掉了" +
                        "（重启无线调试 / 换了网络后常见）。请在无线调试里点「使用配对码配对设备」，" +
                        "把 6 位配对码和那个配对端口发我，配对一次之后就会长期有效。"
        }
    }

    /** wlan0 的 IPv4 地址；没有就是「没连上 WLAN」（只是开关打开不算） */
    private fun wlanIpv4(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .firstNotNullOfOrNull { ni ->
                ni.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }
    }.getOrNull()

    private suspend fun devicesOnline(app: Application): Boolean =
        shell(app, "adb devices", 20_000).lineSequence()
            .any { it.trim().endsWith("\tdevice") }

    // ---- 跑 adb 命令 ----

    /** 在容器里执行 `adb <args>`（自动确保连接）。返回 (成功?, 输出) */
    suspend fun runAdb(context: Context, args: String, timeoutMs: Long = 180_000): Pair<Boolean, String> {
        val app = context.applicationContext as? Application ?: return false to "上下文无效"
        val st = ensureConnected(app)
        if (!st.connected) return false to st.message
        // 操作屏幕的命令：把系统的「显示点按操作反馈」打开，用户能看到它点了哪里
        val uiDriving = Regex("""\bshell\s+input\s+(tap|swipe|text|keyevent)""").containsMatchIn(args)
        if (uiDriving) setTouchMarker(app, true)
        val out = shell(app, "adb $args 2>&1", timeoutMs)
        // adb 在管道里退不了码，按常见失败字样判断
        val failed = out.contains("error:", true) || out.contains("Failure [", true) ||
                out.contains("failed to connect", true) || out.contains("no devices/emulators", true)
        return !failed to out
    }

    /**
     * 打开/关闭系统的「显示点按操作反馈」（开发者选项里那两项）。
     * 走 adb shell 改 global/system 设置，不需要用户手动去开发者选项里翻。
     */
    suspend fun setTouchMarker(context: Context, on: Boolean) {
        val app = context.applicationContext as? Application ?: return
        val v = if (on) 1 else 0
        shell(app, "adb shell settings put system show_touches $v; adb shell settings put system pointer_location $v", 20_000)
    }

    /**
     * 唤醒并尝试解屏。
     *
     * 锁屏时界面操作**不拦**（锁屏也得能把活干完），但黑屏上点屏是点不中的，所以先按电源键唤醒、
     * 再请求系统收起锁屏：没设密码的设备会直接进桌面；设了密码的仍停在锁屏，
     * 后面的操作就只能在锁屏界面上做——这一点交给模型自己从读屏结果判断。
     */
    suspend fun wakeAndDismiss(context: Context): Boolean {
        runCatching { runAdb(context, "shell input keyevent 224", 15_000) }
        kotlinx.coroutines.delay(400)
        runCatching { runAdb(context, "shell wm dismiss-keyguard", 15_000) }
        kotlinx.coroutines.delay(400)
        return true
    }

    /** 免点击安装：`adb install -r`。成功返回 null，失败返回原因（调用方据此退回系统安装器） */
    suspend fun install(context: Context, path: String): String? {
        val app = context.applicationContext as? Application ?: return "上下文无效"
        val st = ensureConnected(app)
        if (!st.connected) return st.message
        val inContainer = if (path.startsWith("/")) path
        else LinuxRuntime.workdirAbsPath(app)?.let { "$it/$path" } ?: return "找不到文件 $path"
        val out = shell(app, "adb install -r \"$inContainer\" 2>&1 | tail -3", 300_000)
        return if (out.contains("Success", true)) null else out.take(400)
    }

    /** 容器里跑一条命令 */
    private suspend fun shell(app: Application, command: String, timeoutMs: Long): String =
        PersistentShell.run(
            context = app, sid = SID, command = command,
            initialCwd = "/root", cdTo = null, timeoutMs = timeoutMs,
        ).output

    // ---- 密钥备份 / 恢复 ----

    private fun keyDir(context: Context): File = File(context.filesDir, "android/adb")

    /** 连上后备份私钥：rootfs 重新解压会清掉 /root/.android，配对信息就没了 */
    fun backupKey(context: Context) {
        runCatching {
            val src = File(LinuxRuntime.rootfsDir(context), "root/.android")
            val key = File(src, "adbkey")
            if (!key.isFile) return
            val dir = keyDir(context)
            dir.mkdirs()
            key.copyTo(File(dir, "adbkey"), overwrite = true)
            File(src, "adbkey.pub").takeIf { it.isFile }
                ?.copyTo(File(dir, "adbkey.pub"), overwrite = true)
        }
    }

    /** 容器里的密钥丢了就补回去（幂等；没有备份时什么都不做） */
    fun restoreKeyToRootfs(context: Context) {
        runCatching {
            val backup = File(keyDir(context), "adbkey")
            if (!backup.isFile) return
            val target = File(LinuxRuntime.rootfsDir(context), "root/.android")
            val live = File(target, "adbkey")
            if (live.isFile && live.length() == backup.length()) return
            if (!target.isDirectory) target.mkdirs()
            backup.copyTo(live, overwrite = true)
            File(keyDir(context), "adbkey.pub").takeIf { it.isFile }
                ?.copyTo(File(target, "adbkey.pub"), overwrite = true)
            ServerProcessManager.appLog("adb 密钥已从备份恢复到容器（配对信息保留）")
        }
    }
}
