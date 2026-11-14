package com.iris.localai.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentClassifierTest {

    @Test
    fun plainTextAndUtf8AreText() {
        assertTrue(AttachmentClassifier.looksLikeText("hello\nworld".toByteArray()))
        assertTrue(AttachmentClassifier.looksLikeText("你好，世界\n第二行".toByteArray()))
        assertTrue(AttachmentClassifier.looksLikeText(ByteArray(0)))
    }

    @Test
    fun zipAndCommonBinarySignaturesAreNotText() {
        assertFalse(
            AttachmentClassifier.looksLikeText(
                byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00),
            ),
        )
        assertFalse(AttachmentClassifier.looksLikeText(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)))
        assertFalse(AttachmentClassifier.looksLikeText("%PDF-1.7".toByteArray()))
    }

    @Test
    fun utf16BomIsText() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0, 'i'.code.toByte(), 0)
        assertTrue(AttachmentClassifier.looksLikeText(bytes))
    }

    @Test
    fun nulBytesMarkBinaryFiles() {
        assertFalse(AttachmentClassifier.looksLikeText(byteArrayOf(0x61, 0x62, 0x00, 0x63)))
    }

    @Test
    fun archiveExtensionsAreDetected() {
        assertTrue(AttachmentClassifier.isArchiveFileName("资料.zip"))
        assertTrue(AttachmentClassifier.isArchiveFileName("backup.tar.gz"))
        assertFalse(AttachmentClassifier.isArchiveFileName("notes.txt"))
    }
}
