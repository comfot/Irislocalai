package com.iris.localai.motion

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 把 VPD 姿势转成两帧保持的 VMD；VPD 是 Shift-JIS 文本，骨骼名最长 15 字节。
 * 转换后是一段可循环的静止姿势，和 xiaoke.ai 的导入行为一致。
 */
object VpdToVmd {

    private val shiftJis: Charset = Charset.forName("Shift_JIS")

    private val bonePattern = Regex(
        "Bone\\d+\\s*\\{\\s*([^\\r\\n]+)\\r?\\n\\s*" +
            "([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*;[^\\r\\n]*\\r?\\n\\s*" +
            "([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)\\s*;",
    )

    private val interpolation = byteArrayOf(
        20, 20, 20, 20, 20, 20, 20, 20, 107, 107, 107, 107, 107, 107, 107, 107,
        20, 20, 20, 20, 20, 20, 20, 20, 107, 107, 107, 107, 107, 107, 107, 107,
        20, 20, 20, 20, 20, 20, 20, 20, 107, 107, 107, 107, 107, 107, 107, 107,
        20, 20, 20, 20, 20, 20, 20, 20, 107, 107, 107, 107, 107, 107, 107, 107,
    )

    fun convert(vpd: ByteArray, holdFrames: Int = 60): ByteArray {
        val text = String(vpd, shiftJis)
        val bones = bonePattern.findAll(text).map { match ->
            val values = (1..8).map { index -> match.groupValues[index].trim() }
            Bone(
                name = values[0],
                position = floatArrayOf(values[1].toFloat(), values[2].toFloat(), values[3].toFloat()),
                rotation = floatArrayOf(
                    values[4].toFloat(),
                    values[5].toFloat(),
                    values[6].toFloat(),
                    values[7].toFloat(),
                ),
            )
        }.toList()
        require(bones.isNotEmpty()) { "VPD 里没有解析到骨骼" }

        val out = ByteArrayOutputStream()
        out.write(sjis("Vocaloid Motion Data 0002", 30))
        out.write(sjis("vpd_pose", 20))
        out.writeIntLe(bones.size * 2)
        for (frame in intArrayOf(0, holdFrames)) {
            for (bone in bones) {
                out.write(sjis(bone.name, 15))
                out.writeIntLe(frame)
                bone.position.forEach { out.writeFloatLe(it) }
                bone.rotation.forEach { out.writeFloatLe(it) }
                out.write(interpolation)
            }
        }
        out.writeIntLe(0)
        out.writeIntLe(0)
        out.writeIntLe(0)
        return out.toByteArray()
    }

    private fun sjis(text: String, size: Int): ByteArray {
        val raw = text.toByteArray(shiftJis)
        val out = ByteArray(size)
        raw.copyInto(out, endIndex = minOf(raw.size, size))
        return out
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeFloatLe(value: Float) {
        writeIntLe(java.lang.Float.floatToIntBits(value))
    }

    private data class Bone(
        val name: String,
        val position: FloatArray,
        val rotation: FloatArray,
    )
}
