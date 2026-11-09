package com.iris.localai.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.AssetDownloadState
import com.iris.localai.motion.MotionAsset
import com.iris.localai.motion.MotionBridge
import com.iris.localai.motion.MotionCategory
import com.iris.localai.motion.RemoteAsset
import com.iris.localai.scene.StageAssetCatalog
import com.iris.localai.scene.StageAssetItem
import com.iris.localai.scene.StageAssetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
internal fun StageAssetPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val assets = remember(graph) { graph.motionAssets }
    val downloads = remember(graph) { graph.assetDownloads }
    val scenes by graph.scenes.scenes.collectAsStateWithLifecycle()
    val activeSceneId by graph.scenes.activeId.collectAsStateWithLifecycle()
    val tasks by downloads.tasks.collectAsStateWithLifecycle()
    val motionAssets by assets.assets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val backgroundDir = remember(context) { File(context.filesDir, "stage_assets/backgrounds").apply { mkdirs() } }
    val textureDir = remember(context) { File(context.filesDir, "stage_assets/textures").apply { mkdirs() } }
    val activeScene = scenes.firstOrNull { it.id == activeSceneId }

    var remotePath by remember { mutableStateOf("motions") }
    var remoteAssets by remember { mutableStateOf<List<RemoteAsset>>(emptyList()) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteLoading by remember { mutableStateOf(false) }
    var motionFilter by remember { mutableStateOf<MotionCategory?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun playMotion(asset: MotionAsset) {
        MotionBridge.playAsset(
            path = asset.id,
            loop = asset.category == MotionCategory.DANCE,
            bgmPath = asset.bgm?.absolutePath,
        )
        toast("正在播放：${asset.name}")
    }

    val importMotion = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { assets.import(uri) }
            result.onSuccess { toast("已导入：${it.name}") }
                .onFailure { toast(it.message ?: "导入失败") }
        }
    }
    val importBgm = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { assets.importBgm(uri) }
            result.onSuccess { toast("已导入配乐：${it.name}") }
                .onFailure { toast(it.message ?: "导入失败") }
        }
    }

    val completedKeys = tasks.filter { it.state == AssetDownloadState.DONE }.map { it.key }
    LaunchedEffect(completedKeys) {
        if (completedKeys.isNotEmpty()) assets.refresh()
    }

    SettingsPage {
        SettingsCard("素材库") {
            Text(
                text = "场景背景与舞台纹理来自 xiaoke.ai（Apache-2.0）；动作、镜头与配乐来自 self-resources 仓库。" +
                    "素材按需下载，不增加安装包体积。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "场景素材：${backgroundDir.parentFile?.absolutePath}\n动作素材：${assets.storageRoot().absolutePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("场景") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("舞台") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("动作舞蹈") })
        }

        when (tab) {
            0 -> StageAssetList(
                items = StageAssetCatalog.backgrounds,
                dir = backgroundDir,
                keyPrefix = "stage-bg",
                tasks = tasks,
                actionLabel = "设为场景",
                onDownload = { item ->
                    downloads.enqueue(
                        key = "stage-bg-${item.id}",
                        title = item.name,
                        url = item.url,
                        destDir = backgroundDir,
                        fileName = item.fileName,
                    )
                },
                onCancel = { item -> downloads.cancel("stage-bg-${item.id}") },
                onApply = { item ->
                    val file = File(backgroundDir, item.fileName)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            graph.scenes.createFromBackground(
                                name = item.name,
                                intro = "来自素材库的场景背景。",
                                source = file,
                            )
                        }
                        result.onSuccess { toast("已应用场景：${it.name}") }
                            .onFailure { toast(it.message ?: "应用失败") }
                    }
                },
            )

            1 -> {
                SettingsCard("当前舞台") {
                    Text(
                        text = activeScene?.let { "${it.name} · ${it.baseStyle.label}" } ?: "尚无场景",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "下载纹理后点击「设为基础」，会应用到当前场景的底座。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StageAssetList(
                    items = StageAssetCatalog.textures,
                    dir = textureDir,
                    keyPrefix = "stage-tex",
                    tasks = tasks,
                    actionLabel = "设为基础",
                    onDownload = { item ->
                        downloads.enqueue(
                            key = "stage-tex-${item.id}",
                            title = item.name,
                            url = item.url,
                            destDir = textureDir,
                            fileName = item.fileName,
                        )
                    },
                    onCancel = { item -> downloads.cancel("stage-tex-${item.id}") },
                    onApply = { item ->
                        val file = File(textureDir, item.fileName)
                        graph.scenes.applyBaseTexture(file)
                            .onSuccess { toast("舞台底座已切换为「${item.name}」") }
                            .onFailure { toast(it.message ?: "应用失败") }
                    },
                )
            }

            else -> {
                SettingsCard("本地动作与舞蹈") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { importMotion.launch("*/*") }) { Text("导入 VMD/VPD") }
                        OutlinedButton(onClick = { importBgm.launch("audio/*") }) { Text("导入配乐") }
                        TextButton(onClick = { assets.refresh() }) { Text("刷新") }
                        TextButton(onClick = { MotionBridge.stopAsset() }) { Text("停止") }
                    }
                    Text(
                        text = "VMD 动作可直接驱动 PMX/MMD 模型；VRM/GLB 仍使用 HAST-SEC 或动作服务。" +
                            "PMX 模型体积大且依赖贴图目录，请从仓库下载后在「角色模型」导入 ZIP。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("舞蹈自动播放配乐", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "按动作文件名匹配 bgm 目录中的同名音频",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.danceBgmEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.update { it.copy(danceBgmEnabled = enabled) }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = motionFilter == null,
                            onClick = { motionFilter = null },
                            label = { Text("全部 ${motionAssets.size}") },
                        )
                        MotionCategory.entries.filter { it != MotionCategory.CAMERA }.forEach { category ->
                            val count = motionAssets.count { it.category == category }
                            if (count > 0) {
                                FilterChip(
                                    selected = motionFilter == category,
                                    onClick = { motionFilter = category },
                                    label = { Text("${category.label} $count") },
                                )
                            }
                        }
                    }
                    val visible = motionAssets.filter { motionFilter == null || it.category == motionFilter }
                    if (visible.isEmpty()) {
                        Text("还没有动作素材，可在下方从仓库下载或导入 VMD。", style = MaterialTheme.typography.bodySmall)
                    }
                    visible.take(60).forEach { asset ->
                        MotionAssetRow(
                            asset = asset,
                            idleSelected = settings.idleMotionAsset == asset.id,
                            onPlay = { playMotion(asset) },
                            onSetIdle = {
                                viewModel.update { it.copy(idleMotionAsset = asset.id) }
                                toast("已设为 MMD 待机动作")
                            },
                            onDelete = {
                                if (assets.delete(asset)) toast("已删除：${asset.name}")
                            },
                        )
                    }
                    if (visible.size > 60) {
                        Text("仅显示前 60 个，共 ${visible.size} 个。", style = MaterialTheme.typography.labelSmall)
                    }
                }

                SettingsCard("镜头素材") {
                    val cameras = motionAssets.filter { it.category == MotionCategory.CAMERA }
                    Text(
                        text = "镜头 VMD 会接管舞台相机；也可直接切景别。拖动画面或点「停止」恢复手动视角。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("特写" to "ecu", "半身" to "half", "全身" to "full", "远景" to "long").forEach { (label, id) ->
                            FilterChip(
                                selected = false,
                                onClick = { MotionBridge.playCamera("shot:$id") },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (cameras.isEmpty()) {
                        Text("还没有镜头素材，可从下方仓库的「镜头」分类下载。", style = MaterialTheme.typography.bodySmall)
                    }
                    cameras.take(24).forEach { asset ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(asset.name, style = MaterialTheme.typography.bodyLarge)
                                Text(asset.sizeLabel, style = MaterialTheme.typography.labelSmall)
                            }
                            Button(onClick = { MotionBridge.playCamera(asset.id) }) { Text("播放") }
                            TextButton(onClick = { MotionBridge.stopCamera() }) { Text("停止") }
                            TextButton(onClick = { assets.delete(asset) }) { Text("删除") }
                        }
                    }
                }

                SettingsCard("素材仓库") {
                    Text(
                        text = "私有仓库需要 GitHub Token（只读即可）。Token 仅保存在本机偏好设置中；" +
                            "动作/音频版权归原作者，请遵守素材仓库与原作者授权。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = settings.assetRepo,
                        onValueChange = { viewModel.update { s -> s.copy(assetRepo = it.trim()) } },
                        label = { Text("仓库 owner/name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = settings.assetBranch,
                            onValueChange = { viewModel.update { s -> s.copy(assetBranch = it.trim()) } },
                            label = { Text("分支") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = settings.assetToken,
                            onValueChange = { viewModel.update { s -> s.copy(assetToken = it.trim()) } },
                            label = { Text("Token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val url = "https://github.com/${settings.assetRepo}"
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { toast("没有可用的浏览器") }
                            },
                        ) { Text("打开仓库") }
                        Button(
                            enabled = !remoteLoading,
                            onClick = {
                                remoteLoading = true
                                remoteError = null
                                scope.launch {
                                    runCatching { graph.githubAssets.list(remotePath) }
                                        .onSuccess { remoteAssets = it }
                                        .onFailure { remoteError = it.message }
                                    remoteLoading = false
                                }
                            },
                        ) {
                            if (remoteLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("加载列表")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("motions" to "动作", "cameras" to "镜头", "audio" to "配乐").forEach { (path, label) ->
                            FilterChip(
                                selected = remotePath == path,
                                onClick = {
                                    remotePath = path
                                    remoteAssets = emptyList()
                                    remoteError = null
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    remoteError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (remoteAssets.isNotEmpty()) {
                        val files = remoteAssets.filterNot { it.directory }
                        Text(
                            text = "共 ${files.size} 个文件，按需下载到本地素材目录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        files.take(40).forEach { remote ->
                            RemoteAssetRow(
                                remote = remote,
                                tasks = tasks,
                                onDownload = {
                                    val (url, headers) = graph.githubAssets.request(remote.path)
                                    val destination = when {
                                        remote.path.startsWith("cameras/") -> assets.camerasDir()
                                        remote.path.startsWith("audio/") -> assets.bgmDir()
                                        else -> assets.motionsDir()
                                    }
                                    downloads.enqueue(
                                        key = "repo-${remote.path}",
                                        title = remote.name,
                                        url = url,
                                        destDir = destination,
                                        fileName = safeFileName(remote.name),
                                        headers = headers,
                                    )
                                },
                                onCancel = { downloads.cancel("repo-${remote.path}") },
                            )
                        }
                        if (files.size > 40) {
                            Text("仅显示前 40 个，可切换分类或缩小仓库范围。", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        text = "下载后点「刷新」，动作会出现在上方列表；舞蹈会自动匹配同名配乐。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StageAssetList(
    items: List<StageAssetItem>,
    dir: File,
    keyPrefix: String,
    tasks: List<com.iris.localai.data.AssetDownloadTask>,
    actionLabel: String,
    onDownload: (StageAssetItem) -> Unit,
    onCancel: (StageAssetItem) -> Unit,
    onApply: (StageAssetItem) -> Unit,
) {
    SettingsCard(if (items.firstOrNull()?.kind == StageAssetKind.TEXTURE) "纹理素材" else "背景素材") {
        items.forEach { item ->
            val file = File(dir, item.fileName)
            val task = tasks.firstOrNull { it.key == "$keyPrefix-${item.id}" }
            val running = task?.state == AssetDownloadState.RUNNING
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = buildString {
                            append(formatSize(item.bytes))
                            append(" · ")
                            append(
                                when {
                                    running -> "下载中 ${(task.progress * 100).toInt()}%"
                                    file.isFile -> "已下载"
                                    task?.state == AssetDownloadState.FAILED -> task.error ?: "下载失败"
                                    else -> "未下载"
                                },
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (running) {
                        LinearProgressIndicator(
                            progress = { task.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                if (running) {
                    TextButton(onClick = { onCancel(item) }) { Text("取消") }
                } else if (file.isFile) {
                    Button(onClick = { onApply(item) }) { Text(actionLabel) }
                } else {
                    OutlinedButton(onClick = { onDownload(item) }) { Text("下载") }
                }
            }
        }
    }
}

@Composable
private fun MotionAssetRow(
    asset: MotionAsset,
    idleSelected: Boolean,
    onPlay: () -> Unit,
    onSetIdle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(asset.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${asset.category.label} · ${asset.sizeLabel}" +
                    (asset.bgm?.let { " · 配乐 ${it.name}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (idleSelected) {
            Text("待机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onSetIdle) { Text("设为待机") }
        }
        Button(onClick = onPlay) { Text("播放") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun RemoteAssetRow(
    remote: RemoteAsset,
    tasks: List<com.iris.localai.data.AssetDownloadTask>,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val task = tasks.firstOrNull { it.key == "repo-${remote.path}" }
    val running = task?.state == AssetDownloadState.RUNNING
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(remote.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (running) "下载中 ${(task.progress * 100).toInt()}%" else formatSize(remote.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        when {
            running -> TextButton(onClick = onCancel) { Text("取消") }
            task?.state == AssetDownloadState.DONE -> Text("已下载", style = MaterialTheme.typography.labelSmall)
            else -> OutlinedButton(onClick = onDownload) { Text("下载") }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}

private fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "asset.bin" }.take(120)
