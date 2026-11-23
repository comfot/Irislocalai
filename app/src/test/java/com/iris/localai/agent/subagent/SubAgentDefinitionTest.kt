package com.iris.localai.agent.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDefinitionTest {

    private val allTools = listOf(
        "read_file", "write_file", "edit_file", "shell", "terminal", "workspace", "task",
    )

    @Test
    fun `read only agent cannot modify or execute`() {
        val explore = SubAgentDefinition.BUILTINS.first { it.id == "builtin-explore" }
        val tools = explore.effectiveTools(allTools)
        assertTrue("read_file" in tools)
        assertFalse("write_file" in tools)
        assertFalse("shell" in tools)
        assertFalse("task" in tools)
    }

    @Test
    fun `all tools mode still strips nested task`() {
        val agent = SubAgentDefinition(
            id = "custom",
            name = "自定义",
            description = "",
            systemPrompt = "",
            toolMode = SubAgentToolMode.ALL,
            allowedTools = emptySet(),
        )
        val tools = agent.effectiveTools(allTools)
        assertFalse("task" in tools)
        assertEquals(allTools.size - 1, tools.size)
    }
}
