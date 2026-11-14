package com.iris.localai.ui.chat

/**
 * 判断一段字节是否适合当作文本附件读取。只看开头一段：
 * 常见二进制签名（ZIP/DOCX、GZIP、PDF、ELF）直接判为非文本；
 * 含 NUL 或控制字符过多的也按二进制处理，避免把乱码拼进提示词。
 */
internal object AttachmentClassifier {

    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (startsWith(bytes, 0x50, 0x4B, 0x03, 0x04)) return false
        if (startsWith(bytes, 0x1F, 0x8B)) return false
        if (startsWith(bytes, 0x25, 0x50, 0x44, 0x46)) return false
        if (startsWith(bytes, 0x7F, 0x45, 0x4C, 0x46)) return false
        // UTF-16 BOM 文本含大量 NUL，不能按二进制拒绝。
        if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) return true
        val sample = if (bytes.size > 4096) bytes.copyOf(4096) else bytes
        if (sample.any { it == 0.toByte() }) return false
        var controls = 0
        for (byte in sample) {
            val value = byte.toInt() and 0xFF
            if (value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D) controls++
        }
        return controls * 100 <= sample.size
    }

    fun isArchiveFileName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2")

    private fun startsWith(bytes: ByteArray, vararg signature: Int): Boolean {
        if (bytes.size < signature.size) return false
        return signature.indices.all { (bytes[it].toInt() and 0xFF) == signature[it] }
    }
}
