package com.localllm.server.core

import android.content.Context
import java.io.File

/**
 * 引擎安装器，四种模式（都从 assets 按需释放到 files/engines/<mode>/）：
 * - cpu    ：上游 llama.cpp，纯 CPU
 * - opencl ：上游 llama.cpp，带 OpenCL GPU 后端（ngl>0 时使用）
 * - prism  ：PrismML fork（纯 CPU），支持 PQ2_0 / PTQ1_0 等 PrismML 打包的 Bonsai 模型
 *            只有设置里「Bonsai 优化引擎」打开且 ngl=0 时才用；GPU 模式一律走 opencl（该 fork 无 OpenCL 后端）
 * - htp    ：上游 llama.cpp + Hexagon NPU 后端（libggml-hexagon.so + 四档 DSP skel）。
 *            设置里「Hexagon NPU」打开时优先，强制 ngl=99 并走 `--device HTP0`；
 *            包里故意不含 libggml-opencl.so，避免它去枚举 Adreno GPU（TROUBLESHOOTING #19）
 */
object BinaryInstaller {
    // 5：htp 引擎补上 libggml-opencl.so（构建产物的硬依赖），需要强制重新释放
    // 6：cpu/opencl 引擎带上 MoE 专家按需读补丁（scripts/patches/0001-lazy-expert-tensors.patch）
    // 7：再带上专家级预读补丁（0002-moe-expert-prefetch.patch）
    // 8：htp（NPU）引擎也用两个补丁重建——之前它没有补丁，14.4GB 模型会被整份预读
    private const val VERSION = "9"
    private const val ASSET_ROOT = "engines"

    const val MODE_CPU = "cpu"
    const val MODE_OPENCL = "opencl"
    const val MODE_PRISM = "prism"
    const val MODE_HTP = "htp"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 按开关挑引擎：NPU 优先级最高（它在这套参数里当 GPU 使，-ngl 决定卸载范围），
     * 其次 GPU，最后按 prism 开关在 prism / cpu 中选。
     */
    fun modeFor(useGpu: Boolean, prism: Boolean, npu: Boolean = false): String = when {
        npu -> MODE_HTP
        useGpu -> MODE_OPENCL
        prism -> MODE_PRISM
        else -> MODE_CPU
    }

    fun engineDir(useGpu: Boolean): File = engineDir(if (useGpu) MODE_OPENCL else MODE_CPU)

    fun engineDir(mode: String): File = File(appContext.filesDir, "engines/$mode")

    /** 当前可用内存（字节），用于内存保险丝计算 */
    fun availableBytes(): Long {
        val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.availMem
    }

    /**
     * 物理内存总量（字节）。判断「这个模型是不是根本不可能装下」要用总量，
     * 不能用可用量——可用量随后台 App 波动，拿它当阈值会把本来能跑的模型误拦。
     */
    fun totalBytes(): Long {
        val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem
    }

    fun ensureInstalled(useGpu: Boolean): File =
        ensureInstalled(if (useGpu) MODE_OPENCL else MODE_CPU)

    /** 返回该模式的引擎目录（必要时释放 assets），目录内含 llama-server 与全部 .so */
    fun ensureInstalled(mode: String): File {
        val dir = engineDir(mode)
        val marker = File(dir, "version")
        val binary = File(dir, "llama-server")
        if (!binary.exists() || !marker.exists() || marker.readText() != VERSION) {
            val assetDir = "$ASSET_ROOT/$mode"
            val assetNames = appContext.assets.list(assetDir)
            if (assetNames.isNullOrEmpty()) {
                throw IllegalStateException("本安装包未内置 $mode 引擎（assets/$assetDir 为空）")
            }
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            assetNames.forEach { name ->
                appContext.assets.open("$assetDir/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            binary.setExecutable(true, false)
            // 系统 libc++ 副本：防止 LD 路径里 /vendor 的 VNDK libc++ 遮蔽系统版（TROUBLESHOOTING #5）
            val sysLibc = File("/system/lib64/libc++.so")
            if (sysLibc.canRead()) {
                runCatching { sysLibc.copyTo(File(dir, "libc++.so"), overwrite = true) }
            }
            marker.writeText(VERSION)
        }
        return dir
    }
}
