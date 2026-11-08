package com.iris.localai.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.data.DownloadStatus
import com.iris.localai.data.DownloadTask
import com.iris.localai.data.HfFile
import com.iris.localai.data.HfModel
import com.iris.localai.data.ModelSource
import com.iris.localai.data.RecommendedModel
import com.iris.localai.data.RecommendedModels
import com.iris.localai.plugin.InstalledPlugin
import com.iris.localai.plugin.OperitMarketPlugin
import kotlinx.coroutines.delay
import java.io.File

@Composable
internal fun MarketPage(
    initialSection: MarketSection = MarketSection.MODELS,
    viewModel: MarketViewModel = viewModel(),
) {
    LaunchedEffect(initialSection) {
        viewModel.setSection(initialSection)
    }
    val section by viewModel.section.collectAsStateWithLifecycle()
    val modelTab by viewModel.modelTab.collectAsStateWithLifecycle()

    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val selectedRepo by viewModel.selectedRepo.collectAsStateWithLifecycle()
    val selectedSource by viewModel.selectedSource.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val loadingFiles by viewModel.loadingFiles.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val sourceNotice by viewModel.sourceNotice.collectAsStateWithLifecycle()

    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val pluginBusy by viewModel.pluginBusy.collectAsStateWithLifecycle()
    val pluginQuery by viewModel.pluginQuery.collectAsStateWithLifecycle()
    val pluginResults by viewModel.pluginResults.collectAsStateWithLifecycle()
    val pluginSearching by viewModel.pluginSearching.collectAsStateWithLifecycle()
    val pluginError by viewModel.pluginError.collectAsStateWithLifecycle()
    val marketPlugins by viewModel.marketPlugins.collectAsStateWithLifecycle()
    val marketSort by viewModel.marketSort.collectAsStateWithLifecycle()
    val marketFilter by viewModel.marketFilter.collectAsStateWithLifecycle()
    val marketLoading by viewModel.marketLoading.collectAsStateWithLifecycle()
    val marketHasMore by viewModel.marketHasMore.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showPluginUrl by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InstalledPlugin?>(null) }

    val zipImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importPluginUri(context, uri)
    }

    LaunchedEffect(message, error, pluginError) {
        if (message != null || error != null || pluginError != null) {
            delay(4000)
            viewModel.clearMessage()
            viewModel.clearError()
            viewModel.clearPluginError()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TabRow(selectedTabIndex = section.ordinal) {
                MarketSection.entries.forEach { tab ->
                    Tab(
                        selected = section == tab,
                        onClick = { viewModel.setSection(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }
        }

        if (section == MarketSection.MODELS) {
            item {
                TabRow(selectedTabIndex = modelTab.ordinal) {
                    ModelMarketTab.entries.forEach { tab ->
                        Tab(
                            selected = modelTab == tab,
                            onClick = { viewModel.setModelTab(tab) },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
            if (modelTab == ModelMarketTab.LLM) {
                llmMarket(
                    viewModel = viewModel,
                    query = query,
                    results = results,
                    searching = searching,
                    selectedRepo = selectedRepo,
                    selectedSource = selectedSource,
                    files = files,
                    loadingFiles = loadingFiles,
                    tasks = tasks,
                    downloaded = downloaded,
                    error = error,
                    message = message,
                    sourceNotice = sourceNotice,
                )
            } else {
                item { CharacterPlazaSection() }
            }
        } else {
            pluginMarket(
                viewModel = viewModel,
                plugins = plugins,
                busy = pluginBusy,
                query = pluginQuery,
                results = pluginResults,
                searching = pluginSearching,
                error = pluginError,
                context = context,
                marketPlugins = marketPlugins,
                marketSort = marketSort,
                marketFilter = marketFilter,
                marketLoading = marketLoading,
                marketHasMore = marketHasMore,
                onImport = { zipImporter.launch("*/*") },
                onUrlInstall = { showPluginUrl = true },
                onDelete = { pendingDelete = it },
            )
        }
    }

    if (showPluginUrl) {
        PluginUrlDialog(
            onDismiss = { showPluginUrl = false },
            onConfirm = { url ->
                showPluginUrl = false
                viewModel.installPluginUrl(url)
            },
        )
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除插件") },
            text = { Text("确定删除「${plugin.name}」及其文件？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.deletePlugin(plugin)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

private fun LazyListScope.llmMarket(
    viewModel: MarketViewModel,
    query: String,
    results: List<HfModel>,
    searching: Boolean,
    selectedRepo: String?,
    selectedSource: ModelSource,
    files: List<HfFile>,
    loadingFiles: Boolean,
    tasks: List<DownloadTask>,
    downloaded: List<File>,
    error: String?,
    message: String?,
    sourceNotice: String?,
) {
    item {
        SettingsCard("搜索本地模型") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("搜索模型仓库") },
                    placeholder = { Text("例如 qwen3 gguf") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::search, enabled = !searching) {
                    Text("搜索")
                }
            }
            Text(
                text = "Hugging Face 优先；失败或无结果时自动切换到 ModelScope 备用检索（参照 Zorv AI 离线模型下载中心）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sourceNotice?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    item {
        SettingsCard("IRIDIS 推荐模型") {
            Text(
                text = "来自 IRIDIS 的推荐目录，均提供 GGUF 文件；下载后可设为当前本地模型。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecommendedModels.items.forEach { model ->
                RecommendedModelRow(
                    model = model,
                    busy = searching,
                    onAction = { viewModel.selectRecommended(model) },
                )
            }
        }
    }

    if (searching) {
        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
    }

    items(results, key = { "${it.source}-${it.id}" }) { model ->
        HfModelRow(model = model, onClick = { viewModel.selectRepo(model) })
    }

    if (selectedRepo != null) {
        item {
            Text(
                text = "仓库文件 · $selectedRepo · ${selectedSource.label}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (loadingFiles) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        items(files, key = { "${it.source}-${it.name}" }) { file ->
            HfFileRow(file = file, onDownload = { viewModel.download(file) })
        }
        if (!loadingFiles && files.isEmpty()) {
            item {
                Text(
                    text = "该仓库没有 GGUF 文件，或读取失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val visibleTasks = tasks.filter { it.status != DownloadStatus.COMPLETED }.takeLast(6)
    if (visibleTasks.isNotEmpty()) {
        item {
            SettingsCard("下载任务") {
                visibleTasks.forEach { task ->
                    DownloadTaskRow(task = task, onCancel = { viewModel.cancelDownload(task.id) })
                }
            }
        }
        if (visibleTasks.any { it.status == DownloadStatus.RUNNING }) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
    }

    item {
        SettingsCard("已下载模型") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本地 GGUF",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::refreshDownloaded) { Text("刷新") }
            }
            if (downloaded.isEmpty()) {
                Text(
                    text = "models/ 目录为空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            downloaded.forEach { file ->
                DownloadedRow(file = file, onUse = { viewModel.setAsLocalModel(file) })
            }
        }
    }
}

private fun LazyListScope.pluginMarket(
    viewModel: MarketViewModel,
    plugins: List<InstalledPlugin>,
    busy: Boolean,
    query: String,
    results: List<GithubPluginRepo>,
    searching: Boolean,
    error: String?,
    context: Context,
    marketPlugins: List<OperitMarketPlugin>,
    marketSort: String,
    marketFilter: String,
    marketLoading: Boolean,
    marketHasMore: Boolean,
    onImport: () -> Unit,
    onUrlInstall: () -> Unit,
    onDelete: (InstalledPlugin) -> Unit,
) {
    item {
        SettingsCard("插件市场") {
            Text(
                text = "安装 Operit ToolPkg 或 IRIS 插件包。仓库 ZIP 需在根目录或一级子目录包含 manifest.json / manifest.hjson。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("导入 ZIP")
                }
                Button(onClick = onUrlInstall, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("从 URL 安装")
                }
                Button(onClick = viewModel::refreshPlugins, modifier = Modifier.weight(1f)) {
                    Text("刷新")
                }
            }
            if (busy) {
                Text("正在处理插件包…", style = MaterialTheme.typography.labelSmall)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    item {
        SettingsCard("已安装插件（${plugins.size}）") {
            if (plugins.isEmpty()) {
                Text(
                    text = "尚未安装插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plugins.forEach { plugin ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(plugin.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = listOfNotNull(
                                plugin.origin.label,
                                plugin.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                                "${plugin.toolCount} 个工具",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = { viewModel.setPluginEnabled(plugin, it) },
                    )
                    IconButton(onClick = { onDelete(plugin) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除插件")
                    }
                }
            }
        }
    }

    item {
        SettingsCard("Operit 插件市场") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SortChip("下载最多", marketSort == "downloads") { viewModel.setMarketSort("downloads") }
                SortChip("最多喜欢", marketSort == "likes") { viewModel.setMarketSort("likes") }
                SortChip("最近更新", marketSort == "updated") { viewModel.setMarketSort("updated") }
            }
            OutlinedTextField(
                value = marketFilter,
                onValueChange = viewModel::onMarketFilterChange,
                label = { Text("筛选已加载插件") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "来自 static.operit.app 的公开市场索引，仅展示 ToolPkg 资产；安装前校验市场提供的 SHA-256。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (marketLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    val filteredMarket = marketPlugins.filter { plugin ->
        marketFilter.isBlank() || plugin.title.contains(marketFilter, ignoreCase = true) ||
            plugin.description.contains(marketFilter, ignoreCase = true) ||
            plugin.author.contains(marketFilter, ignoreCase = true)
    }
    items(filteredMarket, key = { it.id }) { plugin ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (plugin.featured) {
                        StatusPill("推荐")
                    }
                }
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = listOfNotNull(
                            plugin.author.takeIf { it.isNotBlank() },
                            plugin.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                            "${formatCount(plugin.downloads)} 下载",
                            "${formatCount(plugin.likes)} 喜欢",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.installMarketPlugin(plugin) },
                        enabled = !busy,
                    ) { Text("安装") }
                }
            }
        }
    }

    if (marketHasMore) {
        item {
            TextButton(
                onClick = { viewModel.loadPluginMarket() },
                enabled = !marketLoading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (marketLoading) "加载中…" else "加载更多") }
        }
    }

    item {
        SettingsCard("在线发现（GitHub）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onPluginQueryChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("搜索插件仓库") },
                    placeholder = { Text("toolpkg operit") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = viewModel::searchPlugins, enabled = !searching) {
                    Text("搜索")
                }
            }
            Text(
                text = "通过 GitHub 搜索 ToolPkg 仓库，安装时下载默认分支 ZIP；匿名 API 有频率限制。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (searching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    items(results, key = { it.fullName }) { repo ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(repo.fullName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (repo.description.isNotBlank()) {
                    Text(
                        text = repo.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "★ ${repo.stars} · ${repo.branch}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { openUrl(context, repo.htmlUrl) }) { Text("打开") }
                    TextButton(
                        onClick = { viewModel.installPluginRepo(repo) },
                        enabled = !busy,
                    ) { Text("安装") }
                }
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun RecommendedModelRow(
    model: RecommendedModel,
    busy: Boolean,
    onAction: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = model.purpose,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    model.repoId,
                    model.quant,
                    model.licence,
                    formatBytes(model.sizeBytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { openUrl(context, "https://huggingface.co/${model.repoId}") }) {
            Text("仓库")
        }
        TextButton(onClick = onAction, enabled = !busy) {
            Text(if (model.fileName != null) "下载" else "选择文件")
        }
    }
}

@Composable
private fun HfModelRow(model: HfModel, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatCount(model.downloads)} 下载 · ${formatCount(model.likes)} 喜欢" +
                    if (model.tags.isNotEmpty()) {
                        " · ${model.tags.take(3).joinToString(" ")}"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "来源：${model.source.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HfFileRow(file: HfFile, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatBytes(file.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDownload) { Text("下载") }
    }
}

@Composable
private fun DownloadTaskRow(task: DownloadTask, onCancel: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${task.status.label} · ${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
        if (task.status == DownloadStatus.RUNNING) {
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        if (task.message.isNotBlank()) {
            Text(
                text = task.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DownloadedRow(file: File, onUse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatBytes(file.length()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUse) { Text("设为本地模型") }
    }
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatCount(value: Long): String =
    if (value >= 1000) "%.1fk".format(value / 1000.0) else value.toString()
