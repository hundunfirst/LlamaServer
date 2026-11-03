package com.localllm.server.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localllm.server.core.ChatStore
import com.localllm.server.core.DocExtract
import com.localllm.server.core.FileBytes
import com.localllm.server.core.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用内文件查看器（照 DSH 那套）：点产物卡里的文件先在这里看，而不是立刻甩给别的 App。
 *
 * 分类型处理，和用户看到的一致：
 *  - 文本 / Markdown / 代码 / csv → 直接渲染内容（带高亮、可复制）；
 *  - 图片 → 直接显示；
 *  - PDF → 用系统 PdfRenderer 逐页渲染成图（前若干页，避免低内存机被打爆）；
 *  - xlsx / docx → 抽出文本/表格显示——**图表渲染不出来**，这一步只能靠 Excel/WPS；
 *  - apk / zip / 其它二进制 → 明说「无法预览」，给「安装」「用其他应用打开」两个按钮。
 */
@Composable
internal fun FileViewerDialog(
    gf: ChatStore.GenFile,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val name = gf.path.substringAfterLast('/').ifBlank { gf.path }
    val kind = remember(gf.path) { FileBytes.kindOf(name) }
    val sizeText = remember(gf.path) {
        val n = FileBytes.sizeOf(ctx, gf.path)
        when {
            n < 0 -> ""
            n >= 1024 * 1024 -> String.format("%.1f MB", n / 1024.0 / 1024.0)
            n >= 1024 -> "${n / 1024} KB"
            else -> "$n B"
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏：文件名 + 路径 + 大小，右侧动作和应用内查看无关的都在这里
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            listOfNotNull(gf.path, sizeText.ifBlank { null }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    TextButton(onClick = { FileOpener.share(ctx, gf.path) }) {
                        Text("分享", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onExport) {
                        Text("导出", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()

                when (kind) {
                    FileBytes.Kind.Text -> TextBody(gf)
                    FileBytes.Kind.Image -> ImageBody(gf)
                    FileBytes.Kind.Pdf -> PdfBody(gf)
                    FileBytes.Kind.Office -> OfficeBody(gf)
                    else -> CannotPreviewBody(gf)
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 二进制/未知类型也允许"交给系统"，但按钮文案跟着类型走
                    val openLabel = when {
                        name.endsWith(".apk", true) -> "安装（用系统安装器）"
                        else -> "用其他应用打开"
                    }
                    TextButton(onClick = {
                        if (!FileOpener.open(ctx, gf.path)) {
                            android.widget.Toast.makeText(ctx, "没有应用能打开这个文件", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(openLabel, style = MaterialTheme.typography.labelMedium) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val text = gf.content.ifBlank { "" }
                        if (text.isNotBlank()) {
                            clipboard.setText(AnnotatedString(text))
                            android.widget.Toast.makeText(ctx, "已复制内容", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("复制", style = MaterialTheme.typography.labelMedium) }
                    TextButton(onClick = onDismiss) { Text("关闭", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun TextBody(gf: ChatStore.GenFile) {
    val ctx = LocalContext.current
    val body by produceState(initialValue = gf.content, gf.path) {
        if (value.isBlank()) {
            val bytes = withContext(Dispatchers.IO) { FileBytes.read(ctx, gf.path, 4L * 1024 * 1024) }
            value = bytes?.let { String(it, Charsets.UTF_8) } ?: ""
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        if (body.isBlank()) {
            Text("（空文件或读不到内容）", style = MaterialTheme.typography.bodySmall)
        } else {
            val lang = gf.path.substringAfterLast('.', "").lowercase()
            Text(
                highlightCode(
                    lang, body,
                    keywordColor = MaterialTheme.colorScheme.primary,
                    stringColor = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    numberColor = androidx.compose.ui.graphics.Color(0xFF6A1B9A)
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ImageBody(gf: ChatStore.GenFile) {
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, gf.path) {
        value = withContext(Dispatchers.IO) {
            val bytes = FileBytes.read(ctx, gf.path) ?: return@withContext null
            runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val b = bmp
        if (b == null) Text("图片解码失败", style = MaterialTheme.typography.bodySmall)
        else Image(bitmap = b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

@Composable
private fun PdfBody(gf: ChatStore.GenFile) {
    val ctx = LocalContext.current
    // 只渲染前 20 页：PDF 可能几百页，全渲染在手机上直接 OOM
    val pages by produceState<List<android.graphics.Bitmap>>(initialValue = emptyList(), gf.path) {
        value = withContext(Dispatchers.IO) { renderPdfPages(ctx, gf.path, limit = 20) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (pages.isEmpty()) {
            Text("PDF 渲染失败（文件可能损坏或加密）", style = MaterialTheme.typography.bodySmall)
        }
        pages.forEach { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.FillWidth
            )
        }
    }
}

private fun renderPdfPages(context: android.content.Context, path: String, limit: Int): List<android.graphics.Bitmap> {
    val pfd = runCatching {
        val doc = com.localllm.server.core.FileTools.documentFor(context, path)
        if (doc != null) context.contentResolver.openFileDescriptor(doc.uri, "r")
        else FileBytes.realFile(context, path)?.let {
            android.os.ParcelFileDescriptor.open(it, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }.getOrNull() ?: return emptyList()
    return runCatching {
        android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
            val out = ArrayList<android.graphics.Bitmap>()
            val n = minOf(renderer.pageCount, limit)
            for (i in 0 until n) {
                renderer.openPage(i).use { page ->
                    // 2 倍缩放保证文字可读，又不至于把内存吃光
                    val w = page.width * 2
                    val h = page.height * 2
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out += bmp
                }
            }
            out
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun OfficeBody(gf: ChatStore.GenFile) {
    val ctx = LocalContext.current
    val body by produceState(initialValue = "", gf.path) {
        val bytes = withContext(Dispatchers.IO) { FileBytes.read(ctx, gf.path) } ?: return@produceState
        val name = gf.path.substringAfterLast('/')
        val doc = withContext(Dispatchers.IO) { DocExtract.extract(bytes, name, FileOpener.mimeOf(name)) }
        value = doc?.text.orEmpty()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text(
            "表格/文档内容（图表无法在这里显示，需要 Excel / WPS 打开）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (body.isBlank()) {
            Text("没有可提取的文字", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(body, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CannotPreviewBody(gf: ChatStore.GenFile) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                gf.path.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }.take(4),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("无法预览", style = MaterialTheme.typography.bodyMedium)
        Text(
            "这个类型需要别的应用打开（APK 点下面的「安装」即可）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
