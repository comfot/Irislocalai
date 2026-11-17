package com.iris.localai.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginPathGuardTest {

    @Test
    fun acceptsRelativeAndAbsolutePathsInsideRoot() {
        val root = createTempDir()
        val nested = File(root, "nested").apply { mkdirs() }
        assertEquals(File(nested, "a.txt").canonicalFile, PluginPathGuard.resolve(listOf(root), "nested/a.txt"))
        assertEquals(File(nested, "a.txt").canonicalFile, PluginPathGuard.resolve(listOf(root), File(nested, "a.txt").absolutePath))
    }

    @Test
    fun rejectsEscapeAndSiblingPaths() {
        val root = createTempDir()
        val outside = createTempDir()
        assertNull(PluginPathGuard.resolve(listOf(root), "../outside.txt"))
        assertNull(PluginPathGuard.resolve(listOf(root), File(outside, "x.txt").absolutePath))
        assertNull(PluginPathGuard.resolve(listOf(root), ""))
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory().toFile()
}
