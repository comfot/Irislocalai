package com.iris.localai.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitTranspilerTest {

    @Test
    fun rewritesCrossToolCalls() {
        val source = """
            async function run() {
              const a = await toolCall('read_file', { path: '/a' })
              const b = NativeInterface.callTool('shell', { command: 'pwd' })
              NativeInterface.callToolAsync('cb', 'system', 'device_info', '{}')
            }
        """.trimIndent()
        val result = OperitTranspiler.transpile(source)
        assertEquals(3, result.rewrites)
        assertTrue(result.source.contains("__irisCompat.callTool('read_file'"))
        assertTrue(result.source.contains("__irisCompat.callTool('shell'"))
        assertTrue(result.source.contains("__irisCompat.callToolAsync('cb'"))
    }

    @Test
    fun keepsLocalToolCallDeclaration() {
        val source = """
            function toolCall(name) { return name }
            const value = toolCall('x')
        """.trimIndent()
        val result = OperitTranspiler.transpile(source)
        assertTrue(result.source.contains("function toolCall(name)"))
        assertTrue(result.source.contains("__irisCompat.callTool('x')"))
        assertEquals(1, result.rewrites)
    }

    @Test
    fun reportsUiAndJavaLimitations() {
        val result = OperitTranspiler.transpile(
            "ctx.UI.Text({ text: 'x' }); Java.use('android.content.Intent');",
        )
        assertTrue(result.warnings.any { it.contains("Compose DSL") })
        assertTrue(result.warnings.any { it.contains("Java/Kotlin") })
    }
}
