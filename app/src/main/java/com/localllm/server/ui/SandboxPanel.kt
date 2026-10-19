package com.localllm.server.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.server.core.HtmlSandbox

/**
 * 执行宿主：真正跑自检的那个 WebView。
 *
 * 它**不能**用来给用户看——注入的虚拟时钟接管了 requestAnimationFrame，
 * 页面会停在第一帧。所以这里只负责执行，位置由调用方摆到屏幕外。
 * generation 变化时整实例换新（死循环后旧实例不可复用）。
 */
@Composable
fun SandboxHost(modifier: Modifier = Modifier) {
    val gen by HtmlSandbox.generation.collectAsStateWithLifecycle()
    key(gen) {
        AndroidView(
            modifier = modifier,
            factory = { c -> WebView(c).also { HtmlSandbox.attach(it) } },
            onRelease = { wv ->
                HtmlSandbox.detach(wv)
                wv.stopLoading()
                wv.destroy()
            }
        )
    }
}

/**
 * 运行预览：收起时是一行摘要（不占输入框、不挡消息），点开是画中画弹窗。
 * 弹窗里是**另一个** WebView，加载未注入探针的原始页面，所以游戏是真的在动。
 */
@Composable
fun SandboxPanel() {
    // 委派属性无法智能转换，先取成普通 val
    val htmlOrNull by HtmlSandbox.lastHtml.collectAsStateWithLifecycle()
    val html = htmlOrNull ?: return
    val running by HtmlSandbox.running.collectAsStateWithLifecycle()
    val label by HtmlSandbox.lastLabel.collectAsStateWithLifecycle()
    val summary by HtmlSandbox.lastSummary.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        onClick = { showDialog = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (summary?.contains("错误") == true) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 4.dp)
            ) {
                Text(
                    if (running) "正在运行自检…" else "运行预览",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                val sub = buildString {
                    if (label.isNotBlank()) append(label)
                    if (summary != null) {
                        if (isNotEmpty()) append(" · ")
                        append(summary)
                    }
                    if (isEmpty()) append("点开查看")
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summary?.contains("错误") == true || summary == "卡死（超时）")
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { HtmlSandbox.clearPreview() }) {
                Icon(Icons.Default.Close, contentDescription = "关闭预览", modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label.ifBlank { "运行预览" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                    PreviewWebView(
                        html = html,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(460.dp)
                            .padding(horizontal = 8.dp)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}

/** 纯展示用：加载原始页面（不含探针），原生 rAF 生效，可以真的玩。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(html: String, modifier: Modifier = Modifier) {
    var wv by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).also {
                it.settings.javaScriptEnabled = true
                it.settings.domStorageEnabled = true
                it.settings.allowFileAccess = false
                it.settings.blockNetworkLoads = true
                wv = it
            }
        },
        onRelease = {
            wv = null
            it.stopLoading()
            it.destroy()
        }
    )
    LaunchedEffect(wv, html) {
        wv?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
