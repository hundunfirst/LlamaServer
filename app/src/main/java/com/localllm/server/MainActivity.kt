package com.localllm.server

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.localllm.server.ui.MainViewModel
import com.localllm.server.ui.Root
import com.localllm.server.ui.theme.LlamaTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        // 闹钟在进程被杀后不会自己保留：每次启动重新登记定时任务
        runCatching { com.localllm.server.core.ScheduledTasks.rescheduleAll(this) }
        // 覆盖安装本 App 后系统会停用无障碍服务，但设置里的开关仍显示"开"：
        // 用过无障碍的话，启动时提醒一次去重新打开，别让人对着"开着却不能用"发懵
        runCatching {
            if (com.localllm.server.core.AppSettings.a11yWasEnabled &&
                !com.localllm.server.core.A11yService.isEnabled(this)
            ) {
                android.widget.Toast.makeText(
                    this,
                    "无障碍服务被系统停用了（覆盖安装后常见）：如需要模型操作屏幕，请到 设置 → 无障碍 重新打开",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            LlamaTheme {
                Root(vm)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** 接收其它 App 分享来的文本/链接 → 填进聊天输入框 */
    private fun handleShareIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
        com.localllm.server.core.ShareInbox.put(text)
    }
}
