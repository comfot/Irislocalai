package com.iris.localai.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

data class QqInboundMessage(
    val targetId: String,
    val userName: String,
    val text: String,
    val messageId: String,
    val isGroup: Boolean,
)

/**
 * QQ 开放平台 Bot API v2（WebSocket 网关，仅 C2C 私聊）。
 * 需要 AppID + AppSecret；在 q.qq.com 创建机器人后填入。
 */
class QqBotService(
    private val store: IntegrationStore,
    private val router: BotMessageRouter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Stopped)
    val status: StateFlow<IntegrationStatus> = _status.asStateFlow()
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var sessionId = ""
    @Volatile private var lastSeq = 0L
    private val aiSlots = Semaphore(2)
    private val lastPeerAt = mutableMapOf<String, Long>()
    private val seenMessages = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > 512
    }

    fun start() {
        stop()
        val config = store.settings.value.qq
        if (!config.enabled || config.appId.isBlank() || config.appSecret.isBlank()) {
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

    private suspend fun runLoop(config: QqBotConfig) {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
        var token = ""
        var tokenExpireAt = 0L
        while (currentCoroutineContext().isActive) {
            try {
                if (token.isBlank() || System.currentTimeMillis() >= tokenExpireAt) {
                    val (access, expiresIn) = fetchAccessToken(http, config)
                    token = access
                    tokenExpireAt = System.currentTimeMillis() + (expiresIn - 120) * 1000
                }
                val gateway = fetchGateway(http, token)
                _status.value = IntegrationStatus.Connecting
                val queue = Channel<QqInboundMessage>(Channel.UNLIMITED)
                _status.value = IntegrationStatus.Online("QQ")
                kotlinx.coroutines.coroutineScope {
                    val connection = launch { connect(http, gateway, token, queue) }
                    for (message in queue) {
                        if (message.messageId.isNotBlank() && !markSeen(message.messageId)) continue
                        val now = System.currentTimeMillis()
                        val allowed = synchronized(lastPeerAt) {
                            val last = lastPeerAt[message.targetId] ?: 0L
                            if (now - last < 800) {
                                false
                            } else {
                                lastPeerAt[message.targetId] = now
                                true
                            }
                        }
                        if (!allowed) continue
                        val reply = aiSlots.withPermit {
                            runCatching {
                                router.reply(
                                    platform = if (message.isGroup) "QQ群" else "QQ",
                                    chatId = message.targetId,
                                    displayName = message.userName,
                                    text = message.text,
                                )
                            }.getOrElse { "处理失败：${it.message ?: it.javaClass.simpleName}" }
                        }
                        runCatching { sendMessage(http, token, message, reply) }
                            .onFailure { error ->
                                _status.value = IntegrationStatus.Error(
                                    "发送 QQ 消息失败：${error.message ?: error.javaClass.simpleName}",
                                )
                            }
                    }
                    connection.join()
                }
                delay(2_000)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _status.value = IntegrationStatus.Error(error.message ?: error.javaClass.simpleName)
                delay(6_000)
            }
        }
    }

    private suspend fun connect(
        http: OkHttpClient,
        gateway: String,
        token: String,
        queue: Channel<QqInboundMessage>,
    ) {
        var heartbeatJob: Job? = null
        val closed = CompletableDeferred<Unit>()
        val socket = http.newWebSocket(
            Request.Builder().url(gateway).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val root = json.parseToJsonElement(text).jsonObject
                        val op = root["op"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@runCatching
                        val seq = root["s"]?.jsonPrimitive?.longOrNull
                        if (seq != null) lastSeq = seq
                        when (op) {
                            10 -> {
                                val interval = root["d"]?.jsonObject
                                    ?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 30_000L
                                val resumable = sessionId.isNotBlank() && lastSeq > 0
                                webSocket.send(if (resumable) resumePayload(token) else identifyPayload(token))
                                heartbeatJob?.cancel()
                                heartbeatJob = scope.launch {
                                    while (isActive) {
                                        delay(interval.coerceAtLeast(5_000))
                                        webSocket.send(
                                            buildJsonObject {
                                                put("op", 1)
                                                put("d", lastSeq)
                                            }.toString(),
                                        )
                                    }
                                }
                            }
                            0 -> {
                                val type = root["t"]?.jsonPrimitive?.contentOrNull
                                when (type) {
                                    "READY" -> {
                                        sessionId = root["d"]?.jsonObject
                                            ?.get("session_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                                    }
                                    "C2C_MESSAGE_CREATE" -> parseInbound(root["d"]?.jsonObject, false)
                                        ?.let { queue.trySend(it) }
                                    "GROUP_AT_MESSAGE_CREATE" -> parseInbound(root["d"]?.jsonObject, true)
                                        ?.let { queue.trySend(it) }
                                }
                            }
                            9 -> {
                                sessionId = ""
                                lastSeq = 0L
                                webSocket.close(4000, "invalid session")
                            }
                            7 -> webSocket.close(4000, "reconnect")
                        }
                    }.onFailure { error ->
                        _status.value = IntegrationStatus.Error("消息解析失败：${error.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    queue.close(t)
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    queue.close()
                    closed.complete(Unit)
                }
            },
        )
        try {
            closed.await()
        } finally {
            heartbeatJob?.cancel()
            socket.cancel()
        }
    }

    private fun identifyPayload(token: String): String = buildJsonObject {
        put("op", 2)
        put(
            "d",
            buildJsonObject {
                put("token", "QQBot $token")
                put("intents", 1 shl 25)
                put("shard", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(0))
                    add(kotlinx.serialization.json.JsonPrimitive(1))
                })
                put(
                    "properties",
                    buildJsonObject {
                        put("\$os", "android")
                        put("\$browser", "iris")
                        put("\$device", "android")
                    },
                )
            },
        )
    }.toString()

    private fun resumePayload(token: String): String = buildJsonObject {
        put("op", 6)
        put(
            "d",
            buildJsonObject {
                put("token", "QQBot $token")
                put("session_id", sessionId)
                put("seq", lastSeq)
            },
        )
    }.toString()

    private fun parseInbound(data: JsonObject?, isGroup: Boolean): QqInboundMessage? {
        val payload = data ?: return null
        val author = payload["author"]?.jsonObject ?: return null
        val memberId = author["user_openid"]?.jsonPrimitive?.contentOrNull
            ?: author["member_openid"]?.jsonPrimitive?.contentOrNull
            ?: author["id"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val targetId = if (isGroup) {
            payload["group_openid"]?.jsonPrimitive?.contentOrNull ?: return null
        } else {
            memberId
        }
        val text = payload["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            .replace(Regex("<@!?[^>]+>"), "")
            .trim()
        if (text.isBlank()) return null
        if (text.isBlank()) return null
        val messageId = payload["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = author["username"]?.jsonPrimitive?.contentOrNull
            ?: memberId
        return QqInboundMessage(targetId, name, text, messageId, isGroup)
    }

    private fun markSeen(messageId: String): Boolean = synchronized(seenMessages) {
        if (seenMessages.containsKey(messageId)) {
            false
        } else {
            seenMessages[messageId] = true
            true
        }
    }

    private suspend fun fetchAccessToken(
        http: OkHttpClient,
        config: QqBotConfig,
    ): Pair<String, Long> {
        val body = buildJsonObject {
            put("appId", config.appId)
            put("clientSecret", config.appSecret)
        }.toString()
        val request = Request.Builder()
            .url("https://bots.qq.com/app/getAppAccessToken")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("getAppAccessToken 失败：${response.code} $text")
            val root = json.parseToJsonElement(text).jsonObject
            val access = root["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("getAppAccessToken 缺少 access_token：$text")
            val expires = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
            return access to expires
        }
    }

    private suspend fun fetchGateway(http: OkHttpClient, token: String): String {
        val request = Request.Builder()
            .url("https://api.sgroup.qq.com/gateway")
            .header("Authorization", "QQBot $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("获取 QQ 网关失败：${response.code} $text")
            return json.parseToJsonElement(text).jsonObject["url"]
                ?.jsonPrimitive?.contentOrNull
                ?: error("QQ 网关缺少 url")
        }
    }

    private suspend fun sendMessage(
        http: OkHttpClient,
        token: String,
        message: QqInboundMessage,
        text: String,
    ) {
        val content = text.take(1800)
        val body = buildJsonObject {
            put("content", content)
            put("msg_type", 0)
            if (message.messageId.isNotBlank()) put("msg_id", message.messageId)
        }.toString()
        val path = if (message.isGroup) "groups" else "users"
        val request = Request.Builder()
            .url("https://api.sgroup.qq.com/v2/$path/${message.targetId}/messages")
            .header("Authorization", "QQBot $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("发送 QQ 消息失败：${response.code} ${response.body?.string().orEmpty()}")
            }
        }
    }
}
