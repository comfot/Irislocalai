package com.iris.localai.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.coroutines.coroutineContext

/** 16 kHz 单声道 PCM16 录音，输出可直接上传的 WAV。 */
class VoiceRecorder {

    suspend fun record(
        maxMillis: Int = 6_000,
        minAmplitude: Int = 450,
    ): File? = withContext(Dispatchers.IO) {
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return@withContext null
        val bufferSize = maxOf(minBuffer * 2, sampleRate)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull() ?: return@withContext null
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return@withContext null
        }

        val pcm = java.io.ByteArrayOutputStream()
        val buffer = ShortArray(bufferSize / 2)
        var peak = 0
        val startedAt = System.currentTimeMillis()
        try {
            recorder.startRecording()
            while (coroutineContext.isActive &&
                System.currentTimeMillis() - startedAt < maxMillis
            ) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                for (index in 0 until read) {
                    val value = buffer[index].toInt()
                    if (abs(value) > peak) peak = abs(value)
                    pcm.write(value and 0xFF)
                    pcm.write((value shr 8) and 0xFF)
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        if (peak < minAmplitude || pcm.size() == 0) return@withContext null
        if (!coroutineContext.isActive) return@withContext null
        val file = runCatching { File.createTempFile("iris-voice-", ".wav") }.getOrNull()
            ?: return@withContext null
        runCatching {
            writeWav(file, pcm.toByteArray(), sampleRate)
        }.getOrElse {
            file.delete()
            return@withContext null
        }
        file
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
}
