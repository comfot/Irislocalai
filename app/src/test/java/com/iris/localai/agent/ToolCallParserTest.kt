package com.iris.localai.agent

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `parses tool call with hybrid dsml closing tag`() {
        val text = """<tool_call>{"name":"shell","arguments":{"command":"cat /proc/mounts | grep -E 'data|workspace|sdcard' | head -20; echo ---; ls / | head -30; echo ---; apk search chromium 2>&1 | head"}}</｜｜DSML｜｜_call>"""

        val call = ToolCallParser.parse(text)

        assertEquals("shell", call?.name)
        assertEquals(
            "cat /proc/mounts | grep -E 'data|workspace|sdcard' | head -20; echo ---; ls / | head -30; echo ---; apk search chromium 2>&1 | head",
            call?.arguments?.get("command")?.jsonPrimitive?.content,
        )
        assertEquals("先检查环境。", ToolCallParser.stripToolCalls("先检查环境。\n$text"))
    }

    @Test
    fun `extracts bare command from body and fenced block`() {
        val command = "ls -la /mnt/iris 2>&1; echo \"== attachments ==\"; ls -la /mnt/iris/attachments 2>&1 | head -30"
        assertEquals(command, ToolCallParser.bareShellCommand(command))
        assertTrue(ToolCallParser.isStrongShellCommand(command))
        assertEquals(
            "unzip /sdcard/a.zip",
            ToolCallParser.bareShellCommand("```bash\nunzip /sdcard/a.zip\n```"),
        )
    }

    @Test
    fun `does not treat explanation as command`() {
        assertNull(ToolCallParser.bareShellCommand("cd 是切换目录"))
        assertFalse(ToolCallParser.isStrongShellCommand("cd"))
    }

    @Test
    fun `parses tool call without closing tag`() {
        val call = ToolCallParser.parse("""<tool_call>{"name":"shell","arguments":{"command":"pwd"}}""")
        assertEquals("shell", call?.name)
        assertEquals("pwd", call?.arguments?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `parses fullwidth bar tool call`() {
        val text = """<｜tool_call>{"name":"shell","arguments":{"command":"sed -n '1,20p' a.py"}}"""
        val call = ToolCallParser.parse(text)
        assertEquals("shell", call?.name)
        assertEquals("sed -n '1,20p' a.py", call?.arguments?.get("command")?.jsonPrimitive?.content)
        assertEquals(0, ToolCallParser.earliestStart(text))
        assertEquals("", ToolCallParser.stripToolCalls(text))
        assertTrue(ToolCallParser.holdSuffixLength("<｜tool_cal") > 0)
    }

    @Test
    fun `parses bare json tool call`() {
        val text = """{"command":"sed -n '420,760p' /sdcard/a.py"}"""
        val call = ToolCallParser.parse(text)
        assertEquals("shell", call?.name)
        assertEquals("sed -n '420,760p' /sdcard/a.py", call?.arguments?.get("command")?.jsonPrimitive?.content)
        assertEquals(0, ToolCallParser.earliestStart(text))
        assertEquals("", ToolCallParser.stripToolCalls(text))

        val named = ToolCallParser.parse("""{"name":"read_file","arguments":{"path":"app://private/plugins"}}""")
        assertEquals("read_file", named?.name)
    }

    @Test
    fun `unwraps double nested arguments string`() {
        val text = """<tool_call>{"name":"shell","arguments":{"arguments":"{\"command\": \"cd /workspace && pwd\"}"}}</tool_call>"""
        val call = ToolCallParser.parse(text)
        assertEquals("shell", call?.name)
        assertEquals("cd /workspace && pwd", call?.arguments?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `unwraps double nested arguments object`() {
        val call = ToolCallParser.parse("""<tool_call>{"name":"shell","arguments":{"arguments":{"command":"pwd"}}}</tool_call>""")
        assertEquals("pwd", call?.arguments?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `keeps arguments when outer object has other fields`() {
        val call = ToolCallParser.parse(
            """<tool_call>{"name":"plugin_dev","arguments":{"arguments":{"op":"x"},"project":"p"}}</tool_call>""",
        )
        assertEquals("p", call?.arguments?.get("project")?.jsonPrimitive?.content)
        assertEquals("{\"op\":\"x\"}", call?.arguments?.get("arguments")?.toString())
    }

    @Test
    fun `plain json answer is not a tool call`() {
        assertNull(ToolCallParser.parse("""{"total":3,"items":[]}"""))
        assertEquals(-1, ToolCallParser.earliestStart("""{"total":3}"""))
    }

    @Test
    fun `plain answer is untouched`() {
        val text = "当前目录是 /workspace，运行正常。"
        assertNull(ToolCallParser.earliestStart(text).takeIf { it >= 0 })
        assertEquals(text, ToolCallParser.stripToolCalls(text))
    }

    @Test
    fun `detects truncated tool call`() {
        assertTrue(ToolCallParser.hasUnclosedToolCall("""<tool_call>{"name":"shell","arguments":{"command":"grep -r foo"""))
        assertTrue(ToolCallParser.hasUnclosedToolCall("""<tool_call>{"name":"shell"""))
        assertEquals(
            false,
            ToolCallParser.hasUnclosedToolCall("""<tool_call>{"name":"shell","arguments":{"command":"pwd"}}</tool_call>"""),
        )
        assertEquals(false, ToolCallParser.hasUnclosedToolCall("普通回答"))
    }

    @Test
    fun `parses fenced json tool call`() {
        val text = "先检查一下。\n```json\n{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}\n```"
        val call = ToolCallParser.parse(text)
        assertEquals("read_file", call?.name)
        assertTrue(call?.loose == true)
        assertEquals("先检查一下。", ToolCallParser.stripToolCalls(text, setOf("read_file")))
    }

    @Test
    fun `parses trailing json after explanation`() {
        val text = "我来读取工作区。\n{\"name\":\"read_file\",\"arguments\":{\"path\":\"a.txt\"}}"
        val call = ToolCallParser.parse(text)
        assertEquals("read_file", call?.name)
        assertTrue(call?.loose == true)
        assertEquals("我来读取工作区。", ToolCallParser.stripToolCalls(text, setOf("read_file")))
    }

    @Test
    fun `does not strip example json for unknown tool`() {
        val text = "示例：\n{\"name\":\"demo\",\"arguments\":{}}"
        assertEquals(text, ToolCallParser.stripToolCalls(text, setOf("read_file")))
        assertEquals("示例：", ToolCallParser.stripToolCalls(text, setOf("demo")))
    }

    @Test
    fun `parses single quoted tool call`() {
        val call = ToolCallParser.parse("<tool_call>{'name':'shell','arguments':{'command':'pwd'}}</tool_call>")
        assertEquals("shell", call?.name)
        assertEquals("pwd", call?.arguments?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `parses tool call with raw newline in string`() {
        val call = ToolCallParser.parse("""<tool_call>{"name":"shell","arguments":{"command":"cd /tmp
ls"}}</tool_call>""")
        assertEquals("shell", call?.name)
        assertEquals("cd /tmp\nls", call?.arguments?.get("command")?.jsonPrimitive?.content)
    }

    @Test
    fun `detects malformed tool call`() {
        assertTrue(
            ToolCallParser.looksLikeMalformedToolCall("""{"name": "shell", "arguments": {"command": } }"""),
        )
        assertFalse(ToolCallParser.looksLikeMalformedToolCall("这是普通回答，没有工具调用。"))
    }

    @Test
    fun `keeps plain json examples out of tool calls`() {
        assertNull(ToolCallParser.parse("""{"total":3,"items":[]}"""))
        assertNull(ToolCallParser.parse("""{"name":"张三","age":3}"""))
        val fenced = "示例：\n```json\n{\"total\":3}\n```"
        assertEquals(fenced, ToolCallParser.stripToolCalls(fenced))
    }

    @Test
    fun `detects bare command answer`() {
        val command = "cd '/sdcard/foo'; echo '== x =='; grep -o 'const-string[^,]*, \"[^\"]*\"' a.smali | head -60"
        assertTrue(ToolCallParser.looksLikeBareCommand(command))
        assertEquals(false, ToolCallParser.looksLikeBareCommand("cd 是切换目录的命令。"))
        assertEquals(false, ToolCallParser.looksLikeBareCommand("```\ncd /tmp && ls\n```"))
        assertEquals(false, ToolCallParser.looksLikeBareCommand(""))
    }
}
