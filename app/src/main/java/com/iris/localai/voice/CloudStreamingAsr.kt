package com.iris.localai.voice

import com.iris.localai.data.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎实时语音识别（大模型双向流式，`/api/v3/sauc/bigmodel_async`）。
 *
 * 协议帧：4 字节 header + 4 字节大端序列号 + 4 字节大端 payload 长度 + payload。
 * 首包是 gzip JSON 配置，音频包是 gzip PCM16；最后一包用负序列号表示结束。
 */
class CloudStreamingAsrSession internal constructor(
    private val socket: WebSocket,
    private val sampleRate: Int = 16_000,
) : StreamingAsrHandle {

    private val lock = Any()
    private val pending = ByteArrayOutputStream()
    private val done = CompletableDeferred<Unit>()
    private var sequence = 1
    @Volatile
    private var transcript = ""
    @Volatile
    private var failure: String? = null
    @Volatile
    private var closed = false

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
                sendAudio(all.copyOfRange(0, FRAME_BYTES), last = false)
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
                sendAudio(rest, last = true)
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
        sendFirst()
    }

    internal fun onBinary(bytes: ByteArray) {
        runCatching { parseFrame(bytes) }
            .onFailure { failure = failure ?: (it.message ?: "ASR 响应解析失败") }
    }

    internal fun onFailure(message: String) {
        failure = failure ?: message
        done.complete(Unit)
    }

    internal fun onClosed() {
        done.complete(Unit)
    }

    private fun sendFirst() {
        val config = buildJsonObject {
            putJsonObject("user") { put("uid", "iris-localai") }
            putJsonObject("audio") {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", sampleRate)
                put("bits", 16)
                put("channel", 1)
            }
            putJsonObject("request") {
                put("model_name", "bigmodel")
                put("enable_itn", true)
                put("enable_punc", true)
                put("show_utterances", true)
                put("enable_nonstream", true)
                put("end_window_size", 800)
            }
        }.toString()
        val payload = gzip(config.toByteArray())
        val packet = ByteArrayOutputStream().apply {
            write(
                byteArrayOf(
                    ((PROTOCOL_VERSION shl 4) or HEADER_SIZE).toByte(),
                    ((CLIENT_FULL_REQUEST shl 4) or POS_SEQUENCE).toByte(),
                    ((JSON shl 4) or GZIP).toByte(),
                    0,
                ),
            )
            writeInt(sequence++)
            writeInt(payload.size)
            write(payload)
        }.toByteArray()
        socket.send(packet.toByteString())
    }

    private fun sendAudio(data: ByteArray, last: Boolean) {
        if (closed) return
        val flags = if (last) NEG_SEQUENCE_1 else POS_SEQUENCE
        val seq = if (last) -sequence else sequence++
        val payload = gzip(data)
        val packet = ByteArrayOutputStream().apply {
            write(
                byteArrayOf(
                    ((PROTOCOL_VERSION shl 4) or HEADER_SIZE).toByte(),
                    ((CLIENT_AUDIO_ONLY_REQUEST shl 4) or flags).toByte(),
                    ((JSON shl 4) or GZIP).toByte(),
                    0,
                ),
            )
            writeInt(seq)
            writeInt(payload.size)
            write(payload)
        }.toByteArray()
        socket.send(packet.toByteString())
    }

    private fun parseFrame(bytes: ByteArray) {
        if (bytes.size < HEADER_BYTES) return
        val headerBytes = (bytes[0].toInt() and 0x0F) * 4
        val messageType = (bytes[1].toInt() shr 4) and 0x0F
        val flags = bytes[1].toInt() and 0x0F
        val compression = bytes[2].toInt() and 0x0F
        var offset = headerBytes
        if (flags and FLAG_SEQUENCE != 0) offset += 4
        if (flags and FLAG_EVENT != 0) offset += 4

        if (messageType == SERVER_ERROR_RESPONSE) {
            if (offset + 8 > bytes.size) return
            val code = readInt(bytes, offset)
            offset += 4
            val size = readInt(bytes, offset)
            offset += 4
            val message = runCatching {
                val payload = bytes.copyOfRange(offset, minOf(offset + size, bytes.size))
                String(if (compression == GZIP) gunzip(payload) else payload)
            }.getOrDefault("")
            failure = "ASR 错误 $code${if (message.isBlank()) "" else "：$message"}"
            done.complete(Unit)
            return
        }

        if (offset + 4 > bytes.size) return
        val size = readInt(bytes, offset)
        offset += 4
        if (size <= 0 || offset + size > bytes.size) return
        val payload = bytes.copyOfRange(offset, offset + size)
        val json = runCatching {
            val raw = if (compression == GZIP) gunzip(payload) else payload
            Json.parseToJsonElement(String(raw)).jsonObject
        }.getOrNull() ?: return

        val last = (flags and FLAG_LAST != 0) ||
            json["is_last_package"]?.jsonPrimitive?.booleanOrNull == true
        val result = json["payload_msg"]?.jsonObject?.get("result")?.jsonObject
        val text = result?.get("text")?.jsonPrimitive?.contentOrNull
        if (!text.isNullOrBlank()) transcript = text
        if (last) done.complete(Unit)
    }

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        GZIPOutputStream(out).use { it.write(data) }
        out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        const val PROTOCOL_VERSION = 0b0001
        const val HEADER_SIZE = 0b0001
        const val CLIENT_FULL_REQUEST = 0b0001
        const val CLIENT_AUDIO_ONLY_REQUEST = 0b0010
        const val POS_SEQUENCE = 0b0001
        const val NEG_SEQUENCE_1 = 0b0011
        const val FLAG_SEQUENCE = 0b0001
        const val FLAG_LAST = 0b0010
        const val FLAG_EVENT = 0b0100
        const val JSON = 0b0001
        const val NO_SERIALIZATION = 0b0000
        const val GZIP = 0b0001
        const val SERVER_ERROR_RESPONSE = 0b1111
        const val HEADER_BYTES = 4

        /** 100 ms @ 16 kHz s16le。 */
        const val FRAME_BYTES = 3200
    }
}

object CloudStreamingAsr {

    private const val DEFAULT_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 使用设置里的云端 ASR Key 与 Endpoint 建立流式会话；未配置 Key 时返回 null。 */
    fun connect(settings: AppSettings): CloudStreamingAsrSession? {
        val key = settings.sttCloudKey.trim()
        if (key.isBlank()) return null
        val url = settings.sttCloudEndpoint.trim()
            .takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
            ?: DEFAULT_URL
        val resourceId = settings.sttStreamingResourceId.trim()
            .ifBlank { "volc.seedasr.sauc.duration" }
        val request = Request.Builder()
            .url(url)
            .header("X-Api-Key", key)
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        val holder = AtomicReference<CloudStreamingAsrSession?>()
        val opened = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                // newWebSocket 的回调可能在 holder.set 之前抢跑，短暂等待避免丢掉首包。
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

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                holder.get()?.onBinary(bytes.toByteArray())
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
        val session = CloudStreamingAsrSession(socket)
        holder.set(session)
        if (!opened.await(6, TimeUnit.SECONDS)) {
            socket.cancel()
            return null
        }
        return session.takeIf { it.error() == null }
    }
}
