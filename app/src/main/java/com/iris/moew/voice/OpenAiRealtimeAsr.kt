package com.iris.moew.voice

import android.util.Base64
import com.iris.moew.data.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI Realtime transcription（`wss://api.openai.com/v1/realtime?intent=transcription`）。
 *
 * 走 JSON 事件：session.update(type=transcription) → input_audio_buffer.append(base64 PCM16 24k)
 * → input_audio_buffer.commit → conversation.item.input_audio_transcription.delta/completed。
 * 录音是 16 kHz，这里内部重采样到 24 kHz。
 */
class OpenAiRealtimeAsrSession internal constructor(
    private val socket: WebSocket,
    private val model: String,
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
    @Volatile
    private var sentAudio = false
    private var lastSample = 0f
    private var position = 0.0

    override fun accept(samples: ShortArray, size: Int) {
        synchronized(lock) {
            if (closed) return
            resample(samples, size, pending)
            while (pending.size() >= FRAME_BYTES) {
                val all = pending.toByteArray()
                sendAppend(all.copyOfRange(0, FRAME_BYTES))
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
                if (rest.isNotEmpty()) sendAppend(rest)
                if (sentAudio) {
                    socket.send(
                        buildJsonObject { put("type", "input_audio_buffer.commit") }.toString(),
                    )
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

    internal fun onOpen() {
        val update = buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "transcription")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", 24_000)
                        }
                        putJsonObject("transcription") {
                            put("model", model)
                        }
                        put("turn_detection", kotlinx.serialization.json.JsonNull)
                    }
                }
            }
        }.toString()
        socket.send(update)
    }

    internal fun onText(text: String) {
        val event = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "conversation.item.input_audio_transcription.delta" -> {
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) transcript += delta
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val finalText = event["transcript"]?.jsonPrimitive?.contentOrNull
                if (!finalText.isNullOrBlank()) transcript = finalText
                done.complete(Unit)
            }
            "error" -> {
                failure = event["error"]?.jsonObject?.get("message")
                    ?.jsonPrimitive?.contentOrNull ?: "OpenAI Realtime 错误"
                done.complete(Unit)
            }
        }
    }

    internal fun onFailure(message: String) {
        failure = failure ?: message
        done.complete(Unit)
    }

    internal fun onClosed() {
        done.complete(Unit)
    }

    private fun sendAppend(pcm24k: ByteArray) {
        sentAudio = true
        val audio = Base64.encodeToString(pcm24k, Base64.NO_WRAP)
        val body = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", audio)
        }.toString()
        socket.send(body)
    }

    /** 16 kHz → 24 kHz 线性插值，保持跨帧相位。 */
    private fun resample(input: ShortArray, size: Int, out: ByteArrayOutputStream) {
        var index = 0
        while (index < size) {
            val current = input[index].toFloat()
            while (position < 1.0) {
                val value = (lastSample + (current - lastSample) * position)
                    .toInt().coerceIn(-32768, 32767)
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
                position += SRC_RATE / DST_RATE
            }
            position -= 1.0
            lastSample = current
            index++
        }
    }

    private companion object {
        const val SRC_RATE = 16_000.0
        const val DST_RATE = 24_000.0

        /** 100 ms @ 24 kHz s16le。 */
        const val FRAME_BYTES = 4800
    }
}

object OpenAiRealtimeAsr {

    private const val DEFAULT_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun connect(settings: AppSettings): OpenAiRealtimeAsrSession? {
        val key = settings.sttCloudKey.trim()
        if (key.isBlank()) return null
        val url = settings.sttCloudEndpoint.trim()
            .takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
            ?: DEFAULT_URL
        val model = settings.openaiRealtimeModel.trim().ifBlank { "gpt-live-transcribe" }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("OpenAI-Beta", "realtime=v1")
            .build()

        val holder = AtomicReference<OpenAiRealtimeAsrSession?>()
        val opened = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                var session = holder.get()
                var spins = 0
                while (session == null && spins < 100) {
                    Thread.sleep(5)
                    session = holder.get()
                    spins++
                }
                session?.onOpen()
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                holder.get()?.onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                holder.get()?.onText(bytes.utf8())
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
        val socket = client.newWebSocket(request, listener)
        val session = OpenAiRealtimeAsrSession(socket, model)
        holder.set(session)
        if (!opened.await(6, TimeUnit.SECONDS)) {
            socket.cancel()
            return null
        }
        return session.takeIf { it.error() == null }
    }
}
