package com.iris.localai

import com.iris.localai.agent.ToolRegistry
import com.iris.localai.agent.tools.CurrentTimeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun registerAndFindTool() {
        val registry = ToolRegistry()
        val tool = CurrentTimeTool()

        registry.register(tool)

        assertEquals(tool, registry.find("current_time"))
        assertNull(registry.find("missing"))
        assertEquals(listOf(tool), registry.all())
    }
}
