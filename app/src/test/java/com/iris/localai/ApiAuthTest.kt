package com.iris.localai

import com.iris.localai.server.ApiAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiAuthTest {

    @Test
    fun loopbackWithoutKeyIsAllowed() {
        assertNull(ApiAuth.check("", mapOf("host" to "127.0.0.1:8686")))
    }

    @Test
    fun browserWithoutKeyIsRejected() {
        assertEquals(
            403,
            ApiAuth.check(
                "",
                mapOf("host" to "127.0.0.1:8686", "origin" to "https://example.com"),
            ),
        )
    }

    @Test
    fun nonLoopbackHostWithoutKeyIsRejected() {
        assertEquals(403, ApiAuth.check("", mapOf("host" to "192.168.1.2:8686")))
    }

    @Test
    fun keyAcceptsBearerAndHeader() {
        assertEquals(401, ApiAuth.check("secret", mapOf("host" to "127.0.0.1")))
        assertNull(
            ApiAuth.check(
                "secret",
                mapOf("host" to "127.0.0.1", "authorization" to "Bearer secret"),
            ),
        )
        assertNull(
            ApiAuth.check(
                "secret",
                mapOf("host" to "127.0.0.1", "x-api-key" to "secret"),
            ),
        )
    }
}
