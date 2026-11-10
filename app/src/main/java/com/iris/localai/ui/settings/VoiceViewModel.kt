package com.iris.localai.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.VoiceModelPack
import com.iris.localai.voice.CloudVoiceClient
import com.iris.localai.voice.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph
    private val cloud = CloudVoiceClient()
    private val recorder = VoiceRecorder()

    val settings = graph.settings.settings
    val installedIds = graph.voiceModels.installedIds
    val tasks = graph.voiceDownloads.tasks
    val ttsStatus = graph.tts.status

    private val _cloudTest = MutableStateFlow<String?>(null)
    val cloudTest: StateFlow<String?> = _cloudTest.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) = graph.settings.update(transform)

    fun testSystemTts(text: String) {
        graph.tts.speak(text, graph.settings.current.ttsSpeed)
    }

    fun retrySystemTts() = graph.tts.retry()

    fun importVoiceSample(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val displayName = resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    val ext = displayName?.substringAfterLast('.', "")?.lowercase()
                        ?.takeIf { it == "mp3" || it == "wav" }
                        ?: when (resolver.getType(uri)?.lowercase()) {
                            "audio/mpeg", "audio/mp3" -> "mp3"
                            else -> "wav"
                        }
                    val dir = File(getApplication<Application>().filesDir, "voice_clones").apply { mkdirs() }
                    val target = File(dir, "sample-${System.currentTimeMillis()}.$ext")
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取音频")
                    target.absolutePath
                }
            }
            path.onSuccess { file -> update { it.copy(ttsCloudVoiceSample = file) } }
                .onFailure { _cloudTest.value = "导入失败：${it.message ?: "未知错误"}" }
        }
    }

    fun testCloudTts(text: String) {
        _cloudTest.value = "正在请求云端合成…"
        graph.tts.speakCloud(text, graph.settings.current)
    }

    fun testLocalTts(text: String) {
        _cloudTest.value = "已发起本地合成（失败会回退系统朗读）"
        graph.tts.speakLocal(text, graph.settings.current)
    }

    fun testLocalAsr() {
        viewModelScope.launch {
            _cloudTest.value = "正在录音…"
            val file = recorder.record(maxMillis = 5_000)
            if (file == null) {
                _cloudTest.value = "没有录到有效语音"
                return@launch
            }
            _cloudTest.value = "正在本地识别…"
            val result = runCatching { graph.localVoice.transcribe(file) }
            file.delete()
            _cloudTest.value = result.fold(
                onSuccess = { "识别结果：$it" },
                onFailure = { "识别失败：${it.message ?: "未知错误"}" },
            )
        }
    }

    fun testCloudAsr() {
        viewModelScope.launch {
            _cloudTest.value = "正在录音…"
            val file = recorder.record(maxMillis = 5_000)
            if (file == null) {
                _cloudTest.value = "没有录到有效语音"
                return@launch
            }
            _cloudTest.value = "正在识别…"
            val result = cloud.transcribe(graph.settings.current, file)
            file.delete()
            _cloudTest.value = result.fold(
                onSuccess = { "识别结果：$it" },
                onFailure = { "识别失败：${it.message ?: "未知错误"}" },
            )
        }
    }

    fun download(pack: VoiceModelPack) {
        graph.voiceDownloads.enqueue(pack, graph.settings.current.downloadSource)
    }

    fun cancel(pack: VoiceModelPack) = graph.voiceDownloads.cancel(pack.id)

    fun delete(pack: VoiceModelPack) {
        graph.voiceModels.delete(pack.id)
    }
}
