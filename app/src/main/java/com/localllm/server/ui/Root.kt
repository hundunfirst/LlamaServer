package com.localllm.server.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun Root(vm: MainViewModel) {
    val chatVm: ChatViewModel = viewModel()
    val tabs = listOf(
        Tab("聊天", Icons.AutoMirrored.Filled.Chat),
        Tab("状态", Icons.Default.PlayArrow),
        Tab("日志", Icons.Default.History),
        Tab("模型", Icons.Default.Storage),
        Tab("下载", Icons.Default.Download),
        Tab("设置", Icons.Default.Settings)
    )
    var selected by remember { mutableIntStateOf(0) }
    val error by vm.actionError.collectAsStateWithLifecycle()
    val saveMsg by vm.saveMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error!!)
            vm.clearError()
        }
    }
    LaunchedEffect(saveMsg) {
        if (saveMsg != null) {
            snackbarHostState.showSnackbar(saveMsg!!)
            vm.clearSaveMessage()
        }
    }

    // 启动自动检查到新版本 → 弹窗（同一版本只提示一次）
    val autoUpdate by vm.autoUpdate.collectAsStateWithLifecycle()
    autoUpdate?.let { info ->
        val upCtx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissAutoUpdate(true) },
            title = { androidx.compose.material3.Text("发现新版本 v${info.version}") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
                ) {
                    androidx.compose.material3.Text(
                        info.notes.take(600),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    runCatching {
                        upCtx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(info.apkUrl)
                            )
                        )
                    }
                    vm.dismissAutoUpdate(true)
                }) { androidx.compose.material3.Text("下载更新") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.dismissAutoUpdate(true) }) {
                    androidx.compose.material3.Text("稍后")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            selected = index
                            if (index == 1 || index == 3) vm.refreshModels()
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            val modifier = Modifier.padding(padding)
            when (selected) {
                0 -> {
                    val st by vm.serverState.collectAsStateWithLifecycle()
                    val title = com.localllm.server.core.AppSettings.aliasFor(st.modelPath)
                        ?: (st.modelPath?.substringAfterLast('/') ?: "")
                    ChatScreen(chatVm, modifier, modelTitle = title)
                }
                1 -> StatusScreen(vm, modifier)
                2 -> LogsScreen(vm, modifier)
                3 -> ModelsScreen(vm, modifier)
                4 -> DownloadScreen(vm, modifier)
                5 -> SettingsScreen(vm, modifier)
            }
            // 执行宿主：只负责跑自检，摆到屏幕外——既不会被标签页切换销毁，也不占版面、不挡触摸。
            // 不能用它给用户看：探针接管了 requestAnimationFrame，页面会停在第一帧。
            val sandboxHtml by com.localllm.server.core.HtmlSandbox.lastHtml.collectAsStateWithLifecycle()
            if (sandboxHtml != null) {
                Box(modifier = Modifier.align(Alignment.BottomStart)) {
                    SandboxHost(
                        Modifier
                            .size(400.dp, 300.dp)
                            .offset(x = (-6000).dp)
                    )
                }
            }
            // 预览摘要行放回聊天页内部（在输入框上方），避免和输入栏叠在一起；
            // 执行宿主独立挂在上面，所以关掉/切页都不影响自检。
        }
    }
}
