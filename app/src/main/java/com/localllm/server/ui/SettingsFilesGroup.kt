package com.localllm.server.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 设置页「文件与图片」分组：授权目录、危险操作确认、图片压缩、各项上限。
 *
 * 从 SettingsScreen 搬出来的第一组：块内状态都是自己的 remember，与其它分组没有共享，
 * 抽成独立 Composable 后设置页只负责分组折叠。
 */
@Composable
internal fun SettingGroupFiles() {

            // ---- 文件工具（授权目录）----
            val fileCtx = androidx.compose.ui.platform.LocalContext.current
            var fileOn by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.fileToolsEnabled)
            }
            var workDirName by remember {
                mutableStateOf(com.localllm.server.core.FileTools.rootName(fileCtx) ?: "")
            }
            val treeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        fileCtx.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    com.localllm.server.core.AppSettings.workDirTreeUri = uri.toString()
                    workDirName = com.localllm.server.core.FileTools.rootName(fileCtx)
                        ?: (uri.lastPathSegment ?: "")
                    fileOn = true
                    com.localllm.server.core.AppSettings.fileToolsEnabled = true
                    com.localllm.server.core.ServerProcessManager.appLog("已授权工作目录：$workDirName")
                }
            }
            SectionHeader(
                title = "文件工具（授权目录）",
                help = "授权一个文件夹后，模型可用 file_list / file_read / file_write / file_grep 在该目录内读写检索；" +
                        "写入自动建子目录。仅能访问你授权的目录，其他位置不可见。适合让它生成 HTML/代码/表格文件并直接落盘。\n\n" +
                        "提示：Android 不允许授权「内部存储根 / Download / Android/data」本身。" +
                        "请在内部存储根下新建专用文件夹（如 LlamaWork）并选中它，或选择 Download 下的子目录。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (workDirName.isBlank()) "未授权目录" else "当前目录：$workDirName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { treeLauncher.launch(null) }) { Text("选择目录") }
            }
            QuickChips(
                options = listOf("true" to "开启", "false" to "关闭"),
                current = fileOn.toString()
            ) { v ->
                fileOn = v.toBoolean()
                com.localllm.server.core.AppSettings.fileToolsEnabled = fileOn
            }

            // ---- 全盘文件访问（Linux 环境需要 bind /storage）----
            // targetSdk 28 + WRITE_EXTERNAL_STORAGE = legacy storage，即 /sdcard 全盘读写；
            // 授权后 Linux 环境（run_bash）里的 /storage 与文件工具目录是同一棵树。
            var storageGranted by remember {
                mutableStateOf(
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        fileCtx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            }
            val storageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                storageGranted = grants[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                if (storageGranted) {
                    com.localllm.server.core.ServerProcessManager.appLog("已开启全盘文件访问（legacy storage）")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (storageGranted) "已开启全盘文件访问"
                    else "未开启全盘访问——Linux 环境（run_bash）的 /storage 需要它",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = {
                    storageLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }) { Text(if (storageGranted) "已授权" else "开启全盘访问") }
            }

            // ---- 工作目录回收站 ----
            // Android 11+ 删掉的文件 30 天内仍留在工作目录里（名字带 .trashed-），占空间且看不见。
            var trashCount by remember { mutableStateOf<Int?>(null) }
            var trashBytes by remember { mutableStateOf(0L) }
            var purgePending by remember { mutableStateOf(false) }
            var purging by remember { mutableStateOf(false) }
            val trashScope = androidx.compose.runtime.rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        trashCount == null -> "回收站：点右侧扫描（删掉的文件 30 天内仍占空间）"
                        trashCount == 0 -> "回收站是空的"
                        else -> "回收站里有 ${trashCount} 项，共 ${trashBytes / 1024} KB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        purging = true
                        trashScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val (n, b) = com.localllm.server.core.FileTools.trashSummary(fileCtx)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                purging = false
                                trashCount = n
                                trashBytes = b
                                if (n > 0) purgePending = true
                            }
                        }
                    },
                    enabled = !purging && workDirName.isNotBlank()
                ) { Text(if (purging) "…" else "清理回收站") }
            }
            if (purgePending) {
                AlertDialog(
                    onDismissRequest = { purgePending = false },
                    title = { Text("永久删除回收站里 ${trashCount ?: 0} 项？") },
                    text = {
                        Text(
                            "共 ${trashBytes / 1024} KB，删除后无法恢复。\n\n" +
                                    "只清理工作目录（$workDirName）下的回收站文件，不动其他位置。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            purgePending = false
                            purging = true
                            trashScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val (ok, fail) = com.localllm.server.core.FileTools.purgeTrash(fileCtx)
                                val (n, b) = com.localllm.server.core.FileTools.trashSummary(fileCtx)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    purging = false
                                    trashCount = n
                                    trashBytes = b
                                    android.widget.Toast.makeText(
                                        fileCtx,
                                        if (fail == 0) "已删除 $ok 项"
                                        else "删除 $ok 项，$fail 项失败（可能被系统占用）",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { purgePending = false }) { Text("取消") }
                    }
                )
            }

            // ---- 危险操作确认 ----
            var approvalOn by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.toolApprovalEnabled)
            }
            SectionHeader(
                title = "危险操作确认",
                help = "开启后，模型每次要执行命令（run_bash）、写入/修改文件、或调用带写语义的 MCP 工具，" +
                        "都会先弹框把具体命令或路径给你看，点「允许」才真的执行；点「总是允许」只放行本会话内同类操作。\n\n" +
                        "危险命令（删除、改权限、磁盘操作、管道执行远程脚本等）会标红，并且每次都问、不给免打扰。" +
                        "确认框 10 分钟没人回答按拒绝处理。\n\n" +
                        "关掉 = 模型可以不经确认直接改你的文件。除非你在旁边盯着，否则不建议关。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("true" to "开启（推荐）", "false" to "关闭"),
                current = approvalOn.toString()
            ) { v ->
                approvalOn = v.toBoolean()
                com.localllm.server.core.AppSettings.toolApprovalEnabled = approvalOn
            }

            // ---- 图片压缩 ----
            var imgMode by remember {
                mutableStateOf(com.localllm.server.core.AppSettings.imageCompressMode)
            }
            SectionHeader(
                title = "图片压缩",
                help = "重编码是截图里小字、小数点糊掉的主因，所以默认不压缩：原图直传，" +
                        "只有超过 6MB 才会降采样（再大请求体会拖垮预填充）。\n\n" +
                        "自动：小图直传，大图压到长边 3072、质量 95。\n\n" +
                        "省上下文：一律压到长边 1600、质量 85——图片占的 token 最少，代价是小字容易糊。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("original" to "不压缩", "auto" to "自动", "small" to "省上下文"),
                current = imgMode
            ) { v ->
                imgMode = v
                com.localllm.server.core.AppSettings.imageCompressMode = v
            }

            // ---- 附件上限（文本文件）----
            var attachMb by remember {
                androidx.compose.runtime.mutableIntStateOf(com.localllm.server.core.AppSettings.attachmentMaxMb)
            }
            var attachPct by remember {
                androidx.compose.runtime.mutableIntStateOf(
                    com.localllm.server.core.AppSettings.attachmentBudgetPct
                )
            }
            SectionHeader(
                title = "附件上限",
                help = "文本类附件（代码/PDF/docx/xlsx 抽出的文本）的体积上限，以及它最多能占多少上下文。\n\n" +
                        "这两条只是本地兜底：用云端大模型时上下文更大，可以调高或设成不限。" +
                        "设成不限后，超长附件会一直送到模型的上下文上限为止（那时由模型端报错）。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "单个文本附件上限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            QuickChips(
                options = listOf("32" to "32MB", "64" to "64MB", "128" to "128MB", "0" to "不限"),
                current = attachMb.toString()
            ) { v ->
                attachMb = v.toInt()
                com.localllm.server.core.AppSettings.attachmentMaxMb = attachMb
            }
            Text(
                "附件最多占上下文",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            QuickChips(
                options = listOf("45" to "45%", "70" to "70%", "100" to "100%", "0" to "不截断"),
                current = attachPct.toString()
            ) { v ->
                attachPct = v.toInt()
                com.localllm.server.core.AppSettings.attachmentBudgetPct = attachPct
            }

            // ---- 其余本地上限 ----
            var resultPct by remember {
                androidx.compose.runtime.mutableIntStateOf(
                    com.localllm.server.core.AppSettings.toolResultBudgetPct
                )
            }
            var errChars by remember {
                androidx.compose.runtime.mutableIntStateOf(com.localllm.server.core.AppSettings.errorMaxChars)
            }
            SectionHeader(
                title = "其余本地上限",
                help = "这两条都是本地兜底，用云端大窗口时可以放开：\n\n" +
                        "· 工具结果回传上限：单个工具（读文件、搜索等）返回的内容最多占多少上下文，" +
                        "超了会砍掉中间部分。\n" +
                        "· 错误提示长度：界面上那条红字最多显示多少字。设成不限要小心——" +
                        "引擎有时会把几十 KB 的原始响应塞进错误里，铺到界面上会让页面卡死。",
                titleStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text("工具结果占上下文", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            QuickChips(
                options = listOf("17" to "17%", "33" to "33%", "50" to "50%", "0" to "不截断"),
                current = resultPct.toString()
            ) { v ->
                resultPct = v.toInt()
                com.localllm.server.core.AppSettings.toolResultBudgetPct = resultPct
            }
            Text("错误提示长度", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            QuickChips(
                options = listOf("300" to "300 字", "1000" to "1000 字", "0" to "不限"),
                current = errChars.toString()
            ) { v ->
                errChars = v.toInt()
                com.localllm.server.core.AppSettings.errorMaxChars = errChars
            }

            }
