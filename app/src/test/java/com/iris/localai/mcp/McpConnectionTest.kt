package com.iris.localai.mcp

import com.iris.localai.agent.ToolRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectionTest {

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun connectRegistersToolAndNotifiesCatalog() = runBlocking {
        val http = startFakeServer()
        val registry = ToolRegistry()
        var catalogSyncs = 0
        val manager = McpConnectionManager(
            registry = registry,
            transportFactory = { server -> StreamableHttpTransport(server) { _, _ -> } },
        ) { catalogSyncs++ }
        val config = McpServerConfig(
            id = "fake",
            name = "Fake MCP",
            url = "http://127.0.0.1:${http.address.port}/mcp",
        )

        val result = manager.connect(config)

        assertTrue(result.isSuccess)
        assertEquals(1, catalogSyncs)
        val tool = registry.find("mcp_fake_echo")
        assertNotNull(tool)
        assertEquals("pong", tool!!.execute(JsonObject(emptyMap())))
        assertEquals(listOf("mcp_fake_echo"), manager.connected.value["fake"])

        manager.disconnect("fake")
        assertNull(registry.find("mcp_fake_echo"))
    }

    @Test
    fun streamableHttpAcceptsSseResponse() = runBlocking {
        val http = startFakeSseResponseServer()
        val registry = ToolRegistry()
        val manager = McpConnectionManager(
            registry = registry,
            transportFactory = { server -> StreamableHttpTransport(server) { _, _ -> } },
        )
        val config = McpServerConfig(
            id = "sse-http",
            name = "SSE HTTP MCP",
            url = "http://127.0.0.1:${http.address.port}/mcp",
        )

        val result = manager.connect(config)

        assertTrue(result.isSuccess)
        val tool = registry.find("mcp_sse_http_echo")
        assertNotNull(tool)
        assertEquals("pong", tool!!.execute(JsonObject(emptyMap())))
        manager.disconnect("sse-http")
    }

    private fun startFakeSseResponseServer(): HttpServer {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/mcp") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val id = Regex("\\\"id\\\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
            if (id == null) {
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
                return@createContext
            }
            val method = Regex("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}"""
                "tools/list" -> """{"tools":[{"name":"echo","description":"回声","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"pong"}],"isError":false}"""
                else -> """{}"""
            }
            val payload = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n"
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        http.start()
        server = http
        return http
    }

    private fun startFakeServer(): HttpServer {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/mcp") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            if (method == "notifications/initialized") {
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
                return@createContext
            }
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}"""
                "tools/list" -> """{"tools":[{"name":"echo","description":"回声","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"pong"}],"isError":false}"""
                else -> """{}"""
            }
            val payload = """{"jsonrpc":"2.0","id":1,"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Mcp-Session-Id", "test-session")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        http.start()
        server = http
        return http
    }
}
