package com.iris.localai.mcp

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SseTransportTest {

    private var server: HttpServer? = null
    private val messages = LinkedBlockingQueue<String>()

    @Volatile
    private var running = true

    @After
    fun tearDown() {
        running = false
        messages.offer("")
        server?.stop(0)
    }

    @Test
    fun connectsAndCallsToolsOverLegacySse() = runBlocking {
        val http = startServer()
        val config = McpServerConfig(
            id = "legacy",
            name = "Legacy SSE",
            transport = McpTransportKind.SSE,
            url = "http://127.0.0.1:${http.address.port}/sse",
        )

        val client = McpClient(SseTransport(config) { _, _ -> }, "legacy")
        try {
            client.connect()
            assertEquals(listOf("echo"), client.tools.map { it.name })
            assertEquals("pong", client.callTool("echo", JsonObject(emptyMap())))
        } finally {
            client.close()
            running = false
            messages.offer("")
            http.stop(0)
        }
    }

    private fun startServer(): HttpServer {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }
        val port = http.address.port

        http.createContext("/sse") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val output = exchange.responseBody
            output.write("event: endpoint\ndata: http://127.0.0.1:$port/messages\n\n".toByteArray())
            output.flush()
            try {
                while (running) {
                    val message = messages.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (message.isEmpty()) break
                    output.write("event: message\ndata: $message\n\n".toByteArray())
                    output.flush()
                }
            } catch (_: IOException) {
                // 测试结束时连接被关闭
            } finally {
                runCatching { exchange.close() }
            }
        }

        http.createContext("/messages") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
            val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            if (id != null) {
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}"""
                    "tools/list" -> """{"tools":[{"name":"echo","description":"回声","inputSchema":{"type":"object"}}]}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"pong"}],"isError":false}"""
                    else -> """{}"""
                }
                messages.put("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }

        http.start()
        server = http
        return http
    }
}
