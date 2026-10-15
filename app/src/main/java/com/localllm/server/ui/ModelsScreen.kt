package com.localllm.server.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import com.localllm.server.core.ModelFile
import com.localllm.server.core.AppSettings

@Composable
fun ModelsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val models by vm.models.collectAsStateWithLifecycle()
    val projectors by vm.projectors.collectAsStateWithLifecycle()
    val selected by vm.selectedModel.collectAsStateWithLifecycle()
    val selectedMmproj by vm.selectedMmproj.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()

    // 待确认类型的导入 URI（先选文件，再选「对话模型 / 多模态投影」）
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var renamingModel by remember { mutableStateOf<ModelFile?>(null) }
    var renameText by remember { mutableStateOf("") }

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

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (models.isEmpty()) {
                item { Text("还没有对话模型，点右上角「导入 GGUF」。", style = MaterialTheme.typography.bodyMedium) }
            }
            items(models, key = { "chat:" + it.file.absolutePath }) { model ->
                ModelCard(
                    model = model,
                    selected = model.file.absolutePath == selected,
                    onSelect = { vm.selectModel(model) },
                    onDelete = { vm.deleteModel(model) },
                    onRename = { mm -> renameText = AppSettings.aliasFor(mm.file.absolutePath) ?: mm.name; renamingModel = mm }
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
                    onDelete = { vm.deleteModel(proj) },
                    onRename = { mm -> renameText = AppSettings.aliasFor(mm.file.absolutePath) ?: mm.name; renamingModel = mm }
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
        }
    }
}

@Composable
private fun ModelCard(model: ModelFile, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit,
                      onRename: (ModelFile) -> Unit = {}) {
    val alias = AppSettings.aliasFor(model.file.absolutePath)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(alias ?: model.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                Text(
                    (if (alias != null) model.name + " · " else "") + formatSize(model.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onRename(model) }) {
                Icon(Icons.Default.Edit, contentDescription = "命名", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
}
