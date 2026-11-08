package com.iris.localai.server

import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * OpenAI 兼容路由：/health、/v1/models、/v1/chat/completions 与 /v1/completions（均支持 SSE）。
 * 模型侧只依赖 EngineManager，换成真实引擎后这些接口无需改动。
 */
class ApiRoutes(
    private val engines: EngineManager,
    private val modelIdProvider: () -> String = { "iris-local" },
    private val keyProvider: () -> String = { "" },
) {

    suspend fun handle(request: HttpRequest, response: HttpResponse) {
        if (request.method == "OPTIONS") {
            response.empty(204)
            return
        }
        val authCode = ApiAuth.check(keyProvider(), request.headers)
        if (authCode != null) {
            response.json(
                authCode,
                errorJson(
                    if (authCode == 401) "invalid or missing API key" else "forbidden",
                ),
            )
            return
        }
        when {
            request.method == "GET" && request.path == "/health" -> health(response)
            request.method == "GET" && request.path == "/v1/models" -> models(response)
            request.method == "POST" && request.path == "/v1/chat/completions" -> chat(request, response)
            request.method == "POST" && request.path == "/v1/completions" -> completion(request, response)
            else -> response.json(404, errorJson("未知路由：${request.method} ${request.path}"))
        }
    }

    private fun health(response: HttpResponse) {
        response.json(
            200,
            buildJsonObject {
                put("status", "ok")
                put("model_loaded", engines.isReady)
                put("engine_state", engines.state.value.label())
                put("model", modelIdProvider())
            }.toString(),
        )
    }

    private fun models(response: HttpResponse) {
        response.json(
            200,
            buildJsonObject {
                put("object", "list")
                putJsonArray("data") {
                    addJsonObject {
                        put("id", modelIdProvider())
                        put("object", "model")
                        put("owned_by", "local")
                    }
                }
            }.toString(),
        )
    }

    private suspend fun chat(request: HttpRequest, response: HttpResponse) {
        val json = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        if (json == null) {
            response.json(400, errorJson("请求体必须是 JSON 对象"))
            return
        }
        val messages = parseMessages(json["messages"])
        if (messages.isEmpty()) {
            response.json(400, errorJson("messages 不能为空"))
            return
        }

        val stream = (json["stream"] as? JsonPrimitive)?.booleanOrNull ?: false
        val params = SamplingConfig(
            temperature = (json["temperature"] as? JsonPrimitive)?.floatOrNull ?: 0.7f,
            maxNewTokens = (json["max_tokens"] as? JsonPrimitive)?.intOrNull ?: 512,
        )
        val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        val created = System.currentTimeMillis() / 1000
        val modelId = modelIdProvider()

        if (stream) {
            response.beginSse()
            engines.generate(messages, params).collect { event ->
                when (event) {
                    is GenEvent.Delta -> response.event(chunkJson(id, created, modelId, event.text))
                    is GenEvent.Reasoning -> Unit
                    is GenEvent.Failed -> response.event(errorJson(event.message))
                    GenEvent.Done -> Unit
                }
            }
            response.event("[DONE]")
        } else {
            val text = StringBuilder()
            var failure: String? = null
            engines.generate(messages, params).collect { event ->
                when (event) {
                    is GenEvent.Delta -> text.append(event.text)
                    is GenEvent.Reasoning -> Unit
                    is GenEvent.Failed -> failure = event.message
                    GenEvent.Done -> Unit
                }
            }
            val error = failure
            if (error != null) {
                response.json(500, errorJson(error))
            } else {
                response.json(200, completionJson(id, created, modelId, text.toString()))
            }
        }
    }

    private suspend fun completion(request: HttpRequest, response: HttpResponse) {
        val json = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        if (json == null) {
            response.json(400, errorJson("请求体必须是 JSON 对象"))
            return
        }
        val promptElement = json["prompt"]
        val prompt = when (promptElement) {
            is JsonPrimitive -> promptElement.contentOrNull.orEmpty()
            is JsonArray -> promptElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .joinToString("\n")
            else -> ""
        }.trim()
        if (prompt.isEmpty()) {
            response.json(400, errorJson("prompt 不能为空"))
            return
        }

        val stream = (json["stream"] as? JsonPrimitive)?.booleanOrNull ?: false
        val params = SamplingConfig(
            temperature = (json["temperature"] as? JsonPrimitive)?.floatOrNull ?: 0.7f,
            maxNewTokens = (json["max_tokens"] as? JsonPrimitive)?.intOrNull ?: 512,
        )
        val messages = listOf(PromptMessage(PromptMessage.USER, prompt))
        val id = "cmpl-${UUID.randomUUID().toString().take(8)}"
        val created = System.currentTimeMillis() / 1000
        val modelId = modelIdProvider()

        if (stream) {
            response.beginSse()
            engines.generate(messages, params).collect { event ->
                when (event) {
                    is GenEvent.Delta -> response.event(completionChunkJson(id, created, modelId, event.text))
                    is GenEvent.Reasoning -> Unit
                    is GenEvent.Failed -> response.event(errorJson(event.message))
                    GenEvent.Done -> Unit
                }
            }
            response.event("[DONE]")
        } else {
            val text = StringBuilder()
            var failure: String? = null
            engines.generate(messages, params).collect { event ->
                when (event) {
                    is GenEvent.Delta -> text.append(event.text)
                    is GenEvent.Reasoning -> Unit
                    is GenEvent.Failed -> failure = event.message
                    GenEvent.Done -> Unit
                }
            }
            val error = failure
            if (error != null) {
                response.json(500, errorJson(error))
            } else {
                response.json(200, textCompletionJson(id, created, modelId, text.toString()))
            }
        }
    }

    private fun parseMessages(element: kotlinx.serialization.json.JsonElement?): List<PromptMessage> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val role = (obj["role"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            PromptMessage(role, content)
        }
    }

    private fun chunkJson(id: String, created: Long, modelId: String, text: String): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", modelId)
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                putJsonObject("delta") { put("content", text) }
                put("finish_reason", JsonNull)
            }
        }
    }.toString()

    private fun completionJson(id: String, created: Long, modelId: String, text: String): String = buildJsonObject {
        put("id", id)
        put("object", "chat.completion")
        put("created", created)
        put("model", modelId)
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", text)
                }
                put("finish_reason", "stop")
            }
        }
    }.toString()

    private fun completionChunkJson(id: String, created: Long, modelId: String, text: String): String =
        buildJsonObject {
            put("id", id)
            put("object", "text_completion")
            put("created", created)
            put("model", modelId)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    put("text", text)
                    put("logprobs", JsonNull)
                    put("finish_reason", JsonNull)
                }
            }
        }.toString()

    private fun textCompletionJson(id: String, created: Long, modelId: String, text: String): String =
        buildJsonObject {
            put("id", id)
            put("object", "text_completion")
            put("created", created)
            put("model", modelId)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    put("text", text)
                    put("logprobs", JsonNull)
                    put("finish_reason", "stop")
                }
            }
        }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        putJsonObject("error") {
            put("message", message)
            put("type", "invalid_request_error")
        }
    }.toString()

    private fun EngineState.label(): String = when (this) {
        EngineState.Idle -> "idle"
        is EngineState.Loading -> "loading"
        is EngineState.Ready -> "ready"
        is EngineState.Failed -> "failed"
    }
}
