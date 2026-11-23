package com.iris.moew.voice

/**
 * 流式识别会话的统一入口：本地 sherpa 与云端火山 ASR 各自实现。
 *
 * 调用方在录音的每一帧调用 [accept]，录音结束后用 [finishAndGetText] 取整段文本。
 */
interface StreamingAsrHandle : AutoCloseable {

    fun accept(samples: ShortArray, size: Int = samples.size)

    fun text(): String

    fun error(): String? = null

    /** 结束音频流并等待最终结果；本地模型可立即返回。 */
    suspend fun finishAndGetText(timeoutMs: Long = 700L): String = text()
}
