package com.iris.localai.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.data.HfFile
import com.iris.localai.data.HfModel
import com.iris.localai.data.ModelScopeClient
import com.iris.localai.data.ModelSource
import com.iris.localai.data.ProviderKind
import com.iris.localai.data.RecommendedModel
import com.iris.localai.data.RecommendedModels
import com.iris.localai.data.RemoteFetch
import com.iris.localai.plugin.InstalledPlugin
import com.iris.localai.plugin.OperitMarketClient
import com.iris.localai.plugin.OperitMarketPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URLEncoder

enum class MarketSection(val label: String) {
    MODELS("模型市场"),
    PLUGINS("插件市场"),
}

enum class ModelMarketTab(val label: String) {
    LLM("LLM 本地模型"),
    CHARACTER("角色模型"),
}

data class GithubPluginRepo(
    val fullName: String,
    val description: String,
    val stars: Long,
    val branch: String,
    val htmlUrl: String,
)

class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph
    private val client = graph.huggingFace
    private val downloads = graph.downloads
    private val modelScope = ModelScopeClient()
    private val pluginStore = graph.plugins
    private val operitMarket = OperitMarketClient()
    private var repoRequest: Job? = null

    private val _section = MutableStateFlow(MarketSection.MODELS)
    val section: StateFlow<MarketSection> = _section.asStateFlow()

    private val _modelTab = MutableStateFlow(ModelMarketTab.LLM)
    val modelTab: StateFlow<ModelMarketTab> = _modelTab.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<HfModel>>(emptyList())
    val results: StateFlow<List<HfModel>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _selectedRepo = MutableStateFlow<String?>(null)
    val selectedRepo: StateFlow<String?> = _selectedRepo.asStateFlow()

    private val _selectedSource = MutableStateFlow(ModelSource.HUGGING_FACE)
    val selectedSource: StateFlow<ModelSource> = _selectedSource.asStateFlow()

    private val _files = MutableStateFlow<List<HfFile>>(emptyList())
    val files: StateFlow<List<HfFile>> = _files.asStateFlow()

    private val _loadingFiles = MutableStateFlow(false)
    val loadingFiles: StateFlow<Boolean> = _loadingFiles.asStateFlow()

    private val _downloaded = MutableStateFlow<List<File>>(emptyList())
    val downloaded: StateFlow<List<File>> = _downloaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _sourceNotice = MutableStateFlow<String?>(null)
    val sourceNotice: StateFlow<String?> = _sourceNotice.asStateFlow()

    val recommended: List<RecommendedModel> = RecommendedModels.items
    val tasks = downloads.tasks

    private val _plugins = pluginStore.plugins
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins

    private val _pluginBusy = pluginStore.busy
    val pluginBusy: StateFlow<Boolean> = _pluginBusy

    private val _pluginQuery = MutableStateFlow("toolpkg operit")
    val pluginQuery: StateFlow<String> = _pluginQuery.asStateFlow()

    private val _pluginResults = MutableStateFlow<List<GithubPluginRepo>>(emptyList())
    val pluginResults: StateFlow<List<GithubPluginRepo>> = _pluginResults.asStateFlow()

    private val _pluginSearching = MutableStateFlow(false)
    val pluginSearching: StateFlow<Boolean> = _pluginSearching.asStateFlow()

    private val _pluginError = MutableStateFlow<String?>(null)
    val pluginError: StateFlow<String?> = _pluginError.asStateFlow()

    private val _marketPlugins = MutableStateFlow<List<OperitMarketPlugin>>(emptyList())
    val marketPlugins: StateFlow<List<OperitMarketPlugin>> = _marketPlugins.asStateFlow()

    private val _marketSort = MutableStateFlow("downloads")
    val marketSort: StateFlow<String> = _marketSort.asStateFlow()

    private val _marketFilter = MutableStateFlow("")
    val marketFilter: StateFlow<String> = _marketFilter.asStateFlow()

    private val _marketLoading = MutableStateFlow(false)
    val marketLoading: StateFlow<Boolean> = _marketLoading.asStateFlow()

    private val _marketPage = MutableStateFlow(0)
    private val _marketHasMore = MutableStateFlow(false)
    val marketHasMore: StateFlow<Boolean> = _marketHasMore.asStateFlow()

    init {
        refreshDownloaded()
        loadPluginMarket(reset = true)
        viewModelScope.launch {
            downloads.tasks
                .map { list -> list.count { it.status == com.iris.localai.data.DownloadStatus.COMPLETED } }
                .distinctUntilChanged()
                .collect { refreshDownloaded() }
        }
    }

    fun setSection(value: MarketSection) {
        _section.value = value
    }

    fun setModelTab(value: ModelMarketTab) {
        _modelTab.value = value
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun search() {
        val query = _query.value.trim()
        if (query.isEmpty()) return
        _searching.value = true
        _error.value = null
        _sourceNotice.value = null
        _results.value = emptyList()
        _selectedRepo.value = null
        _files.value = emptyList()
        viewModelScope.launch {
            val mirrorsEnabled = graph.settings.current.mirrorEnabled
            val primary = client.search(query, graph.settings.current.downloadSource)
            val primaryItems = primary.getOrNull().orEmpty()
            val primaryError = primary.exceptionOrNull()
            if (primaryItems.isNotEmpty()) {
                _results.value = primaryItems.distinctBy { it.id }
            } else if (!mirrorsEnabled) {
                _error.value = primaryError?.let { "搜索失败：${it.message ?: "网络错误"}（备用源已关闭）" }
                    ?: "Hugging Face 未返回结果（备用源已关闭）"
            } else {
                val fallback = modelScope.search(query)
                fallback.onSuccess { items ->
                    _results.value = items.distinctBy { it.id }
                    _sourceNotice.value = when {
                        items.isNotEmpty() && primaryError != null ->
                            "Hugging Face 请求失败（${primaryError.message ?: "网络错误"}），已使用 ModelScope 备用检索"
                        items.isNotEmpty() -> "Hugging Face 未返回结果，已使用 ModelScope 备用检索"
                        primaryError != null ->
                            "Hugging Face 请求失败（${primaryError.message ?: "网络错误"}），ModelScope 也没有匹配结果"
                        else -> "Hugging Face 与 ModelScope 都没有匹配结果"
                    }
                }.onFailure { fallbackError ->
                    _error.value = buildString {
                        append("搜索失败：")
                        append(primaryError?.message ?: "Hugging Face 无结果")
                        append("；备用源：")
                        append(fallbackError.message ?: "ModelScope 不可用")
                    }
                }
            }
            _searching.value = false
        }
    }

    fun selectRepo(model: HfModel) {
        repoRequest?.cancel()
        _selectedRepo.value = model.id
        _selectedSource.value = model.source
        _files.value = emptyList()
        _loadingFiles.value = true
        _error.value = null
        repoRequest = viewModelScope.launch {
            val result = if (model.source == ModelSource.MODELSCOPE) {
                modelScope.files(model.id)
            } else {
                client.files(model.id, graph.settings.current.downloadSource)
            }
            if (_selectedRepo.value != model.id) return@launch
            result.onSuccess { _files.value = it.distinctBy { file -> file.name } }
                .onFailure { _error.value = it.message ?: "读取仓库文件失败" }
            _loadingFiles.value = false
        }
    }

    fun selectRecommended(item: RecommendedModel) {
        if (item.fileName != null) {
            downloadRecommended(item)
        } else {
            selectRepo(HfModel(item.repoId, 0, 0, listOf("推荐"), ModelSource.HUGGING_FACE))
        }
    }

    fun downloadRecommended(item: RecommendedModel) {
        val fileName = item.fileName ?: return
        downloads.enqueue(item.repoId, fileName, item.sizeBytes, graph.settings.current.downloadSource)
        _message.value = "已加入下载：${item.name}"
        if (item.mmprojFile != null) {
            downloads.enqueue(item.repoId, item.mmprojFile, item.mmprojSize, graph.settings.current.downloadSource)
        }
    }

    fun download(file: HfFile) {
        val repo = _selectedRepo.value ?: return
        downloads.enqueue(
            repoId = repo,
            fileName = file.name,
            size = file.size,
            source = graph.settings.current.downloadSource,
            origin = file.source,
        )
        _message.value = "已加入下载：${file.name}"
    }

    fun cancelDownload(id: String) = downloads.cancel(id)

    fun refreshDownloaded() {
        val files = downloads.modelsDirs()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.equals("gguf", ignoreCase = true) &&
                        !file.name.contains("mmproj", ignoreCase = true)
                }?.toList().orEmpty()
            }
            .distinctBy { it.name }
        _downloaded.value = files.sortedBy { it.name }
    }

    fun setAsLocalModel(file: File) {
        graph.models.assignLocalModel(file)
        graph.settings.update { it.copy(modelPath = file.absolutePath) }
        _message.value = "已将 ${file.name} 设为当前本地模型"
    }

    fun onPluginQueryChange(value: String) {
        _pluginQuery.value = value
    }

    fun onMarketFilterChange(value: String) {
        _marketFilter.value = value
    }

    fun setMarketSort(sort: String) {
        if (_marketSort.value == sort) return
        _marketSort.value = sort
        loadPluginMarket(reset = true)
    }

    fun loadPluginMarket(reset: Boolean = false) {
        if (_marketLoading.value) return
        val page = if (reset) 1 else _marketPage.value + 1
        _marketLoading.value = true
        viewModelScope.launch {
            operitMarket.list(_marketSort.value, page)
                .onSuccess { items ->
                    _marketPlugins.value = if (reset) {
                        items
                    } else {
                        (_marketPlugins.value + items).distinctBy { it.id }
                    }
                    _marketPage.value = page
                    _marketHasMore.value = items.size >= 100
                }
                .onFailure { _pluginError.value = it.message ?: "Operit 插件市场加载失败" }
            _marketLoading.value = false
        }
    }

    fun installMarketPlugin(plugin: OperitMarketPlugin) {
        viewModelScope.launch {
            _pluginError.value = null
            _message.value = "正在下载 ${plugin.title}…"
            runCatching { pluginStore.installUrl(plugin.assetUrl, plugin.sha256) }
                .onSuccess { _message.value = "已安装插件：${it.name}" }
                .onFailure { _pluginError.value = it.message ?: "插件安装失败" }
        }
    }

    fun searchPlugins() {
        val query = _pluginQuery.value.trim().ifBlank { "toolpkg operit" }
        _pluginSearching.value = true
        _pluginError.value = null
        viewModelScope.launch {
            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                val url = "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=20"
                val raw = RemoteFetch.text(url, 2L * 1024 * 1024, timeoutMs = 20_000)
                val items = Json.parseToJsonElement(raw).jsonObject["items"]?.jsonArray
                    ?: return@runCatching emptyList<GithubPluginRepo>()
                items.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val fullName = obj["full_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    GithubPluginRepo(
                        fullName = fullName,
                        description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        stars = obj["stargazers_count"]?.jsonPrimitive?.longOrNull ?: 0L,
                        branch = obj["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main",
                        htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$fullName",
                    )
                }
            }.onSuccess { _pluginResults.value = it }
                .onFailure { _pluginError.value = it.message ?: "插件市场搜索失败" }
            _pluginSearching.value = false
        }
    }

    fun installPluginRepo(repo: GithubPluginRepo) {
        viewModelScope.launch {
            _pluginError.value = null
            _message.value = "正在下载 ${repo.fullName}…"
            runCatching {
                pluginStore.installUrl("https://github.com/${repo.fullName}/archive/refs/heads/${repo.branch}.zip")
            }.onSuccess { _message.value = "已安装插件：${it.name}" }
                .onFailure { _pluginError.value = it.message ?: "插件安装失败" }
        }
    }

    fun installPluginUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _pluginError.value = null
            _message.value = "正在下载插件包…"
            runCatching { pluginStore.installUrl(url.trim()) }
                .onSuccess { _message.value = "已安装插件：${it.name}" }
                .onFailure { _pluginError.value = it.message ?: "插件安装失败" }
        }
    }

    fun importPluginUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { readPluginZip(context, uri) }
            bytes.onSuccess { importPlugin(it, uri.lastPathSegment.orEmpty()) }
                .onFailure { _pluginError.value = it.message ?: "无法读取插件包" }
        }
    }

    fun importPlugin(bytes: ByteArray, source: String) {
        viewModelScope.launch {
            _pluginError.value = null
            runCatching { pluginStore.installZip(bytes, source) }
                .onSuccess { _message.value = "已安装插件：${it.name}" }
                .onFailure { _pluginError.value = it.message ?: "插件安装失败" }
        }
    }

    fun setPluginEnabled(plugin: InstalledPlugin, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pluginStore.setEnabled(plugin, enabled) }
                .onFailure { _pluginError.value = it.message ?: "插件状态更新失败" }
        }
    }

    fun refreshPlugins() {
        viewModelScope.launch(Dispatchers.IO) { pluginStore.refresh() }
    }

    fun deletePlugin(plugin: InstalledPlugin) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pluginStore.delete(plugin) }
                .onSuccess { removed ->
                    if (removed) {
                        _message.value = "已删除插件：${plugin.name}"
                    } else {
                        _pluginError.value = "插件删除失败"
                    }
                }
                .onFailure { _pluginError.value = it.message ?: "插件删除失败" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun clearPluginError() {
        _pluginError.value = null
    }

    fun reportPluginError(message: String) {
        _pluginError.value = message
    }
}
