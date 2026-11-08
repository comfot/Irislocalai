package com.iris.localai.mcp

import com.iris.localai.agent.Tool
import com.iris.localai.agent.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

/** 最小 MCP Streamable HTTP 客户端：initialize → tools/list / tools/call。 */
class McpClient {

    suspend fun listTools(server: McpServerConfig): Result<List<McpToolInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = initialize(server)
                val result = request(server, "tools/list", buildJsonObject { }, session)
                (result["tools"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    McpToolInfo(
                        name = name,
                        description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        inputSchema = obj["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                }
            }
        }

    suspend fun callTool(
        server: McpServerConfig,
        tool: String,
        arguments: JsonObject,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val session = initialize(server)
            val result = request(
                server = server,
                method = "tools/call",
                params = buildJsonObject {
                    put("name", tool)
                    put("arguments", arguments)
                },
                session = session,
            )
            val text = (result["content"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("\n")
            val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
            if (isError) "MCP 工具错误：$text" else text
        }
    }

    private fun initialize(server: McpServerConfig): String? {
        val (_, session) = requestRaw(
            server = server,
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "IRIS LocalAI")
                    put("version", "0.1.0")
                }
            },
            id = 1,
            session = null,
        )
        runCatching {
            requestRaw(
                server = server,
                method = "notifications/initialized",
                params = buildJsonObject { },
                id = null,
                session = session,
            )
        }
        return session
    }

    private fun request(
        server: McpServerConfig,
        method: String,
        params: JsonObject,
        session: String?,
    ): JsonObject {
        val (result, _) = requestRaw(server, method, params, id = 2, session = session)
        return result ?: JsonObject(emptyMap())
    }

    private fun requestRaw(
        server: McpServerConfig,
        method: String,
        params: JsonObject,
        id: Int?,
        session: String?,
    ): Pair<JsonObject?, String?> {
        if (server.url.isBlank()) throw IOException("MCP URL 为空")
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

        val connection = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
            session?.let { setRequestProperty("Mcp-Session-Id", it) }
            parseHeaders(server.headers).forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val newSession = connection.getHeaderField("Mcp-Session-Id")
            if (code == 202 || code == 204) return null to newSession
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(200).orEmpty()
                throw IOException("HTTP $code $error".trim())
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = parseRpc(text.substringBefore("\n\n").ifBlank { text })
                ?: parseRpc(text)
                ?: throw IOException("MCP 响应不是 JSON-RPC")
            val error = json["error"] as? JsonObject
            if (error != null) {
                throw IOException(error["message"]?.jsonPrimitive?.contentOrNull ?: "MCP 错误")
            }
            return (json["result"] as? JsonObject) to newSession
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRpc(text: String): JsonObject? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        }
        for (line in trimmed.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun parseHeaders(raw: String): Map<String, String> =
        raw.lineSequence().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }.toMap()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

class McpToolProxy(
    override val name: String,
    private val server: McpServerConfig,
    private val info: McpToolInfo,
    private val client: McpClient,
) : Tool {

    override val description: String =
        "${server.name} · ${info.description.ifBlank { info.name }}"

    override suspend fun execute(arguments: JsonObject): String =
        client.callTool(server, info.name, arguments).getOrThrow()
}

class McpConnectionManager(
    private val client: McpClient,
    private val registry: ToolRegistry,
) {
    private val _connected = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val connected: StateFlow<Map<String, List<String>>> = _connected.asStateFlow()

    suspend fun connect(server: McpServerConfig): Result<List<McpToolInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                disconnect(server.id)
                val tools = client.listTools(server).getOrThrow()
                val names = tools.map { info ->
                    val name = toolName(server, info.name)
                    registry.register(McpToolProxy(name, server, info, client))
                    name
                }
                _connected.update { it + (server.id to names) }
                tools
            }
        }

    fun disconnect(serverId: String) {
        _connected.value[serverId]?.forEach { registry.unregister(it) }
        _connected.update { it - serverId }
    }

    private fun toolName(server: McpServerConfig, tool: String): String {
        fun clean(value: String) = value
            .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
        return "mcp_${clean(server.id).take(8)}_${clean(tool).take(40)}"
    }
}
