package com.iris.localai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultTruncateTest {

    @Test
    fun `short result unchanged`() {
        assertEquals("hello", truncateToolResult("hello", 6000))
        assertEquals("", truncateToolResult("", 6000))
    }

    @Test
    fun `long result keeps head and tail`() {
        val result = "H".repeat(5000) + "M".repeat(4000) + "T".repeat(5000)
        val truncated = truncateToolResult(result, 6000)

        assertTrue(truncated.startsWith("H".repeat(4500)))
        assertTrue(truncated.endsWith("T".repeat(1500)))
        assertTrue(truncated.contains("中间省略 8000 字符"))
        val marker = "\n…（中间省略 8000 字符，需要更多请缩小读取范围或分段读取）…"
        assertEquals(4500 + marker.length + 1500, truncated.length)
    }

    @Test
    fun `exact limit unchanged`() {
        assertEquals("A".repeat(6000), truncateToolResult("A".repeat(6000), 6000))
    }

    @Test
    fun `smallest over-limit keeps both sides`() {
        val truncated = truncateToolResult("AB".repeat(3001), 6000)
        assertTrue(truncated.contains("中间省略 2 字符"))
        assertTrue(truncated.endsWith("B"))
    }
}
