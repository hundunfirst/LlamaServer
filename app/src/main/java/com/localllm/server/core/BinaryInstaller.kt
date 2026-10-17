package com.localllm.server.core

import android.content.Context
import java.io.File

/**
 * 双引擎安装器：
 * - engines/cpu    ：纯 CPU（无 OpenCL）。解码速度正常（~3x 优势，见 TROUBLESHOOTING #19）
 * - engines/opencl ：带 OpenCL GPU 后端。GPU offload（ngl>0）时使用
 * 启动服务时按 ngl 自动选择；两套引擎首次使用时分别从 assets 释放。
 */
object BinaryInstaller {
    private const val VERSION = "4"
    private const val ASSET_ROOT = "engines"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun engineDir(useGpu: Boolean): File =
        File(appContext.filesDir, "engines/${if (useGpu) "opencl" else "cpu"}")

    /** 当前可用内存（字节），用于内存保险丝计算 */
    fun availableBytes(): Long {
        val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.availMem
    }

    /** 返回该模式的引擎目录（必要时释放 assets），目录内含 llama-server 与全部 .so */
    fun ensureInstalled(useGpu: Boolean): File {
        val mode = if (useGpu) "opencl" else "cpu"
        val dir = File(appContext.filesDir, "engines/$mode")
        val marker = File(dir, "version")
        val binary = File(dir, "llama-server")
        if (!binary.exists() || !marker.exists() || marker.readText() != VERSION) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val assetDir = "$ASSET_ROOT/$mode"
            appContext.assets.list(assetDir)?.forEach { name ->
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
