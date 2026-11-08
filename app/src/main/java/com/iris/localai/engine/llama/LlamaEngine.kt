package com.iris.localai.engine.llama

import com.iris.localai.engine.Backend
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.InferenceEngine
import com.iris.localai.engine.LoadConfig
import com.iris.localai.engine.ModelDetails
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.Executors

/**
 * GGUF inference through the prebuilt libiris_llama.so (llama.cpp + JNI).
 * All native calls are serialized on one dedicated thread.
 */
class LlamaEngine : InferenceEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val handleLock = Any()

    @Volatile private var handle = 0L
    @Volatile private var loaded = false
    @Volatile private var activeBackend = Backend.CPU
    @Volatile private var active: Request? = null

    private class Request {
        @Volatile var cancelled = false
    }

    override val isLoaded: Boolean get() = loaded

    override suspend fun load(
        modelPath: String,
        config: LoadConfig,
        onProgress: (Float) -> Unit,
    ): ModelDetails = withContext(nativeThread) {
        LlamaNative.unavailableReason?.let { throw IllegalStateException(it) }
        val file = File(modelPath)
        if (!file.isFile || file.length() == 0L) {
            throw IllegalStateException("本地模型文件不存在或为空：$modelPath")
        }
        if (!modelPath.endsWith(".gguf", ignoreCase = true)) {
            throw IllegalStateException("当前本地运行库只支持 GGUF 模型")
        }
        if (config.backend == Backend.NPU) {
            throw IllegalStateException("当前预编译运行库未包含 Hexagon/NPU 后端，请选择 CPU 或 GPU")
        }

        val attempts = when {
            config.backend == Backend.GPU && config.fallbackToCpu -> listOf(Backend.GPU, Backend.CPU)
            else -> listOf(config.backend)
        }
        var lastError: String? = null
        for ((index, backend) in attempts.withIndex()) {
            destroyInternal()
            val newHandle = LlamaNative.nativeCreate()
            if (newHandle == 0L) {
                lastError = "无法创建 llama.cpp 引擎实例"
                continue
            }
            handle = newHandle
            val listener = LoadProgressListener { progress ->
                onProgress(progress.coerceIn(0f, 1f))
                true
            }
            val error = LlamaNative.nativeLoad(
                newHandle,
                modelPath.toByteArray(Charsets.UTF_8),
                config.contextLength.coerceIn(256, 131072),
                config.threads.coerceIn(1, 16),
                config.batchSize.coerceIn(32, 2048),
                config.flashAttention,
                if (backend == Backend.GPU) GPU_ALL_LAYERS else 0,
                if (config.flashAttention) config.kvCacheType.coerceIn(0, 2) else 0,
                config.useMmap,
                listener,
            )
            if (error != null) {
                lastError = error
                destroyInternal()
                continue
            }
            val meta = runCatching {
                val obj = Json.parseToJsonElement(LlamaNative.nativeMeta(newHandle)).jsonObject
                NativeMeta(
                    description = obj.string("description"),
                    architecture = obj.string("architecture"),
                    gpu = obj.string("gpu"),
                    nParams = obj.long("nParams"),
                    sizeBytes = obj.long("sizeBytes"),
                    nCtxTrain = obj.int("nCtxTrain"),
                    nCtx = obj.int("nCtx"),
                    hasChatTemplate = obj.bool("hasChatTemplate"),
                    supportsThinking = obj.bool("supportsThinking"),
                    thinkingPrefilled = obj.bool("thinkingPrefilled"),
                    vision = obj.bool("vision"),
                    visionGpu = obj.bool("visionGpu"),
                )
            }.getOrDefault(NativeMeta())
            if (backend == Backend.GPU && meta.gpu.isBlank()) {
                lastError = "未检测到可用的 OpenCL GPU，已尝试回退 CPU"
                destroyInternal()
                continue
            }
            loaded = true
            activeBackend = if (backend == Backend.GPU && meta.gpu.isNotBlank()) Backend.GPU else Backend.CPU
            val suffix = if (index > 0) "（已回退 CPU）" else ""
            return@withContext ModelDetails(
                name = meta.description.ifBlank { file.nameWithoutExtension } + suffix,
                contextLength = meta.nCtx.takeIf { it > 0 } ?: config.contextLength,
                backend = activeBackend,
                parameterCount = meta.nParams,
            )
        }
        throw IllegalStateException(lastError ?: "llama.cpp 模型加载失败")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = channelFlow {
        if (!loaded) {
            send(GenEvent.Failed("本地模型尚未加载"))
            return@channelFlow
        }
        val request = Request()
        active = request
        try {
            val roles = Array(history.size) { history[it].role.toByteArray(Charsets.UTF_8) }
            val contents = Array(history.size) { history[it].content.toByteArray(Charsets.UTF_8) }
            val floats = floatArrayOf(
                params.temperature,
                params.topP,
                params.minP,
                params.repeatPenalty,
                params.frequencyPenalty,
                params.presencePenalty,
            )
            val ints = intArrayOf(
                params.topK,
                params.repeatLastN,
                params.maxNewTokens,
                params.seed ?: -1,
                0,
                1,
                0,
                4,
            )
            val result = withContext(nativeThread) {
                val h = handle
                if (h == 0L || !loaded) return@withContext null
                LlamaNative.nativeClearCancel(h)
                if (request.cancelled) return@withContext CANCELLED_JSON
                LlamaNative.nativeGenerate(
                    h,
                    roles,
                    contents,
                    floats,
                    ints,
                    PieceSink { bytes ->
                        if (request.cancelled) {
                            false
                        } else {
                            trySend(GenEvent.Delta(String(bytes, Charsets.UTF_8))).isSuccess
                        }
                    },
                )
            }
            if (result == null) {
                send(GenEvent.Failed("本地模型已卸载"))
                return@channelFlow
            }
            val stats = runCatching {
                val obj = Json.parseToJsonElement(result).jsonObject
                NativeStats(
                    promptTokens = obj.int("promptTokens"),
                    reusedTokens = obj.int("reusedTokens"),
                    prefillUs = obj.long("prefillUs"),
                    generatedTokens = obj.int("generatedTokens"),
                    generateUs = obj.long("generateUs"),
                    droppedMessages = obj.int("droppedMessages"),
                    shiftedTokens = obj.int("shiftedTokens"),
                    kvUsed = obj.int("kvUsed"),
                    liveSwitches = obj.int("liveSwitches"),
                    stop = obj.int("stop", 4),
                    error = obj.string("error"),
                )
            }.getOrDefault(NativeStats())
            when (stats.stop) {
                0, 1, 2 -> send(GenEvent.Done)
                3 -> send(GenEvent.Failed("上下文已满，无法继续生成"))
                else -> send(GenEvent.Failed(stats.error.ifBlank { "llama.cpp 生成失败" }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(GenEvent.Failed(e.message ?: "llama.cpp 生成失败"))
        } finally {
            if (active === request) active = null
        }
    }.buffer(Channel.UNLIMITED)

    override fun cancel() {
        val request = active
        if (request != null) {
            request.cancelled = true
            nativeCancel()
        } else {
            nativeCancel()
        }
    }

    private fun nativeCancel() {
        synchronized(handleLock) {
            val h = handle
            if (h != 0L) runCatching { LlamaNative.nativeCancel(h) }
        }
    }

    override suspend fun resetContext() = withContext(nativeThread) {
        val h = handle
        if (h != 0L) runCatching { LlamaNative.nativeReset(h) }
    }

    override suspend fun unload() {
        cancel()
        withContext(nativeThread) { destroyInternal() }
    }

    private fun destroyInternal() {
        val h = handle
        handle = 0L
        loaded = false
        if (h != 0L) {
            runCatching { LlamaNative.nativeUnload(h) }
            runCatching { LlamaNative.nativeDestroy(h) }
        }
    }

    private data class NativeMeta(
        val description: String = "",
        val architecture: String = "",
        val gpu: String = "",
        val nParams: Long = 0,
        val sizeBytes: Long = 0,
        val nCtxTrain: Int = 0,
        val nCtx: Int = 0,
        val hasChatTemplate: Boolean = false,
        val supportsThinking: Boolean = false,
        val thinkingPrefilled: Boolean = false,
        val vision: Boolean = false,
        val visionGpu: Boolean = false,
    )

    private data class NativeStats(
        val promptTokens: Int = 0,
        val reusedTokens: Int = 0,
        val prefillUs: Long = 0,
        val generatedTokens: Int = 0,
        val generateUs: Long = 0,
        val droppedMessages: Int = 0,
        val shiftedTokens: Int = 0,
        val kvUsed: Int = 0,
        val liveSwitches: Int = 0,
        val stop: Int = 4,
        val error: String = "",
    )

    private fun JsonObject.string(key: String): String =
        (this[key]?.jsonPrimitive?.contentOrNull).orEmpty()

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L

    private fun JsonObject.int(key: String, fallback: Int = 0): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: fallback

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private companion object {
        const val GPU_ALL_LAYERS = 999
        const val CANCELLED_JSON = """{"stop":2}"""

        val nativeThread by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "iris-llama").apply {
                    priority = Thread.MAX_PRIORITY
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
        }
    }
}
