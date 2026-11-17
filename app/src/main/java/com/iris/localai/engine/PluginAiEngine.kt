package com.iris.localai.engine

import com.iris.localai.plugin.PluginRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 插件 AI Provider 引擎：走 ToolPkg 的 sendMessage 通道。
 * Operit 的 provider 返回完整文本而不是 token 流，因此这里只产生一次 Delta。
 */
class PluginAiEngine(
    private val runtime: PluginRuntime,
    private val pluginId: String,
    private val providerId: String,
    private val modelName: String,
    private val contextLength: Int,
) : InferenceEngine {

    @Volatile
    private var loaded = false

    override val isLoaded: Boolean get() = loaded

    override suspend fun load(
        modelPath: String,
        config: LoadConfig,
        onProgress: (Float) -> Unit,
    ): ModelDetails {
        loaded = true
        onProgress(1f)
        return ModelDetails(
            name = modelName.ifBlank { providerId },
            contextLength = contextLength,
            backend = Backend.CPU,
        )
    }

    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = flow {
        if (!loaded) {
            emit(GenEvent.Failed("插件 Provider 尚未加载"))
            return@flow
        }
        try {
            val turns = buildJsonArray {
                history.forEach { message ->
                    add(buildJsonObject {
                        put("kind", message.role.uppercase())
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            }
            val payload = buildJsonObject {
                put("event", "toolpkg_ai_provider_send_message")
                put("eventName", "toolpkg_ai_provider_send_message")
                put("eventPayload", buildJsonObject {
                    put("providerId", providerId)
                    put("providerDisplayName", modelName.ifBlank { providerId })
                    put("config", buildJsonObject {
                        put("id", providerId)
                        put("name", modelName.ifBlank { providerId })
                        put("apiProviderType", "plugin")
                        put("modelName", modelName)
                        put("enableToolCall", false)
                    })
                    put("chatHistory", turns)
                    put("modelParameters", buildJsonArray {})
                    put("enableThinking", false)
                    put("stream", false)
                    put("preserveThinkInHistory", true)
                    put("enableRetry", true)
                })
            }
            val raw = runtime.callAiProvider(pluginId, providerId, "sendMessage", payload)
            val text = parseText(raw)
            if (text.isBlank()) {
                emit(GenEvent.Failed("插件 Provider 没有返回文本"))
            } else {
                emit(GenEvent.Delta(text))
                emit(GenEvent.Done)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(GenEvent.Failed(e.message ?: "插件 Provider 调用失败"))
        }
    }

    private fun parseText(raw: String): String {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        return when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonObject -> element.string("text")
                ?: (element["data"] as? JsonObject)?.string("text")
                ?: (element["eventPayload"] as? JsonObject)?.string("text")
                ?: ""
            else -> ""
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    override fun cancel() = Unit

    override suspend fun resetContext() = Unit

    override suspend fun unload() {
        loaded = false
    }
}
