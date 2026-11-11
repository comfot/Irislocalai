package com.iris.localai.integration

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class WeixinInbound(
    val fromUserId: String,
    val text: String,
    val contextToken: String,
)

/**
 * 微信 iLink Bot 协议（ilinkai.weixin.qq.com）：getupdates 长轮询 + sendmessage。
 * botToken 来自扫码登录；本版本暂不做扫码 UI，可粘贴已有 token。
 */
class WeixinBotService(
    private val store: IntegrationStore,
    private val router: BotMessageRouter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Stopped)
    val status: StateFlow<IntegrationStatus> = _status.asStateFlow()
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        stop()
        val config = store.settings.value.weixin
        if (!config.enabled || config.botToken.isBlank()) {
            _status.value = IntegrationStatus.Stopped
            return
        }
        job = scope.launch { runLoop(config) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = IntegrationStatus.Stopped
    }

    private suspend fun runLoop(config: WeixinBotConfig) {
        val pollClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(38, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
        val shortClient = pollClient.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        var cursor = store.readCursor(cursorKey(config))
        while (currentCoroutineContext().isActive) {
            try {
                val result = fetchUpdates(pollClient, config, cursor)
                cursor = result.cursor
                store.saveCursor(cursorKey(config), cursor)
                _status.value = IntegrationStatus.Online(config.botId.ifBlank { "微信" })
                for (message in result.messages) {
                    val reply = runCatching {
                        router.reply("WeChat", message.fromUserId, message.fromUserId, message.text)
                    }.getOrElse { "处理失败：${it.message ?: it.javaClass.simpleName}" }
                    if (reply.isNotBlank()) {
                        sendMessage(shortClient, config, message, reply)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: java.net.SocketTimeoutException) {
                // 长轮询无消息，继续。
            } catch (error: Exception) {
                _status.value = IntegrationStatus.Error(error.message ?: error.javaClass.simpleName)
                delay(6_000)
            }
        }
    }

    private data class UpdatesResult(val cursor: String, val messages: List<WeixinInbound>)

    private fun fetchUpdates(
        client: OkHttpClient,
        config: WeixinBotConfig,
        cursor: String,
    ): UpdatesResult {
        val body = buildJsonObject {
            put("get_updates_buf", cursor)
            putJsonObject("base_info") { put("channel_version", CHANNEL_VERSION) }
        }.toString()
        val response = post(client, config, "ilink/bot/getupdates", body)
        val messages = mutableListOf<WeixinInbound>()
        val msgs = response["msgs"] as? JsonArray ?: return UpdatesResult(cursor, messages)
        for (element in msgs) {
            val message = element as? JsonObject ?: continue
            val type = message["message_type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (type != 1) continue
            val from = message["from_user_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val contextToken = message["context_token"]?.jsonPrimitive?.contentOrNull ?: continue
            val text = extractText(message)
            if (text.isBlank()) continue
            messages += WeixinInbound(from, text, contextToken)
        }
        val nextCursor = response["get_updates_buf"]?.jsonPrimitive?.contentOrNull ?: cursor
        return UpdatesResult(nextCursor, messages)
    }

    private fun extractText(message: JsonObject): String {
        val items = message["item_list"] as? JsonArray ?: return ""
        for (item in items) {
            val obj = item as? JsonObject ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()) {
                1 -> obj["text_item"]?.jsonObject?.get("text")
                    ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                3 -> {
                    val text = obj["voice_item"]?.jsonObject?.get("text")
                        ?.jsonPrimitive?.contentOrNull
                    return if (text.isNullOrBlank()) "[语音]" else "[语音] $text"
                }
                2 -> return "[图片]"
                4 -> {
                    val name = obj["file_item"]?.jsonObject?.get("file_name")
                        ?.jsonPrimitive?.contentOrNull
                    return "[文件] ${name ?: ""}"
                }
                5 -> return "[视频]"
            }
        }
        return ""
    }

    private fun cursorKey(config: WeixinBotConfig): String =
        "weixin_${config.botToken.hashCode()}"

    private fun sendMessage(
        client: OkHttpClient,
        config: WeixinBotConfig,
        inbound: WeixinInbound,
        text: String,
    ) {
        val clientId = "iris-${UUID.randomUUID()}"
        val body = buildJsonObject {
            putJsonObject("msg") {
                put("from_user_id", "")
                put("to_user_id", inbound.fromUserId)
                put("client_id", clientId)
                put("message_type", 2)
                put("message_state", 2)
                put("context_token", inbound.contextToken)
                putJsonArray("item_list") {
                    add(
                        buildJsonObject {
                            put("type", 1)
                            putJsonObject("text_item") { put("text", text.take(1800)) }
                        },
                    )
                }
            }
            putJsonObject("base_info") { put("channel_version", CHANNEL_VERSION) }
        }.toString()
        post(client, config, "ilink/bot/sendmessage", body)
    }

    private fun post(
        client: OkHttpClient,
        config: WeixinBotConfig,
        path: String,
        body: String,
    ): JsonObject {
        val base = config.baseUrl.trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val uin = Base64.encodeToString(
            (Random.nextInt().toLong() and 0xFFFFFFFFL).toString().toByteArray(),
            Base64.NO_WRAP,
        )
        val request = Request.Builder()
            .url("$base/$path")
            .header("Content-Type", "application/json")
            .header("AuthorizationType", "ilink_bot_token")
            .header("X-WECHAT-UIN", uin)
            .header("Authorization", "Bearer ${config.botToken}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("微信 $path 失败：${response.code} $text")
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
        const val CHANNEL_VERSION = "1.0.2"
    }
}
