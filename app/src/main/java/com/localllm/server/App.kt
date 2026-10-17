package com.localllm.server

import android.app.Application
import com.localllm.server.core.AppSettings
import com.localllm.server.core.BinaryInstaller
import com.localllm.server.core.ModelRepository
import com.localllm.server.core.ServerProcessManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        AppSettings.migrateMcpSingleToMulti()
        BinaryInstaller.init(this)
        ServerProcessManager.init(this)
        modelRepository = ModelRepository(this)

        // 捕获未处理异常：写入 files/crash.log 与运行日志（排查闪退用），随后交给系统默认处理
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(java.util.Date())
                val stack = android.util.Log.getStackTraceString(e)
                java.io.File(filesDir, "crash.log").appendText(
                    "\n=== $stamp  thread=${thread.name} ===\n$stack\n"
                )
                com.localllm.server.core.ServerProcessManager.appLog(
                    "应用崩溃（${e.javaClass.simpleName}）：${e.message ?: ""}，详情见 crash.log"
                )
            }
            prevHandler?.uncaughtException(thread, e)
        }
    }

    companion object {
        lateinit var modelRepository: ModelRepository
            private set
    }
}
