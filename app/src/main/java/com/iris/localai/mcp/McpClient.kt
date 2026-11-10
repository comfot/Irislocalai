package com.iris.localai.mcp

import com.iris.localai.agent.Tool
import com.iris.localai.agent.ToolRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

/**
 * 单个 MCP server 的持久会话：握手、列工具、转发调用都复用同一条 [McpTransport]。
 * HTTP 会话 id、SSE 长连接或 stdio 子进程的生命周期由传输层自己维护。
 */
class McpClient(
    private val transport: McpTransport,
    private val serverName: String = "",
) {

    @Volatile
    var tools: List<McpToolInfo> = emptyList()
        private set

    suspend fun connect() {
        val params = buildJsonObject {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") { }
            putJsonObject("clientInfo") {
                put("name", "IRIS LocalAI")
                put("version", "0.1.0")
            }
        }
        transport.request("initialize", params)
        transport.notify("notifications/initialized")
        tools = listTools()
    }

    suspend fun listTools(): List<McpToolInfo> {
        val result = transport.request("tools/list")
        val array = result["tools"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            McpToolInfo(
                name = name,
                description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                inputSchema = obj["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }

    suspend fun callTool(tool: String, arguments: JsonObject): String {
        val params = buildJsonObject {
            put("name", tool)
            put("arguments", arguments)
        }
        val result = transport.request("tools/call", params)
        val text = (result["content"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        return if (isError) "MCP 工具错误：$text" else text
    }

    fun close() = transport.close()

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

class McpToolProxy(
    override val name: String,
    private val serverName: String,
    private val client: McpClient,
    private val info: McpToolInfo,
) : Tool {

    override val description: String =
        "${serverName} · ${info.description.ifBlank { info.name }}"

    override suspend fun execute(arguments: JsonObject): String =
        client.callTool(info.name, arguments)
}

class McpConnectionManager(
    private val registry: ToolRegistry,
    private val transportFactory: (McpServerConfig) -> McpTransport,
    private val onToolsChanged: () -> Unit = {},
) {
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val toolNames = ConcurrentHashMap<String, List<String>>()

    private val _connected = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val connected: StateFlow<Map<String, List<String>>> = _connected.asStateFlow()

    suspend fun connect(server: McpServerConfig): Result<List<McpToolInfo>> =
        withContext(Dispatchers.IO) {
            try {
                disconnect(server.id)
                val client = McpClient(transportFactory(server), server.name)
                try {
                    client.connect()
                    val names = client.tools.map { info ->
                        val name = toolName(server, info.name)
                        registry.register(McpToolProxy(name, server.name, client, info))
                        name
                    }
                    clients[server.id] = client
                    toolNames[server.id] = names
                    _connected.update { it + (server.id to names) }
                    onToolsChanged()
                    Result.success(client.tools)
                } catch (e: Exception) {
                    client.close()
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun disconnect(serverId: String) {
        toolNames.remove(serverId)?.forEach { registry.unregister(it) }
        clients.remove(serverId)?.close()
        _connected.update { it - serverId }
    }

    fun disconnectAll() {
        clients.keys.toList().forEach(::disconnect)
    }

    private fun toolName(server: McpServerConfig, tool: String): String {
        fun clean(value: String) = value
            .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
        return "mcp_${clean(server.id).take(8)}_${clean(tool).take(40)}"
    }
}
