package com.iris.localai.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.iris.localai.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

enum class SystemTtsStatus(val label: String) {
    INITIALIZING("引擎初始化中"),
    READY("引擎可用"),
    UNAVAILABLE("未找到可用的系统 TTS 引擎"),
}

/**
 * 系统 TTS 的薄封装：异步初始化、语速设置、长文本分段与队列播放。
 * 优先 synthesizeToFile 后再用 MediaPlayer 播放，以便接 Visualizer 提供真实振幅；
 * 引擎不支持时自动回退到直接 speak，此时振幅保持 0，口型回退到说话状态。
 */
class TtsController(
    context: Context,
    private val localVoice: LocalVoiceRuntime? = null,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cloudClient = CloudVoiceClient()
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cloudJob: Job? = null
    private var localJob: Job? = null
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initGeneration = 0
    private var currentAttempt = 0
    private var pending: Pending? = null
    private var generation = 0
    private var fileMode = true
    private var directRemaining = 0
    private var currentSynthId: String? = null
    private var currentText: String = ""

    private val queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var currentFile: File? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _status = MutableStateFlow(SystemTtsStatus.INITIALIZING)
    val status: StateFlow<SystemTtsStatus> = _status.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private data class Pending(val text: String, val speed: Float)

    init {
        appContext.cacheDir.listFiles { file -> file.name.startsWith("iris-tts-") }?.forEach { it.delete() }
        initEngine()
    }

    private fun initEngine() {
        val token = ++initGeneration
        ready = false
        currentAttempt = 0
        _status.value = SystemTtsStatus.INITIALIZING
        runCatching { engine?.shutdown() }
        engine = null
        startAttempt(token, 1)
    }

    private fun startAttempt(token: Int, attempt: Int) {
        if (token != initGeneration) return
        currentAttempt = attempt
        ready = false
        _status.value = SystemTtsStatus.INITIALIZING
        val holder = arrayOfNulls<TextToSpeech>(1)
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                if (token != initGeneration || attempt != currentAttempt) return@post
                val instance = holder[0]
                if (status == TextToSpeech.SUCCESS && instance != null) {
                    engine = instance
                    ready = true
                    currentAttempt = 0
                    _status.value = SystemTtsStatus.READY
                    configureLanguage()
                    attachSystemListener(instance)
                    pending?.let { speakNow(it) }
                } else {
                    runCatching { instance?.shutdown() }
                    retryOrFail(token, attempt)
                }
            }
        }
        val created = runCatching {
            val engineName = if (attempt > 1) defaultEngineName() else null
            if (!engineName.isNullOrBlank()) {
                TextToSpeech(appContext, listener, engineName)
            } else {
                TextToSpeech(appContext, listener)
            }
        }.getOrNull()
        holder[0] = created
        if (created == null) {
            retryOrFail(token, attempt)
            return
        }
        mainHandler.postDelayed({
            if (token == initGeneration && attempt == currentAttempt && !ready) {
                retryOrFail(token, attempt)
            }
        }, ATTEMPT_TIMEOUT_MS)
    }

    private fun retryOrFail(token: Int, attempt: Int) {
        if (token != initGeneration || attempt != currentAttempt) return
        currentAttempt = 0
        if (attempt < MAX_INIT_ATTEMPTS) {
            mainHandler.postDelayed({ startAttempt(token, attempt + 1) }, RETRY_DELAY_MS)
        } else {
            ready = false
            _status.value = SystemTtsStatus.UNAVAILABLE
            pending = null
            _speaking.value = false
        }
    }

    private fun defaultEngineName(): String? = runCatching {
        TextToSpeech::class.java.getDeclaredMethod("getDefaultEngine")
            .apply { isAccessible = true }
            .invoke(null) as? String
    }.getOrNull()

    private fun attachSystemListener(instance: TextToSpeech) {
        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId?.startsWith("synth-") != true) _speaking.value = true
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId?.startsWith("synth-") == true) {
                        onSynthDone(utteranceId)
                    } else {
                        finishDirect()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId?.startsWith("synth-") == true) fallbackToDirect() else finishDirect()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post {
                    if (utteranceId?.startsWith("synth-") == true) fallbackToDirect() else finishDirect()
                }
            }
        })
    }

    fun retry() {
        if (_status.value == SystemTtsStatus.INITIALIZING) return
        initEngine()
    }

    fun speak(text: String, speed: Float) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        stop()
        val request = Pending(trimmed, speed.coerceIn(0.5f, 2.0f))
        if (!ready) {
            pending = request
            if (_status.value == SystemTtsStatus.UNAVAILABLE) retry()
            return
        }
        speakNow(request)
    }

    fun speakLocal(text: String, settings: AppSettings) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        stop()
        val generationAtStart = generation
        _speaking.value = true
        localJob = cloudScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val runtime = localVoice ?: error("本地语音运行时不可用")
                    runtime.synthesize(trimmed, settings.ttsSpeed)
                }
            }
            if (generation != generationAtStart) return@launch
            result.onSuccess { audio ->
                if (generation != generationAtStart) return@onSuccess
                val file = File(
                    appContext.cacheDir,
                    "iris-local-tts-$generationAtStart-${System.nanoTime()}.wav",
                )
                runCatching { WavCodec.writeFloatWav(file, audio.samples, audio.sampleRate) }
                    .onSuccess {
                        currentFile = file
                        currentSynthId = null
                        currentText = trimmed
                        queue.clear()
                        playFile(file)
                    }
                    .onFailure {
                        _speaking.value = false
                        speak(trimmed, settings.ttsSpeed)
                    }
            }.onFailure {
                _speaking.value = false
                speak(trimmed, settings.ttsSpeed)
            }
        }
    }

    fun speakCloud(text: String, settings: AppSettings) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        stop()
        val generationAtStart = generation
        _speaking.value = true
        cloudJob = cloudScope.launch {
            val result = withContext(Dispatchers.IO) {
                cloudClient.synthesize(settings, trimmed)
            }
            if (generation != generationAtStart) return@launch
            result.onSuccess { bytes ->
                if (generation != generationAtStart) return@onSuccess
                val file = File(appContext.cacheDir, "iris-cloud-tts-$generationAtStart-${System.nanoTime()}.wav")
                runCatching { file.writeBytes(bytes) }
                    .onSuccess {
                        currentFile = file
                        currentSynthId = null
                        currentText = trimmed
                        queue.clear()
                        playFile(file)
                    }
                    .onFailure {
                        _speaking.value = false
                        speak(trimmed, settings.ttsSpeed)
                    }
            }.onFailure {
                _speaking.value = false
                speak(trimmed, settings.ttsSpeed)
            }
        }
    }

    fun stop() {
        pending = null
        generation += 1
        cloudJob?.cancel()
        cloudJob = null
        localJob?.cancel()
        localJob = null
        queue.clear()
        currentSynthId = null
        currentText = ""
        directRemaining = 0
        releasePlayback()
        currentFile?.delete()
        currentFile = null
        _amplitude.value = 0f
        _speaking.value = false
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        initGeneration += 1
        currentAttempt = 0
        stop()
        cloudScope.cancel()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        _status.value = SystemTtsStatus.UNAVAILABLE
    }

    private fun speakNow(request: Pending) {
        pending = null
        val tts = engine ?: return
        tts.setSpeechRate(request.speed)
        val chunks = split(request.text)
        queue.clear()
        queue.addAll(chunks)
        _speaking.value = chunks.isNotEmpty()
        if (fileMode) synthNext() else directSpeak(chunks)
    }

    private fun synthNext() {
        val tts = engine ?: return
        if (queue.isEmpty()) {
            _speaking.value = false
            _amplitude.value = 0f
            currentSynthId = null
            return
        }
        val text = queue.removeFirst()
        currentText = text
        val index = generation
        val id = "synth-$index-${System.nanoTime()}"
        val file = File(appContext.cacheDir, "iris-tts-$index-${System.nanoTime()}.wav")
        currentSynthId = id
        currentFile = file
        val result = runCatching {
            tts.synthesizeToFile(text, Bundle(), file, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            file.delete()
            fileMode = false
            currentFile = null
            currentSynthId = null
            fallbackToDirect()
        }
    }

    private fun onSynthDone(utteranceId: String) {
        if (utteranceId != currentSynthId) return
        val file = currentFile
        if (file == null || !file.isFile || file.length() <= 0) {
            fileMode = false
            currentFile = null
            currentSynthId = null
            fallbackToDirect()
            return
        }
        playFile(file)
    }

    private fun playFile(file: File) {
        releasePlayback()
        runCatching {
            val media = MediaPlayer.create(appContext, Uri.fromFile(file)) ?: error("MediaPlayer 创建失败")
            media.setOnCompletionListener {
                finishPlayback()
                synthNext()
            }
            media.setOnErrorListener { _, _, _ ->
                finishPlayback()
                fileMode = false
                fallbackToDirect()
                true
            }
            attachVisualizer(media)
            player = media
            media.start()
            _speaking.value = true
        }.onFailure {
            file.delete()
            fileMode = false
            currentFile = null
            currentSynthId = null
            fallbackToDirect()
        }
    }

    private fun attachVisualizer(media: MediaPlayer) {
        runCatching {
            val range = Visualizer.getCaptureSizeRange()
            val size = range.getOrElse(1) { 1024 }.coerceIn(256, 1024)
            visualizer = Visualizer(media.audioSessionId).apply {
                captureSize = size
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (waveform == null || waveform.isEmpty()) return
                            var sum = 0.0
                            for (value in waveform) {
                                val sample = (value.toInt() and 0xFF) - 128
                                val normalized = sample / 128.0
                                sum += normalized * normalized
                            }
                            val rms = sqrt(sum / waveform.size).toFloat()
                            _amplitude.value = (rms * 8f).coerceIn(0f, 1f)
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) = Unit
                    },
                    (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(10),
                    true,
                    false,
                )
                enabled = true
            }
        }.onFailure {
            visualizer?.release()
            visualizer = null
        }
    }

    private fun fallbackToDirect() {
        fileMode = false
        val rest = buildList {
            if (currentText.isNotBlank()) add(currentText)
            addAll(queue)
        }
        queue.clear()
        currentText = ""
        currentFile?.delete()
        currentFile = null
        currentSynthId = null
        releasePlayback()
        _amplitude.value = 0f
        if (rest.isEmpty()) {
            _speaking.value = false
            return
        }
        directSpeak(rest)
    }

    private fun directSpeak(chunks: List<String>) {
        val tts = engine ?: return
        directRemaining = chunks.size
        _speaking.value = chunks.isNotEmpty()
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk, mode, null, "iris-tts-${generation}-${index}")
        }
    }

    private fun finishDirect() {
        directRemaining = (directRemaining - 1).coerceAtLeast(0)
        if (directRemaining == 0) {
            _speaking.value = false
            _amplitude.value = 0f
        }
    }

    private fun finishPlayback() {
        _amplitude.value = 0f
        releasePlayback()
        currentFile?.delete()
        currentFile = null
        currentSynthId = null
        if (queue.isEmpty()) _speaking.value = false
    }

    private fun releasePlayback() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        runCatching { player?.release() }
        player = null
        _amplitude.value = 0f
    }

    private fun configureLanguage() {
        val tts = engine ?: return
        val candidates = listOf(Locale.getDefault(), Locale.SIMPLIFIED_CHINESE, Locale.US)
        for (locale in candidates) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) return
        }
    }

    private fun split(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val chunks = mutableListOf<String>()
        val builder = StringBuilder()
        text.split(Regex("(?<=[。！？!?；;\\n])")).forEach { sentence ->
            if (builder.length + sentence.length > MAX_CHUNK && builder.isNotEmpty()) {
                chunks += builder.toString()
                builder.clear()
            }
            if (sentence.length > MAX_CHUNK) {
                sentence.chunked(MAX_CHUNK).forEach { part ->
                    if (builder.isNotEmpty()) {
                        chunks += builder.toString()
                        builder.clear()
                    }
                    chunks += part
                }
            } else {
                builder.append(sentence)
            }
        }
        if (builder.isNotEmpty()) chunks += builder.toString()
        return chunks
    }

    private companion object {
        const val MAX_CHUNK = 3200
        const val MAX_INIT_ATTEMPTS = 3
        const val ATTEMPT_TIMEOUT_MS = 5_000L
        const val RETRY_DELAY_MS = 800L
    }
}
