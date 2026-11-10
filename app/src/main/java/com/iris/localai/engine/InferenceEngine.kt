package com.iris.localai.engine

import kotlinx.coroutines.flow.Flow

data class PromptMessage(
    val role: String,
    val content: String,
    /** OpenAI 多模态图片，内容是 `data:image/...;base64,...`。 */
    val images: List<String> = emptyList(),
) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
    }
}

data class LoadConfig(
    val contextLength: Int = 4096,
    val threads: Int = 4,
    val backend: Backend = Backend.CPU,
    val flashAttention: Boolean = true,
    val kvCacheType: Int = 0,
    val batchSize: Int = 512,
    val useMmap: Boolean = true,
    val fallbackToCpu: Boolean = true,
)

data class SamplingConfig(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxNewTokens: Int = 512,
    val minP: Float = 0f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0f,
    val seed: Int? = null,
    /** 关闭模型思考块；本地 llama.cpp 通过预填空 think 实现。 */
    val disableThinking: Boolean = false,
)

data class ModelDetails(
    val name: String,
    val contextLength: Int,
    val backend: Backend,
    val parameterCount: Long = 0,
    val supportsThinking: Boolean = false,
    /** 对话模板已在提示词末尾预填 `<think>`，模型回复会从思考块内部开始。 */
    val thinkingPrefilled: Boolean = false,
)

sealed interface GenEvent {
    data class Delta(val text: String) : GenEvent
    data class Reasoning(val text: String) : GenEvent
    data object Done : GenEvent
    data class Failed(val message: String) : GenEvent
}

/**
 * 推理引擎契约。实现方负责把模型跑起来并流式产出 token；调用方不感知 llama.cpp / MNN /
 * LiteRT 的差异。替换实现时只需在 AppGraph 里改 engineFactory。
 */
interface InferenceEngine {

    val isLoaded: Boolean

    /** 失败时抛出带用户可读信息的异常。 */
    suspend fun load(
        modelPath: String,
        config: LoadConfig,
        onProgress: (Float) -> Unit = {},
    ): ModelDetails

    /** 流式产出，正常结束时恰好一个 [GenEvent.Done]；取消收集即停止生成。 */
    fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent>

    /** 线程安全、非阻塞地请求停止当前生成。 */
    fun cancel()

    suspend fun resetContext()

    suspend fun unload()
}
