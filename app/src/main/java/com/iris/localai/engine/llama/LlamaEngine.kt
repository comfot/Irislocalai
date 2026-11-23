package com.iris.localai.engine.llama

import android.content.Context
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
class LlamaEngine(private val context: Context? = null) : InferenceEngine {

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

        // 开发者选项：把 /vendor 的 Adreno OpenCL 驱动链复制到私有目录，供 cl_shim 用
        // android_dlopen_ext + 独立命名空间加载（部分 ROM 的 vendor libOpenCL 是 stub，
        // 三方进程直接 dlopen 会因依赖不在公共库白名单被 linker 拒绝）。
        if (config.backend == Backend.GPU && context != null) {
            val adrenoDir = File(context.filesDir, "adreno").apply { mkdirs() }
            val vendorDrivers = listOf(
                "/vendor/lib64/libOpenCL.so",
                "/vendor/lib64/libOpenCL_adreno.so",
                "/vendor/lib64/libopencl_loader.so",
                "/vendor/lib64/libCB.so",
                "/vendor/lib64/libgsl.so",
                "/vendor/lib64/libar-gsl.so",
                "/vendor/lib64/libadreno_utils.so",
                "/vendor/lib64/libkernelmanager.so",
                "/vendor/lib64/libkcl.so",
                "/vendor/lib64/libllvm-glnext.so",
                "/vendor/lib64/libllvm-qcom.so",
                // llama.cpp #29786：libllvm-qgl.so（高通 shader 编译器）是 OpenCL 成败的关键单文件，
                // 缺它时 clGetPlatformIDs 返回 0 平台。
                "/vendor/lib64/libllvm-qgl.so",
                "/vendor/lib64/egl/libq3dtools_adreno.so",
                "/vendor/lib64/egl/libEGL_adreno.so",
                "/vendor/lib64/egl/libGLESv2_adreno.so",
            )
            vendorDrivers.forEach { src ->
                runCatching { File(src).copyTo(File(adrenoDir, File(src).name), overwrite = true) }
            }
            LlamaNative.nativeSetOpenclDir(adrenoDir.absolutePath)
        }

        val attempts = when {
            config.backend == Backend.GPU && config.fallbackToCpu -> listOf(Backend.GPU, Backend.CPU)
            else -> listOf(config.backend)
        }
        // 性能页的 0 表示“自动”：按大小核数折算（超线程/大小核各占一半），而非单线程。
        val threads = if (config.threads <= 0) {
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 16)
        } else {
            config.threads.coerceIn(1, 16)
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
                threads,
                config.batchSize.coerceIn(32, 2048),
                config.flashAttention,
                if (backend == Backend.GPU) GPU_ALL_LAYERS else 0,
                if (config.flashAttention) config.kvCacheType.coerceIn(0, 2) else 0,
                config.useMmap,
                listener,
            )
            if (error != null) {
                val gpuLog = LlamaNative.gpuLastLog().takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                lastError = error + gpuLog
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
                val gpuLog = LlamaNative.gpuLastLog().takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                val clStatus = LlamaNative.nativeOpenclStatus().takeIf { it.isNotBlank() }?.let { " · shim: $it" }.orEmpty()
                lastError = "未检测到可用的 OpenCL GPU$gpuLog$clStatus，已尝试回退 CPU"
                destroyInternal()
                continue
            }
            loaded = true
            activeBackend = if (backend == Backend.GPU && meta.gpu.isNotBlank()) Backend.GPU else Backend.CPU
            val suffix = if (index > 0) {
                val reason = lastError?.let { "：$it" }.orEmpty()
                "（GPU 未生效$reason）"
            } else ""
            return@withContext ModelDetails(
                name = file.nameWithoutExtension.ifBlank { meta.description.ifBlank { file.name } } + suffix,
                contextLength = meta.nCtx.takeIf { it > 0 } ?: config.contextLength,
                backend = activeBackend,
                parameterCount = meta.nParams,
                supportsThinking = meta.supportsThinking,
                thinkingPrefilled = meta.thinkingPrefilled,
                gpuNote = if (index > 0) lastError?.takeIf { it.isNotBlank() } ?: "GPU 加载失败" else "",
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
                if (params.disableThinking) 1 else 0,
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
