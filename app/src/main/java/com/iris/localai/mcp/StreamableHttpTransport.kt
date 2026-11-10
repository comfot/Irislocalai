package com.iris.localai.mcp

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * MCP Streamable HTTP：单端点 POST，响应可以是 JSON，也可以是带单条 message 事件的 SSE。
 * 复用 initialize 下发的 Mcp-Session-Id，并在 401 时用 OAuth 刷新令牌重试一次。
 */
class StreamableHttpTransport(
    private val config: McpServerConfig,
    private val onTokens: (String, String) -> Unit,
) : McpTransport {

    private val oauth = McpOAuth(config, onTokens)
    private val customHeaders = parseHeaderLines(config.headers)
    private val idCounter = AtomicLong(0)

    @Volatile
    private var sessionId: String? = null

    override suspend fun request(method: String, params: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val id = idCounter.incrementAndGet()
            send(jsonRpcRequest(id, method, params), expectedId = id, method = method, retry = true)
        }

    override suspend fun notify(method: String, params: JsonObject) = withContext(Dispatchers.IO) {
        sendNotification(jsonRpcNotification(method, params), retry = true)
    }

    override fun close() {
        sessionId = null
    }

    private suspend fun send(body: String, expectedId: Long, method: String, retry: Boolean): JsonObject {
        val connection = openConnection()
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            sessionId = connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() } ?: sessionId
            if (code == 401 && retry && !authorizationOverride(customHeaders) && oauth.refresh()) {
                return send(body, expectedId, method, retry = false)
            }
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(300).orEmpty()
                throw McpTransportException("HTTP $code 调用 $method 失败：$error".trim())
            }
            val contentType = connection.getHeaderField("Content-Type").orEmpty()
            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                val event = connection.inputStream.bufferedReader().use {
                    readSseData(it, expectedId, method)
                } ?: throw McpTransportException("SSE 响应为空：$method")
                return validateRpcObject(event, method)
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            if (raw.isBlank()) throw McpTransportException("$method 响应体为空")
            return validateRpcResponse(raw, method)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sendNotification(body: String, retry: Boolean) {
        val connection = openConnection()
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            sessionId = connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() } ?: sessionId
            if (code == 401 && retry && !authorizationOverride(customHeaders) && oauth.refresh()) {
                return sendNotification(body, retry = false)
            }
            if (code !in 200..299 && code != 202 && code != 204) {
                throw McpTransportException("HTTP $code 发送 MCP 通知失败")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(): HttpURLConnection {
        if (config.url.isBlank()) throw McpTransportException("MCP URL 为空")
        val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            customHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            if (!authorizationOverride(customHeaders)) {
                oauth.authorization?.let { setRequestProperty("Authorization", it) }
            }
        }
        return connection
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}
