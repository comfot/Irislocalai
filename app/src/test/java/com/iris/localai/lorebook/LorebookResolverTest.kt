package com.iris.localai.lorebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookResolverTest {

    private fun bookOf(entry: LorebookEntry): Lorebook =
        Lorebook(id = "b1", name = "test", entries = listOf(entry))

    @Test
    fun `orAny activates when only secondary key hits`() {
        val entry = LorebookEntry(
            keys = listOf("alpha"),
            secondaryKeys = listOf("beta"),
            selective = true,
            logic = SelectiveLogic.OR_ANY,
        )
        val result = LorebookResolver.resolve(
            listOf(bookOf(entry)),
            "这里提到了 beta",
            emptyList(),
            WorldBookSettings(),
        )
        assertEquals(listOf(entry), result)
    }

    @Test
    fun `orAny stays inactive when no key hits`() {
        val entry = LorebookEntry(
            keys = listOf("alpha"),
            secondaryKeys = listOf("beta"),
            selective = true,
            logic = SelectiveLogic.OR_ANY,
        )
        val result = LorebookResolver.resolve(
            listOf(bookOf(entry)),
            "什么都不相关",
            emptyList(),
            WorldBookSettings(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `andAny requires both primary and secondary`() {
        val entry = LorebookEntry(
            keys = listOf("alpha"),
            secondaryKeys = listOf("beta"),
            selective = true,
            logic = SelectiveLogic.AND_ANY,
        )
        val both = LorebookResolver.resolve(
            listOf(bookOf(entry)),
            "alpha 与 beta 同时出现",
            emptyList(),
            WorldBookSettings(),
        )
        assertEquals(listOf(entry), both)
        val onlyPrimary = LorebookResolver.resolve(
            listOf(bookOf(entry)),
            "只有 alpha",
            emptyList(),
            WorldBookSettings(),
        )
        assertTrue(onlyPrimary.isEmpty())
    }

    @Test
    fun `negative entry scanDepth does not throw`() {
        val entry = LorebookEntry(keys = listOf("alpha"), scanDepth = -3)
        val result = LorebookResolver.resolve(
            listOf(bookOf(entry)),
            "alpha",
            listOf("历史一", "历史二"),
            WorldBookSettings(scanDepth = 2),
        )
        assertEquals(listOf(entry), result)
    }

    @Test
    fun `notAny rejects when secondary hits`() {
        val entry = LorebookEntry(
            keys = listOf("alpha"),
            secondaryKeys = listOf("beta"),
            selective = true,
            logic = SelectiveLogic.NOT_ANY,
        )
        val hit = LorebookResolver.resolve(
            listOf(bookOf(entry)),
            "alpha 与 beta 同现",
            emptyList(),
            WorldBookSettings(),
        )
        assertTrue(hit.isEmpty())
        val clean = LorebookResolver.resolve(
            listOf(bookOf(entry)),
            "只有 alpha",
            emptyList(),
            WorldBookSettings(),
        )
        assertFalse(clean.isEmpty())
    }
}
