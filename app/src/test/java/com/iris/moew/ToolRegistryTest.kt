package com.iris.moew

import com.iris.moew.agent.ToolRegistry
import com.iris.moew.agent.ToolSource
import com.iris.moew.agent.tools.CurrentTimeTool
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
        assertEquals(ToolSource.Builtin, registry.sourceOf("current_time"))
    }

    @Test
    fun tracksPluginSource() {
        val registry = ToolRegistry()
        val tool = CurrentTimeTool()
        val source = ToolSource.Plugin("demo", "演示插件", "tools")

        registry.register(tool, source)

        assertEquals(source, registry.sourceOf(tool.name))
        assertEquals("插件：演示插件", source.label)
    }
}
