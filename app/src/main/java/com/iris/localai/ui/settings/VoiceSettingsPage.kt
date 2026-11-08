package com.iris.localai.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import androidx.compose.runtime.DisposableEffect
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
import com.iris.localai.voice.VoiceDownloadStatus
import com.iris.localai.voice.VoiceDownloadTask
import java.util.Locale

@Composable
internal fun VoiceSettingsPage(viewModel: VoiceViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installed by viewModel.installedIds.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }
    DisposableEffect(tts) {
        onDispose {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

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
                                text = if (ttsReady) "引擎可用" else "引擎初始化中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                tts.language = Locale.SIMPLIFIED_CHINESE
                                tts.setSpeechRate(settings.ttsSpeed)
                                tts.speak(
                                    "你好，这是 IRIS LocalAI 的语音测试。",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "iris-test",
                                )
                            },
                            enabled = ttsReady,
                        ) { Text("测试朗读") }
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
                                text = "AI 回复完成后自动调用 TTS（接入朗读链路后生效）",
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
                        text = "模型下载入口已接入 Hugging Face；本地推理运行时尚待接入。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                VoiceEngineType.CLOUD -> {
                    CloudVoiceFields(
                        provider = settings.ttsCloudProvider,
                        endpoint = settings.ttsCloudEndpoint,
                        apiKey = settings.ttsCloudKey,
                        model = settings.ttsCloudModel,
                        voice = settings.ttsCloudVoice,
                        onProvider = { provider ->
                            viewModel.update { current ->
                                current.copy(
                                    ttsCloudProvider = provider,
                                    ttsCloudEndpoint = if (current.ttsCloudEndpoint.isBlank()) {
                                        provider.defaultEndpoint
                                    } else {
                                        current.ttsCloudEndpoint
                                    },
                                )
                            }
                        },
                        onEndpoint = { value ->
                            viewModel.update { it.copy(ttsCloudEndpoint = value) }
                        },
                        onKey = { value -> viewModel.update { it.copy(ttsCloudKey = value) } },
                        onModel = { value -> viewModel.update { it.copy(ttsCloudModel = value) } },
                        onVoice = { value -> viewModel.update { it.copy(ttsCloudVoice = value) } },
                    )
                    Text(
                        text = "云端语音调用尚未接入；参数会持久化。",
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
                        text = "模型下载入口已接入 Hugging Face；本地 ASR 运行时尚待接入。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                VoiceEngineType.CLOUD -> {
                    CloudVoiceFields(
                        provider = settings.sttCloudProvider,
                        endpoint = settings.sttCloudEndpoint,
                        apiKey = settings.sttCloudKey,
                        model = settings.sttCloudModel,
                        voice = null,
                        onProvider = { provider ->
                            viewModel.update { current ->
                                current.copy(
                                    sttCloudProvider = provider,
                                    sttCloudEndpoint = if (current.sttCloudEndpoint.isBlank()) {
                                        provider.defaultEndpoint
                                    } else {
                                        current.sttCloudEndpoint
                                    },
                                )
                            }
                        },
                        onEndpoint = { value ->
                            viewModel.update { it.copy(sttCloudEndpoint = value) }
                        },
                        onKey = { value -> viewModel.update { it.copy(sttCloudKey = value) } },
                        onModel = { value -> viewModel.update { it.copy(sttCloudModel = value) } },
                        onVoice = {},
                    )
                    Text(
                        text = "云端语音识别尚未接入；参数会持久化。",
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
