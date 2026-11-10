package com.iris.localai.voice

import android.util.Base64
import com.iris.localai.data.AppSettings
import com.iris.localai.data.CloudVoiceProvider
import com.iris.localai.data.defaultAsrModel
import com.iris.localai.data.defaultTtsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端语音：MiMo 的 TTS/ASR 走 OpenAI 兼容的 /chat/completions。
 * voiceclone 需要把音频样本编码成 `data:audio/...;base64,...` 放进 audio.voice。
 */
class CloudVoiceClient {

    suspend fun synthesize(settings: AppSettings, text: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val provider = settings.ttsCloudProvider
                val endpoint = endpoint(settings.ttsCloudEndpoint, provider)
                val key = settings.ttsCloudKey.trim()
                require(key.isNotBlank()) { "请先填写云端 TTS API Key" }
                require(text.isNotBlank()) { "朗读文本为空" }
                if (provider == CloudVoiceProvider.OPENAI) {
                    openAiSpeech(endpoint, key, settings, text)
                } else {
                    chatSpeech(endpoint, key, provider, settings, text)
                }
            }
        }

    suspend fun transcribe(settings: AppSettings, file: File): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(file.isFile && file.length() > 0) { "录音文件不存在" }
                require(file.length() <= MAX_AUDIO_BYTES) { "音频超过 10 MB 上限" }
                transcribe(settings, file.readBytes(), mimeOf(file.name))
            }.getOrElse { Result.failure(it) }
        }

    suspend fun transcribe(
        settings: AppSettings,
        bytes: ByteArray,
        mime: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.isNotEmpty()) { "音频为空" }
            require(bytes.size <= MAX_AUDIO_BYTES) { "音频超过 10 MB 上限" }
            val provider = settings.sttCloudProvider
            val endpoint = endpoint(settings.sttCloudEndpoint, provider)
            val key = settings.sttCloudKey.trim()
            require(key.isNotBlank()) { "请先填写云端 ASR API Key" }
            if (provider == CloudVoiceProvider.OPENAI) {
                openAiTranscription(endpoint, key, settings, bytes, mime)
            } else {
                chatTranscription(endpoint, key, provider, settings, bytes, mime)
            }
        }
    }

    private fun chatSpeech(
        endpoint: String,
        key: String,
        provider: CloudVoiceProvider,
        settings: AppSettings,
        text: String,
    ): ByteArray {
        val model = settings.ttsCloudModel.ifBlank { provider.defaultTtsModel() }
            .ifBlank { error("请填写 TTS 模型，例如 mimo-v2.5-tts-voiceclone") }
        val voice = resolveVoice(provider, model, settings)
        val style = settings.ttsCloudStyle.trim()
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            putJsonArray("messages") {
                if (style.isNotBlank()) {
                    addJsonObject {
                        put("role", "user")
                        put("content", style)
                    }
                }
                addJsonObject {
                    put("role", "assistant")
                    put("content", text)
                }
            }
            putJsonObject("audio") {
                put("format", "wav")
                put("voice", voice)
            }
        }.toString()
        val raw = postJson(endpoint + "/chat/completions", key, body, provider)
        val audio = runCatching {
            Json.parseToJsonElement(raw)
                .jsonObject["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("audio")
                ?.jsonObject
                ?.get("data")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull() ?: error("响应中没有音频数据")
        return Base64.decode(audio, Base64.DEFAULT)
    }

    private fun openAiSpeech(
        endpoint: String,
        key: String,
        settings: AppSettings,
        text: String,
    ): ByteArray {
        val body = buildJsonObject {
            put("model", settings.ttsCloudModel.ifBlank { "gpt-4o-mini-tts" })
            put("input", text)
            put("voice", settings.ttsCloudVoice.ifBlank { "alloy" })
            put("response_format", "wav")
        }.toString()
        return postForBytes(endpoint + "/audio/speech", key, body.toByteArray(), "application/json")
    }

    private fun chatTranscription(
        endpoint: String,
        key: String,
        provider: CloudVoiceProvider,
        settings: AppSettings,
        bytes: ByteArray,
        mime: String,
    ): String {
        val model = settings.sttCloudModel.ifBlank { provider.defaultAsrModel() }
            .ifBlank { error("请填写 ASR 模型，例如 mimo-v2.5-asr") }
        val dataUrl = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        require(dataUrl.substringAfter("base64,").length <= MAX_ENCODED_CHARS) {
            "音频 Base64 编码超过 10 MB 上限"
        }
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") { put("data", dataUrl) }
                        }
                    }
                }
            }
            if (provider == CloudVoiceProvider.MIMO) {
                putJsonObject("asr_options") { put("language", language(settings.sttLanguage)) }
            }
        }.toString()
        val raw = postJson(endpoint + "/chat/completions", key, body, provider)
        return parseTranscription(raw)
    }

    private fun openAiTranscription(
        endpoint: String,
        key: String,
        settings: AppSettings,
        bytes: ByteArray,
        mime: String,
    ): String {
        val boundary = "----iris${System.nanoTime()}"
        val model = settings.sttCloudModel.ifBlank { "whisper-1" }
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".toByteArray())
            write("$model\r\n".toByteArray())
            write("--$boundary\r\n".toByteArray())
            write(
                ("Content-Disposition: form-data; name=\"file\"; filename=\"audio." +
                    extensionOf(mime) + "\"\r\n").toByteArray(),
            )
            write("Content-Type: $mime\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val raw = postForBytes(
            endpoint + "/audio/transcriptions",
            key,
            body,
            "multipart/form-data; boundary=$boundary",
        )
        return parseTranscription(String(raw, Charsets.UTF_8))
    }

    private fun resolveVoice(
        provider: CloudVoiceProvider,
        model: String,
        settings: AppSettings,
    ): String {
        val configured = settings.ttsCloudVoice.trim()
        if (configured.startsWith("data:")) return configured
        if (model.contains("voiceclone", ignoreCase = true)) {
            val sample = settings.ttsCloudVoiceSample.trim()
            require(sample.isNotBlank()) { "voiceclone 需要先选择一段音频样本" }
            val file = File(sample)
            require(file.isFile && file.length() > 0) { "语音样本文件不存在：$sample" }
            require(file.length() <= MAX_AUDIO_BYTES) { "语音样本超过 10 MB 上限" }
            val mime = mimeOf(file.name)
            val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            require(encoded.length <= MAX_ENCODED_CHARS) { "语音样本 Base64 编码超过 10 MB 上限" }
            return "data:$mime;base64,$encoded"
        }
        return settings.ttsCloudVoice.trim().ifBlank {
            if (provider == CloudVoiceProvider.MIMO) "mimo_default" else "alloy"
        }
    }

    private fun postJson(
        url: String,
        key: String,
        body: String,
        provider: CloudVoiceProvider?,
    ): String = String(postForBytes(url, key, body.toByteArray(), "application/json", provider), Charsets.UTF_8)

    private fun postForBytes(
        url: String,
        key: String,
        body: ByteArray,
        contentType: String?,
        provider: CloudVoiceProvider? = null,
    ): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $key")
            if (provider == CloudVoiceProvider.MIMO) setRequestProperty("api-key", key)
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "*/*")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val text = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?.take(300)
                    .orEmpty()
                throw IOException("HTTP $code ${text.trim()}".trim())
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTranscription(raw: String): String {
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: error("识别响应不是 JSON")
        val content = json["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("")
            else -> json["text"]?.jsonPrimitive?.contentOrNull
        }
        return text.orEmpty().trim().ifBlank { error("没有识别到语音内容") }
    }

    private fun endpoint(configured: String, provider: CloudVoiceProvider): String {
        val value = configured.trim().ifBlank { provider.defaultEndpoint }
        require(value.isNotBlank()) { "请填写 API Endpoint" }
        return value.trimEnd('/')
    }

    private fun language(locale: String): String = when {
        locale.startsWith("zh", ignoreCase = true) -> "zh"
        locale.startsWith("en", ignoreCase = true) -> "en"
        else -> "auto"
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        else -> "audio/wav"
    }

    private fun extensionOf(mime: String): String = when (mime) {
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "ogg"
        else -> "wav"
    }

    private companion object {
        const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
        const val MAX_ENCODED_CHARS = 10 * 1024 * 1024
    }
}
