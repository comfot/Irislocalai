package com.iris.localai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 16 kHz 单声道 PCM16 录音，输出可直接上传的 WAV。
 *
 * 语音起止优先用 Silero VAD（[SileroVadDetector]，模型随包）判定，模型不可用时回退能量阈值；
 * 录音源优先 VOICE_COMMUNICATION 并启用系统 AEC / 降噪 / 自动增益，TTS 外放时更不容易自打断。
 */
class VoiceRecorder(private val context: Context? = null) {

    private val silero by lazy { context?.let { SileroVadDetector(it) } }

    @SuppressLint("MissingPermission")
    suspend fun record(
        maxMillis: Int = 15_000,
        minAmplitude: Int = 450,
        // 普通聆听：Silero 判定起止；回退时用能量阈值。
        vad: Boolean = true,
        vadSpeechMillis: Int = 150,
        vadSilenceMillis: Int = 400,
        // 播放中监听：检测到人声立即回调，可在 TTS 播报时插话。
        bargeIn: Boolean = false,
        bargeInThreshold: Int = 1_800,
        bargeInMillis: Int = 300,
        bargeInDelayMillis: Int = 400,
        onSpeechStart: (() -> Unit)? = null,
        stopWhen: (() -> Boolean)? = null,
        /** 每帧 PCM16 回调，供流式识别持续喂音频；在 IO 线程执行，必须立即消费。 */
        onFrame: ((ShortArray, Int) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return@withContext null
        val bufferSize = maxOf(minBuffer * 2, sampleRate)
        val recorder = createRecorder(sampleRate, bufferSize) ?: return@withContext null
        val effects = enableAudioEffects(recorder.audioSessionId)
        val detector = if (vad || bargeIn) silero?.takeIf { it.ensureReady() } else null

        val pcm = java.io.ByteArrayOutputStream()
        // 20 ms 一帧，便于 Silero 窗口与打断延迟都保持细粒度。
        val buffer = ShortArray(FRAME_SAMPLES)
        var peak = 0
        var speechMillis = 0
        var silenceMillis = 0
        var speechStarted = false
        val startedAt = System.currentTimeMillis()
        try {
            recorder.startRecording()
            while (coroutineContext.isActive &&
                System.currentTimeMillis() - startedAt < maxMillis
            ) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                var framePeak = 0
                for (index in 0 until read) {
                    val value = buffer[index].toInt()
                    val amplitude = if (value < 0) -value else value
                    if (amplitude > framePeak) framePeak = amplitude
                    pcm.write(value and 0xFF)
                    pcm.write((value shr 8) and 0xFF)
                }
                if (framePeak > peak) peak = framePeak
                if (onFrame != null) onFrame(buffer, read)
                val frameMillis = (read * 1000 / sampleRate).coerceAtLeast(1)
                val speechNow = detector?.accept(buffer, read) ?: (framePeak >= minAmplitude)

                if (bargeIn) {
                    if (!speechStarted && stopWhen?.invoke() == true) break
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (!speechStarted) {
                        // 播放起始的几百毫秒容易把 TTS 起音当成人声，先跳过。
                        if (elapsed < bargeInDelayMillis) continue
                        if (speechNow) speechMillis += frameMillis else speechMillis = 0
                        if (speechMillis >= bargeInMillis) {
                            speechStarted = true
                            onSpeechStart?.invoke()
                        }
                    } else {
                        if (speechNow) silenceMillis = 0 else silenceMillis += frameMillis
                        if (silenceMillis >= BARGE_IN_SILENCE_MS) break
                    }
                } else if (vad) {
                    if (!speechStarted) {
                        if (speechNow) {
                            speechMillis += frameMillis
                            if (speechMillis >= vadSpeechMillis) {
                                speechStarted = true
                                silenceMillis = 0
                            }
                        } else {
                            speechMillis = 0
                        }
                    } else {
                        if (speechNow) silenceMillis = 0 else silenceMillis += frameMillis
                        if (silenceMillis >= vadSilenceMillis) break
                    }
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            effects.forEach { effect -> runCatching { effect.release() } }
            detector?.reset()
        }

        // 整段没人声就丢弃，避免把静音、环境噪声或 TTS 回声送去识别。
        if ((bargeIn || vad) && !speechStarted) return@withContext null
        if (peak < minAmplitude || pcm.size() == 0) return@withContext null
        if (!coroutineContext.isActive) return@withContext null
        val file = runCatching { File.createTempFile("iris-voice-", ".wav") }.getOrNull()
            ?: return@withContext null
        val written = runCatching { writeWav(file, pcm.toByteArray(), sampleRate) }
        if (written.isFailure) {
            file.delete()
            return@withContext null
        }
        file
    }

    /** VOICE_COMMUNICATION 在多数机型上带通话降噪与回声消除，初始化失败再退回识别源。 */
    private fun createRecorder(sampleRate: Int, bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )
        for (source in sources) {
            val record = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            runCatching { record.release() }
        }
        return null
    }

    private fun enableAudioEffects(sessionId: Int): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>(3)
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }?.let(effects::add)
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }?.let(effects::add)
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            runCatching {
                AutomaticGainControl.create(sessionId)?.apply { enabled = true }?.let(effects::add)
            }
        }
        return effects
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        FileOutputStream(file).use { out ->
            val dataSize = pcm.size
            val header = ByteArray(44)
            fun putInt(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
                header[offset + 2] = ((value shr 16) and 0xFF).toByte()
                header[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            fun putShort(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }
            "RIFF".forEachIndexed { index, char -> header[index] = char.code.toByte() }
            putInt(4, 36 + dataSize)
            "WAVE".forEachIndexed { index, char -> header[8 + index] = char.code.toByte() }
            "fmt ".forEachIndexed { index, char -> header[12 + index] = char.code.toByte() }
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, 1)
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2)
            putShort(32, 2)
            putShort(34, 16)
            "data".forEachIndexed { index, char -> header[36 + index] = char.code.toByte() }
            putInt(40, dataSize)
            out.write(header)
            out.write(pcm)
        }
    }

    private companion object {
        /** 16 kHz 下 20 ms 一帧。 */
        const val FRAME_SAMPLES = 320
        /** 打断后连续静音这么久就认为用户说完了。 */
        const val BARGE_IN_SILENCE_MS = 700
    }
}
