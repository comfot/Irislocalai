package com.iris.localai.server

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class SseResponseTest {

    @Test
    fun writesSseHeadersAndNamedEvent() {
        val output = ByteArrayOutputStream()
        val response = HttpResponse(output)
        response.beginSse()
        response.event("message", """{"jsonrpc":"2.0","id":1,"result":{}}""")
        response.close()

        val text = output.toString(Charsets.UTF_8)
        assertTrue(text.contains("Content-Type: text/event-stream"))
        assertTrue(text.contains("Cache-Control: no-cache"))
        assertTrue(text.contains("Connection: close"))
        assertTrue(text.contains("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n"))
    }
}
