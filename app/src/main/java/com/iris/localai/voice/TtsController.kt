package com.iris.localai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 系统 TTS 的薄封装：异步初始化、语速设置、长文本分段与队列播放。
 * 本地/云端 TTS 仍走各自的后续实现，这里只负责已可用的系统引擎。
 */
class TtsController(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: Pending? = null
    private var utterance = 0
    private var remainingUtterances = 0
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private data class Pending(val text: String, val speed: Float)

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                configureLanguage()
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _speaking.value = true
                    }

                    override fun onDone(utteranceId: String?) = finishUtterance()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finishUtterance()

                    override fun onError(utteranceId: String?, errorCode: Int) = finishUtterance()
                })
                pending?.let { speakNow(it) }
            } else {
                pending = null
                _speaking.value = false
            }
        }
    }

    private fun finishUtterance() {
        remainingUtterances = (remainingUtterances - 1).coerceAtLeast(0)
        if (remainingUtterances == 0) _speaking.value = false
    }

    fun speak(text: String, speed: Float) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val request = Pending(trimmed, speed.coerceIn(0.5f, 2.0f))
        if (!ready) {
            pending = request
            return
        }
        speakNow(request)
    }

    fun stop() {
        pending = null
        remainingUtterances = 0
        _speaking.value = false
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        pending = null
        remainingUtterances = 0
        _speaking.value = false
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    private fun speakNow(request: Pending) {
        pending = null
        val tts = engine ?: return
        tts.setSpeechRate(request.speed)
        val chunks = split(request.text)
        remainingUtterances = chunks.size
        _speaking.value = chunks.isNotEmpty()
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(chunk, mode, null, "iris-tts-${utterance++}")
        }
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
    }
}
