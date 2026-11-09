package com.iris.localai.motion

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpdToVmdTest {

    private val sjis = Charset.forName("Shift_JIS")

    @Test
    fun convertsBonesIntoTwoFrameVmd() {
        val vpd = """
            Bone0 {
              センター
              0.00,1.50,0.00;
              0.0,0.0,0.0,1.0;
            }
            Bone1 {
              頭
              0.00,0.00,0.00;
              0.1,0.2,0.3,0.9;
            }
        """.trimIndent().toByteArray(sjis)

        val vmd = VpdToVmd.convert(vpd)

        val header = String(vmd.copyOfRange(0, 30), Charset.forName("US-ASCII")).trim('\u0000')
        assertEquals("Vocaloid Motion Data 0002", header)
        assertEquals(4, readIntLe(vmd, 50))
        assertEquals(50 + 4 + 4 * 111 + 12, vmd.size)
        val firstName = String(vmd.copyOfRange(54, 69), sjis).trim('\u0000')
        assertEquals("センター", firstName)
        assertTrue(vmd.size > 100)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
