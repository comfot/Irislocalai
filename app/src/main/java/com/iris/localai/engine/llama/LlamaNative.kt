package com.iris.localai.engine.llama

import androidx.annotation.Keep
import java.io.File

/** Called from native code; names/signatures are part of the JNI contract. */
@Keep
fun interface PieceSink {
    /** UTF-8 bytes on a code-point boundary. Return false to stop generation. */
    fun onPiece(utf8: ByteArray): Boolean
}

@Keep
fun interface LoadProgressListener {
    /** Progress in [0,1]. Return false to abort loading. */
    fun onProgress(progress: Float): Boolean
}

/** JNI surface of the prebuilt libiris_llama.so. */
@Keep
object LlamaNative {

    val unavailableReason: String? by lazy { tryLoad() }

    private fun tryLoad(): String? {
        CpuFeatures.missingForLlama()?.let { return it }
        return try {
            System.loadLibrary("iris_llama")
            null
        } catch (t: Throwable) {
            "无法加载 llama.cpp 运行库：${t.message ?: t.javaClass.simpleName}"
        }
    }

    @JvmStatic external fun nativeGpuTicks(): Int
    @JvmStatic external fun nativeGpuLastLog(): String

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeLoad(
        handle: Long,
        pathUtf8: ByteArray,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        flashAttn: Boolean,
        nGpuLayers: Int,
        kvType: Int,
        useMmap: Boolean,
        listener: LoadProgressListener?,
    ): String?

    @JvmStatic external fun nativeUnload(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeClearCancel(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeSetThreads(handle: Long, threads: Int)
    @JvmStatic external fun nativeMeta(handle: Long): String
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        floats: FloatArray,
        ints: IntArray,
        sink: PieceSink?,
    ): String

    fun gpuTicks(): Int = if (unavailableReason == null) runCatching { nativeGpuTicks() }.getOrDefault(0) else 0
    fun gpuLastLog(): String = if (unavailableReason == null) runCatching { nativeGpuLastLog() }.getOrDefault("") else ""
}

/** The prebuilt library targets armv8.2 + dotprod + i8mm + fp16. */
object CpuFeatures {
    private val features: Set<String> by lazy {
        runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')?.trim()?.split(' ', '\t')?.filter { it.isNotEmpty() }?.toSet()
            } ?: emptySet()
        }.getOrDefault(emptySet())
    }

    fun missingForLlama(): String? {
        if (features.isEmpty()) return null
        val required = mapOf("asimddp" to "dotprod", "i8mm" to "i8mm", "asimdhp" to "fp16")
        val missing = required.filterKeys { it !in features }.values
        return if (missing.isEmpty()) {
            null
        } else {
            "此设备 CPU 缺少 ${missing.joinToString("、")} 指令集，无法运行 GGUF 引擎（需骁龙 8 Gen 1 及以上）"
        }
    }
}
