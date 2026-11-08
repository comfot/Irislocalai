package com.iris.localai.engine

import com.iris.localai.data.ProviderConfig
import com.iris.localai.data.RoleConfig
import com.iris.localai.data.CloudVendor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的流式推理引擎：读取当前模型档案的提供商、API Key、端点与语言模型参数。
 * 支持自定义请求头与 SSE 增量；工具调用仍由 AgentLoop 的文本协议负责。
 */
class OpenAiCompatibleEngine(
    private val provider: ProviderConfig,
    private val role: RoleConfig,
    private val visionRole: RoleConfig? = null,
) : InferenceEngine {

    @Volatile
    private var loaded = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    override val isLoaded: Boolean get() = loaded

    private val modelName: String
        get() = role.modelName.ifBlank {
            provider.modelName.ifBlank { provider.vendor.defaultModel }
        }

    override suspend fun load(
        modelPath: String,
        config: LoadConfig,
        onProgress: (Float) -> Unit,
    ): ModelDetails {
        onProgress(1f)
        loaded = true
        return ModelDetails(
            name = modelName,
            contextLength = role.contextLength,
            backend = config.backend,
        )
    }

    override fun generate(
        history: List<PromptMessage>,
        params: SamplingConfig,
    ): Flow<GenEvent> = flow {
        if (!loaded) {
            emit(GenEvent.Failed("远程引擎尚未加载"))
            return@flow
        }
        val apiKey = provider.apiKey.trim()
        if (apiKey.isBlank()) {
            emit(GenEvent.Failed("API Key 为空，请在模型设置中填写"))
            return@flow
        }
        val endpointBase = provider.endpoint.ifBlank { provider.vendor.defaultEndpoint }.trimEnd('/')
        if (endpointBase.isBlank()) {
            emit(GenEvent.Failed("API Endpoint 为空"))
            return@flow
        }

        val body = buildJsonObject {
            put("model", modelFor(history))
            put("stream", true)
            put("temperature", params.temperature)
            put("top_p", params.topP)
            put("max_tokens", params.maxNewTokens)
            put("presence_penalty", params.presencePenalty)
            put("frequency_penalty", params.frequencyPenalty)
            params.seed?.let { put("seed", it) }
            if (provider.vendor == CloudVendor.CUSTOM || provider.vendor == CloudVendor.OPENROUTER) {
                put("top_k", params.topK)
                if (params.minP > 0f) put("min_p", params.minP)
                put("repeat_penalty", params.repeatPenalty)
                put("repeat_last_n", params.repeatLastN)
            }
            putJsonArray("messages") {
                history.forEach { message ->
                    val roleName = if (message.role == PromptMessage.TOOL) "user" else message.role
                    val content = if (message.role == PromptMessage.TOOL) {
                        "工具返回：\n${message.content}"
                    } else {
                        message.content
                    }
                    addJsonObject {
                        put("role", roleName)
                        if (message.images.isEmpty()) {
                            put("content", content)
                        } else {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", content)
                                }
                                message.images.forEach { image ->
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", image) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()

        val endpoint = endpointBase + "/chat/completions"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "text/event-stream")
            val headers = if (history.any { it.images.isNotEmpty() } && visionRole != null) {
                role.customHeaders + visionRole.customHeaders
            } else {
                role.customHeaders
            }
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            activeConnection = this
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val bodyText = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?.take(200)
                    .orEmpty()
                emit(GenEvent.Failed("HTTP $code ${bodyText.trim()}".trim()))
                return@flow
            }

            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        Json.parseToJsonElement(data)
                            .jsonObject["choices"]
                            ?.jsonArray
                            ?.firstOrNull()
                            ?.jsonObject
                            ?.get("delta")
                            ?.jsonObject
                    }.getOrNull()
                    val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                    val reasoning = (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
                        ?: (delta?.get("reasoning") as? JsonPrimitive)?.contentOrNull
                    if (!reasoning.isNullOrEmpty()) {
                        emit(GenEvent.Reasoning(reasoning))
                    }
                    if (!content.isNullOrEmpty()) {
                        emit(GenEvent.Delta(content))
                    }
                }
            }
            emit(GenEvent.Done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emit(GenEvent.Failed("网络请求失败：${e.message ?: e.javaClass.simpleName}"))
        } catch (e: Exception) {
            emit(GenEvent.Failed("请求失败：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        activeConnection?.disconnect()
    }

    private fun modelFor(history: List<PromptMessage>): String {
        val hasImages = history.any { it.images.isNotEmpty() }
        val visionModel = visionRole
            ?.takeIf { it.enabled && it.modelName.isNotBlank() }
            ?.modelName
        return if (hasImages && visionModel != null) visionModel else modelName
    }

    override suspend fun resetContext() = Unit

    override suspend fun unload() {
        loaded = false
        cancel()
    }
}
