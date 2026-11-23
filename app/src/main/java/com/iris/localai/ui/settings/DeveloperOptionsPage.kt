package com.iris.localai.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.IrisApp
import com.iris.localai.data.VoiceEngineType
import com.iris.localai.data.StreamingAsrProvider

@Composable
internal fun DeveloperOptionsPage(
    viewModel: SettingsViewModel = viewModel(),
    skillViewModel: SkillViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    var exportDir by remember(settings.pluginExportDir) {
        mutableStateOf(settings.pluginExportDir.ifBlank { graph.pluginDev.exportDir().absolutePath })
    }
    var repo by remember(settings.hostSourceRepo) { mutableStateOf(settings.hostSourceRepo) }
    var branch by remember(settings.hostSourceBranch) { mutableStateOf(settings.hostSourceBranch) }
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage {
        SettingsCard("实验功能") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AR 6DoF SLAM", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "视频通话 AR 模式使用 MenthaAR/ORB-SLAM2 视觉定位，提供位移跟踪和重定位；" +
                            "需要纹理丰富的环境，跟踪失败会自动回退 IMU + 光流。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.videoCallArSlam,
                    onCheckedChange = { enabled -> viewModel.update { it.copy(videoCallArSlam = enabled) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("显示 AR 调试信息", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "在 AR 舞台左上角显示 SLAM 状态、地图、尺度与锚点投影坐标，排查定位问题时使用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.videoCallArDebug,
                    onCheckedChange = { enabled -> viewModel.update { it.copy(videoCallArDebug = enabled) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("流式语音识别（实验）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "打开后，视频通话与语音输入优先使用流式 ASR：边说边出字幕，端音后直接提交。" +
                            "本地需要下载流式模型；云端走火山双向流式识别（需要云端 ASR Key）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.streamingAsrEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(streamingAsrEnabled = enabled) }
                    },
                )
            }
            if (settings.streamingAsrEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.streamingAsrProvider == VoiceEngineType.LOCAL,
                        onClick = {
                            viewModel.update { it.copy(streamingAsrProvider = VoiceEngineType.LOCAL) }
                        },
                        label = { Text("本地流式模型") },
                    )
                    FilterChip(
                        selected = settings.streamingAsrProvider == VoiceEngineType.CLOUD,
                        onClick = {
                            viewModel.update { it.copy(streamingAsrProvider = VoiceEngineType.CLOUD) }
                        },
                        label = { Text("云端（流式）") },
                    )
                }
                Text(
                    "本地流式模型在 设置 › 语音 › 语音识别（STT） 下载并选择；" +
                        "云端模式使用 设置 › 语音 的云端 ASR Key 与 Endpoint。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.streamingAsrProvider == VoiceEngineType.CLOUD) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StreamingAsrProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = settings.streamingAsrCloudProvider == provider,
                                onClick = {
                                    viewModel.update { it.copy(streamingAsrCloudProvider = provider) }
                                },
                                label = { Text(provider.label) },
                            )
                        }
                    }
                    when (settings.streamingAsrCloudProvider) {
                        StreamingAsrProvider.VOLCENGINE -> {
                            OutlinedTextField(
                                value = settings.sttStreamingResourceId,
                                onValueChange = { value ->
                                    viewModel.update { it.copy(sttStreamingResourceId = value) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("X-Api-Resource-Id") },
                                singleLine = true,
                            )
                            Text(
                                "Key 填 X-Api-Key；Endpoint 留空时用火山官方流式地址；" +
                                    "Resource-Id 可选 volc.seedasr.sauc.duration / volc.seedasr.sauc.concurrent。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        StreamingAsrProvider.OPENAI -> {
                            OutlinedTextField(
                                value = settings.openaiRealtimeModel,
                                onValueChange = { value ->
                                    viewModel.update { it.copy(openaiRealtimeModel = value) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Realtime 模型") },
                                singleLine = true,
                            )
                            Text(
                                "Key 用 设置 › 语音 › 云端 ASR 的 API Key（Bearer）；" +
                                    "Endpoint 留空使用 api.openai.com；模型默认 gpt-live-transcribe。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        StreamingAsrProvider.XFYUN -> {
                            OutlinedTextField(
                                value = settings.xfyunAppId,
                                onValueChange = { value ->
                                    viewModel.update { it.copy(xfyunAppId = value) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("讯飞 AppID") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = settings.xfyunApiKey,
                                onValueChange = { value ->
                                    viewModel.update { it.copy(xfyunApiKey = value) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("讯飞 APIKey") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = settings.xfyunApiSecret,
                                onValueChange = { value ->
                                    viewModel.update { it.copy(xfyunApiSecret = value) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("讯飞 APISecret") },
                                singleLine = true,
                            )
                            Text(
                                "讯飞流式听写（iat）：16 kHz PCM，中文普通话，40 ms/1280B 分片。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                "实验性：引擎为 GPLv3，打包体积增加数十 MB；旧设备可能发热、掉帧。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("开发者选项") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("插件开发模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "打开后向智能体暴露 plugin_dev 工具。套件面板请在侧栏插件区或 设置 › 插件 中打开，用法见 Skill「插件开发套件」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.pluginDevMode,
                    onCheckedChange = { enabled -> viewModel.update { it.copy(pluginDevMode = enabled) } },
                )
            }
            if (!settings.pluginDevMode) {
                Text(
                    "插件开发模式已关闭，以下入口暂不显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (settings.pluginDevMode) {
            SettingsCard("插件开发") {
                Text(
                    "工程目录 filesDir/plugin_dev；ZIP 上限 256 MB，解压 512 MB，条目 5000。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { skillViewModel.rebuildBundledSkills() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("恢复内置技能（覆盖未删除的内置项）") }
            }

            SettingsCard("导出与源码") {
                OutlinedTextField(
                    value = exportDir,
                    onValueChange = { exportDir = it },
                    label = { Text("ZIP 导出文件夹") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.update { it.copy(pluginExportDir = exportDir.trim()) }
                            Toast.makeText(context, "已保存导出文件夹", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存目录") }
                    Button(
                        onClick = {
                            exportDir = graph.pluginDev.exportDir().absolutePath
                            viewModel.update { it.copy(pluginExportDir = exportDir) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("使用默认目录") }
                }
                Text(
                    "当前实际目录：${graph.pluginDev.exportDir().absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (storageGranted) "已授予所有文件访问" else "未授予所有文件访问（会回退到应用外置目录）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (!storageGranted) {
                        Button(onClick = {
                            val perApp = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { context.startActivity(perApp) }
                                .recoverCatching {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                                .onFailure {
                                    Toast.makeText(context, "无法打开存储权限设置", Toast.LENGTH_SHORT).show()
                                }
                        }) { Text("授权") }
                    }
                }
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("GitHub 仓库 owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("分支") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        viewModel.update {
                            it.copy(hostSourceRepo = repo.trim(), hostSourceBranch = branch.trim())
                        }
                        Toast.makeText(context, "已保存源码地址", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存源码地址") }
                Text(
                    "源码通过 raw.githubusercontent.com 读取，plugin_dev 的 host_source 会缓存 6 小时。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
