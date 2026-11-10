package com.iris.localai.server

import com.iris.localai.data.CloudVoiceProvider
import com.iris.localai.data.ModelProfileStore
import com.iris.localai.data.ModelRole
import com.iris.localai.data.ProviderKind
import com.iris.localai.data.SettingsRepository
import com.iris.localai.data.defaultAsrModel
import com.iris.localai.data.defaultTtsModel
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.memory.MemoryEmbeddingClient
import com.iris.localai.voice.CloudVoiceClient
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * OpenAI 兼容路由：/health、/v1/models、/v1/chat/completions 与 /v1/completions（均支持 SSE）。
 * 模型侧只依赖 EngineManager，换成真实引擎后这些接口无需改动。
 */
class ApiRoutes(
    private val engines: EngineManager,
    private val settings: SettingsRepository,
    private val models: ModelProfileStore,
    private val modelIdProvider: () -> String = { "iris-local" },
    private val keyProvider: () -> String = { "" },
) {

    private val embeddingClient = MemoryEmbeddingClient({ models.active() }) { profile ->
        models.providerFor(profile, ModelRole.EMBEDDING)
    }
    private val voiceClient = CloudVoiceClient()

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
            request.method == "GET" && request.path == "/v1/models" -> listModels(response)
            request.method == "POST" && request.path == "/v1/chat/completions" -> chat(request, response)
            request.method == "POST" && request.path == "/v1/completions" -> completion(request, response)
            request.method == "POST" && request.path == "/v1/embeddings" -> embeddings(request, response)
            request.method == "POST" && request.path == "/v1/images/generations" -> images(request, response)
            request.method == "POST" && request.path == "/v1/audio/speech" -> speech(request, response)
            request.method == "POST" && request.path == "/v1/audio/transcriptions" -> transcription(request, response)
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

    private fun listModels(response: HttpResponse) {
        val catalog = linkedMapOf<String, String>()
        models.providers.value.forEach { provider ->
            provider.models.filter { it.enabled }.forEach { model ->
                catalog.putIfAbsent(model.name, provider.name)
            }
        }
        catalog.putIfAbsent(modelIdProvider(), "iris")
        response.json(
            200,
            buildJsonObject {
                put("object", "list")
                putJsonArray("data") {
                    catalog.forEach { (id, owner) ->
                        addJsonObject {
                            put("id", id)
                            put("object", "model")
                            put("owned_by", owner)
                        }
                    }
                }
            }.toString(),
        )
    }

    private suspend fun embeddings(request: HttpRequest, response: HttpResponse) {
        val json = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        if (json == null) {
            response.json(400, errorJson("请求体必须是 JSON 对象"))
            return
        }
        val texts = when (val input = json["input"]) {
            is JsonPrimitive -> listOfNotNull(input.contentOrNull)
            is JsonArray -> input.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }.filter { it.isNotBlank() }
        if (texts.isEmpty()) {
            response.json(400, errorJson("input 不能为空"))
            return
        }
        val vectors = embeddingClient.embedBatch(texts)
        if (vectors == null || vectors.any { it == null }) {
            response.json(503, errorJson("Embedding 未配置或请求失败"))
            return
        }
        val modelName = models.modelNameFor(models.active(), ModelRole.EMBEDDING)
        response.json(
            200,
            buildJsonObject {
                put("object", "list")
                put("model", modelName)
                putJsonArray("data") {
                    vectors.forEachIndexed { index, vector ->
                        val values = vector?.values ?: return@forEachIndexed
                        addJsonObject {
                            put("object", "embedding")
                            put("index", index)
                            putJsonArray("embedding") { values.forEach { add(it) } }
                        }
                    }
                }
                putJsonObject("usage") {
                    put("prompt_tokens", 0)
                    put("total_tokens", 0)
                }
            }.toString(),
        )
    }

    private suspend fun images(request: HttpRequest, response: HttpResponse) {
        val json = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        if (json == null) {
            response.json(400, errorJson("请求体必须是 JSON 对象"))
            return
        }
        val provider = models.providerFor(models.active(), ModelRole.LANGUAGE)
        if (provider.kind != ProviderKind.CLOUD || provider.apiKey.isBlank()) {
            response.json(503, errorJson("图片生成需要已配置的云端模型提供商"))
            return
        }
        val endpoint = provider.endpoint.ifBlank { provider.vendor.defaultEndpoint }
            .trimEnd('/') + "/images/generations"
        val model = (json["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: models.modelNameFor(models.active(), ModelRole.LANGUAGE)
        val body = buildJsonObject {
            json.forEach { (key, value) ->
                if (key != "model") put(key, value)
            }
            put("model", model)
        }.toString()
        val result = runCatching { postJson(endpoint, provider.apiKey, body) }
        result.onSuccess { (code, raw) ->
            if (code in 200..299) response.json(200, raw) else response.json(502, raw)
        }.onFailure { error ->
            response.json(502, errorJson("图片生成失败：${error.message ?: "未知错误"}"))
        }
    }

    private suspend fun speech(request: HttpRequest, response: HttpResponse) {
        val json = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
            ?: run { response.json(400, errorJson("请求体必须是 JSON 对象")); return }
        val input = (json["input"] as? JsonPrimitive)?.contentOrNull
            ?: (json["text"] as? JsonPrimitive)?.contentOrNull
        if (input.isNullOrBlank()) {
            response.json(400, errorJson("input 不能为空"))
            return
        }
        val current = settings.current
        val voice = (json["voice"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: current.ttsCloudVoice
        val model = (json["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: current.ttsCloudModel.ifBlank { current.ttsCloudProvider.defaultTtsModel() }
        val configured = current.copy(ttsCloudVoice = voice, ttsCloudModel = model)
        val result = voiceClient.synthesize(configured, input)
        result.onSuccess { bytes -> response.bytes(200, "audio/wav", bytes) }
            .onFailure { error ->
                response.json(503, errorJson("语音合成失败：${error.message ?: "未知错误"}"))
            }
    }

    private suspend fun transcription(request: HttpRequest, response: HttpResponse) {
        val json = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        val current = settings.current
        val audio: ByteArray
        val mime: String
        if (json != null) {
            val encoded = (json["audio"] as? JsonPrimitive)?.contentOrNull?.substringAfter("base64,")
                ?: (json["file"] as? JsonPrimitive)?.contentOrNull?.substringAfter("base64,")
            if (encoded.isNullOrBlank()) {
                response.json(400, errorJson("audio 不能为空"))
                return
            }
            audio = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                ?: run { response.json(400, errorJson("audio 不是有效的 Base64")); return }
            mime = "audio/wav"
        } else {
            val part = multipartAudio(request)
            if (part == null) {
                response.json(400, errorJson("请传入 JSON audio 或 multipart file"))
                return
            }
            audio = part.first
            mime = part.second
        }
        val model = (json?.get("model") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: current.sttCloudModel.ifBlank { current.sttCloudProvider.defaultAsrModel() }
        val configured = current.copy(sttCloudModel = model)
        voiceClient.transcribe(configured, audio, mime).onSuccess { text ->
            response.json(200, buildJsonObject { put("text", text) }.toString())
        }.onFailure { error ->
            response.json(503, errorJson("语音识别失败：${error.message ?: "未知错误"}"))
        }
    }

    private fun postJson(endpoint: String, apiKey: String, body: String): Pair<Int, String> {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${raw.take(200)}".trim())
            return code to raw
        } finally {
            connection.disconnect()
        }
    }

    private fun multipartAudio(request: HttpRequest): Pair<ByteArray, String>? {
        val contentType = request.headers["content-type"] ?: return null
        val boundary = contentType.substringAfter("boundary=", "").trim('"')
        if (boundary.isBlank()) return null
        val bytes = request.bodyBytes
        val marker = "--$boundary".toByteArray()
        val headerSeparator = "\r\n\r\n".toByteArray()
        var position = indexOf(bytes, marker, 0) ?: return null
        while (true) {
            val partStart = position + marker.size + 2
            val headerEnd = indexOf(bytes, headerSeparator, partStart) ?: return null
            val partEnd = indexOf(bytes, "\r\n$marker".toByteArray(), headerEnd + 4) ?: return null
            val headers = String(
                bytes,
                partStart,
                (headerEnd - partStart).coerceAtLeast(0),
                Charsets.UTF_8,
            )
            if (headers.contains("filename=") || headers.contains("name=\"file\"")) {
                val mimeLine = headers.lineSequence()
                    .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "audio/wav" }
                val bodyStart = headerEnd + 4
                if (partEnd > bodyStart) {
                    return bytes.copyOfRange(bodyStart, partEnd) to mimeLine
                }
            }
            position = indexOf(bytes, marker, partEnd + 2) ?: return null
        }
        return null
    }

    private fun indexOf(source: ByteArray, target: ByteArray, start: Int): Int? {
        if (target.isEmpty() || source.size < target.size) return null
        for (index in start..(source.size - target.size)) {
            var matched = true
            for (offset in target.indices) {
                if (source[index + offset] != target[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return null
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
