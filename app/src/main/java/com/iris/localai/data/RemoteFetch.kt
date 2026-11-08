package com.iris.localai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** 插件包、角色广场配置与模型文件的受限 HTTP 访问。 */
object RemoteFetch {

    private const val USER_AGENT = "IRIS-LocalAI/0.1"

    fun isPublicHttpUrl(raw: String): Boolean {
        val url = runCatching { URL(raw.trim()) }.getOrNull() ?: return false
        if (url.protocol != "http" && url.protocol != "https") return false
        val host = url.host.trim().lowercase()
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return false
        if (host == "::1" || host == "[::1]") return false
        val address = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return address.all { it.isPublicAddress() }
    }

    suspend fun bytes(url: String, maxBytes: Long, timeoutMs: Int = 30_000): ByteArray =
        withContext(Dispatchers.IO) { bytesBlocking(url, maxBytes, timeoutMs) }

    suspend fun text(url: String, maxBytes: Long, timeoutMs: Int = 30_000): String =
        withContext(Dispatchers.IO) { String(bytesBlocking(url, maxBytes, timeoutMs), Charsets.UTF_8) }

    suspend fun downloadToFile(
        url: String,
        target: java.io.File,
        maxBytes: Long,
        timeoutMs: Int = 60_000,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        if (!isPublicHttpUrl(url)) throw IOException("仅支持公网 HTTP/HTTPS 地址")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (!isPublicHttpUrl(connection.url.toString())) throw IOException("重定向到非公网地址")
            if (code !in 200..299) throw IOException("HTTP $code")
            if (connection.contentLengthLong > maxBytes) throw IOException("文件超过大小限制")
            target.parentFile?.mkdirs()
            var total = 0L
            val reported = connection.contentLengthLong
            target.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("文件超过大小限制")
                        output.write(buffer, 0, read)
                        onProgress(total, reported)
                    }
                }
            }
            total
        } finally {
            connection.disconnect()
        }
    }

    fun bytesBlocking(url: String, maxBytes: Long, timeoutMs: Int = 30_000): ByteArray {
        if (!isPublicHttpUrl(url)) throw IOException("仅支持公网 HTTP/HTTPS 地址")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = connection.responseCode
            if (!isPublicHttpUrl(connection.url.toString())) throw IOException("重定向到非公网地址")
            if (code !in 200..299) throw IOException("HTTP $code")
            if (connection.contentLengthLong > maxBytes) throw IOException("文件超过大小限制")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("文件超过大小限制")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun InetAddress.isPublicAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress ||
            isSiteLocalAddress || isMulticastAddress
        ) {
            return false
        }
        val bytes = address
        return !(bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
    }
}
