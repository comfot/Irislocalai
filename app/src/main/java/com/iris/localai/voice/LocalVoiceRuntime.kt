package com.iris.localai.voice

import android.content.Context
import com.iris.localai.data.SettingsRepository
import com.iris.localai.data.VoiceKind
import com.iris.localai.data.VoiceModelCatalog
import com.iris.localai.data.VoiceModelPack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

data class LocalAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * sherpa-onnx 本地语音运行时。ASR 与 TTS 各自串行在独立线程上，
 * 模型只在首次使用或切换包时加载；调用方只处理文件/WAV 与 Float PCM。
 */
class LocalVoiceRuntime(
    private val context: Context,
    private val store: VoiceModelStore,
    private val settings: SettingsRepository,
) {

    private val asrWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iris-local-asr")
    }.asCoroutineDispatcher()

    private val ttsWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iris-local-tts")
    }.asCoroutineDispatcher()

    private var recognizer: OfflineRecognizer? = null
    private var asrPack: VoiceModelPack? = null
    private var tts: OfflineTts? = null
    private var ttsPack: VoiceModelPack? = null

    fun selectedAsrPack(): VoiceModelPack? {
        val preferred = VoiceModelCatalog.of(settings.current.sttLocalModelId)
            ?.takeIf { it.kind == VoiceKind.STT && store.isInstalled(it.id) }
        return preferred ?: VoiceModelCatalog.packs.firstOrNull {
            it.kind == VoiceKind.STT && store.isInstalled(it.id)
        }
    }

    fun selectedTtsPack(): VoiceModelPack? {
        val preferred = VoiceModelCatalog.of(settings.current.ttsLocalModelId)
            ?.takeIf { it.kind == VoiceKind.TTS && store.isInstalled(it.id) }
        return preferred ?: VoiceModelCatalog.packs.firstOrNull {
            it.kind == VoiceKind.TTS && store.isInstalled(it.id)
        }
    }

    val isAsrAvailable: Boolean get() = selectedAsrPack() != null
    val isTtsAvailable: Boolean get() = selectedTtsPack() != null

    suspend fun transcribe(file: File): String = withContext(asrWorker) {
        val pack = selectedAsrPack() ?: error("请先下载并启用本地语音识别模型")
        val samples = WavCodec.readPcm16(file)
        if (samples.isEmpty()) error("录音为空")
        ensureRecognizer(pack)
        val engine = recognizer ?: error("本地识别器加载失败")
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, ASR_SAMPLE_RATE)
            engine.decode(stream)
            cleanup(engine.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    suspend fun synthesize(text: String, speed: Float): LocalAudio = withContext(ttsWorker) {
        if (text.isBlank()) error("朗读文本为空")
        val pack = selectedTtsPack() ?: error("请先下载并启用本地语音合成模型")
        ensureTts(pack)
        val engine = tts ?: error("本地合成器加载失败")
        val chunks = ArrayList<FloatArray>()
        val callback = TtsChunkCallback { samples ->
            if (samples.isNotEmpty()) chunks += samples
            true
        }
        engine.generateWithCallback(text, 0, speed.coerceIn(0.5f, 2.0f), callback)
        val total = chunks.sumOf { it.size }
        if (total == 0) error("本地合成没有返回音频")
        val merged = FloatArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }
        LocalAudio(merged, engine.sampleRate())
    }

    suspend fun release() {
        withContext(asrWorker) {
            recognizer?.release()
            recognizer = null
            asrPack = null
        }
        withContext(ttsWorker) {
            tts?.release()
            tts = null
            ttsPack = null
        }
    }

    private fun ensureRecognizer(pack: VoiceModelPack) {
        if (recognizer != null && asrPack?.id == pack.id) return
        recognizer?.release()
        recognizer = null
        val dir = store.dirFor(pack)
        fun path(name: String) = File(dir, name).absolutePath
        when (pack.id) {
            "sensevoice" -> {
                requireFiles(path("model.int8.onnx"), path("tokens.txt"))
                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = ASR_SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = path("model.int8.onnx"),
                                language = "",
                                useInverseTextNormalization = true,
                            ),
                            tokens = path("tokens.txt"),
                            numThreads = 4,
                            provider = "cpu",
                        ),
                    ),
                )
            }
            "paraformer" -> {
                requireFiles(path("model.int8.onnx"), path("tokens.txt"))
                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = ASR_SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            paraformer = OfflineParaformerModelConfig(model = path("model.int8.onnx")),
                            tokens = path("tokens.txt"),
                            numThreads = 4,
                            provider = "cpu",
                        ),
                    ),
                )
            }
            else -> error("暂不支持该本地识别模型：${pack.title}")
        }
        asrPack = pack
    }

    private fun ensureTts(pack: VoiceModelPack) {
        if (tts != null && ttsPack?.id == pack.id) return
        tts?.release()
        tts = null
        val dir = store.dirFor(pack)
        fun path(name: String) = File(dir, name).absolutePath
        when (pack.id) {
            "kokoro" -> {
                requireFiles(
                    path("model.int8.onnx"), path("voices.bin"), path("tokens.txt"),
                    path("espeak-ng-data"), path("lexicon-us-en.txt"), path("lexicon-zh.txt"),
                    path("date-zh.fst"), path("phone-zh.fst"), path("number-zh.fst"),
                )
                tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            kokoro = OfflineTtsKokoroModelConfig(
                                model = path("model.int8.onnx"),
                                voices = path("voices.bin"),
                                tokens = path("tokens.txt"),
                                dataDir = path("espeak-ng-data"),
                                lexicon = "${path("lexicon-us-en.txt")},${path("lexicon-zh.txt")}",
                            ),
                            numThreads = 2,
                            provider = "cpu",
                        ),
                        ruleFsts = listOf("date-zh.fst", "phone-zh.fst", "number-zh.fst")
                            .joinToString(",") { path(it) },
                        maxNumSentences = 1,
                    ),
                )
            }
            "melo" -> {
                requireFiles(
                    path("model.onnx"), path("lexicon.txt"), path("tokens.txt"),
                    path("date.fst"), path("number.fst"),
                )
                tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = path("model.onnx"),
                                lexicon = path("lexicon.txt"),
                                tokens = path("tokens.txt"),
                                dictDir = "",
                            ),
                            numThreads = 2,
                            provider = "cpu",
                        ),
                        ruleFsts = listOf("date.fst", "number.fst").joinToString(",") { path(it) },
                        maxNumSentences = 1,
                    ),
                )
            }
            else -> error("暂不支持该本地合成模型：${pack.title}")
        }
        ttsPack = pack
    }

    private fun requireFiles(vararg paths: String) {
        val missing = paths.filter { !File(it).exists() }
        check(missing.isEmpty()) {
            "语音模型文件不完整（缺少 ${missing.joinToString { File(it).name }}），请删除后重新下载"
        }
    }

    private fun cleanup(text: String): String =
        Regex("<\\|[^|]*\\|>").replace(text, "").trim()

    private companion object {
        const val ASR_SAMPLE_RATE = 16_000
    }
}

/**
 * sherpa-onnx JNI 通过对象自身类查找 `invoke([F)Ljava/lang/Integer;`。
 * Kotlin 2 的 lambda 可能只生成桥接 invoke(Object)，因此必须用真实类声明签名。
 */
class TtsChunkCallback(private val onAudio: (FloatArray) -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int =
        try {
            if (onAudio(samples)) 1 else 0
        } catch (_: Throwable) {
            0
        }
}

object WavCodec {

    fun readPcm16(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size < 44) return FloatArray(0)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = littleInt(bytes, offset + 4)
            val body = offset + 8
            if (id == "data") {
                val end = minOf(bytes.size, body + size)
                val count = (end - body).coerceAtLeast(0) / 2
                val samples = FloatArray(count)
                for (index in 0 until count) {
                    val value = ((bytes[body + index * 2].toInt() and 0xFF) or
                        (bytes[body + index * 2 + 1].toInt() shl 8)).toShort()
                    samples[index] = value / 32768f
                }
                return samples
            }
            offset = body + size + (size and 1)
        }
        return FloatArray(0)
    }

    fun writeFloatWav(file: File, samples: FloatArray, sampleRate: Int) {
        val bytes = ByteArray(44 + samples.size * 2)
        putAscii(bytes, 0, "RIFF")
        putInt(bytes, 4, 36 + samples.size * 2)
        putAscii(bytes, 8, "WAVE")
        putAscii(bytes, 12, "fmt ")
        putInt(bytes, 16, 16)
        putShort(bytes, 20, 1)
        putShort(bytes, 22, 1)
        putInt(bytes, 24, sampleRate)
        putInt(bytes, 28, sampleRate * 2)
        putShort(bytes, 32, 2)
        putShort(bytes, 34, 16)
        putAscii(bytes, 36, "data")
        putInt(bytes, 40, samples.size * 2)
        var offset = 44
        samples.forEach { sample ->
            val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[offset++] = (value and 0xFF).toByte()
            bytes[offset++] = ((value shr 8) and 0xFF).toByte()
        }
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun putAscii(bytes: ByteArray, offset: Int, text: String) {
        text.forEachIndexed { index, char -> bytes[offset + index] = char.code.toByte() }
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun putShort(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }
}
