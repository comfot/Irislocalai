package com.iris.localai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量扫描 [ToolCallParser.earliestStartFrom] 与全文扫描 [ToolCallParser.earliestStart]
 * 的等价性：按 delta 逐段喂入，游标只回退 [ToolCallParser.SCAN_LOOKBACK]。
 */
class ToolCallStreamScanTest {

    private fun scanChunked(full: String): Int {
        var from = 0
        var found = -1
        for (i in 1..full.length) {
            found = ToolCallParser.earliestStartFrom(
                full.substring(0, i),
                (from - ToolCallParser.SCAN_LOOKBACK).coerceAtLeast(0),
            )
            if (found >= 0) break
            from = i
        }
        return found
    }

    @Test
    fun `incremental scan matches full scan for tagged tool call`() {
        val preamble = "先看一下环境。"
        val close = "</｜tool_call>"
        val full = preamble + "<｜tool_call>" + """{"name":"shell","arguments":{"command":"pwd"}}""" + close

        assertEquals(ToolCallParser.earliestStart(full), scanChunked(full))
        assertEquals(preamble.length, scanChunked(full))
    }

    @Test
    fun `incremental scan matches full scan for dsml invoke`() {
        val preamble = "我先看看环境。"
        val full = preamble + """
            <｜｜DSML｜｜ invoke name="shell">
            <｜｜DSML｜｜ parameter name="command">pwd</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
        """.trimIndent()

        assertEquals(ToolCallParser.earliestStart(full), scanChunked(full))
        assertEquals(preamble.length, scanChunked(full))
    }

    @Test
    fun `incremental scan detects bare json at head`() {
        val full = """{"command":"pwd"}"""

        assertEquals(0, scanChunked(full))
    }

    @Test
    fun `incremental scan ignores plain text`() {
        val full = "当前目录是 /workspace，包含 <maintext> 标签与 {\"total\":3} 之类的 JSON。"

        assertEquals(-1, ToolCallParser.earliestStart(full))
        assertEquals(-1, scanChunked(full))
    }

    @Test
    fun `incremental scan finds marker as soon as tag completes`() {
        // 标签跨 delta 拼接：出现半个 <｜tool_call> 时未命中，完整标签一到立即命中。
        val close = "</｜tool_call>"
        val full = "AB" + "<｜tool_call>" + """{"name":"shell","arguments":{"command":"pwd"}}""" + close
        var seenComplete = false

        var from = 0
        for (i in 1..full.length) {
            val head = full.substring(0, i)
            val found = ToolCallParser.earliestStartFrom(head, (from - ToolCallParser.SCAN_LOOKBACK).coerceAtLeast(0))
            if (found >= 0) {
                assertEquals(2, found)
                assertTrue(i >= head.indexOf('>') + 1)
                seenComplete = true
                break
            }
            assertEquals(-1, ToolCallParser.earliestStart(head))
            from = i
        }
        assertTrue(seenComplete)
    }
}
