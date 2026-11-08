package com.iris.localai.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * 极简 HTTP/1.1 服务：一个连接一个请求、Content-Length 请求体、支持 SSE 流式响应。
 * 不引入第三方服务器，与 IRIDIS 的 server/HttpServer 思路一致，只保留本地 API 需要的能力。
 */
class HttpServer(
    private val port: Int,
    private val host: String = "127.0.0.1",
    private val handler: suspend (HttpRequest, HttpResponse) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var accepting = false
    private var serverSocket: ServerSocket? = null

    val isRunning: Boolean
        get() = synchronized(lock) { serverSocket?.isClosed == false }

    fun start() {
        synchronized(lock) {
            if (isRunning) return
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(host, port), 16)
            serverSocket = socket
            accepting = true
            scope.launch {
                while (accepting && isActive) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        if (!accepting) break else continue
                    }
                    launch {
                        try {
                            client.use { serve(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 单个连接超时/断开不影响整个服务
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            accepting = false
            serverSocket?.runCatching { close() }
            serverSocket = null
        }
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun serve(client: Socket) {
        client.soTimeout = 15_000
        val input = client.getInputStream().buffered()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty().split(" ")
        if (requestLine.size < 2) return

        val method = requestLine[0].uppercase()
        val path = requestLine[1].substringBefore('?')
        val headers = buildMap {
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    put(line.substring(0, colon).trim().lowercase(), line.substring(colon + 1).trim())
                }
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length in 1..MAX_BODY) readBody(input, length) else ""

        val response = HttpResponse(client.getOutputStream())
        try {
            handler(HttpRequest(method, path, headers, body), response)
        } finally {
            response.close()
        }
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HEAD) {
            val byte = input.read()
            if (byte == -1) return null
            buffer.write(byte)
            matched = when {
                matched == 0 && byte == '\r'.code -> 1
                matched == 1 && byte == '\n'.code -> 2
                matched == 2 && byte == '\r'.code -> 3
                matched == 3 && byte == '\n'.code -> return buffer.toString("ISO-8859-1")
                byte == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private companion object {
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

/** 写响应。SSE 模式下先发响应头，之后每次 [event] 立即 flush。 */
class HttpResponse(out: OutputStream) {

    private val out = out.buffered()
    private var headSent = false
    private var streaming = false

    fun json(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendHead(status, "application/json; charset=utf-8", bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun empty(status: Int) {
        sendHead(status, "text/plain; charset=utf-8", 0)
        out.flush()
    }

    fun beginSse() {
        sendHead(200, "text/event-stream; charset=utf-8", -1)
        out.flush()
        streaming = true
    }

    fun event(data: String) {
        check(streaming) { "SSE 响应尚未开始" }
        out.write("data: $data\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun close() {
        runCatching { out.flush() }
    }

    private fun sendHead(status: Int, contentType: String, length: Int) {
        if (headSent) return
        headSent = true
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            if (length >= 0) append("Content-Length: $length\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
