package com.iris.localai.agent

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun `parses json tool call`() {
        val call = ToolCallParser.parse(
            """<tool_call>{"name":"shell","arguments":{"command":"pwd"}}</tool_call>""",
        )
        assertEquals("shell", call?.name)
        assertEquals("pwd", call?.arguments?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `parses dsml shell invoke`() {
        val text = """
            我先看看环境。
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="shell">
            <｜｜DSML｜｜ parameter name="command" string="true">pwd; echo "---"; uname -a; echo "---"; id; echo "---"; ls -la</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>
        """.trimIndent()

        val call = ToolCallParser.parse(text)

        assertEquals("shell", call?.name)
        assertEquals(
            """pwd; echo "---"; uname -a; echo "---"; id; echo "---"; ls -la""",
            call?.arguments?.get("command")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `parses ascii pipe dsml invoke`() {
        val text = """<|DSML| invoke name="shell"><|DSML| parameter name="command">ls -la</|DSML| parameter></|DSML| invoke>"""
        val call = ToolCallParser.parse(text)
        assertEquals("shell", call?.name)
        assertEquals("ls -la", call?.arguments?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `stream holds partial tags and hides the block`() {
        val preamble = "先看一下环境。"
        val full = preamble + """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="shell">
            <｜｜DSML｜｜ parameter name="command">pwd</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>
        """.trimIndent()

        assertTrue(ToolCallParser.holdSuffixLength("$preamble<｜｜DSM") > 0)
        assertEquals(preamble.length, ToolCallParser.earliestStart(full))
        assertEquals(preamble, ToolCallParser.stripToolCalls(full))
    }

    @Test
    fun `plain answer is untouched`() {
        val text = "当前目录是 /workspace，运行正常。"
        assertNull(ToolCallParser.earliestStart(text).takeIf { it >= 0 })
        assertEquals(text, ToolCallParser.stripToolCalls(text))
    }
}
