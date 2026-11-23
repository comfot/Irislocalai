package com.iris.localai.voice

import android.util.Base64
import com.iris.localai.data.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞语音听写（流式版）WebAPI：`wss://iat-api.xfyun.cn/v2/iat`。
 *
 * 鉴权用 HMAC-SHA256 签名拼到 URL 查询参数；音频为 16 kHz PCM16，按 40 ms / 1280 字节分片，
 * 数据帧 status 0（首帧）/1（中间）/2（结束），结果按 `data.result.ws[].cw[].w` 拼接。
 */
class XfyunRealtimeAsrSession internal constructor(
    private val socket: WebSocket,
    private val appId: String,
) : StreamingAsrHandle {

    private val lock = Any()
    private val pending = ByteArrayOutputStream()
    private val done = CompletableDeferred<Unit>()
    @Volatile
    private var transcript = ""
    @Volatile
    private var failure: String? = null
    @Volatile
    private var closed = false
    private var firstSent = false
    private var lastSn = 0

    override fun accept(samples: ShortArray, size: Int) {
        synchronized(lock) {
            if (closed) return
            var index = 0
            while (index < size) {
                val value = samples[index].toInt()
                pending.write(value and 0xFF)
                pending.write((value shr 8) and 0xFF)
                index++
            }
            while (pending.size() >= FRAME_BYTES) {
                val all = pending.toByteArray()
                sendAudio(all.copyOfRange(0, FRAME_BYTES))
                pending.reset()
                pending.write(all, FRAME_BYTES, all.size - FRAME_BYTES)
            }
        }
    }

    override fun text(): String = transcript

    override fun error(): String? = failure

    override suspend fun finishAndGetText(timeoutMs: Long): String {
        synchronized(lock) {
            if (!closed) {
                val rest = pending.toByteArray()
                pending.reset()
                if (rest.isNotEmpty()) sendAudio(rest)
                if (firstSent) {
                    socket.send(buildJsonObject {
                        putJsonObject("data") { put("status", 2) }
                    }.toString())
                } else {
                    done.complete(Unit)
                }
            }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        return transcript
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        runCatching { socket.close(1000, null) }
    }

    internal fun onText(text: String) {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) {
            val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            failure = "讯飞错误 $code${if (message.isBlank()) "" else "：$message"}"
            done.complete(Unit)
            return
        }
        val data = obj["data"]?.jsonObject ?: return
        val status = data["status"]?.jsonPrimitive?.intOrNull ?: 0
        val result = data["result"]?.jsonObject
        if (result != null) {
            val sn = result["sn"]?.jsonPrimitive?.intOrNull ?: 0
            val piece = StringBuilder()
            result["ws"]?.jsonArray?.forEach { word ->
                word.jsonObject["cw"]?.jsonArray?.forEach { candidate ->
                    candidate.jsonObject["w"]?.jsonPrimitive?.contentOrNull?.let(piece::append)
                }
            }
            if (piece.isNotEmpty() && sn > lastSn) {
                transcript += piece.toString()
                lastSn = sn
            }
        }
        if (status == 2) done.complete(Unit)
    }

    internal fun onFailure(message: String) {
        failure = failure ?: message
        done.complete(Unit)
    }

    internal fun onClosed() {
        done.complete(Unit)
    }

    private fun sendAudio(pcm: ByteArray) {
        val audio = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val first = audioFrameCount == 0
        val json = buildJsonObject {
            if (first) {
                putJsonObject("common") { put("app_id", appId) }
                putJsonObject("business") {
                    put("language", "zh_cn")
                    put("domain", "iat")
                    put("accent", "mandarin")
                }
            }
            putJsonObject("data") {
                put("status", if (first) 0 else 1)
                put("format", "audio/L16;rate=16000")
                put("encoding", "raw")
                put("audio", audio)
            }
        }.toString()
        audioFrameCount++
        firstSent = true
        socket.send(json)
    }

    private var audioFrameCount = 0

    private companion object {
        /** 40 ms @ 16 kHz s16le。 */
        const val FRAME_BYTES = 1280
    }
}

object XfyunRealtimeAsr {

    private const val HOST = "iat-api.xfyun.cn"
    private const val PATH = "/v2/iat"
    private const val DEFAULT_URL = "wss://$HOST$PATH"
    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun connect(settings: AppSettings): XfyunRealtimeAsrSession? {
        val appId = settings.xfyunAppId.trim()
        val apiKey = settings.xfyunApiKey.trim()
        val apiSecret = settings.xfyunApiSecret.trim()
        if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) return null
        val url = runCatching { signedUrl(apiKey, apiSecret) }.getOrNull() ?: return null

        val holder = AtomicReference<XfyunRealtimeAsrSession?>()
        val opened = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                holder.get()?.onText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val detail = response?.let { "HTTP ${it.code}" } ?: t.message ?: "连接失败"
                holder.get()?.onFailure(detail)
                opened.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                holder.get()?.onClosed()
            }
        }
        val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        val session = XfyunRealtimeAsrSession(socket, appId)
        holder.set(session)
        if (!opened.await(6, TimeUnit.SECONDS)) {
            socket.cancel()
            return null
        }
        return session
    }

    private fun signedUrl(apiKey: String, apiSecret: String): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())
        val signatureOrigin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal(signatureOrigin.toByteArray()), Base64.NO_WRAP)
        val authorizationOrigin =
            "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authorizationOrigin.toByteArray(), Base64.NO_WRAP)
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return "$DEFAULT_URL?host=$HOST&date=${encode(date)}&authorization=${encode(authorization)}"
    }
}
