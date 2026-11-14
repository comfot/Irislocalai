package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.Charset

/** 抓取网页正文：限制内网地址、响应体积与返回长度，避免把整页噪声塞进上下文。 */
class FetchUrlTool : Tool {

    override val name: String = "fetch_url"

    override val description: String =
        "抓取 http(s) 网页或文本接口并返回正文，参数：{\"url\":\"https://example.com\"}；最多返回 6000 字符。"

    override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.IO) {
        fetch(arguments.string("url").trim())
    }

    private fun fetch(raw: String): String {
        if (raw.isEmpty()) return "缺少 url 参数"
        var url = runCatching { URL(raw) }.getOrNull() ?: return "URL 格式不正确"
        var redirects = 0

        while (redirects <= MAX_REDIRECTS) {
            if (url.protocol.lowercase() !in setOf("http", "https")) {
                return "仅支持 http/https 地址"
            }
            if (isPrivateHost(url.host)) return "不允许访问本机或内网地址"
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
                setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) return "重定向地址无效"
                    url = runCatching { URL(url, location) }.getOrNull()
                        ?: return "重定向地址无效"
                    redirects++
                    continue
                }
                if (code !in 200..299) return "抓取失败：HTTP $code"
                val contentType = connection.contentType.orEmpty()
                val bytes = connection.inputStream.use { it.readAtMost(MAX_BYTES) }
                val charset = charsetOf(contentType)
                val body = runCatching { String(bytes, charset) }
                    .getOrElse { String(bytes, Charsets.UTF_8) }
                val text = if (contentType.contains("html", ignoreCase = true)) htmlToText(body) else body
                val trimmed = text.trim()
                return when {
                    trimmed.isEmpty() -> "页面没有可读正文"
                    trimmed.length > MAX_CHARS -> trimmed.take(MAX_CHARS) + "\n…（正文过长，已截断）"
                    else -> trimmed
                }
            } catch (e: Exception) {
                return "抓取失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                connection.disconnect()
            }
        }
        return "重定向过多"
    }

    private fun isPrivateHost(host: String?): Boolean {
        val name = host?.lowercase() ?: return true
        if (name.isEmpty()) return true
        if (name == "127.0.0.1" || name == "localhost" || name == "::1" || name == "[::1]") return false
        if (name == "localhost" || name.endsWith(".localhost")) return true
        if (name.contains(':')) return true
        val numbers = name.split('.').mapNotNull { it.toIntOrNull() }
        if (numbers.size == 4 && numbers.all { it in 0..255 }) {
            val (a, b) = numbers
            return a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127)
        }
        return runCatching {
            InetAddress.getAllByName(name).any { address ->
                address.isAnyLocalAddress || address.isLoopbackAddress ||
                    address.isLinkLocalAddress || address.isSiteLocalAddress
            }
        }.getOrDefault(false)
    }

    private fun charsetOf(contentType: String): Charset {
        val marker = "charset="
        val index = contentType.indexOf(marker, ignoreCase = true)
        if (index < 0) return Charsets.UTF_8
        val raw = contentType.substring(index + marker.length)
            .substringBefore(';')
            .trim()
            .trim('"')
        return runCatching { Charset.forName(raw) }.getOrDefault(Charsets.UTF_8)
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(buffer)
            if (count < 0) break
            val take = minOf(count, limit - output.size())
            if (take > 0) output.write(buffer, 0, take)
        }
        return output.toByteArray()
    }

    private fun htmlToText(html: String): String {
        var text = html.replace(
            Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"),
            " ",
        )
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|pre)>"), "\n")
        text = text.replace(Regex("(?s)<[^>]+>"), " ")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return text.lineSequence()
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        const val MAX_BYTES = 512 * 1024
        const val MAX_CHARS = 6000
        const val MAX_REDIRECTS = 3
    }
}
