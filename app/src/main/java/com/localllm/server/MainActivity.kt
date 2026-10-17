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
