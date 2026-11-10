package com.iris.localai.mcp

import com.iris.localai.agent.ToolRegistry
import com.iris.localai.server.ApiAuth
import com.iris.localai.server.HttpRequest
import com.iris.localai.server.HttpResponse
import com.iris.localai.server.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

sealed interface McpState {
    data object Stopped : McpState
    data class Running(
        val port: Int,
        val lan: Boolean,
        val addresses: List<String>,
    ) : McpState

    data class Failed(val message: String) : McpState
}

/**
 * 把内置工具以 MCP Streamable HTTP 暴露给本机其它 AI 客户端。
 * 支持 initialize / tools/list / tools/call / ping；POST 可按 Accept 返回 SSE，GET 可建立事件流。
 */
private val LOCAL_ONLY_TOOLS = setOf("shell", "workspace")
private const val LATEST_PROTOCOL = "2025-06-18"
private val SUPPORTED_PROTOCOLS = setOf("2024-11-05", "2025-03-26", LATEST_PROTOCOL)

class McpServerService(
    private val store: McpStore,
    private val tools: ToolRegistry,
) {

    private var server: HttpServer? = null

    private val _state = MutableStateFlow<McpState>(McpState.Stopped)
    val state: StateFlow<McpState> = _state.asStateFlow()

    fun syncEnabled() {
        if (store.settings.value.enabled) start() else stop()
    }

    fun start() {
        if (server != null) return
        val settings = store.settings.value
        val lan = settings.allowLan && settings.apiKey.isNotBlank()
        val host = if (lan) "0.0.0.0" else "127.0.0.1"
        try {
            val created = HttpServer(
                port = settings.port,
                host = host,
                handler = ::handle,
            )
            created.start()
            server = created
            _state.value = McpState.Running(
                port = settings.port,
                lan = lan,
                addresses = addresses(settings.port, lan),
            )
        } catch (e: Exception) {
            _state.value = McpState.Failed(e.message ?: "MCP 服务启动失败")
        }
    }

    fun stop() {
        server?.stop()
        server = null
        _state.value = McpState.Stopped
    }

    fun restart() {
        stop()
        start()
    }

    private suspend fun handle(request: HttpRequest, response: HttpResponse) {
        if (request.method == "OPTIONS") {
            response.empty(204)
            return
        }
        val settings = store.settings.value
        val authCode = ApiAuth.check(settings.apiKey, request.headers)
        if (authCode != null) {
            response.json(authCode, errorJson(null, -32600, "unauthorized"))
            return
        }
        when (request.method) {
            "GET" -> {
                streamSse(response)
                return
            }
            "POST" -> Unit
            else -> {
                response.empty(405)
                return
            }
        }
        val body = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
        if (body == null) {
            response.json(200, errorJson(null, -32700, "parse error"))
            return
        }
        val id = body["id"]?.takeUnless { it is JsonNull }
        val method = body["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (method.startsWith("notifications/")) {
            response.empty(202)
            return
        }

        val result: JsonObject? = when (method) {
            "initialize" -> {
                val requested = (body["params"] as? JsonObject)
                    ?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
                buildJsonObject {
                    put("protocolVersion", requested?.takeIf { it in SUPPORTED_PROTOCOLS } ?: LATEST_PROTOCOL)
                    putJsonObject("capabilities") {
                        putJsonObject("tools") { put("listChanged", false) }
                    }
                    putJsonObject("serverInfo") {
                        put("name", "IRIS LocalAI")
                        put("version", "0.1.0")
                    }
                }
            }
            "ping" -> buildJsonObject { }
            "tools/list" -> buildJsonObject {
                putJsonArray("tools") {
                    tools.all().filterNot { it.name.startsWith("mcp_") || it.name in LOCAL_ONLY_TOOLS }.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            putJsonObject("inputSchema") {
                                put("type", "object")
                                putJsonObject("properties") { }
                                put("additionalProperties", true)
                            }
                        }
                    }
                }
            }
            "tools/call" -> callTool(body)
            else -> null
        }

        val payload = if (result == null) {
            errorJson(id, -32601, "method not found: $method")
        } else {
            resultJson(id, result)
        }
        val accept = request.headers["accept"].orEmpty()
        if (accept.contains("text/event-stream", ignoreCase = true)) {
            response.beginSse()
            response.event("message", payload)
        } else {
            response.json(200, payload)
        }
    }

    /** Streamable HTTP 允许 GET 建立服务端推送流；当前只发 ping/注释保活。 */
    private suspend fun streamSse(response: HttpResponse) {
        response.beginSse()
        response.comment("IRIS MCP stream ready")
        while (currentCoroutineContext().isActive) {
            delay(15_000)
            response.comment("ping")
        }
    }

    private suspend fun callTool(body: JsonObject): JsonObject {
        val params = body["params"] as? JsonObject
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val arguments = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
        val tool = tools.find(name)?.takeIf { !name.startsWith("mcp_") && it.name !in LOCAL_ONLY_TOOLS }
        val text = if (tool == null) {
            "未知工具：$name"
        } else {
            runCatching { tool.execute(arguments) }
                .getOrElse { "工具执行失败：${it.message ?: "未知错误"}" }
        }
        return buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            put("isError", tool == null)
        }
    }

    private fun resultJson(id: JsonElement?, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }.toString()

    private fun errorJson(id: JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()

    private fun addresses(port: Int, lan: Boolean): List<String> {
        val list = mutableListOf("http://127.0.0.1:$port/mcp")
        if (lan) {
            localIpv4()?.let { list += "http://$it:$port/mcp" }
        }
        return list
    }

    private fun localIpv4(): String? = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
