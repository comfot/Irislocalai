package com.iris.localai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePresetCodecTest {

    @Test
    fun roundTripsAllAppearanceFields() {
        val preset = ThemePreset(
            name = "雾青玻璃",
            style = ThemeStyle.LIQUID_GLASS,
            accent = ThemeAccent.VIOLET,
            themeMode = ThemeMode.DARK,
            bubbleStyle = BubbleStyle.OUTLINED,
            roleChatCard = false,
        )
        val decoded = ThemePresetCodec.decode(ThemePresetCodec.encode(preset))
        assertEquals(preset, decoded)
    }

    @Test
    fun missingFieldsFallBackToCurrent() {
        val fallback = ThemePreset(
            style = ThemeStyle.E_INK,
            accent = ThemeAccent.AMBER,
            bubbleStyle = BubbleStyle.SQUARE,
            roleChatCard = false,
        )
        val decoded = ThemePresetCodec.decode("""{"name":"只改名字"}""", fallback)
        assertEquals("只改名字", decoded.name)
        assertEquals(ThemeStyle.E_INK, decoded.style)
        assertEquals(ThemeAccent.AMBER, decoded.accent)
        assertEquals(BubbleStyle.SQUARE, decoded.bubbleStyle)
        assertEquals(false, decoded.roleChatCard)
    }

    @Test
    fun rejectsUnknownValuesAndBrokenJson() {
        assertTrue(runCatching { ThemePresetCodec.decode("""{"style":"neon"}""") }.isFailure)
        assertTrue(runCatching { ThemePresetCodec.decode("""{"accent":"cyan"}""") }.isFailure)
        assertTrue(runCatching { ThemePresetCodec.decode("not json") }.isFailure)
        assertTrue(runCatching { ThemePresetCodec.decode("""{"schema":99}""") }.isFailure)
    }
}
