package com.localllm.server.ui

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.server.App
import com.localllm.server.core.AppSettings
import com.localllm.server.core.HfMirrorClient
import com.localllm.server.core.ModelFile
import com.localllm.server.core.ServerProcessManager
import com.localllm.server.core.ServerService
import com.localllm.server.core.ServerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

data class SettingsUiState(
    val port: String = "8080",
    val ctxSize: String = "4096",
    val threads: String = "0",
    val ngl: String = "0",
    val lanEnabled: Boolean = false,
    val apiToken: String = "",
    val apiTokenEnabled: Boolean = false,
    val cacheTypeK: String = "",
    val cacheTypeV: String = "",
    val specType: String = "none",
    val faMode: String = "",
    val thinkEnabled: Boolean = true,
    val chatCloudEnabled: Boolean = false,
    val chatCloudUrl: String = "",
    val chatCloudKey: String = "",
    val chatCloudModel: String = "",
    val savedHint: String = ""
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val serverState: StateFlow<ServerUiState> = ServerProcessManager.state
    val logLines: StateFlow<List<String>> = ServerProcessManager.logLines

    private val _models = MutableStateFlow<List<ModelFile>>(emptyList())
    val models: StateFlow<List<ModelFile>> = _models.asStateFlow()

    private val _projectors = MutableStateFlow<List<ModelFile>>(emptyList())
    val projectors: StateFlow<List<ModelFile>> = _projectors.asStateFlow()

    private val _selectedModel = MutableStateFlow<String?>(AppSettings.selectedModelPath)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()

    private val _selectedMmproj = MutableStateFlow<String?>(AppSettings.selectedMmprojPath)
    val selectedMmproj: StateFlow<String?> = _selectedMmproj.asStateFlow()

    private val _settings = MutableStateFlow(
        SettingsUiState(
            port = AppSettings.port.toString(),
            ctxSize = AppSettings.ctxSize.toString(),
            threads = AppSettings.threads.toString(),
            ngl = AppSettings.ngl.toString(),
            lanEnabled = AppSettings.lanEnabled,
            apiToken = AppSettings.apiToken,
            apiTokenEnabled = AppSettings.apiTokenEnabled,
            cacheTypeK = AppSettings.cacheTypeK,
            cacheTypeV = AppSettings.cacheTypeV,
            specType = AppSettings.specType,
            faMode = AppSettings.faMode,
            thinkEnabled = AppSettings.thinkEnabled,
            chatCloudEnabled = AppSettings.chatCloudEnabled,
            chatCloudUrl = AppSettings.chatCloudUrl,
            chatCloudKey = AppSettings.chatCloudKey,
            chatCloudModel = AppSettings.chatCloudModel
        )
    )
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    // ---- 在线下载（hf-mirror） ----
    val recommendedRepos = listOf(
        "Qwen/Qwen2.5-0.5B-Instruct-GGUF"
    )

    private val _searchResults = MutableStateFlow<List<HfMirrorClient.RepoInfo>>(emptyList())
    val searchResults: StateFlow<List<HfMirrorClient.RepoInfo>> = _searchResults.asStateFlow()

    private val _browsingRepo = MutableStateFlow<String?>(null)
    val browsingRepo: StateFlow<String?> = _browsingRepo.asStateFlow()

    private val _repoFiles = MutableStateFlow<List<HfMirrorClient.FileInfo>>(emptyList())
    val repoFiles: StateFlow<List<HfMirrorClient.FileInfo>> = _repoFiles.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    data class DownloadState(
        val id: Long,
        val name: String,
        val readBytes: Long,
        val totalBytes: Long,
        val speedBps: Long = 0,
        val finished: Boolean = false,
        val error: String? = null,
        val paused: Boolean = false,
        val repoId: String = "",
        val filePath: String = "",
        val asProjector: Boolean = false
    )

    private val _downloads = MutableStateFlow<List<DownloadState>>(emptyList())
    val downloads: StateFlow<List<DownloadState>> = _downloads.asStateFlow()

    private val downloadJobs = mutableMapOf<Long, Job>()
    private val pollerJobs = mutableMapOf<Long, Job>()
    private val targetFiles = mutableMapOf<Long, File>()

    fun searchRepos(query: String) {
        if (query.isBlank() || _searching.value) return
        _searching.value = true
        viewModelScope.launch {
            runCatching { HfMirrorClient.searchRepos(query) }
                .onSuccess { list ->
                    if (list.isEmpty()) {
                        _actionError.value = "未找到相关仓库，已恢复推荐列表"
                        showRecommendedRepos()
                    } else {
                        _searchResults.value = list
                    }
                }
                .onFailure { _actionError.value = "搜索失败：${it.message}" }
            _searching.value = false
        }
    }

    fun showRecommendedRepos() {
        _searchResults.value = recommendedRepos.map { HfMirrorClient.RepoInfo(it, 0, 0) }
    }

    private val _repoReadme = MutableStateFlow<String?>(null) // 已弃用展示，仅保留状态避免多处改动

    fun openRepo(repoId: String) {
        _browsingRepo.value = repoId
        _repoFiles.value = emptyList()
        viewModelScope.launch {
            runCatching { HfMirrorClient.listGgufFiles(repoId) }
                .onSuccess { _repoFiles.value = it }
                .onFailure {
                    _actionError.value = "读取仓库文件失败：${it.message}"
                    _browsingRepo.value = null
                }
        }
    }

    fun closeRepo() {
        _browsingRepo.value = null
        _repoFiles.value = emptyList()
    }

    fun startDownload(repoId: String, filePath: String, asProjector: Boolean, allowDuringRestore: Boolean = false) {
        // 已有同文件任务：暂停态→继续；进行中→忽略；恢复场景→跳过
        val existing = _downloads.value.find {
            it.repoId == repoId && it.filePath == filePath && !it.finished && it.error == null
        }
        if (existing != null) {
            if (existing.paused) resumeDownload(existing.id)
            return
        }
        val active = _downloads.value.count { !it.finished && it.error == null && !it.paused }
        if (!allowDuringRestore && active >= 3) {
            _actionError.value = "最多同时进行 3 个下载，请等待进行中的完成"
            return
        }
        val id = (repoId + "|" + filePath).hashCode().toLong()
        val fileName = filePath.substringAfterLast('/')
        val dir = if (asProjector) App.modelRepository.projectorsDir else App.modelRepository.modelsDir
        val target = resolveTarget(dir, fileName)
        val url = HfMirrorClient.resolveUrl(repoId, filePath)
        writeMeta(target, repoId, filePath, asProjector)
        val startBytes = maxOf(
            File(dir, target.name + ".part").length(),
            File(dir, target.name + ".part.new").length()
        )
        _downloads.value = _downloads.value + DownloadState(
            id, fileName, startBytes, -1,
            repoId = repoId, filePath = filePath, asProjector = asProjector
        )
        persistDownloads()
        downloadJobs[id] = beginDownload(id, target, url, asProjector)
    }

    /** 继续已暂停的任务（从 .part 断点恢复） */
    fun resumeDownload(id: Long) {
        val d = _downloads.value.find { it.id == id } ?: return
        if (!d.paused || _streamingActive(id)) return
        val dir = if (d.asProjector) App.modelRepository.projectorsDir else App.modelRepository.modelsDir
        val target = resolveTarget(dir, d.name)
        writeMeta(target, d.repoId, d.filePath, d.asProjector)
        val startBytes = File(dir, target.name + ".part").length()
        _downloads.value = _downloads.value.map {
            if (it.id == id) it.copy(paused = false, speedBps = 0, readBytes = startBytes) else it
        }
        persistDownloads()
        downloadJobs[id] = beginDownload(id, target, HfMirrorClient.resolveUrl(d.repoId, d.filePath), d.asProjector)
    }

    private fun _streamingActive(id: Long) = downloadJobs[id]?.isActive == true

    private fun resolveTarget(dir: File, fileName: String): File {
        var target = File(dir, fileName)
        if (target.exists()) {
            val base = fileName.removeSuffix(".gguf").removeSuffix(".GGUF")
            var i = 1
            while (target.exists()) {
                target = File(dir, "$base($i).gguf")
                i++
            }
        }
        return target
    }

    private fun writeMeta(target: File, repoId: String, filePath: String, asProjector: Boolean) {
        runCatching {
            File(target.parentFile, target.name + ".part.meta").writeText(
                org.json.JSONObject()
                    .put("repo", repoId)
                    .put("file", filePath)
                    .put("proj", asProjector)
                    .toString()
            )
        }
    }

    private fun beginDownload(id: Long, target: File, url: String, asProjector: Boolean): Job {
        targetFiles[id] = target
        // 进度轮询器：直接读磁盘 .part 大小 —— 唯一进度事实源（对齐 DownloadManager 模式）
        val poller = viewModelScope.launch {
            var lastLen = File(target.parentFile, target.name + ".part").length()
            var lastMs = System.currentTimeMillis()
            var speed = 0L
            while (true) {
                kotlinx.coroutines.delay(500)
                val len = File(target.parentFile, target.name + ".part").length()
                val now = System.currentTimeMillis()
                if (len != lastLen) {
                    val raw = (len - lastLen) * 1000 / (now - lastMs).coerceAtLeast(1)
                    speed = if (speed > 0) (raw * 35 + speed * 65) / 100 else raw
                    lastLen = len
                    lastMs = now
                } else {
                    speed = speed * 7 / 10 // 无增长时缓降，避免暂停瞬间还显示高速
                }
                _downloads.value = _downloads.value.map {
                    if (it.id == id && !it.finished && it.error == null && !it.paused) {
                        it.copy(readBytes = len, speedBps = speed)
                    } else it
                }
            }
        }
        pollerJobs[id] = poller
        return viewModelScope.launch {
            runCatching {
                HfMirrorClient.download(url, target) { _, total ->
                    if (_downloads.value.firstOrNull { it.id == id }?.totalBytes != total) {
                        _downloads.value = _downloads.value.map {
                            if (it.id == id) it.copy(totalBytes = total) else it
                        }
                    }
                }
            }.onSuccess {
                poller.cancel(); pollerJobs.remove(id); targetFiles.remove(id)
                _downloads.value = _downloads.value.map {
                    if (it.id == id) it.copy(finished = true, speedBps = 0) else it
                }
                refreshModels()
                val model = ModelFile(target.name, file = target, sizeBytes = target.length())
                if (asProjector) selectMmproj(model) else selectModel(model)
                persistDownloads()
            }.onFailure { e ->
                poller.cancel(); pollerJobs.remove(id); targetFiles.remove(id)
                _downloads.value = _downloads.value.map {
                    if (it.id == id) it.copy(error = e.message ?: "下载失败", speedBps = 0) else it
                }
                persistDownloads()
            }
            downloadJobs.remove(id)
        }
    }

    /** 暂停：任务保留在列表（⏸ 已暂停），.part 保留，可继续或取消 */
    fun pauseDownload(id: Long) {
        downloadJobs[id]?.cancel()
        downloadJobs.remove(id)
        pollerJobs.remove(id)?.cancel()
        val t = targetFiles.remove(id)
        val len = t?.let { File(it.parentFile, it.name + ".part").length() } ?: 0L
        _downloads.value = _downloads.value.map {
            if (it.id == id) it.copy(paused = true, speedBps = 0, readBytes = len) else it
        }
        persistDownloads()
    }

    fun cancelDownload(id: Long) {
        downloadJobs[id]?.cancel()
        downloadJobs.remove(id)
        pollerJobs.remove(id)?.cancel()
        targetFiles.remove(id)?.let { t ->
            // 用户主动取消 = 不再续传，清理全部半成品
            File(t.parentFile, t.name + ".part").delete()
            File(t.parentFile, t.name + ".part.old").delete()
            File(t.parentFile, t.name + ".part.meta").delete()
        }
        _downloads.value = _downloads.value.filterNot { it.id == id }
        persistDownloads()
    }

    fun dismissDownload(id: Long) {
        pollerJobs.remove(id)?.cancel()
        targetFiles.remove(id)
        _downloads.value = _downloads.value.filterNot { it.id == id }
        persistDownloads()
    }

    /**
     * 云端渠道即时生效：改内存态并立刻落盘。
     * 聊天路由每次发送都直接读 AppSettings（磁盘），若只改 StateFlow 会与实际路由不一致
     * ——表现为"开关关了聊天还走云端、重启后又变回开"。
     */
    fun updateCloudSettings(transform: (SettingsUiState) -> SettingsUiState) {
        val s = transform(_settings.value)
        _settings.value = s
        AppSettings.chatCloudEnabled = s.chatCloudEnabled
        AppSettings.chatCloudUrl = s.chatCloudUrl.trim()
        AppSettings.chatCloudKey = s.chatCloudKey.trim()
        AppSettings.chatCloudModel = s.chatCloudModel.trim()
    }

    /** 从磁盘重新加载全部设置到 UI 状态（切换云端预设后调用） */
    fun reloadSettings() {
        _settings.value = _settings.value.copy(
            chatCloudEnabled = AppSettings.chatCloudEnabled,
            chatCloudUrl = AppSettings.chatCloudUrl,
            chatCloudKey = AppSettings.chatCloudKey,
            chatCloudModel = AppSettings.chatCloudModel
        )
    }

    /** 思考模式同理：每次发送现读 AppSettings.thinkEnabled，即改即存 */
    fun updateThinkEnabled(value: Boolean) {
        _settings.value = _settings.value.copy(thinkEnabled = value)
        AppSettings.thinkEnabled = value
    }

    fun generateToken(): String {
        val t = (1..24).joinToString("") { ("abcdefghijklmnopqrstuvwxyz0123456789").random().toString() }
        _settings.value = _settings.value.copy(apiToken = t)
        return t
    }

    init {
        refreshModels()
        restoreDownloads()
    }

    /** 进程重启后恢复未完成的下载（.part 断点续传） */
    private fun restoreDownloads() {
        val records = runCatching {
            val arr = org.json.JSONArray(AppSettings.downloadTasksJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Triple(o.optString("repo"), o.optString("file"), o.optBoolean("proj")) to o.optBoolean("paused")
            }
        }.getOrDefault(emptyList())
            .filter { it.first.first.isNotBlank() && it.first.second.isNotBlank() }

        records.forEach { (repoFile, paused) ->
            val (repo, file, proj) = repoFile
            val dir = if (proj) App.modelRepository.projectorsDir else App.modelRepository.modelsDir
            val dest = File(dir, file.substringAfterLast('/'))
            if (dest.exists()) return@forEach // 已完成
            if (paused) {
                val id = (repo + "|" + file).hashCode().toLong()
                val partLen = File(dir, dest.name + ".part").length()
                _downloads.value = _downloads.value + DownloadState(
                    id, dest.name, partLen, -1, paused = true,
                    repoId = repo, filePath = file, asProjector = proj
                )
            } else {
                startDownload(repo, file, proj, allowDuringRestore = true)
            }
        }

        // 孤儿断点：有 .part.meta 但没进记录的（如记录被清但文件还在）→ 自动入列续传
        for (proj in listOf(false, true)) {
            val dir = if (proj) App.modelRepository.projectorsDir else App.modelRepository.modelsDir
            dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }?.forEach { part ->
                val meta = File(part.path + ".meta")
                if (!meta.exists()) return@forEach
                runCatching { org.json.JSONObject(meta.readText()) }.getOrNull()?.let { o ->
                    val repo = o.optString("repo")
                    val file = o.optString("file")
                    if (repo.isNotBlank() && file.isNotBlank() &&
                        records.none { it.first.first == repo && it.first.second == file }
                    ) {
                        startDownload(repo, file, o.optBoolean("proj"), allowDuringRestore = true)
                    }
                }
            }
        }
    }

    private fun persistDownloads() {
        val arr = org.json.JSONArray()
        _downloads.value.filter { !it.finished && it.error == null }.forEach { d ->
            arr.put(
                org.json.JSONObject()
                    .put("repo", d.repoId)
                    .put("file", d.filePath)
                    .put("proj", d.asProjector)
                    .put("paused", d.paused)
            )
        }
        AppSettings.downloadTasksJson = arr.toString()
    }

    fun refreshModels() {
        val list = App.modelRepository.listChatModels()
        _models.value = list
        _projectors.value = App.modelRepository.listProjectors()
        val selected = AppSettings.selectedModelPath
        if (selected == null || list.none { it.file.absolutePath == selected }) {
            _selectedModel.value = null
        }
        val mmproj = AppSettings.selectedMmprojPath
        if (mmproj != null && _projectors.value.none { it.file.absolutePath == mmproj }) {
            AppSettings.selectedMmprojPath = null
            _selectedMmproj.value = null
        }
    }

    fun selectModel(model: ModelFile) {
        AppSettings.selectedModelPath = model.file.absolutePath
        _selectedModel.value = model.file.absolutePath
    }

    fun selectMmproj(model: ModelFile?) {
        val path = model?.file?.absolutePath
        AppSettings.selectedMmprojPath = path
        _selectedMmproj.value = path
    }

    fun importModel(uri: Uri, asProjector: Boolean) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            val result = App.modelRepository.importFromUri(uri, asProjector)
            _importing.value = false
            result.onSuccess { model ->
                refreshModels()
                if (asProjector) {
                    selectMmproj(model)
                } else {
                    selectModel(model)
                }
            }.onFailure { e ->
                _actionError.value = "导入失败：${e.message}"
            }
        }
    }

    fun deleteModel(model: ModelFile) {
        App.modelRepository.delete(model)
        refreshModels()
    }

    /** 启动自动检查到的新版本（非空时 Root 弹窗提示） */
    private val _autoUpdate = MutableStateFlow<com.localllm.server.core.UpdateChecker.Info?>(null)
    val autoUpdate: StateFlow<com.localllm.server.core.UpdateChecker.Info?> = _autoUpdate.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val ver = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
                val info = com.localllm.server.core.UpdateChecker.check(ver)
                if (info != null && info.version != AppSettings.lastPromptedUpdateVersion) {
                    _autoUpdate.value = info
                    ServerProcessManager.appLog("发现新版本 v${info.version}（自动检查）")
                }
            }
        }
    }

    fun dismissAutoUpdate(neverAgainForThisVersion: Boolean) {
        if (neverAgainForThisVersion) {
            _autoUpdate.value?.let { AppSettings.lastPromptedUpdateVersion = it.version }
        }
        _autoUpdate.value = null
    }

    fun startServer() {
        val modelPath = _selectedModel.value
        if (modelPath == null) {
            _actionError.value = "请先在「模型」页选择一个对话模型"
            return
        }
        saveSettings(silent = true)

        // 内存预检：仅提示不拦截——用户可自行决定是否冒险启动
        val model = _models.value.find { it.file.absolutePath == modelPath }
        val projSize = _projectors.value.sumOf { it.sizeBytes }
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val need = (model?.sizeBytes ?: 0L) + projSize + 1_610_612_736L
        if (mi.availMem < need) {
            _actionError.value =
                "内存紧张：预计需约 ${need / (1024 * 1024 * 1024)} GB，当前可用约 ${mi.availMem / (1024 * 1024 * 1024)} GB——仍为你启动，但可能失败或被系统回收"
        }
        // 启动本地服务即视为使用本地模型：聊天渠道自动切回本地
        if (AppSettings.chatCloudEnabled) {
            AppSettings.chatCloudEnabled = false
            _settings.value = _settings.value.copy(chatCloudEnabled = false)
            ServerProcessManager.appLog("已自动切换聊天渠道：本地模型（因启动本地服务）")
        }
        ServerService.start(getApplication(), modelPath)
    }

    fun stopServer() {
        ServerService.stop(getApplication())
    }

    fun clearError() {
        _actionError.value = null
    }

    fun clearLogs() {
        ServerProcessManager.clearLogs()
    }

    /** 有未保存改动（驱动设置页「保存」按钮的显隐） */
    private val _settingsDirty = MutableStateFlow(false)
    val settingsDirty: StateFlow<Boolean> = _settingsDirty.asStateFlow()

    fun updateSettings(transform: (SettingsUiState) -> SettingsUiState) {
        _settings.value = transform(_settings.value)
        _settingsDirty.value = true
    }

    fun saveSettings(silent: Boolean = false) {
        val s = _settings.value
        // 记录旧值：用于判断变更是否需要重启服务
        val oldServerKey = listOf(
            AppSettings.port, AppSettings.ctxSize, AppSettings.threads, AppSettings.ngl,
            AppSettings.lanEnabled, AppSettings.apiToken, AppSettings.cacheTypeK,
            AppSettings.cacheTypeV, AppSettings.specType, AppSettings.faMode
        ).joinToString("|")
        val oldThink = AppSettings.thinkEnabled
        val port = s.port.toIntOrNull()?.coerceIn(1024, 65535) ?: AppSettings.port
        val ctx = s.ctxSize.toIntOrNull()?.coerceIn(512, 262144) ?: AppSettings.ctxSize
        val threads = s.threads.toIntOrNull()?.coerceIn(0, 64) ?: AppSettings.threads
        val ngl = s.ngl.toIntOrNull()?.coerceIn(0, 999) ?: AppSettings.ngl
        AppSettings.port = port
        AppSettings.ctxSize = ctx
        AppSettings.threads = threads
        AppSettings.ngl = ngl
        AppSettings.lanEnabled = s.lanEnabled
        AppSettings.apiToken = s.apiToken.trim()
        AppSettings.apiTokenEnabled = s.apiTokenEnabled
        AppSettings.cacheTypeK = s.cacheTypeK.trim().lowercase()
        AppSettings.cacheTypeV = s.cacheTypeV.trim().lowercase()
        AppSettings.specType = s.specType.trim().lowercase().ifEmpty { "none" }
        AppSettings.faMode = s.faMode.trim().lowercase()
        AppSettings.thinkEnabled = s.thinkEnabled
        AppSettings.chatCloudEnabled = s.chatCloudEnabled
        AppSettings.chatCloudUrl = s.chatCloudUrl.trim()
        AppSettings.chatCloudKey = s.chatCloudKey.trim()
        AppSettings.chatCloudModel = s.chatCloudModel.trim()
        _settings.value = s.copy(
            port = port.toString(),
            ctxSize = ctx.toString(),
            threads = threads.toString(),
            ngl = ngl.toString(),
            cacheTypeK = AppSettings.cacheTypeK,
            cacheTypeV = AppSettings.cacheTypeV,
            specType = AppSettings.specType,
            faMode = AppSettings.faMode,
            thinkEnabled = AppSettings.thinkEnabled,
            chatCloudEnabled = AppSettings.chatCloudEnabled,
            chatCloudUrl = AppSettings.chatCloudUrl,
            chatCloudKey = AppSettings.chatCloudKey,
            chatCloudModel = AppSettings.chatCloudModel,
            savedHint = ""
        )
        if (!silent) {
            val newServerKey = listOf(
                AppSettings.port, AppSettings.ctxSize, AppSettings.threads, AppSettings.ngl,
                AppSettings.lanEnabled, AppSettings.apiToken, AppSettings.cacheTypeK,
                AppSettings.cacheTypeV, AppSettings.specType, AppSettings.faMode
            ).joinToString("|")
            _saveMessage.value = if (newServerKey != oldServerKey) {
                "设置成功，重启服务生效"
            } else if (AppSettings.thinkEnabled != oldThink) {
                "设置成功，已生效"
            } else {
                "设置成功"
            }
        }
        _settingsDirty.value = false
    }
}
