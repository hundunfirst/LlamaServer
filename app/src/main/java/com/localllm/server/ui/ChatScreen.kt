package com.localllm.server.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.server.core.AppSettings
import com.localllm.server.core.ChatStore

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: ChatViewModel, modifier: Modifier = Modifier, modelTitle: String = "") {
    val messages by vm.messages.collectAsStateWithLifecycle()
    // 只给最近一个被中断的任务显示续跑入口：否则每中断一次就多一个按钮，
    // 而旧的点了也只会去续跑最新那个，容易误解
    val lastStoppedIdx = messages.indexOfLast { it.role == "assistant" && it.task?.stopped == true }
    val input by vm.input.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val progressBySession by vm.progress.collectAsStateWithLifecycle()
    val toastMsg by vm.toast.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val lastStats by vm.lastStats.collectAsStateWithLifecycle()
    val contextUsage by vm.contextUsage.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val pendingImage by vm.pendingImageB64.collectAsStateWithLifecycle()
    val imageNote by vm.pendingImageNote.collectAsStateWithLifecycle()
    val queue by vm.taskQueue.collectAsStateWithLifecycle()
    val pendingFile by vm.pendingFile.collectAsStateWithLifecycle()
    // 音视频附件：原样发送，仅在当轮有效；界面要显示出来，否则用户不知道加上了没有
    val mediaNote by vm.pendingMediaNote.collectAsStateWithLifecycle()

    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    // 正在编辑端点的会话 id（null = 没开选择弹窗）
    var endpointEditing by remember { mutableStateOf<String?>(null) }
    // 正在设置文件夹的会话 id
    var folderEditing by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 后台会话生成完成/失败时给一次提示
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var showCompressConfirm by remember { mutableStateOf(false) }
    var showSysPrompt by remember { mutableStateOf(false) }
    var previewImageB64 by remember { mutableStateOf<String?>(null) }
    var previewFileName by remember { mutableStateOf("") }
    var showSkills by remember { mutableStateOf(false) }
    var readMsg by remember { mutableStateOf<com.localllm.server.core.ChatStore.Msg?>(null) }
    var previewGenFile by remember {
        mutableStateOf<com.localllm.server.core.ChatStore.GenFile?>(null)
    }
    var previewFileText by remember { mutableStateOf("") }
    var showFilePreview by remember { mutableStateOf(false) }
    var sysPromptText by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }

    // 相册：走系统的图片选择器（Android 13+ 是系统相册 Photo Picker，旧系统回退到图库）。
    // 之前和「文件」共用 OpenDocument，点出来是文档浏览器，跟「相册」这个名字不符。
    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.attachImage(uri)
    }

    // 剪贴板里复制了文件时给一个「插入路径」入口：
    // Android 的文本框不接受粘贴文件（剪贴板是 content:// URI），
    // 但用户复制文件的意图通常就是引用它——换成路径插进输入框，模型自己会 file_read。
    val clipMgr = context.getSystemService(android.content.ClipboardManager::class.java)
    var clipRef by remember { mutableStateOf<Pair<String, String>?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val handler = android.os.Handler(context.mainLooper)
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            val clip = runCatching { clipMgr?.primaryClip }.getOrNull()
            val uri = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).uri else null
            val resolved = uri?.let { com.localllm.server.core.FileRef.resolve(context, it) }
            handler.post { clipRef = resolved }
        }
        runCatching { clipMgr?.addPrimaryClipChangedListener(listener) }
        onDispose { runCatching { clipMgr?.removePrimaryClipChangedListener(listener) } }
    }

    // 附加文件：图片走多模态/OCR，文本类文件读成文本随消息发送（其余类型暂不支持）
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) vm.attachImage(uri) else vm.attachFile(uri)
        }
    }

    // 输入区「+」面板：相册 / 拍照 / 文件（同一个入口，DSH 那种底部抽屉）
    var showAttachSheet by remember { mutableStateOf(false) }
    var showUsageSheet by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var permissionEditing by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // 拍照：交给系统相机，结果写进 MediaStore 新建的图片条目（不需要 FileProvider）
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) vm.attachImage(uri)
    }

    fun launchCamera() {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "hundun_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = runCatching {
            context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "无法创建照片文件", Toast.LENGTH_SHORT).show()
            return
        }
        cameraUri = uri
        runCatching { takePhoto.launch(uri) }.onFailure {
            cameraUri = null
            Toast.makeText(context, "没有可用的相机：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 面板里「文件」用同一套解析：文本类读成文本，其余提示不支持
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/")) vm.attachImage(uri) else vm.attachFile(uri)
        }
    }

    // 导出生成的文件：走系统保存对话框，位置和文件名由用户定。
    // 比直接塞 Download 更可靠——工作目录里的文件可能被系统清理搬进回收站。
    var exportGenFile by remember {
        mutableStateOf<com.localllm.server.core.ChatStore.GenFile?>(null)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val gf = exportGenFile
        exportGenFile = null
        if (uri != null && gf != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(gf.content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法写入目标文件")
            }.onSuccess {
                Toast.makeText(
                    context, "已导出 ${gf.path.substringAfterLast('/')}", Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- 语音输入（系统识别器，零依赖）----
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            vm.onInput((input + text).trim())
        }
    }

    fun startSpeech() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .apply {
                putExtra(
                    android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说话…")
            }
        runCatching { speechLauncher.launch(intent) }.onFailure {
            Toast.makeText(context, "语音识别不可用（系统未安装语音服务）", Toast.LENGTH_SHORT).show()
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSpeech()
        else Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    // ---- TTS 朗读 ----
    var tts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val engine = android.speech.tts.TextToSpeech(context) { st ->
            ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS
        }
        tts = engine
        onDispose {
            runCatching {
                engine.stop()
                engine.shutdown()
            }
        }
    }

    // 智能贴底滚动：新消息必到底；流式增长仅当"最后一条消息可见且离其底部<400px"（用户未上翻）时跟随。
    // 跟随用增量 scrollBy(尺寸增量)，不用 scrollToItem 重锚定——重锚定在流式期间会闪动。
    var prevCount by remember { mutableStateOf(-1) }
    var prevLastSize by remember { mutableStateOf(0) }
    val lastLen = messages.lastOrNull()?.let { it.content.length + (it.reasoning?.length ?: 0) } ?: 0
    LaunchedEffect(messages.size, lastLen, streaming) {
        if (messages.isEmpty()) {
            prevCount = -1
            prevLastSize = 0
            return@LaunchedEffect
        }
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        val atLastMsgBottom = lastVisible != null &&
                lastVisible.index == messages.size - 1 &&
                (lastVisible.offset + lastVisible.size) - info.viewportEndOffset <= 400
        val newMessage = messages.size != prevCount
        val sizeDelta = (lastVisible?.size ?: 0) - prevLastSize
        prevCount = messages.size
        prevLastSize = lastVisible?.size ?: 0
        if (newMessage) {
            // 锚定最后一条的底部：先滚到该条，再把超出视口的部分补滚掉
            listState.scrollToItem(messages.size - 1)
            val v = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (v != null) {
                val over = (v.offset + v.size) - listState.layoutInfo.viewportEndOffset
                if (over > 0) listState.scroll { scrollBy(over.toFloat()) }
            }
        } else if (atLastMsgBottom && sizeDelta > 0) {
            // 增量跟随：只滚消息长出来的部分，视口不跳
            listState.scroll { scrollBy(sizeDelta.toFloat()) }
        }
    }

    editingIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingIndex = null
                    vm.editAndResend(idx, editText)
                }) { Text("保存并重发") }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = null }) { Text("取消") }
            }
        )
    }

    // 图片预览（发送前的附件图片）
    previewImageB64?.let { b64 ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { previewImageB64 = null }) {
            val bmp = remember(b64) {
                runCatching {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { previewImageB64 = null }
                )
            }
        }
    }

    // 文件预览（发送前的附件文本；PDF/Office 显示的是已抽取文本）
    if (showFilePreview) {
        AlertDialog(
            onDismissRequest = { showFilePreview = false },
            title = { Text(previewFileName, maxLines = 1) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        previewFileText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilePreview = false }) { Text("关闭") }
            }
        )
    }

    // 阅读视图：独立页面内可拖选复制（不在懒加载列表里，避免渲染问题）
    readMsg?.let { m ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { readMsg = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (m.role == "user") "用户消息" else "助手回复",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(m.content))
                            Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
                        }) { Text("复制全文") }
                        TextButton(onClick = { readMsg = null }) { Text("关闭") }
                    }
                    androidx.compose.material3.HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "长按并拖动可选取任意片段复制",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                m.content,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (!m.reasoning.isNullOrBlank()) {
                            androidx.compose.material3.HorizontalDivider()
                            Text(
                                "🧠 思考过程",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    m.reasoning!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 生成文件预览：先进应用内查看器（文本/图片/PDF 直接看，APK 这类给「安装 / 用其他应用打开」）
    previewGenFile?.let { gf ->
        FileViewerDialog(
            gf = gf,
            onDismiss = { previewGenFile = null },
            onExport = {
                exportGenFile = gf
                exportLauncher.launch(gf.path.substringAfterLast('/'))
            }
        )
    }

    // 技能选择 / 管理
    if (showSkills) {
        val cur = sessions.find { it.id == activeId }
        SkillDialog(
            onDismiss = { showSkills = false },
            onApply = { skill ->
                vm.setSessionSystemPrompt(skill.prompt, skillId = skill.id)
                Toast.makeText(context, "已应用技能：${skill.name}", Toast.LENGTH_SHORT).show()
                showSkills = false
            },
            onApplyChain = { chain ->
                // 走执行器：每步独立生成、失败可换方案，不再把各步提示词拼成 system prompt
                vm.setSessionChain(chain.id)
                Toast.makeText(context, "已应用技能链：${chain.name}（逐步执行）", Toast.LENGTH_SHORT).show()
                showSkills = false
            },
            onClear = { vm.setSessionSystemPrompt(null) },
            appliedSkillId = cur?.appliedSkillId,
            appliedChainId = cur?.appliedChainId
        )
    }

    // 本会话助手指令
    if (showSysPrompt) {
        AlertDialog(
            onDismissRequest = { showSysPrompt = false },
            title = { Text("本会话助手指令") },
            text = {
                Column {
                    Text(
                        "留空则使用全局预设；这里的内容只影响本会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CompactFieldMultiline(
                        value = sysPromptText,
                        onValueChange = { sysPromptText = it },
                        label = "系统指令（system prompt）",
                        minHeight = 140
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setSessionSystemPrompt(sysPromptText)
                    showSysPrompt = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.setSessionSystemPrompt(null)
                    showSysPrompt = false
                }) { Text("清除覆盖") }
            }
        )
    }

    // 压缩上下文确认（摘要会替代原文进入后续对话）
    if (showCompressConfirm) {
        AlertDialog(
            onDismissRequest = { showCompressConfirm = false },
            title = { Text("压缩上下文？") },
            text = {
                Text(
                    "将把较早的消息交给模型总结成一条摘要，仅保留最近 4 条原文。" +
                            "摘要在后续对话中替代原文，原消息不再发送给模型（界面里也会被摘要卡取代）。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCompressConfirm = false
                    vm.compressContext()
                }) { Text("压缩") }
            },
            dismissButton = {
                TextButton(onClick = { showCompressConfirm = false }) { Text("取消") }
            }
        )
    }

    // 删除消息确认
    deleteIndex?.let { idx ->
        val target = messages.getOrNull(idx)
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("删除这条消息？") },
            text = {
                Text(
                    (if (target?.role == "user") "用户消息" else "助手回复") +
                            "，共 ${target?.content?.length ?: 0} 字。删除后不可恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAt(idx)
                    deleteIndex = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteIndex = null }) { Text("取消") } }
        )
    }

    // 图片轮联网搜索确认（策略=先问我）：把即将发给搜索引擎的内容展示给用户
    val searchAsk by vm.searchApproval.collectAsStateWithLifecycle()
    searchAsk?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.approveSearch(false) },
            title = { Text("允许联网搜索？") },
            text = {
                Column {
                    Text(
                        "本轮消息包含图片。以下内容将被发送到搜索引擎（Bing）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(pending, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.approveSearch(true) }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { vm.approveSearch(false) }) { Text("拒绝") }
            }
        )
    }

    // 危险操作确认：命令 / 写文件 / 带写语义的 MCP 工具执行前，把要做的事原样摆给用户看
    val toolAsk by vm.toolAsk.collectAsStateWithLifecycle()
    toolAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = { vm.answerToolAsk(0) },
            title = { Text(ask.title) },
            text = {
                Column {
                    Text(
                        ask.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ask.dangerous) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        ask.subjectLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            ask.subject,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                    if (ask.canAlways) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "「总是允许」= 本会话内同类操作不再询问。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (ask.canAlways) {
                        TextButton(onClick = { vm.answerToolAsk(2) }) { Text("总是允许") }
                    }
                    TextButton(onClick = { vm.answerToolAsk(1) }) { Text("允许") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerToolAsk(0) }) {
                    Text("拒绝", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // ---- 顶栏：标题 + 渠道快捷切换 + 会话管理 ----
        var channelMenuOpen by remember { mutableStateOf(false) }
        val cloudPresets = remember {
            mutableStateOf(com.localllm.server.core.AppSettings.loadCloudPresets())
        }
        val cloudOn = com.localllm.server.core.AppSettings.chatCloudEnabled
        val activePresetName = if (cloudOn) {
            val curUrl = com.localllm.server.core.AppSettings.chatCloudUrl.trim().removeSuffix("/")
            cloudPresets.value.find { it.url.trim().removeSuffix("/") == curUrl }?.name ?: "自定义"
        } else "本地"
        // 拉取该预设 /v1/models 并落到预设里：电脑端换了模型、云端上新都能自动发现，
        // 不用再去设置里手敲名字（手敲容易和接口对不上，还会一直停在旧名字上）
        val channelScope = androidx.compose.runtime.rememberCoroutineScope()
        fun refreshPresetModels(p: com.localllm.server.core.AppSettings.CloudPreset) {
            channelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ids = try {
                    com.localllm.server.core.OpenAiCompat.fetchModels(p.url, p.key)
                } catch (e: Throwable) {
                    com.localllm.server.core.ServerProcessManager.appLog(
                        "拉取模型列表失败：${p.name} — ${e.message}"
                    )
                    emptyList<String>()
                }
                if (ids.isEmpty()) return@launch
                val updated = com.localllm.server.core.AppSettings.loadCloudPresets().map { pr ->
                    if (pr.id != p.id) pr
                    else {
                        val old = pr.models.associateBy { it.id }
                        pr.copy(models = ids.map { id ->
                            old[id] ?: com.localllm.server.core.AppSettings.CloudModel(id)
                        })
                    }
                }
                com.localllm.server.core.AppSettings.saveCloudPresets(updated)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    cloudPresets.value = updated
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                // 渠道快捷切换
                TextButton(onClick = {
                    cloudPresets.value = com.localllm.server.core.AppSettings.loadCloudPresets()
                    channelMenuOpen = true
                    // 打开菜单时顺手刷新当前渠道的模型列表（PC 换了模型也能发现）
                    val curUrl = com.localllm.server.core.AppSettings.chatCloudUrl.trim().removeSuffix("/")
                    cloudPresets.value.firstOrNull { it.url.trim().removeSuffix("/") == curUrl }
                        ?.let { refreshPresetModels(it) }
                }) {
                    Text(
                        activePresetName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (cloudOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = channelMenuOpen,
                    onDismissRequest = { channelMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("本地引擎") },
                        onClick = {
                            com.localllm.server.core.AppSettings.chatCloudEnabled = false
                            channelMenuOpen = false
                        }
                    )
                    cloudPresets.value.forEach { p ->
                        val isActive = cloudOn && p.url.trim().removeSuffix("/") ==
                                com.localllm.server.core.AppSettings.chatCloudUrl.trim().removeSuffix("/")
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        p.name + if (isActive) "（当前）" else "",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        p.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            },
                            onClick = {
                                com.localllm.server.core.AppSettings.activateCloudPreset(p)
                                channelMenuOpen = false
                            }
                        )
                        // 该渠道下的模型直接列出来选，完整名字换行显示，不用点进输入框看
                        p.models.forEach { m ->
                            val picked = isActive && p.model == m.id
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            (if (picked) "✓ " else "　 ") + m.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2
                                        )
                                        if (m.label.isNotBlank()) {
                                            Text(
                                                m.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    com.localllm.server.core.AppSettings.activateCloudPreset(
                                        p.copy(model = m.id)
                                    )
                                    channelMenuOpen = false
                                }
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("刷新模型列表") },
                        onClick = {
                            cloudPresets.value.forEach { refreshPresetModels(it) }
                        }
                    )
                }
            }
            Row {
                var searchOn by remember {
                    mutableStateOf(com.localllm.server.core.AppSettings.webSearchEnabled)
                }
                IconButton(onClick = {
                    searchOn = !searchOn
                    com.localllm.server.core.AppSettings.webSearchEnabled = searchOn
                }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "联网搜索",
                        tint = if (searchOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 功能整合入口：技能 / 本会话指令 / 压缩上下文
                var toolsMenuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { toolsMenuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多工具",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = toolsMenuOpen,
                        onDismissRequest = { toolsMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("技能…") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                            onClick = {
                                toolsMenuOpen = false
                                showSkills = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("本会话助手指令…") },
                            leadingIcon = { Icon(Icons.Default.Psychology, null) },
                            onClick = {
                                toolsMenuOpen = false
                                sysPromptText = vm.currentSessionSystemPrompt() ?: ""
                                showSysPrompt = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("压缩上下文…") },
                            leadingIcon = { Icon(Icons.Default.Compress, null) },
                            onClick = {
                                toolsMenuOpen = false
                                showCompressConfirm = true
                            }
                        )
                    }
                }
                BadgedBox(
                    badge = {
                        if (generating.isNotEmpty()) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary)
                        }
                    }
                ) {
                    IconButton(onClick = { showSessions = true }) {
                        Icon(Icons.Default.List, contentDescription = "会话列表")
                    }
                }
                IconButton(onClick = vm::newChat, enabled = generating.isEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "新会话")
                }
                // 会话详情：轨迹 / 改动 / 文件 / 进度 / 上下文
                IconButton(onClick = { showDetail = true }) {
                    Icon(Icons.Default.Info, contentDescription = "会话详情")
                }
            }
        }

        // 全局运行中指示：别的会话在生成时，常驻一条可点击的提示（点一下切过去）
        val bgSession = generating.firstOrNull { it != activeId }
        if (bgSession != null) {
            val bgTitle = sessions.find { it.id == bgSession }?.title ?: "另一条会话"
            val bgProgress = progressBySession[bgSession].orEmpty()
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clickable { vm.switchSession(bgSession) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "「$bgTitle」正在生成",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                        if (bgProgress.isNotBlank()) {
                            Text(
                                bgProgress,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                    Text(
                        "切过去 ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (showSessions) {
            // 分组可收起：会话一多，未分组那块会把正在用的挤到看不见
            var collapsed by remember { mutableStateOf(setOf<String>()) }
            AlertDialog(
                onDismissRequest = { showSessions = false },
                title = { Text("会话管理") },
                text = {
                    val groupedSessions = sessions
                        .sortedByDescending { it.updatedAt }
                        .groupBy { it.folder.orEmpty() }
                    // 一个分组都没有时不要空顶一行「未分组」，那只是噪音
                    val showHeaders = groupedSessions.keys.any { it.isNotEmpty() }
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        groupedSessions.forEach { (folderName, list) ->
                            if (showHeaders) {
                                item(key = "folder:" + folderName) {
                                    val isCollapsed = folderName in collapsed
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                collapsed = if (isCollapsed) collapsed - folderName
                                                else collapsed + folderName
                                            }
                                            .padding(top = 10.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            (if (folderName.isEmpty()) "未分组" else "📁 " + folderName) +
                                                    " · ${list.size}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (isCollapsed) "展开" else "收起",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            if (folderName in collapsed) return@forEach
                            items(list) { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.switchSession(s.id)
                                        showSessions = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = s.id == activeId, onClick = {
                                    vm.switchSession(s.id)
                                    showSessions = false
                                })
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (s.id in generating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            s.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                    Text(
                                        if (s.id in generating)
                                            "生成中… · ${progressBySession[s.id].orEmpty().ifBlank { "${s.messages.size} 条" }}"
                                        else "${s.messages.size} 条 · " +
                                                java.text.SimpleDateFormat("MM-dd HH:mm")
                                                    .format(java.util.Date(s.updatedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (s.id in generating) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (s.id in generating) FontWeight.Medium
                                        else FontWeight.Normal
                                    )
                                    // 这条会话走哪个通道：多会话并行时各绑各的（本地/云端/电脑上的模型）
                                    Text(
                                        "端点：" + sessionEndpointLabel(s) + " ›",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { endpointEditing = s.id }
                                            .padding(top = 2.dp)
                                    )
                                }
                                IconButton(onClick = { folderEditing = s.id }) {
                                    Icon(Icons.Default.Folder, contentDescription = "文件夹",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { vm.deleteSession(s.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSessions = false }) { Text("关闭") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.newChat()
                        showSessions = false
                    }) { Text("新会话") }
                }
            )
        }
        // 文件夹（工作区）：给会话分组，空 = 未分组
        folderEditing?.let { sid ->
            val cur = sessions.find { it.id == sid }
            var name by remember(sid) { mutableStateOf(cur?.folder ?: "") }
            // 已有分组直接点选：手打名字打错一个字符就多出一个分组，会话散在两处
            val existing = sessions.mapNotNull { it.folder }
                .filter { it.isNotBlank() }.distinct().sorted()
            AlertDialog(
                onDismissRequest = { folderEditing = null },
                title = { Text("文件夹") },
                text = {
                    Column {
                        if (existing.isNotEmpty()) {
                            Text(
                                "已有文件夹",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                existing.forEach { f ->
                                    FilterChip(
                                        selected = name.trim() == f,
                                        onClick = { name = f },
                                        label = { Text(f) }
                                    )
                                }
                                FilterChip(
                                    selected = name.isBlank(),
                                    onClick = { name = "" },
                                    label = { Text("未分组") }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("文件夹名（留空 = 未分组）") },
                            singleLine = true
                        )
                        if (name.isNotBlank() && name.trim() !in existing) {
                            Text(
                                "保存后新建文件夹「${name.trim()}」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Text(
                            "文件夹只用来归拢会话列表，不影响会话内容，也不改工作目录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.setSessionFolder(sid, name)
                        folderEditing = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { folderEditing = null }) { Text("取消") }
                }
            )
        }
        // 会话端点选择：多会话可以各走各的通道（本地 / 云端渠道 / 电脑上跑的模型）
        endpointEditing?.let { sid ->
            val cur = sessions.find { it.id == sid }
            val presets = remember { com.localllm.server.core.AppSettings.loadCloudPresets() }
            val globalLabel = if (
                com.localllm.server.core.AppSettings.chatCloudEnabled &&
                com.localllm.server.core.AppSettings.chatCloudUrl.isNotBlank()
            ) {
                "云端 " + com.localllm.server.core.AppSettings.chatCloudModel.ifBlank { "default" }
            } else {
                "本地服务"
            }
            AlertDialog(
                onDismissRequest = { endpointEditing = null },
                title = { Text("本会话端点") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "只影响这条会话之后的发送，其他会话不受影响。" +
                                    "电脑上跑的模型也在「设置 → 云端」里加一条 OpenAI 兼容地址即可。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        EndpointOption(
                            selected = cur?.endpoint == null,
                            title = "跟随全局（$globalLabel）",
                            onClick = {
                                vm.setSessionEndpoint(sid, null)
                                endpointEditing = null
                            }
                        )
                        EndpointOption(
                            selected = cur?.endpoint?.local == true,
                            title = "本地服务（本机 llama-server）",
                            onClick = {
                                vm.setSessionEndpoint(sid, ChatStore.Endpoint(local = true))
                                endpointEditing = null
                            }
                        )
                        presets.forEach { p ->
                            val selected = cur?.endpoint?.let { !it.local && it.baseUrl == p.url } == true
                            EndpointOption(
                                selected = selected,
                                title = "云端 · " + p.name.ifBlank { p.url } +
                                        "（" + p.model.ifBlank { "默认模型" } + "）",
                                onClick = {
                                    vm.setSessionEndpoint(
                                        sid,
                                        ChatStore.Endpoint(
                                            local = false,
                                            baseUrl = p.url,
                                            model = p.model,
                                            apiKey = p.key
                                        )
                                    )
                                    endpointEditing = null
                                }
                            )
                        }
                        if (presets.isEmpty()) {
                            Text(
                                "还没有云端渠道：去「设置 → 云端」添加一条（地址填 OpenAI 兼容的端点）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { endpointEditing = null }) { Text("关闭") }
                }
            )
        }
        if (modelTitle.isNotBlank()) {
            Text(
                "模型：$modelTitle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                // 兜底：错误文本可能夹着引擎吐回的原始响应体，别让它把排版拖死
                maxLines = 6,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "服务运行中即可直接对话，无需额外配置。\n长按/⋮ 菜单支持复制、重发、编辑、删除；代码块可一键复制或保存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 48.dp, start = 8.dp, end = 8.dp)
                    )
                }
            }
            itemsIndexed(messages) { i, msg ->
                // 跨天插一条日期分隔：往上翻的时候先看它，不用逐条读时间戳
                val prevAt = if (i > 0) messages[i - 1].at else 0L
                Column(Modifier.fillMaxWidth()) {
                    if (msg.at > 0 && !ChatStore.sameDay(msg.at, prevAt)) {
                        DayDivider(ChatStore.dayLabel(msg.at))
                    }
                    if (msg.role == "system") {
                        SummaryCard(msg.content)
                    } else {
                        val isUser = msg.role == "user"
                        val waiting = !isUser && streaming && msg.content.isEmpty() && msg.reasoning.isNullOrEmpty()
                        // 思考进行中（有思考文本、正文还没开始）：思考卡显示动态"思考中"
                        val thinking = !isUser && streaming && msg.content.isEmpty() && !msg.reasoning.isNullOrEmpty()
                        // 会话页字号：直接给一套更大的字形，而不是缩放——缩放会让行高与字重脱节
                        androidx.compose.material3.MaterialTheme(
                            typography = chatTypography(androidx.compose.material3.MaterialTheme.typography)
                        ) {
                            MessageBubble(
                                msg = msg,
                                isUser = isUser,
                                waiting = waiting,
                                thinking = thinking,
                                generating = streaming && i == messages.lastIndex && !isUser,
                                showStats = (!isUser && i == messages.lastIndex && lastStats != null),
                                stats = lastStats ?: "",
                                onResumeTask = { vm.resumeStoppedTask(i) },
                                showResume = i == lastStoppedIdx,
                                menuOpen = menuIndex == i,
                                onMenuOpen = { menuIndex = i },
                                onMenuClose = { menuIndex = null },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(msg.content))
                                    menuIndex = null
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                                onResend = {
                                    menuIndex = null
                                    if (isUser) vm.resendAt(i)
                                },
                                onEdit = {
                                    menuIndex = null
                                    if (isUser) {
                                        editText = msg.content
                                        editingIndex = i
                                    }
                                },
                                onDelete = {
                                    menuIndex = null
                                    deleteIndex = i
                                },
                                onOpenGenFile = { gf -> previewGenFile = gf },
                                onUndoFile = { path ->
                                    val msg = vm.undoFileWrite(activeId, path)
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                },
                                onUndoRound = {
                                    val msg = vm.undoRound(activeId, i)
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                },
                                onSaveSkill = {
                                    val msg = vm.saveFlowAsSkill(activeId, i)
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                },
                                onReadText = { readMsg = msg },
                                onSpeak = {
                                    val engine = tts
                                    if (engine == null || !ttsReady) {
                                        Toast.makeText(context, "朗读引擎未就绪", Toast.LENGTH_SHORT).show()
                                    } else if (engine.isSpeaking) {
                                        engine.stop()
                                    } else {
                                        engine.language = java.util.Locale.CHINA
                                        engine.speak(
                                            msg.content.take(4000),
                                            android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "msg_$i"
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 当前生效的技能 / 技能链 / 手写指令：优先用会话里记录的结构化来源；
        // 老会话（升级前只存了 prompt 文本）回退到按文本反查技能链、技能。
        val activeSetup = remember(sessions, activeId) {
            val s = sessions.find { it.id == activeId } ?: return@remember null
            val skills = AppSettings.loadSkills()
            val chains = AppSettings.loadSkillChains()

            fun chainSetup(c: com.localllm.server.core.SkillChain) = ActiveSetup(
                "⛓ 技能链：" + c.name,
                c.skillIds.mapNotNull { cid -> skills.firstOrNull { it.id == cid }?.name }
                    .joinToString(" → ").ifBlank { "步骤缺失，建议重新选择" }
            )

            fun skillSetup(sk: com.localllm.server.core.Skill) =
                ActiveSetup("⚡ 技能：" + sk.name, sk.desc)

            // 技能链由执行器逐步驱动，不再往 systemPrompt 里写提示词，
            // 所以结构化来源必须先查——否则 systemPrompt 为空就把横幅整个挡掉了
            s.appliedChainId?.let { id -> chains.firstOrNull { it.id == id } }
                ?.let { return@remember chainSetup(it) }
            s.appliedSkillId?.let { id -> skills.firstOrNull { it.id == id } }
                ?.let { return@remember skillSetup(it) }

            val cur = s.systemPrompt ?: return@remember null
            skills.firstOrNull { it.prompt == cur }?.let { return@remember skillSetup(it) }
            chains.firstOrNull { AppSettings.chainPrompt(it) == cur }?.let { return@remember chainSetup(it) }
            ActiveSetup("📝 本会话专用指令", "${cur.length} 字，可在此取消")
        }
        activeSetup?.let { a ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            a.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (a.sub.isNotBlank()) {
                            Text(
                                a.sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                maxLines = 1
                            )
                        }
                    }
                    TextButton(onClick = { vm.setSessionSystemPrompt(null) }) { Text("取消") }
                }
            }
        }
        pendingFile?.let { f ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📄 已附带文件：${f.first}（${f.second.length} 字）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            previewFileName = f.first
                            previewFileText = f.second
                            showFilePreview = true
                        }
                )
                TextButton(onClick = {
                    previewFileName = f.first
                    previewFileText = f.second
                    showFilePreview = true
                }) { Text("预览") }
                TextButton(onClick = vm::clearPendingFile) { Text("移除") }
            }
        }
        // 音视频附件：以前完全不在界面上显示，用户以为没加上
        mediaNote?.let { note ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🎬 附件：$note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = vm::clearPendingMedia) { Text("移除") }
            }
        }
        if (pendingImage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🖼 已附带图片 · " + (imageNote ?: "已处理"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { previewImageB64 = pendingImage }) { Text("预览") }
                TextButton(onClick = vm::clearPendingImage) { Text("移除") }
            }
        }

        // 剪贴板里的文件 → 一键插入路径（等价于桌面端「粘贴文件」）
        clipRef?.let { (name, path) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📎 剪贴板：$name",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "插入路径",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            val cur = input
                            vm.onInput(
                                if (cur.isBlank() || cur.endsWith(" ")) cur + path + " "
                                else "$cur $path "
                            )
                            clipRef = null
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    "忽略",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { clipRef = null }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        // 运行预览摘要行：一行，紧贴输入框上方，点开是画中画弹窗
        SandboxPanel()

        // 排队中的指令：本会话在生成时发来的下一条，跑完自动开始
        val myQueue = queue.filter { it.sid == activeId }
        if (myQueue.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "排队 ${myQueue.size} 条 · 下一条：" + myQueue.first().text.take(24),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "清空",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.clearQueue() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        // 计划模式：模型给了计划之后，这里一键退出计划模式并让它照着做
        val planModeOn = sessions.find { it.id == activeId }?.planMode == true
        val lastAssistant = messages.lastOrNull()
        if (planModeOn && !streaming && lastAssistant?.role == "assistant" &&
            !lastAssistant.content.isNullOrBlank()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable {
                        vm.setSessionPlanMode(activeId, false)
                        vm.onInput("按上面的计划执行")
                        vm.send()
                    }
                ) {
                    Text(
                        "按计划执行",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 输入区：一个撑满宽度的胶囊输入框，按钮都嵌在框内（左：加号，右：语音/发送自动切换）。
        // 语音和发送占同一个位置，框里有内容就变发送——两个按钮常驻会把输入宽度吃掉。
        val canSend = input.isNotBlank() || pendingImage != null || pendingFile != null || mediaNote != null
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = { showAttachSheet = true },
                    enabled = !streaming,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "添加附件",
                        tint = if (streaming) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.isEmpty()) {
                        Text(
                            "给 混沌智核 发消息…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = vm::onInput,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 4,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                            MaterialTheme.colorScheme.primary
                        )
                    )
                }
                // 圆环：只在有实际用量时出现（空会话/刚切过来都没有数据）
                usage?.let { u ->
                    val limit = contextUsage?.second ?: 0
                    if (limit > 0) {
                        val pct = ((u.prompt + u.output) * 100L / limit).toInt().coerceIn(0, 100)
                        UsageRing(pct = pct, onClick = { showUsageSheet = true })
                    }
                }
                when {
                    streaming -> IconButton(
                        onClick = vm::stop,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    canSend -> IconButton(
                        onClick = vm::send,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    else -> IconButton(
                        onClick = {
                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) startSpeech()
                            else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "语音输入",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 底部抽屉：相册 / 拍照 / 文件 + 本对话（权限、计划模式）
        if (showAttachSheet) {
            val cur = sessions.find { it.id == activeId }
            AttachSheet(
                permissionLabel = permissionLabel(cur?.permission ?: com.localllm.server.core.AppSettings.defaultPermission),
                planMode = cur?.planMode == true,
                autoDrive = cur?.autoDrive == true,
                planWithCloud = cur?.planWithCloud == true,
                onDismiss = { showAttachSheet = false },
                onGallery = {
                    showAttachSheet = false
                    pickFromGallery.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onCamera = {
                    showAttachSheet = false
                    launchCamera()
                },
                onFile = {
                    showAttachSheet = false
                    pickFile.launch(
                        arrayOf(
                            "text/*",
                            "application/json", "application/xml", "application/javascript",
                            "application/x-yaml", "application/csv",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                    )
                },
                onPermission = {
                    showAttachSheet = false
                    permissionEditing = true
                },
                onPlanMode = { on -> vm.setSessionPlanMode(activeId, on) },
                onAutoDrive = { on -> vm.setSessionAutoDrive(activeId, on) },
                onPlanWithCloud = { on -> vm.setSessionPlanWithCloud(activeId, on) }
            )
        }

        // 权限档位：只影响这条会话之后的行为
        if (permissionEditing) {
            val curLevel = sessions.find { it.id == activeId }?.permission
                ?: com.localllm.server.core.AppSettings.defaultPermission
            AlertDialog(
                onDismissRequest = { permissionEditing = false },
                title = { Text("权限") },
                text = {
                    Column {
                        Text(
                            "执行命令、修改文件前要不要先问你。只影响这条会话。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PermissionOption(
                            title = "仅可查看",
                            desc = "只看文件，不写不改、不跑命令",
                            selected = curLevel == "read"
                        ) {
                            vm.setSessionPermission(activeId, "read")
                            permissionEditing = false
                        }
                        PermissionOption(
                            title = "工作区内修改",
                            desc = "可以改文件、跑命令，但每次动手前先问你",
                            selected = curLevel == "workspace"
                        ) {
                            vm.setSessionPermission(activeId, "workspace")
                            permissionEditing = false
                        }
                        PermissionOption(
                            title = "完全权限",
                            desc = "不再确认，直接执行（危险操作也不问）",
                            selected = curLevel == "full"
                        ) {
                            vm.setSessionPermission(activeId, "full")
                            permissionEditing = false
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { permissionEditing = false }) { Text("关闭") }
                }
            )
        }

        // 会话详情（轨迹 / 改动 / 文件 / 进度 / 上下文）
        if (showDetail) {
            val curSession = sessions.find { it.id == activeId }
            if (curSession != null) {
                SessionDetailDialog(
                    session = curSession,
                    usage = usage,
                    contextLimit = contextUsage?.second ?: 0,
                    onDismiss = { showDetail = false },
                    onOpenGenFile = { gf -> previewGenFile = gf }
                )
            }
        }

        // 用量面板：输入框右边圆环点开
        val curUsage = usage
        if (showUsageSheet && curUsage != null) {
            UsageSheet(
                usage = curUsage,
                limit = contextUsage?.second ?: 0,
                onDismiss = { showUsageSheet = false }
            )
        }
    }
}

/**
 * 输入区「+」打开的底部抽屉：相册 / 拍照 / 文件，下面是「本对话」的权限与计划模式。
 * 常驻按钮只留一个加号——相册、文件、语音挤在同一行时，输入框会被压得很窄。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DiffDialog(gf: ChatStore.GenFile, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val lines = remember(gf.path, gf.content, gf.prev) {
        if (gf.prev == null) emptyList() else diffLines(gf.prev, gf.content)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    gf.path.substringAfterLast('/').ifBlank { gf.path },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    gf.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (gf.prev == null) {
                    Text(
                        "这是新建的文件，共 ${gf.content.lineSequence().count()} 行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (lines.isEmpty()) {
                    Text("内容没有变化。", style = MaterialTheme.typography.bodySmall)
                } else {
                    val box = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(box)
                    ) {
                        lines.forEach { (sign, text) ->
                            Text(
                                "$sign $text",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = when (sign) {
                                    '+' -> MaterialTheme.colorScheme.primary
                                    '-' -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onOpen) { Text("打开文件") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/** 本会话里最后一次产出的文件路径（产物卡片「定位」用） */
internal fun permissionLabel(level: String): String = when (level) {
    "read" -> "仅可查看"
    "full" -> "完全权限"
    else -> "工作区内修改"
}

@Composable
private fun PermissionOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 会话页专用的字形表：只在消息区生效，不动设置页和顶栏。
 * 行高比字号大约 1.5 倍——手机上看长段落，行距不够会明显发闷。
 */
internal fun chatTypography(base: androidx.compose.material3.Typography): androidx.compose.material3.Typography =
    base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 25.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp)
    )

/** 工具行怎么显示：图标、参数块的标签、动词。按 label 前缀判（时间线落盘的就是这些中文前缀）。 */
internal data class ToolStyle(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val argTitle: String,
)

internal fun toolStyle(label: String): ToolStyle = when {
    label.startsWith("执行命令") || label.startsWith("运行 ") -> ToolStyle(Icons.Default.PlayArrow, "命令")
    label.startsWith("写入") -> ToolStyle(Icons.Default.Edit, "路径")
    label.startsWith("编辑") -> ToolStyle(Icons.Default.Edit, "路径")
    label.startsWith("读取") -> ToolStyle(Icons.Default.Description, "路径")
    label.startsWith("列出") -> ToolStyle(Icons.Default.List, "目录")
    label.startsWith("搜索文件") -> ToolStyle(Icons.Default.Search, "模式")
    label.startsWith("生成文档") -> ToolStyle(Icons.Default.InsertDriveFile, "文件")
    label.startsWith("运行自检") -> ToolStyle(Icons.Default.Language, "页面")
    label.startsWith("记住") -> ToolStyle(Icons.Default.Save, "内容")
    label.startsWith("调用工具") || label.startsWith("自动修正") ->
        ToolStyle(Icons.Default.Extension, "参数")
    label.startsWith("已拒绝") -> ToolStyle(Icons.Default.Block, "内容")
    label.startsWith("文件：") -> ToolStyle(Icons.Default.Edit, "参数")
    else -> ToolStyle(Icons.Default.PlayArrow, "参数")
}

/** 从步骤参数里取被操作的文件路径（参数可能是截断的 JSON，path 总在最前面） */
internal fun pathOfStep(args: String?): String? = args?.let {
    Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)
}
