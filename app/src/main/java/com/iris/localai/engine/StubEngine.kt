package com.iris.localai.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 占位引擎：不加载任何模型，用来让骨架端到端可跑。
 *
 * 接入真实推理时，在本类旁边新增 LlamaEngine / MnnEngine（JNI 封装 InferenceEngine），
 * 然后在 AppGraph 里把 engineFactory 指过去即可；Agent、HTTP API、UI 都不用改。
 */
class StubEngine : InferenceEngine {

    @Volatile
    private var loaded = false

    override val isLoaded: Boolean get() = loaded

    override suspend fun load(
        modelPath: String,
        config: LoadConfig,
        onProgress: (Float) -> Unit,
    ): ModelDetails {
        listOf(0.2f, 0.5f, 0.8f, 1f).forEach {
            onProgress(it)
            delay(80)
        }
        loaded = true
        return ModelDetails(
            name = modelPath.ifBlank { "stub-echo" },
            contextLength = config.contextLength,
            backend = config.backend,
        )
    }

    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = flow {
        val last = history.lastOrNull()
        val userText = history.lastOrNull { it.role == PromptMessage.USER }?.content.orEmpty()
        val reply = when {
            last?.role == PromptMessage.TOOL ->
                "工具返回：${last.content}\n\n如需真实回答，请接入本地推理引擎。"
            userText.contains("/time") ->
                """<tool_call>{"name":"current_time","arguments":{}}</tool_call>"""
            else ->
                "（示例引擎）收到：$userText\n\n" +
                    "当前是未加载模型时的回声引擎，没有加载任何模型。" +
                    "把 AppGraph 里的 engineFactory 换成 llama.cpp / MNN 实现后，这里就是真实推理结果。"
        }
        for (chunk in reply.chunked(8)) {
            delay(18)
            emit(GenEvent.Delta(chunk))
        }
        emit(GenEvent.Done)
    }

    override fun cancel() = Unit

    override suspend fun resetContext() = Unit

    override suspend fun unload() {
        loaded = false
    }
}
