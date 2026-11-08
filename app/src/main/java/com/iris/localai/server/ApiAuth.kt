package com.iris.localai.server

import java.security.MessageDigest

/** 本地 API 鉴权：有 Key 时校验 Bearer/x-api-key；无 Key 时只允许回环 Host 且拒绝浏览器 Origin。 */
object ApiAuth {

    const val MAX_KEY_LENGTH = 256

    fun sanitize(raw: String): String =
        raw.filter { it in '!'..'~' }.take(MAX_KEY_LENGTH)

    /** 返回 null 表示放行，否则返回 HTTP 状态码。 */
    fun check(key: String, headers: Map<String, String>): Int? {
        val expected = key.trim()
        if (expected.isEmpty()) {
            val origin = headers["origin"].orEmpty()
            if (origin.isNotBlank()) return 403
            return if (isLoopbackHost(headers["host"])) null else 403
        }
        val candidates = buildList {
            headers["authorization"]?.trim()?.takeIf { it.isNotEmpty() }?.let { header ->
                add(if (header.startsWith("Bearer ", ignoreCase = true)) header.substring(7).trim() else header)
            }
            headers["x-api-key"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        val want = expected.toByteArray(Charsets.UTF_8)
        return if (candidates.any {
                MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), want)
            }
        ) {
            null
        } else {
            401
        }
    }

    fun isLoopbackHost(host: String?): Boolean {
        val value = host?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return true
        val name = if (value.startsWith("[")) value.substringBefore(']') + "]" else value.substringBefore(':')
        return name in setOf("127.0.0.1", "localhost", "[::1]")
    }
}
