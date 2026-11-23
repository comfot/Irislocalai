package com.iris.localai.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Silero VAD（sherpa-onnx）封装：按 512 样本窗口判定语音起止。
 *
 * 模型随应用放在 assets/voice/silero_vad.onnx，首次使用时复制到私有目录；
 * 模型缺失或加载失败时 [ensureReady] 返回 false，调用方回退到能量阈值。
 */
class SileroVadDetector(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private var vad: Vad? = null
    private var failed = false
    private val pending = FloatArray(CAPACITY)
    private var pendingSize = 0
    private var speech = false

    fun ensureReady(): Boolean {
        if (vad != null) return true
        if (failed) return false
        val ready = runCatching {
            val model = ensureModelFile()
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.absolutePath,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_SECONDS,
                    minSpeechDuration = MIN_SPEECH_SECONDS,
                    windowSize = WINDOW_SIZE,
                    maxSpeechDuration = MAX_SPEECH_SECONDS,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 2,
                provider = "cpu",
            )
            vad = Vad(assetManager = null, config = config)
            true
        }.getOrElse {
            failed = true
            false
        }
        return ready
    }

    /** 喂入 16 kHz PCM16 片段，返回当前是否在说话（已完成一次窗口推理）。 */
    fun accept(samples: ShortArray, size: Int = samples.size): Boolean {
        val engine = vad ?: return false
        var index = 0
        while (index < size) {
            val copy = minOf(size - index, CAPACITY - pendingSize)
            var offset = 0
            while (offset < copy) {
                pending[pendingSize + offset] = samples[index + offset] / 32768.0f
                offset++
            }
            pendingSize += copy
            index += copy
            while (pendingSize >= WINDOW_SIZE) {
                engine.acceptWaveform(pending.copyOfRange(0, WINDOW_SIZE))
                speech = engine.isSpeechDetected()
                val remaining = pendingSize - WINDOW_SIZE
                pending.copyInto(pending, 0, WINDOW_SIZE, pendingSize)
                pendingSize = remaining
            }
        }
        return speech
    }

    fun reset() {
        runCatching { vad?.reset() }
        pendingSize = 0
        speech = false
    }

    override fun close() {
        runCatching { vad?.release() }
        vad = null
        pendingSize = 0
        speech = false
    }

    private fun ensureModelFile(): File {
        val target = File(appContext.filesDir, "vad/silero_vad.onnx")
        if (target.isFile && target.length() > 0) return target
        target.parentFile?.mkdirs()
        appContext.assets.open(MODEL_ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private companion object {
        const val MODEL_ASSET = "voice/silero_vad.onnx"
        const val WINDOW_SIZE = 512
        const val CAPACITY = 2048
        const val SAMPLE_RATE = 16000
        /** 参考 ai-assistant-android：0.5 阈值、0.5 s 静音、0.15 s 最短语音。 */
        const val VAD_THRESHOLD = 0.5f
        const val MIN_SILENCE_SECONDS = 0.5f
        const val MIN_SPEECH_SECONDS = 0.15f
        const val MAX_SPEECH_SECONDS = 30.0f
    }
}
