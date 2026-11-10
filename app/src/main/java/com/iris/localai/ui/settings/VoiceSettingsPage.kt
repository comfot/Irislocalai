package com.iris.localai.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.data.AppSettings
import com.iris.localai.data.CloudVoiceProvider
import com.iris.localai.data.VoiceEngineType
import com.iris.localai.data.VoiceKind
import com.iris.localai.data.VoiceModelCatalog
import com.iris.localai.data.VoiceModelPack
import com.iris.localai.data.defaultAsrModel
import com.iris.localai.data.defaultTtsModel
import com.iris.localai.voice.VoiceDownloadStatus
import com.iris.localai.voice.VoiceDownloadTask
import com.iris.localai.voice.SystemTtsStatus

@Composable
internal fun VoiceSettingsPage(viewModel: VoiceViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installed by viewModel.installedIds.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val ttsStatus by viewModel.ttsStatus.collectAsStateWithLifecycle()

    var sttResult by remember { mutableStateOf<String?>(null) }
    var sttMessage by remember { mutableStateOf<String?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text.isNullOrBlank()) {
            sttMessage = "没有识别到内容"
        } else {
            sttResult = text
            sttMessage = null
        }
    }

    fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.sttLanguage)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { sttMessage = "设备没有可用的系统语音识别" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startSpeech() else sttMessage = "未授予录音权限"
    }

    val asrPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.testCloudAsr() else sttMessage = "未授予录音权限"
    }

    val localAsrPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.testLocalAsr() else sttMessage = "未授予录音权限"
    }

    fun testLocalAsr() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.testLocalAsr()
        } else {
            localAsrPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun testCloudAsr() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.testCloudAsr()
        } else {
            asrPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun testStt() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startSpeech()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    SettingsPage {
        SettingsCard("语音合成（TTS）") {
            VoiceEngineChips(settings.ttsEngine) { engine ->
                viewModel.update { it.copy(ttsEngine = engine) }
            }

            when (settings.ttsEngine) {
                VoiceEngineType.SYSTEM -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Android 系统 TTS", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = ttsStatus.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.testSystemTts("你好，这是 IRIS LocalAI 的语音测试。")
                            },
                            enabled = ttsStatus == SystemTtsStatus.READY,
                        ) { Text("测试朗读") }
                        if (ttsStatus == SystemTtsStatus.UNAVAILABLE) {
                            TextButton(onClick = viewModel::retrySystemTts) { Text("重新初始化") }
                        }
                    }
                    SliderRow(
                        title = "语速",
                        value = settings.ttsSpeed,
                        range = 0.6f..1.6f,
                        steps = 9,
                        format = { "%.1fx".format(it) },
                        onCommit = { value ->
                            viewModel.update { it.copy(ttsSpeed = (value * 10).toInt() / 10f) }
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动朗读回复", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "AI 回复完成后自动调用 TTS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.autoSpeak,
                            onCheckedChange = { on -> viewModel.update { it.copy(autoSpeak = on) } },
                        )
                    }
                }

                VoiceEngineType.LOCAL -> {
                    Text(
                        text = "本地 TTS 模型（参考 IRIDIS / IndexTTS-2）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    VoiceModelCatalog.packs.filter { it.kind == VoiceKind.TTS }.forEach { pack ->
                        VoicePackRow(
                            pack = pack,
                            installed = pack.id in installed,
                            selected = settings.ttsLocalModelId == pack.id,
                            task = tasks.firstOrNull { it.packId == pack.id },
                            onDownload = { viewModel.download(pack) },
                            onCancel = { viewModel.cancel(pack) },
                            onUse = {
                                viewModel.update { it.copy(ttsLocalModelId = pack.id) }
                            },
                            onDelete = {
                                viewModel.delete(pack)
                                if (settings.ttsLocalModelId == pack.id) {
                                    viewModel.update { it.copy(ttsLocalModelId = "") }
                                }
                            },
                        )
                    }
                    Text(
                        text = "模型下载入口已接入 Hugging Face；本地推理使用 sherpa-onnx。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.testLocalTts("你好，这是 IRIS LocalAI 的本地语音测试。")
                            },
                        ) { Text("测试本地合成") }
                    }
                    val localTest by viewModel.cloudTest.collectAsStateWithLifecycle()
                    localTest?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                VoiceEngineType.CLOUD -> {
                    var expandedProvider by remember { mutableStateOf(false) }
                    val cloudTest by viewModel.cloudTest.collectAsStateWithLifecycle()
                    val samplePicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let(viewModel::importVoiceSample) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("服务商", modifier = Modifier.weight(1f))
                        Box {
                            AssistChip(
                                onClick = { expandedProvider = true },
                                label = { Text(settings.ttsCloudProvider.label) },
                            )
                            DropdownMenu(
                                expanded = expandedProvider,
                                onDismissRequest = { expandedProvider = false },
                            ) {
                                CloudVoiceProvider.entries.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item.label) },
                                        onClick = {
                                            viewModel.update { current ->
                                                current.copy(
                                                    ttsCloudProvider = item,
                                                    ttsCloudEndpoint = if (
                                                        current.ttsCloudEndpoint.isBlank() ||
                                                        CloudVoiceProvider.entries.any {
                                                            it.defaultEndpoint == current.ttsCloudEndpoint
                                                        }
                                                    ) {
                                                        item.defaultEndpoint
                                                    } else {
                                                        current.ttsCloudEndpoint
                                                    },
                                                    ttsCloudModel = if (
                                                        current.ttsCloudModel.isBlank() ||
                                                        CloudVoiceProvider.entries.any {
                                                            it.defaultTtsModel() == current.ttsCloudModel
                                                        }
                                                    ) {
                                                        item.defaultTtsModel()
                                                    } else {
                                                        current.ttsCloudModel
                                                    },
                                                )
                                            }
                                            expandedProvider = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = settings.ttsCloudEndpoint,
                        onValueChange = { value ->
                            viewModel.update { it.copy(ttsCloudEndpoint = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Endpoint") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = settings.ttsCloudKey,
                        onValueChange = { value -> viewModel.update { it.copy(ttsCloudKey = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = settings.ttsCloudModel,
                        onValueChange = { value -> viewModel.update { it.copy(ttsCloudModel = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型名称") },
                        singleLine = true,
                    )
                    if (settings.ttsCloudProvider == CloudVoiceProvider.MIMO) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "mimo-v2.5-tts" to "预置音色",
                                "mimo-v2.5-tts-voicedesign" to "音色设计",
                                "mimo-v2.5-tts-voiceclone" to "音色复刻",
                            ).forEach { (id, label) ->
                                FilterChip(
                                    selected = settings.ttsCloudModel == id,
                                    onClick = {
                                        viewModel.update { it.copy(ttsCloudModel = id) }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = settings.ttsCloudVoice,
                        onValueChange = { value -> viewModel.update { it.copy(ttsCloudVoice = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("音色 / Voice（预置音色名）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = settings.ttsCloudStyle,
                        onValueChange = { value -> viewModel.update { it.copy(ttsCloudStyle = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("风格指令（可选，随 user 消息发送）") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    Text(
                        text = "音色复刻样本",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = settings.ttsCloudVoiceSample.ifBlank { "未选择（voiceclone 必填，支持 wav/mp3，≤10 MB）" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { samplePicker.launch(arrayOf("audio/*")) }) {
                            Text("选择克隆音频")
                        }
                        TextButton(
                            onClick = { viewModel.testCloudTts("你好，这是 IRIS LocalAI 的云端语音测试。") },
                        ) { Text("测试合成") }
                    }
                    cloudTest?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "TTS 走 OpenAI 兼容的 /chat/completions；voiceclone 会把样本编码为 data URI 传给 audio.voice。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsCard("语音识别（STT）") {
            VoiceEngineChips(settings.sttEngine) { engine ->
                viewModel.update { it.copy(sttEngine = engine) }
            }

            when (settings.sttEngine) {
                VoiceEngineType.SYSTEM -> {
                    Text("系统语音识别语言", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "zh-CN" to "中文",
                            "en-US" to "English",
                            "ja-JP" to "日本語",
                        ).forEach { (id, label) ->
                            FilterChip(
                                selected = settings.sttLanguage == id,
                                onClick = { viewModel.update { it.copy(sttLanguage = id) } },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("系统识别测试", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "调用 Android SpeechRecognizer，需要录音权限",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { testStt() }) { Text("开始识别") }
                    }
                    sttResult?.let {
                        Text("识别结果：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    sttMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                VoiceEngineType.LOCAL -> {
                    Text(
                        text = "本地 STT 模型（sherpa-onnx）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    VoiceModelCatalog.packs.filter { it.kind == VoiceKind.STT }.forEach { pack ->
                        VoicePackRow(
                            pack = pack,
                            installed = pack.id in installed,
                            selected = settings.sttLocalModelId == pack.id,
                            task = tasks.firstOrNull { it.packId == pack.id },
                            onDownload = { viewModel.download(pack) },
                            onCancel = { viewModel.cancel(pack) },
                            onUse = {
                                viewModel.update { it.copy(sttLocalModelId = pack.id) }
                            },
                            onDelete = {
                                viewModel.delete(pack)
                                if (settings.sttLocalModelId == pack.id) {
                                    viewModel.update { it.copy(sttLocalModelId = "") }
                                }
                            },
                        )
                    }
                    Text(
                        text = "模型下载入口已接入 Hugging Face；本地识别使用 sherpa-onnx。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { testLocalAsr() }) { Text("录音并本地识别") }
                    }
                    val localTest by viewModel.cloudTest.collectAsStateWithLifecycle()
                    localTest?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                VoiceEngineType.CLOUD -> {
                    var expandedProvider by remember { mutableStateOf(false) }
                    val cloudTest by viewModel.cloudTest.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("服务商", modifier = Modifier.weight(1f))
                        Box {
                            AssistChip(
                                onClick = { expandedProvider = true },
                                label = { Text(settings.sttCloudProvider.label) },
                            )
                            DropdownMenu(
                                expanded = expandedProvider,
                                onDismissRequest = { expandedProvider = false },
                            ) {
                                CloudVoiceProvider.entries.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item.label) },
                                        onClick = {
                                            viewModel.update { current ->
                                                current.copy(
                                                    sttCloudProvider = item,
                                                    sttCloudEndpoint = if (
                                                        current.sttCloudEndpoint.isBlank() ||
                                                        CloudVoiceProvider.entries.any {
                                                            it.defaultEndpoint == current.sttCloudEndpoint
                                                        }
                                                    ) {
                                                        item.defaultEndpoint
                                                    } else {
                                                        current.sttCloudEndpoint
                                                    },
                                                    sttCloudModel = if (
                                                        current.sttCloudModel.isBlank() ||
                                                        CloudVoiceProvider.entries.any {
                                                            it.defaultAsrModel() == current.sttCloudModel
                                                        }
                                                    ) {
                                                        item.defaultAsrModel()
                                                    } else {
                                                        current.sttCloudModel
                                                    },
                                                )
                                            }
                                            expandedProvider = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = settings.sttCloudEndpoint,
                        onValueChange = { value ->
                            viewModel.update { it.copy(sttCloudEndpoint = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Endpoint") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = settings.sttCloudKey,
                        onValueChange = { value -> viewModel.update { it.copy(sttCloudKey = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = settings.sttCloudModel,
                        onValueChange = { value -> viewModel.update { it.copy(sttCloudModel = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型名称") },
                        singleLine = true,
                    )
                    if (settings.sttCloudProvider == CloudVoiceProvider.MIMO) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = settings.sttCloudModel == "mimo-v2.5-asr",
                                onClick = {
                                    viewModel.update { it.copy(sttCloudModel = "mimo-v2.5-asr") }
                                },
                                label = { Text("MiMo ASR") },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { testCloudAsr() }) { Text("录 5 秒并识别") }
                    }
                    cloudTest?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "MiMo ASR 使用 /chat/completions 的 input_audio；识别结果可直接插入输入框。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsCard("说明") {
            Text(
                text = "系统 TTS/STT 可直接测试；本地模型完成下载与安装管理，" +
                    "IndexTTS-2 约 5.9 GB，需在设备上留出足够空间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceEngineChips(
    selected: VoiceEngineType,
    onSelect: (VoiceEngineType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VoiceEngineType.entries.forEach { engine ->
            FilterChip(
                selected = selected == engine,
                onClick = { onSelect(engine) },
                label = { Text(engine.label) },
            )
        }
    }
}

@Composable
private fun VoicePackRow(
    pack: VoiceModelPack,
    installed: Boolean,
    selected: Boolean,
    task: VoiceDownloadTask?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pack.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${pack.description} · ${pack.sizeLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Text(
                        text = "已选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            val running = task?.status == VoiceDownloadStatus.RUNNING ||
                task?.status == VoiceDownloadStatus.QUEUED
            if (running) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Text(
                    text = "${task.filesDone}/${task.filesTotal} 个文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task?.status == VoiceDownloadStatus.FAILED) {
                Text(
                    text = task.message.ifBlank { "下载失败" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    running -> TextButton(onClick = onCancel) { Text("取消") }
                    !installed -> Button(onClick = onDownload) {
                        Text(if (task?.status == VoiceDownloadStatus.FAILED) "重试" else "下载")
                    }
                    selected -> Text(
                        text = "已安装并使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    else -> Button(onClick = onUse) { Text("使用") }
                }
                if (installed) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun CloudVoiceFields(
    provider: CloudVoiceProvider,
    endpoint: String,
    apiKey: String,
    model: String,
    voice: String?,
    onProvider: (CloudVoiceProvider) -> Unit,
    onEndpoint: (String) -> Unit,
    onKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onVoice: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("服务商", modifier = Modifier.weight(1f))
        Box {
            AssistChip(onClick = { expanded = true }, label = { Text(provider.label) })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                CloudVoiceProvider.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            onProvider(item)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
    OutlinedTextField(
        value = endpoint,
        onValueChange = onEndpoint,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API Endpoint") },
        singleLine = true,
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onKey,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    OutlinedTextField(
        value = model,
        onValueChange = onModel,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("模型名称") },
        singleLine = true,
    )
    if (voice != null) {
        OutlinedTextField(
            value = voice,
            onValueChange = onVoice,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("音色 / Voice") },
            singleLine = true,
        )
    }
}
