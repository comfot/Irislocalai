package com.iris.localai.mcp

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 旧版 MCP HTTP+SSE：GET 长连接接收 `endpoint` 与 `message` 事件，
 * 请求通过 POST 发到 endpoint 事件给出的地址，响应从 SSE 流按 id 路由。
 */
class SseTransport(
    private val config: McpServerConfig,
    private val onTokens: (String, String) -> Unit,
) : McpTransport {

    private val oauth = McpOAuth(config, onTokens)
    private val customHeaders = parseHeaderLines(config.headers)
    private val idCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val endpoint = CompletableDeferred<String>()

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    private var closed = false

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        val target = ensureEndpoint()
        val id = idCounter.incrementAndGet()
        val response = CompletableDeferred<JsonObject>()
        pending[id] = response
        try {
            post(target, jsonRpcRequest(id, method, params), retry = true)
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { response.await() }
            ?: run {
                pending.remove(id)
                throw McpTransportException("$method 等待 SSE 响应超时")
            }
        return validateRpcObject(result, method)
    }

    override suspend fun notify(method: String, params: JsonObject) {
        post(ensureEndpoint(), jsonRpcNotification(method, params), retry = true)
    }

    override fun close() {
        if (closed) return
        closed = true
        val active = connection
        connection = null
        scope.cancel()
        // ChunkedInputStream.close() 会等读线程释放锁；阻塞中的 readLine 又等不到 EOF。
        // 异步 disconnect，避免关闭 SSE 时把调用方卡住。
        Thread {
            runCatching { active?.disconnect() }
        }.apply {
            isDaemon = true
            start()
        }
        val error = McpTransportException("SSE 传输已关闭")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        if (!endpoint.isCompleted) endpoint.completeExceptionally(error)
    }

    private suspend fun ensureEndpoint(): String {
        if (closed) throw McpTransportException("SSE 传输已关闭")
        start()
        return withTimeoutOrNull(START_TIMEOUT_MS) { endpoint.await() }
            ?: throw McpTransportException("SSE 服务未返回 endpoint 事件")
    }

    private suspend fun start() = startMutex.withLock {
        if (connection != null) return@withLock
        if (closed) throw McpTransportException("SSE 传输已关闭")
        if (config.url.isBlank()) throw McpTransportException("MCP SSE URL 为空")
        var http = openGet()
        var code = http.responseCode
        if (code == 401 && !authorizationOverride(customHeaders) && oauth.refresh()) {
            http.disconnect()
            http = openGet()
            code = http.responseCode
        }
        if (code !in 200..299) {
            val error = http.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
            http.disconnect()
            throw McpTransportException("SSE 连接失败：HTTP $code $error".trim())
        }
        connection = http
        scope.launch {
            try {
                readLoop(http.inputStream.bufferedReader())
                if (!closed) {
                    val error = McpTransportException("SSE 连接已断开")
                    if (!endpoint.isCompleted) endpoint.completeExceptionally(error)
                    pending.values.forEach { it.completeExceptionally(error) }
                    pending.clear()
                }
            } catch (e: Exception) {
                if (!closed) {
                    val error = McpTransportException("SSE 连接中断：${e.message}", cause = e)
                    if (!endpoint.isCompleted) endpoint.completeExceptionally(error)
                    pending.values.forEach { it.completeExceptionally(error) }
                    pending.clear()
                }
            }
        }
    }

    private fun openGet(): HttpURLConnection {
        val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            customHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            if (!authorizationOverride(customHeaders)) {
                oauth.authorization?.let { setRequestProperty("Authorization", it) }
            }
        }
        return connection
    }

    private suspend fun post(target: String, body: String, retry: Boolean) {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            customHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            if (!authorizationOverride(customHeaders)) {
                oauth.authorization?.let { setRequestProperty("Authorization", it) }
            }
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401 && retry && !authorizationOverride(customHeaders) && oauth.refresh()) {
                connection.disconnect()
                post(target, body, retry = false)
                return
            }
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                throw McpTransportException("SSE POST 失败：HTTP $code $error".trim())
            }
            // 202/204 是 SSE 消息投递成功的标准响应，没有响应体；不要读 inputStream，避免等不到 EOF。
            if (code == 202 || code == 204) return
            // 兼容少数直接在 POST 响应里回 JSON 的服务；标准 SSE 通常返回 202 并在长连接里回包。
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.isNotBlank()) {
                val json = runCatching { MCP_JSON.parseToJsonElement(text).jsonObject }.getOrNull()
                val id = (json?.get("id") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                if (json != null && id != null) pending.remove(id)?.complete(json)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLoop(reader: BufferedReader) {
        val data = StringBuilder()
        var event = ""
        while (true) {
            val line = try {
                reader.readLine() ?: break
            } catch (_: java.net.SocketTimeoutException) {
                if (closed) break else continue
            }
            if (line.isEmpty()) {
                if (data.isNotEmpty()) dispatch(event, data.toString().trimEnd('\n'))
                data.setLength(0)
                event = ""
                continue
            }
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
            }
        }
    }

    private fun dispatch(event: String, data: String) {
        if (event == "endpoint") {
            if (!endpoint.isCompleted) endpoint.complete(resolveEndpoint(data))
            return
        }
        if (event.isNotEmpty() && event != "message") return
        val json = runCatching { MCP_JSON.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val id = (json["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
        pending.remove(id)?.complete(json)
    }

    private fun resolveEndpoint(raw: String): String = runCatching {
        val base = URL(config.url)
        URL(base, raw).toString()
    }.getOrDefault(raw)

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val START_TIMEOUT_MS = 20_000L
        const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
