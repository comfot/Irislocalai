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
import com.iris.localai.data.AssetDownloadState
import com.iris.localai.data.VrmMotionEngine
import com.iris.localai.motion.HastMotionStore
import com.iris.localai.motion.MotionBridge
import com.iris.localai.motion.MotionCategory
import com.iris.localai.motion.RemoteAsset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 模型市场 › 角色模型 › 动作模型。 */
@Composable
internal fun MotionMarketSection() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("MMD动作") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("VRM动作") })
        }
        if (tab == 0) MmdMotionMarket() else VrmMotionMarket()
    }
}

@Composable
private fun VrmMotionMarket() {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val store = remember(context) { HastMotionStore.get(context) }
    val vrmaStore = remember(context) { graph.vrmaMotions }
    val vrmaAssets by vrmaStore.assets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf(
            if (store.isReady()) "动作库已就绪：${store.catalog().size} 个技能"
            else "未下载动作库（约 21 MB）",
        )
    }
    var progress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf<List<String>>(emptyList()) }
    val importVrma = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { vrmaStore.import(uri) }
            result.onSuccess { status = "已导入 VRMA：${it.name}" }
                .onFailure { status = it.message ?: "VRMA 导入失败" }
        }
    }

    LaunchedEffect(Unit) {
        if (store.isReady()) {
            withContext(Dispatchers.IO) { store.warmUp() }
            skills = store.catalog()
        }
    }

    SettingsCard(title = "VRM动作（HAST-SEC）") {
        Text(
            text = "从 Hugging Face 下载官方动作清单与 20fps 轨迹（53 骨骼四元数），本地解析后直接驱动 VRM/GLB；不运行其 0.8B 中文模型。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        progress?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    progress = "准备下载…"
                    scope.launch {
                        val message = store.install { value -> scope.launch { progress = value } }
                        busy = false
                        progress = null
                        status = message
                        skills = store.catalog()
                        if (store.isReady()) {
                            graph.settings.update {
                                it.copy(
                                    vrmMotionEngine = VrmMotionEngine.HAST_SEC,
                                    motionAutoIdle = true,
                                )
                            }
                        }
                    }
                },
                enabled = !busy,
            ) { Text(if (busy) "下载中…" else "下载/更新动作库") }
            TextButton(
                onClick = {
                    store.warmUp()
                    skills = store.catalog()
                    status = if (store.isReady()) {
                        "动作库已就绪：${skills.size} 个技能"
                    } else {
                        "未下载动作库"
                    }
                },
                enabled = !busy,
            ) { Text("刷新") }
            TextButton(
                onClick = {
                    graph.settings.update {
                        it.copy(
                            vrmMotionEngine = VrmMotionEngine.HAST_SEC,
                            motionAutoIdle = true,
                        )
                    }
                    status = "HAST-SEC 已启用为 VRM 动作引擎"
                },
                enabled = store.isReady() && !busy,
            ) { Text("启用 HAST-SEC") }
        }
        Text(
            text = "HAST-SEC 采用非商用研究许可。下载后可在「设置 › 3D 控制 › 绑定配置 › VRM」添加动作和绑定。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (skills.isNotEmpty()) {
            Text("动作列表（点击播放）", style = MaterialTheme.typography.labelLarge)
            skills.take(30).forEach { skill ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(skill, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { MotionBridge.play(skill) }) { Text("播放") }
                }
            }
        }

        Text("VRMA 动作", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { importVrma.launch("*/*") }) { Text("导入 VRMA") }
            TextButton(
                onClick = {
                    graph.settings.update { it.copy(vrmMotionEngine = VrmMotionEngine.VRMA) }
                    status = "VRMA 已启用为 VRM 动作引擎"
                },
                enabled = vrmaAssets.isNotEmpty(),
            ) { Text("启用 VRMA") }
        }
        if (vrmaAssets.isEmpty()) {
            Text(
                text = "还没有 VRMA 文件，导入 .vrma 后即可在 3D 控制里绑定和使用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vrmaAssets.forEach { asset ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(asset.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { MotionBridge.playVrma(vrmaStore.virtualUrl(asset)) }) {
                    Text("播放")
                }
                TextButton(onClick = { vrmaStore.delete(asset) }) { Text("删除") }
            }
        }
    }
}

@Composable
private fun MmdMotionMarket() {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val assets = remember(graph) { graph.motionAssets }
    val downloads = remember(graph) { graph.assetDownloads }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val tasks by downloads.tasks.collectAsStateWithLifecycle()
    val localAssets by assets.assets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var remotePath by remember { mutableStateOf("motions") }
    var remoteAssets by remember { mutableStateOf<List<RemoteAsset>>(emptyList()) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteLoading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<MotionCategory?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

    SettingsCard(title = "MMD动作（VMD）") {
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
            text = "VMD 动作直接驱动 PMX/MMD 模型；导入或下载后可在「设置 › 3D 控制 › 绑定配置 › MMD」添加绑定。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsSwitchRow(
            title = "舞蹈自动播放配乐",
            summary = "按动作文件名匹配 bgm 目录中的同名音频",
            checked = settings.danceBgmEnabled,
            onCheckedChange = { enabled ->
                graph.settings.update { it.copy(danceBgmEnabled = enabled) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("全部 ${localAssets.size}") },
            )
            MotionCategory.entries.filter { it != MotionCategory.CAMERA }.forEach { category ->
                val count = localAssets.count { it.category == category }
                if (count > 0) {
                    FilterChip(
                        selected = filter == category,
                        onClick = { filter = category },
                        label = { Text("${category.label} $count") },
                    )
                }
            }
        }
        val visible = localAssets.filter { filter == null || it.category == filter }
        if (visible.isEmpty()) {
            Text(
                text = "还没有动作素材，可在下方从仓库下载或导入 VMD。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        visible.take(60).forEach { asset ->
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
                if (settings.idleMotionAsset == asset.id) {
                    Text(
                        "待机",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(
                        onClick = {
                            graph.settings.update { it.copy(idleMotionAsset = asset.id) }
                            toast("已设为 MMD 待机动作")
                        },
                    ) { Text("设为待机") }
                }
                Button(
                    onClick = {
                        MotionBridge.playAsset(
                            path = asset.id,
                            loop = asset.category == MotionCategory.DANCE,
                            bgmPath = asset.bgm?.absolutePath,
                        )
                        toast("正在播放：${asset.name}")
                    },
                ) { Text("播放") }
                TextButton(onClick = { if (assets.delete(asset)) toast("已删除：${asset.name}") }) {
                    Text("删除")
                }
            }
        }
        if (visible.size > 60) {
            Text("仅显示前 60 个，共 ${visible.size} 个。", style = MaterialTheme.typography.labelSmall)
        }
    }

    SettingsCard(title = "素材仓库") {
        Text(
            text = "私有仓库需要 GitHub Token（只读即可）。Token 仅保存在本机偏好设置中；动作/音频版权归原作者。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = settings.assetRepo,
            onValueChange = { value -> graph.settings.update { it.copy(assetRepo = value.trim()) } },
            label = { Text("仓库 owner/name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = settings.assetBranch,
                onValueChange = { value -> graph.settings.update { it.copy(assetBranch = value.trim()) } },
                label = { Text("分支") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = settings.assetToken,
                onValueChange = { value -> graph.settings.update { it.copy(assetToken = value.trim()) } },
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
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { toast("没有可用的浏览器") }
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
                            text = if (running) "下载中 ${(task.progress * 100).toInt()}%"
                            else formatSize(remote.size),
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
                        running -> TextButton(onClick = { downloads.cancel("repo-${remote.path}") }) {
                            Text("取消")
                        }
                        task?.state == AssetDownloadState.DONE -> Text(
                            "已下载",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        else -> OutlinedButton(
                            onClick = {
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
                        ) { Text("下载") }
                    }
                }
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

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun safeFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
