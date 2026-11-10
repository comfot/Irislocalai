package com.iris.localai.mcp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpTransportException(
    message: String,
    val code: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 与具体传输方式解耦的 JSON-RPC 通道。 */
interface McpTransport {
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject
    suspend fun notify(method: String, params: JsonObject = JsonObject(emptyMap()))
    fun close()
}

internal val MCP_JSON = Json { ignoreUnknownKeys = true }

internal fun parseHeaderLines(raw: String): Map<String, String> =
    raw.lineSequence().mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) return@mapNotNull null
        line.substring(0, colon).trim() to line.substring(colon + 1).trim()
    }.toMap()

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

/**
 * OAuth 令牌状态：预置 access token；可在 401 时用 refresh token 或 client credentials 换新。
 * 令牌只落在 SharedPreferences，不写入外置 servers.json。
 */
internal class McpOAuth(
    private val config: McpServerConfig,
    private val onTokens: (String, String) -> Unit,
) {
    private val refreshMutex = Mutex()

    @Volatile
    var accessToken: String = config.accessToken

    @Volatile
    var refreshToken: String = config.refreshToken

    val authorization: String? get() = accessToken.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    suspend fun refresh(): Boolean = refreshMutex.withLock {
        if (config.tokenUrl.isBlank()) return@withLock false
        val refresh = refreshToken
        val grantType = if (refresh.isNotBlank()) "refresh_token" else "client_credentials"
        if (grantType == "refresh_token" && refresh.isBlank()) return@withLock false
        if (grantType == "client_credentials" && config.clientId.isBlank()) return@withLock false

        val form = buildString {
            append("grant_type=").append(encode(grantType))
            if (refresh.isNotBlank()) append("&refresh_token=").append(encode(refresh))
            if (config.clientId.isNotBlank()) append("&client_id=").append(encode(config.clientId))
            if (config.clientSecret.isNotBlank()) append("&client_secret=").append(encode(config.clientSecret))
            if (config.scopes.isNotBlank()) append("&scope=").append(encode(config.scopes))
        }

        val json = withContext(Dispatchers.IO) {
            val connection = (URL(config.tokenUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw McpTransportException("OAuth 令牌请求失败：HTTP $code ${text.take(200)}")
                runCatching { MCP_JSON.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw McpTransportException("OAuth 令牌响应不是 JSON")
            } finally {
                connection.disconnect()
            }
        }

        val access = json.string("access_token")
            ?: throw McpTransportException("OAuth 响应缺少 access_token")
        val nextRefresh = json.string("refresh_token")?.takeIf { it.isNotBlank() } ?: refreshToken
        accessToken = access
        refreshToken = nextRefresh
        onTokens(access, nextRefresh)
        true
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal fun authorizationOverride(headers: Map<String, String>): Boolean =
    headers.keys.any { it.equals("Authorization", ignoreCase = true) }

internal fun jsonRpcRequest(id: Long, method: String, params: JsonObject?): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

internal fun jsonRpcNotification(method: String, params: JsonObject?): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

internal fun validateRpcResponse(raw: String, method: String): JsonObject {
    val json = runCatching { MCP_JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
        throw McpTransportException("$method 响应解析失败：${it.message}", cause = it)
    }
    return validateRpcObject(json, method)
}

internal fun validateRpcObject(json: JsonObject, method: String): JsonObject {
    val error = json["error"] as? JsonObject
    if (error != null) {
        throw McpTransportException(
            message = "$method 返回错误：${error.string("message").orEmpty()}",
            code = error.int("code"),
        )
    }
    return json["result"] as? JsonObject
        ?: throw McpTransportException("$method 响应缺少 result")
}

internal fun readSseData(reader: java.io.BufferedReader, expectedId: Long?, method: String): JsonObject? {
    val data = StringBuilder()
    var eventName = ""
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                val raw = data.toString().trimEnd('\n')
                data.setLength(0)
                if (eventName == "message" || eventName.isEmpty()) {
                    val obj = runCatching { MCP_JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
                    if (obj != null) {
                        if (expectedId == null) return obj
                        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        if (id == expectedId) return obj
                    }
                }
                eventName = ""
            }
            continue
        }
        when {
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
            line.startsWith(":") -> Unit
        }
    }
    if (data.isNotEmpty()) {
        val raw = data.toString().trimEnd('\n')
        val obj = runCatching { MCP_JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (obj != null) {
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            if (expectedId == null || id == expectedId) return obj
        }
    }
    throw McpTransportException("SSE 响应中未找到 $method 的数据")
}
