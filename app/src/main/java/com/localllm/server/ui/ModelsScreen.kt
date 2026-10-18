package com.localllm.server.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.server.core.GgufInfo
import com.localllm.server.core.GgufKind
import com.localllm.server.core.ModelFile
import com.localllm.server.core.AppSettings
import com.localllm.server.core.LocalModelParam
import com.localllm.server.core.ModelRepository

@Composable
fun ModelsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val models by vm.models.collectAsStateWithLifecycle()
    val projectors by vm.projectors.collectAsStateWithLifecycle()
    val ggufInfo by vm.ggufInfo.collectAsStateWithLifecycle()
    val selected by vm.selectedModel.collectAsStateWithLifecycle()
    val selectedMmproj by vm.selectedMmproj.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()

    // 待确认类型的导入 URI（先选文件，再选「对话模型 / 多模态投影」）
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var renamingModel by remember { mutableStateOf<ModelFile?>(null) }
    var paramModel by remember { mutableStateOf<ModelFile?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelFile?>(null) }
    var emptyTrashAsk by remember { mutableStateOf(false) }
    var trashOpen by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 回收站在列表最底部，展开后条目会落在屏幕外：自动滚到底，让用户看到刚展开的内容
    androidx.compose.runtime.LaunchedEffect(trashOpen) {
        if (trashOpen) {
            kotlinx.coroutines.delay(120)
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }
    var renameText by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingUri = uri
    }

    renamingModel?.let { m ->
        AlertDialog(
            onDismissRequest = { renamingModel = null },
            title = { Text("模型命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("显示名称（留空恢复默认）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setAlias(m.file.absolutePath, if (renameText.trim() == m.name) "" else renameText)
                    renamingModel = null
                    vm.refreshModels()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingModel = null }) { Text("取消") }
            }
        )
    }

    pendingUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text("选择模型类型") },
            text = { Text("这个 GGUF 文件属于哪一类？\n（仅用于存放分类，与文件名无关）") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUri = null
                    vm.importModel(uri, asProjector = false)
                }) { Text("对话模型") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingUri = null }) { Text("取消") }
                    TextButton(onClick = {
                        pendingUri = null
                        vm.importModel(uri, asProjector = true)
                    }) { Text("多模态投影") }
                }
            }
        )
    }

    pendingDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型？") },
            text = {
                Text(
                    "${AppSettings.aliasFor(m.file.absolutePath) ?: m.name}（${formatSize(m.sizeBytes)}）\n\n" +
                            "会先移到回收站，${ModelRepository.TRASH_KEEP_DAYS} 天内可以恢复；" +
                            "到期自动清理，也可以现在就去回收站点「彻底删除」。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    vm.deleteModel(m)
                    Toast.makeText(context, "已移入回收站，可在列表底部恢复", Toast.LENGTH_SHORT).show()
                }) { Text("移到回收站") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    if (emptyTrashAsk) {
        AlertDialog(
            onDismissRequest = { emptyTrashAsk = false },
            title = { Text("清空回收站？") },
            text = {
                Text("回收站里 ${trash.size} 个文件（共 ${formatSize(trash.sumOf { it.sizeBytes })}）会被彻底删除，无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    emptyTrashAsk = false
                    vm.emptyTrash()
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { emptyTrashAsk = false }) { Text("取消") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("模型管理", style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !importing
            ) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导入中…")
                } else {
                    Text("导入 GGUF")
                }
            }
        }
        Text(
            "导入时选择类型：对话模型在上、多模态投影在下。" +
                    "启动服务时主模型必选，多模态可选可不选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )

        paramModel?.let { m ->
            LocalModelParamsDialog(model = m, projectors = projectors, onDismiss = { paramModel = null })
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (models.isEmpty()) {
                item { Text("还没有对话模型，点右上角「导入 GGUF」。", style = MaterialTheme.typography.bodyMedium) }
            }
            items(models, key = { "chat:" + it.file.absolutePath }) { model ->
                ModelCard(
                    model = model,
                    selected = model.file.absolutePath == selected,
                    onSelect = { vm.selectModel(model) },
                    onDelete = { pendingDelete = model },
                    onRename = { mm -> renameText = AppSettings.aliasFor(mm.file.absolutePath) ?: mm.name; renamingModel = mm },
                    onParams = { paramModel = it },
                    info = ggufInfo[model.file.absolutePath]
                )
            }

            item {
                Text(
                    "多模态投影（可选，启动时按下方选配附加）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMmproj == null,
                        onClick = { vm.selectMmproj(null) }
                    )
                    Text("不使用多模态", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(projectors, key = { "proj:" + it.file.absolutePath }) { proj ->
                ModelCard(
                    model = proj,
                    selected = proj.file.absolutePath == selectedMmproj,
                    onSelect = { vm.selectMmproj(proj) },
                    onDelete = { pendingDelete = proj },
                    onRename = { mm -> renameText = AppSettings.aliasFor(mm.file.absolutePath) ?: mm.name; renamingModel = mm },
                    onParams = { paramModel = it },
                    info = ggufInfo[proj.file.absolutePath],
                    expected = GgufKind.MMPROJ
                )
            }
            if (projectors.isEmpty()) {
                item {
                    Text(
                        "（还没有投影文件，导入时选「多模态投影」类型）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 回收站默认收起：占地方又容易误碰，点标题才展开
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 4.dp)
                        .clickable { trashOpen = !trashOpen },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "回收站（${trash.size}）",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Icon(
                            if (trashOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (trashOpen) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (trashOpen && trash.isNotEmpty()) {
                        TextButton(onClick = { emptyTrashAsk = true }) { Text("清空") }
                    }
                }
            }
            if (trashOpen && trash.isEmpty()) {
                item {
                    Text(
                        "（空。删除的模型会先放这里，${ModelRepository.TRASH_KEEP_DAYS} 天内可恢复）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trashOpen) items(trash, key = { "trash:" + it.file.absolutePath }) { t ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                t.originalName,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                formatSize(t.sizeBytes) + " · 删除于 " + formatTrashTime(t.deletedAt) +
                                        (if (t.wasProjector) " · 投影" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            vm.restoreTrashed(t)
                            Toast.makeText(context, "已恢复：${t.originalName}", Toast.LENGTH_SHORT).show()
                        }) { Text("恢复") }
                        TextButton(onClick = { vm.purgeTrashed(t) }) {
                            Text("彻底删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelFile, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit,
                      onRename: (ModelFile) -> Unit = {}, onParams: (ModelFile) -> Unit = {},
                      info: GgufInfo? = null, expected: GgufKind = GgufKind.MAIN) {
    // 只在"放错类别"时提示：对话区里出现草稿/投影器，或投影区里出现主模型
    val mismatch: String? = when {
        info == null -> null
        info.kind == expected -> null
        info.kind == GgufKind.UNKNOWN -> null
        expected == GgufKind.MMPROJ && info.kind == GgufKind.MAIN -> "这个文件其实是主模型，不是投影器"
        else -> info.warning
    }
    val alias = AppSettings.aliasFor(model.file.absolutePath)
    val custom = AppSettings.localModelParam(model.file.absolutePath) != null
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(alias ?: model.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                Text(
                    (if (alias != null) model.name + " · " else "") + formatSize(model.sizeBytes) +
                            (if (custom) " · 参数已定制" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (custom) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 头部判定：放错类别时提示（对话区里出现草稿/投影器，或投影区里出现主模型）
                mismatch?.let {
                    Text(
                        "⚠ $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(onClick = { onParams(model) }) {
                Icon(
                    Icons.Default.Tune, contentDescription = "参数",
                    tint = if (custom) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 重命名/删除收进二级菜单：卡片上少一个误触入口，删除还有确认弹窗兜底
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert, contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename(model) }
                    )
                    DropdownMenuItem(
                        text = {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** 本地模型参数弹窗（精简版：上下文 / 输出上限 / mmproj 绑定；留空=跟随全局） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalModelParamsDialog(
    model: ModelFile,
    projectors: List<ModelFile>,
    onDismiss: () -> Unit
) {
    val path = model.file.absolutePath
    val existing = remember { AppSettings.localModelParam(path) }
    var ctx by remember { mutableStateOf(existing?.ctx?.toString() ?: "") }
    var out by remember { mutableStateOf(existing?.out?.toString() ?: "") }
    // ""=跟全局, "none"=明确不用, 其它=投影文件绝对路径
    var mm by remember { mutableStateOf(existing?.mmproj ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "模型参数 · " + (AppSettings.aliasFor(path) ?: model.name),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "留空 = 跟随全局（当前上下文 ${AppSettings.ctxSize}，输出上限默认 4096）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CompactField(
                    value = ctx,
                    onValueChange = { ctx = it.filter(Char::isDigit) },
                    label = "上下文长度（留空=全局）",
                    number = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(32768 to "32K", 65536 to "64K", 131072 to "128K", 262144 to "256K", -1 to "跟全局")
                        .forEach { (v, label) ->
                            FilterChip(
                                selected = if (v == -1) ctx.isBlank() else ctx == v.toString(),
                                onClick = { ctx = if (v == -1) "" else v.toString() },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                }
                CompactField(
                    value = out,
                    onValueChange = { out = it.filter(Char::isDigit) },
                    label = "输出上限（留空=默认 4096）",
                    number = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2048 to "2K", 4096 to "4K", 8192 to "8K", -1 to "跟全局")
                        .forEach { (v, label) ->
                            FilterChip(
                                selected = if (v == -1) out.isBlank() else out == v.toString(),
                                onClick = { out = if (v == -1) "" else v.toString() },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                }
                Text("多模态投影（mmproj）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = mm.isEmpty(),
                        onClick = { mm = "" },
                        label = { Text("跟全局", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = mm == "none",
                        onClick = { mm = "none" },
                        label = { Text("不使用", style = MaterialTheme.typography.labelSmall) }
                    )
                    projectors.forEach { p ->
                        FilterChip(
                            selected = mm == p.file.absolutePath,
                            onClick = { mm = p.file.absolutePath },
                            label = { Text(p.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppSettings.setLocalModelParam(
                    path,
                    LocalModelParam(
                        ctx = ctx.toIntOrNull(),
                        out = out.toIntOrNull(),
                        mmproj = mm.ifBlank { null }
                    )
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024 -> String.format("%.2f GB", mb / 1024)
        mb >= 1 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", bytes / 1024.0)
    }
}

private fun formatTrashTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
