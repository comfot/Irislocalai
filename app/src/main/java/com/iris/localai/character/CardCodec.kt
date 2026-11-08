package com.iris.localai.character

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 角色卡导入导出：
 * - PNG：读取 tEXt/zTXt/iTXt 中的 chara / ccv3（兼容 ccv2 / character / card 关键词），
 *   值为 base64 或直接 JSON；导出时写入 ccv3 与 chara 两个块。
 * - JSON：V1/V2/V3 及春水/Pygmalion 的 char_* 别名，由 [CardSpec] 统一规范化。
 */
object CardCodec {

    class Imported(
        val card: CharacterCardData,
        val avatar: ByteArray?,
        val format: String,
    )

    class CardFormatException(val kind: Kind) : IllegalArgumentException(kind.name) {
        enum class Kind { NOT_A_CARD, PNG_NO_CARD, BAD_JSON, TOO_BIG }
    }

    const val MAX_BYTES = 40L * 1024 * 1024

    private val PNG_SIG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val CARD_KEYS = listOf("ccv3", "chara", "ccv2", "character", "card")

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && (0 until 8).all { bytes[it] == PNG_SIG[it] }

    fun decode(bytes: ByteArray, fileName: String = ""): Imported {
        if (bytes.size > MAX_BYTES) throw CardFormatException(CardFormatException.Kind.TOO_BIG)
        return when {
            isPng(bytes) -> decodePng(bytes)
            fileName.endsWith(".charx", ignoreCase = true) ->
                throw CardFormatException(CardFormatException.Kind.NOT_A_CARD)
            else -> {
                val card = runCatching {
                    CardSpec.parse(String(bytes, Charsets.UTF_8))
                }.getOrElse { error ->
                    val kind = if (error.message == "not a character card") {
                        CardFormatException.Kind.NOT_A_CARD
                    } else {
                        CardFormatException.Kind.BAD_JSON
                    }
                    throw CardFormatException(kind)
                }
                Imported(card, avatar = null, format = "JSON")
            }
        }
    }

    data class Chunk(val type: String, val data: ByteArray)

    fun chunks(png: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var position = 8
        while (position + 8 <= png.size) {
            val length = ((png[position].toInt() and 0xff) shl 24) or
                ((png[position + 1].toInt() and 0xff) shl 16) or
                ((png[position + 2].toInt() and 0xff) shl 8) or
                (png[position + 3].toInt() and 0xff)
            if (length < 0 || position + 12 + length > png.size) break
            val type = String(png, position + 4, 4, Charsets.ISO_8859_1)
            out += Chunk(type, png.copyOfRange(position + 8, position + 8 + length))
            position += 12 + length
            if (type == "IEND") break
        }
        return out
    }

    fun textChunks(png: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (chunk in chunks(png)) {
            val zero = chunk.data.indexOf(0)
            if (zero <= 0) continue
            val key = String(chunk.data, 0, zero, Charsets.ISO_8859_1)
            when (chunk.type) {
                "tEXt" -> map[key] = String(
                    chunk.data, zero + 1, chunk.data.size - zero - 1, Charsets.ISO_8859_1,
                )
                "zTXt" -> runCatching {
                    map[key] = String(
                        inflate(chunk.data.copyOfRange(zero + 2, chunk.data.size)),
                        Charsets.ISO_8859_1,
                    )
                }
                "iTXt" -> runCatching {
                    val compressed = chunk.data[zero + 1].toInt() != 0
                    var cursor = zero + 3
                    cursor = chunk.data.indexOf(0, cursor) + 1
                    cursor = chunk.data.indexOf(0, cursor) + 1
                    val body = chunk.data.copyOfRange(cursor, chunk.data.size)
                    map[key] = String(
                        if (compressed) inflate(body) else body,
                        Charsets.UTF_8,
                    )
                }
            }
        }
        return map
    }

    fun decodePng(png: ByteArray): Imported {
        val texts = textChunks(png)
        for (key in CARD_KEYS) {
            val raw = texts[key] ?: continue
            val card = runCatching { parseCardText(raw) }.getOrNull() ?: continue
            return Imported(card, png, format = if (key == "ccv3") "PNG V3" else "PNG V2")
        }
        throw CardFormatException(CardFormatException.Kind.PNG_NO_CARD)
    }

    private fun parseCardText(raw: String): CharacterCardData {
        val text = runCatching {
            String(Base64.getMimeDecoder().decode(raw.trim()), Charsets.UTF_8)
        }.getOrElse { raw }
        return CardSpec.parse(text)
    }

    fun encodePng(card: CharacterCardData, png: ByteArray): ByteArray {
        require(isPng(png)) { "avatar is not a PNG" }
        val out = ByteArrayOutputStream()
        out.write(PNG_SIG)
        val v3 = Base64.getEncoder().encodeToString(
            card.toJson(pretty = false).toByteArray(Charsets.UTF_8),
        )
        val v2 = Base64.getEncoder().encodeToString(
            CardSpec.json.encodeToString(JsonObject.serializer(), card.toV2())
                .toByteArray(Charsets.UTF_8),
        )
        for (chunk in chunks(png)) {
            if (chunk.type == "IEND") {
                writeChunk(
                    out, "tEXt",
                    "chara".toByteArray(Charsets.ISO_8859_1) + 0 + v2.toByteArray(Charsets.ISO_8859_1),
                )
                writeChunk(
                    out, "tEXt",
                    "ccv3".toByteArray(Charsets.ISO_8859_1) + 0 + v3.toByteArray(Charsets.ISO_8859_1),
                )
            }
            if ((chunk.type == "tEXt" || chunk.type == "zTXt" || chunk.type == "iTXt") &&
                keyOf(chunk) in CARD_KEYS
            ) {
                continue
            }
            writeChunk(out, chunk.type, chunk.data)
        }
        return out.toByteArray()
    }

    /** 纯色 PNG，用于没有头像的 JSON 卡或新建角色。 */
    fun solidPng(size: Int = 256, rgb: Int = 0x8E7CC3): ByteArray {
        val raw = ByteArrayOutputStream()
        val row = ByteArray(1 + size * 3)
        for (x in 0 until size) {
            row[1 + x * 3] = (rgb shr 16).toByte()
            row[2 + x * 3] = (rgb shr 8).toByte()
            row[3 + x * 3] = rgb.toByte()
        }
        repeat(size) { raw.write(row) }

        val deflater = Deflater(9)
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(PNG_SIG)
        val header = ByteArrayOutputStream().apply {
            fun writeInt(value: Int) {
                write(value ushr 24 and 0xff)
                write(value ushr 16 and 0xff)
                write(value ushr 8 and 0xff)
                write(value and 0xff)
            }
            writeInt(size)
            writeInt(size)
            write(8)
            write(2)
            write(0)
            write(0)
            write(0)
        }.toByteArray()
        writeChunk(out, "IHDR", header)
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun keyOf(chunk: Chunk): String {
        val zero = chunk.data.indexOf(0)
        return if (zero <= 0) "" else String(chunk.data, 0, zero, Charsets.ISO_8859_1)
    }

    private fun ByteArray.indexOf(byte: Int, from: Int = 0): Int {
        for (index in from until size) if (this[index].toInt() == byte) return index
        return -1
    }

    private operator fun ByteArray.plus(byte: Int): ByteArray =
        this + byteArrayOf(byte.toByte())

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, count)
            if (out.size() > MAX_BYTES) break
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        fun writeInt(value: Int) {
            out.write(value ushr 24 and 0xff)
            out.write(value ushr 16 and 0xff)
            out.write(value ushr 8 and 0xff)
            out.write(value and 0xff)
        }
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(crc.value.toInt())
    }
}
