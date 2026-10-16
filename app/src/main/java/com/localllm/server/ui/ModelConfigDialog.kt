package com.localllm.server.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.server.core.AppSettings
import com.localllm.server.core.AppSettings.CloudModel
import com.localllm.server.core.AppSettings.CloudPreset
import com.localllm.server.core.OpenAiCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「模型」配置弹窗：多供应商（本地 + 任意 OpenAI 兼容云端）统一管理。
 * 交互参照通用厂商配置界面：顶部供应商 chips → 表单（地址/协议/Key/连接测试）
 * → 模型列表（拉取/手改/选当前）→ 底部 删除/克隆供应商 + 保存。
 * 保存才落盘；「设为当前聊天渠道」决定聊天实际走本地还是哪家云端。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val modelsList by vm.models.collectAsStateWithLifecycle()
    val selPath by vm.selectedModel.collectAsStateWithLifecycle()
    val localLabel = selPath
        ?.let { p -> modelsList.firstOrNull { it.file.absolutePath == p } }
        ?.let { AppSettings.aliasFor(it.file.absolutePath) ?: it.name }
        ?: "未选择"

    var suppliers by remember { mutableStateOf(AppSettings.loadCloudPresets()) }
    var activeTarget by remember {
        val saved = AppSettings.loadCloudPresets()
        mutableStateOf(
            if (!AppSettings.chatCloudEnabled || AppSettings.chatCloudUrl.isBlank()) "local"
            else saved.firstOrNull {
                it.url.trim().removeSuffix("/") == AppSettings.chatCloudUrl.trim().removeSuffix("/")
            }?.id ?: "local"
        )
    }
    var selId by remember { mutableStateOf(activeTarget) }

    // 当前编辑供应商的表单状态
    val initSel = suppliers.firstOrNull { it.id == selId }
    var fName by remember { mutableStateOf(initSel?.name ?: "") }
    var fUrl by remember { mutableStateOf(initSel?.url ?: "") }
    var fKey by remember { mutableStateOf(initSel?.key ?: "") }
    var fModels by remember { mutableStateOf(initSel?.models ?: emptyList()) }
    var fModel by remember { mutableStateOf(initSel?.model ?: "") }
    var keyVisible by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // 连接测试
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 模型编辑弹窗（-1 = 关闭；>= fModels.size 表示新增）
    var editIndex by remember { mutableStateOf(-1) }
    var eId by remember { mutableStateOf("") }
    var eLabel by remember { mutableStateOf("") }
    var eCtx by remember { mutableStateOf("") }
    var eOut by remember { mutableStateOf("") }
    var eMods by remember { mutableStateOf(listOf("文本")) }
    var eError by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf(false) }

    LaunchedEffect(selId) {
        testStatus = null
        testOk = false
        saveError = null
        val s = suppliers.firstOrNull { it.id == selId } ?: return@LaunchedEffect
        fName = s.name
        fUrl = s.url
        fKey = s.key
        fModels = s.models
        fModel = s.model
    }

    fun commitSupplier() {
        val idx = suppliers.indexOfFirst { it.id == selId }
        if (idx < 0) return
        suppliers = suppliers.toMutableList().also {
            it[idx] = it[idx].copy(name = fName, url = fUrl, key = fKey, models = fModels, model = fModel)
        }
    }

    fun selectChip(id: String) {
        commitSupplier()
        selId = id
    }

    fun genId(): String {
        var n = suppliers.size + 1
        while (suppliers.any { it.id == "sup$n" }) n++
        return "sup$n"
    }

    fun addSupplier() {
        commitSupplier()
        val id = genId()
        var name = "新供应商"
        var k = 2
        while (suppliers.any { it.name == name }) {
            name = "新供应商$k"
            k++
        }
        suppliers = suppliers + CloudPreset(id = id, name = name, url = "", key = "", model = "")
        selId = id
    }

    fun cloneSupplier() {
        commitSupplier()
        val s = suppliers.firstOrNull { it.id == selId } ?: return
        val copy = s.copy(id = genId(), name = s.name + " 副本")
        val idx = suppliers.indexOfFirst { it.id == s.id }
        suppliers = suppliers.toMutableList().also { it.add(idx + 1, copy) }
        selId = copy.id
    }

    fun runTest() {
        if (selId == "local" || testing) return
        if (fUrl.isBlank()) {
            testOk = false
            testStatus = "请先填写接口地址"
            return
        }
        testing = true
        testStatus = null
        testOk = false
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) { OpenAiCompat.fetchModels(fUrl, fKey) }
            }
            r.onSuccess { list ->
                if (list.isEmpty()) {
                    testStatus = "连接成功，但接口没有返回模型"
                    testOk = false
                } else {
                    // upsert：已有条目保留用户的显示名/限额/模态，新 id 追加
                    val missing = list.filter { id -> fModels.none { it.id == id } }.map { CloudModel(id = it) }
                    fModels = fModels + missing
                    if (fModel.isBlank()) fModel = fModels.first().id
                    testStatus = "${fModels.size} 个模型"
                    testOk = true
                }
            }.onFailure { e ->
                testStatus = "拉取失败：${e.message ?: e.javaClass.simpleName}"
                testOk = false
            }
            testing = false
        }
    }

    fun openEdit(index: Int?) {
        if (index == null) {
            editIndex = fModels.size
            eId = ""
            eLabel = ""
            eCtx = "32768"
            eOut = "8192"
            eMods = listOf("文本")
        } else {
            val m = fModels[index]
            editIndex = index
            eId = m.id
            eLabel = m.label
            eCtx = m.ctx.toString()
            eOut = m.out.toString()
            eMods = m.modalities
        }
        eError = null
    }

    fun confirmEdit() {
        val id = eId.trim()
        if (id.isBlank()) {
            eError = "模型 ID 不能为空"
            return
        }
        if (fModels.withIndex().any { it.index != editIndex && it.value.id == id }) {
            eError = "已存在相同的模型 ID"
            return
        }
        val ctx = eCtx.filter(Char::isDigit).toIntOrNull() ?: 32768
        val out = eOut.filter(Char::isDigit).toIntOrNull() ?: 8192
        val m = CloudModel(
            id = id,
            label = eLabel.trim(),
            ctx = ctx,
            out = out,
            modalities = eMods.ifEmpty { listOf("文本") }
        )
        val wasSelected = fModels.getOrNull(editIndex)?.id == fModel
        fModels = if (editIndex in 0 until fModels.size) {
            fModels.mapIndexed { i, x -> if (i == editIndex) m else x }
        } else {
            fModels + m
        }
        if (fModel.isBlank() || wasSelected) fModel = m.id
        editIndex = -1
    }

    fun deleteModel(i: Int) {
        val m = fModels[i]
        fModels = fModels.filterIndexed { k, _ -> k != i }
        if (fModel == m.id) fModel = fModels.firstOrNull()?.id ?: ""
    }

    fun save() {
        saveError = null
        commitSupplier()
        val toSave = suppliers.mapIndexed { i, s ->
            s.copy(name = s.name.trim().ifBlank { "供应商${i + 1}" })
        }
        if (activeTarget != "local") {
            val act = toSave.firstOrNull { it.id == activeTarget }
            if (act == null || act.url.isBlank()) {
                saveError = "当前聊天渠道还没填接口地址"
                return
            }
        }
        AppSettings.saveCloudPresets(toSave)
        if (activeTarget == "local") {
            AppSettings.chatCloudEnabled = false
        } else {
            AppSettings.activateCloudPreset(toSave.first { it.id == activeTarget })
        }
        vm.reloadSettings()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("模型", fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "自定义模型使用你自己的密钥：填入各家的 API Key 即可在聊天里选用，仅保存到本地",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---- 供应商 chips ----
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { runTest() },
                        enabled = !testing && selId != "local",
                        modifier = Modifier.size(34.dp)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "重新拉取模型",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FilterChip(
                        selected = selId == "local",
                        onClick = { selectChip("local") },
                        label = { Text("本地模型", style = MaterialTheme.typography.labelSmall) }
                    )
                    suppliers.forEach { s ->
                        FilterChip(
                            selected = selId == s.id,
                            onClick = { selectChip(s.id) },
                            label = {
                                Text(
                                    s.name.ifBlank { "未命名" },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { addSupplier() },
                        label = { Text("＋ 添加供应商", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                if (selId == "local") {
                    // ---- 本地模型面板（只读，配置在别的页面） ----
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("本地模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "接口地址  http://127.0.0.1:${AppSettings.port}/v1",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "当前模型  $localLabel",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "GGUF 导入与服务参数在「模型管理」「服务设置」页配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("聊天使用本地模型", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = activeTarget == "local",
                            onCheckedChange = { on ->
                                activeTarget = if (on) "local" else suppliers.firstOrNull()?.id ?: "local"
                            }
                        )
                    }
                } else {
                    // ---- 云端供应商表单 ----
                    OutlinedTextField(
                        value = suppliers.firstOrNull { it.id == selId }?.id ?: selId,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("供应商 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = fName,
                        onValueChange = { fName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = fUrl,
                        onValueChange = { fUrl = it },
                        label = { Text("接口地址") },
                        placeholder = { Text("https://api.deepseek.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "MiMo" to "https://api.xiaomimimo.com",
                            "DeepSeek" to "https://api.deepseek.com",
                            "通义" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            "智谱" to "https://open.bigmodel.cn/api/paas/v4",
                            "Kimi" to "https://api.moonshot.cn/v1"
                        ).forEach { (label, url) ->
                            FilterChip(
                                selected = fUrl.trim().removeSuffix("/") == url,
                                onClick = { fUrl = url },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    // 接口协议（当前仅 OpenAI 兼容一种，保留字段以便将来扩展其他协议）
                    var protoExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = protoExpanded,
                        onExpandedChange = { protoExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = "/chat/completions（OpenAI 兼容格式）",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("接口协议") },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protoExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = protoExpanded,
                            onDismissRequest = { protoExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("/chat/completions（OpenAI 兼容格式）") },
                                onClick = { protoExpanded = false }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = fKey,
                        onValueChange = { fKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "显示或隐藏 Key",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 连接测试：拉取 {base}/models 并合并进模型列表（状态独立成行，避免挤压按钮）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("连接测试", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = { runTest() },
                            enabled = !testing,
                            modifier = Modifier.height(40.dp)
                        ) {
                            if (testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("测试连接")
                            }
                        }
                    }
                    if (testStatus != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (testOk) {
                                Text(
                                    "${fModels.size} 个模型",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier.size(8.dp)
                                ) {}
                            } else {
                                Text(
                                    testStatus!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // 当前聊天渠道（单选语义：打开=切到本渠道，关闭=回本地）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("设为当前聊天渠道", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = activeTarget == selId,
                            onCheckedChange = { on ->
                                activeTarget = if (on) selId else "local"
                            }
                        )
                    }
                    // 模型列表
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "模型列表",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(onClick = { openEdit(null) }) { Text("＋ 添加模型") }
                    }
                    if (fModels.isEmpty()) {
                        Text(
                            "还没有模型：点「测试连接」自动拉取，或手动「＋ 添加模型」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            fModels.forEachIndexed { i, m ->
                                ModelRow(
                                    model = m,
                                    selected = m.id == fModel,
                                    onSelect = { fModel = m.id },
                                    onEdit = { openEdit(i) },
                                    onDelete = { deleteModel(i) }
                                )
                            }
                        }
                    }
                }

                saveError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text("保存", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = if (selId == "local") {
            null
        } else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = { confirmDel = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("删除供应商") }
                    TextButton(onClick = { cloneSupplier() }) { Text("克隆供应商") }
                }
            }
        }
    )

    // ---- 模型编辑（新增/修改）----
    if (editIndex >= 0) {
        AlertDialog(
            onDismissRequest = { editIndex = -1 },
            title = {
                Text(if (editIndex >= fModels.size) "添加模型" else "编辑模型")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactOutlinedField(
                        value = eId,
                        onValueChange = { eId = it },
                        label = "模型 ID（请求时使用的上游名）",
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactOutlinedField(
                        value = eLabel,
                        onValueChange = { eLabel = it },
                        label = "显示名（可选）",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactOutlinedField(
                            value = eCtx,
                            onValueChange = { eCtx = it },
                            label = "上下文",
                            keyboardNumber = true,
                            modifier = Modifier.weight(1f)
                        )
                        CompactOutlinedField(
                            value = eOut,
                            onValueChange = { eOut = it },
                            label = "输出上限",
                            keyboardNumber = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // 上下文快捷候选（单行放不下时去掉 256K）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            "16384" to "16K",
                            "32768" to "32K",
                            "65536" to "64K",
                            "131072" to "128K"
                        ).forEach { (v, label) ->
                            FilterChip(
                                selected = eCtx.trim() == v,
                                onClick = { eCtx = v },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Text("模态", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("文本", "图像", "视频", "PDF").forEach { mod ->
                            FilterChip(
                                selected = mod in eMods,
                                onClick = {
                                    eMods = if (mod in eMods) eMods - mod else eMods + mod
                                },
                                label = { Text(mod, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    eError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmEdit() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editIndex = -1 }) { Text("取消") } }
        )
    }

    // ---- 删除供应商确认 ----
    if (confirmDel) {
        val sup = suppliers.firstOrNull { it.id == selId }
        AlertDialog(
            onDismissRequest = { confirmDel = false },
            title = { Text("删除供应商") },
            text = {
                Text(
                    "确定删除「${sup?.name ?: ""}」及其全部模型配置？\n" +
                            "如果它是当前聊天渠道，保存后将切回本地模型。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = selId
                        suppliers = suppliers.filterNot { it.id == id }
                        if (activeTarget == id) activeTarget = "local"
                        selId = suppliers.firstOrNull()?.id ?: "local"
                        confirmDel = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("取消") } }
        )
    }
}

/** 紧凑版描边输入框：BasicTextField + DecorationBox，上下内边距 6dp（默认约12dp），整体更矮 */
@Composable
private fun CompactOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardNumber: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (keyboardNumber) KeyboardType.Number else KeyboardType.Text
        ),
        modifier = modifier,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                singleLine = true,
                enabled = true,
                isError = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                label = { Text(label) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                innerTextField = inner
            )
        }
    )
}

/** 模型条目卡片：id + 上下文/输出 chips + 模态标签 + 编辑/删除；点卡片选为当前模型 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelRow(
    model: CloudModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    model.id,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Pill("${model.ctx}")
                Pill("输出 ${model.out}")
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (model.modalities.isNotEmpty()) {
                    Pill(model.modalities.joinToString(" · "))
                }
                if (selected) {
                    Pill(
                        "当前",
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            if (model.label.isNotBlank() && model.label != model.id) {
                Text(
                    model.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 小圆角标签（上下文/模态/状态用） */
@Composable
private fun Pill(
    text: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
