package com.localllm.server.ui

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    }
}
