package com.iris.localai

import com.iris.localai.character.CardCodec
import com.iris.localai.character.CardSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CharacterCardCodecTest {

    @Test
    fun importsV1Aliases() {
        val card = CardSpec.parse(
            """{"char_name":"A","char_persona":"desc","world_scenario":"scene","char_greeting":"hi","example_dialogue":"ex"}""",
        )
        assertEquals("A", card.name)
        assertEquals("desc", card.description)
        assertEquals("scene", card.scenario)
        assertEquals("hi", card.firstMes)
        assertEquals("ex", card.mesExample)
    }

    @Test
    fun importsV2NestedData() {
        val card = CardSpec.parse(
            """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"B","description":"d","tags":["x"]}}""",
        )
        assertEquals("B", card.name)
        assertEquals(listOf("x"), card.tags)
    }

    @Test
    fun importsSpringWaterWrapper() {
        val card = CardSpec.parse(
            """{"character":{"char_name":"C","char_persona":"persona","first_mes":"hello"}}""",
        )
        assertEquals("C", card.name)
        assertEquals("persona", card.description)
        assertEquals("hello", card.firstMes)
    }

    @Test
    fun pngRoundTripKeepsCardAndAvatar() {
        val card = CardSpec.normalize(
            buildJsonObject {
                put("name", "D")
                put("description", "png card")
            },
        )
        val png = CardCodec.solidPng(64, 0x336699)
        val encoded = CardCodec.encodePng(card, png)
        val imported = CardCodec.decodePng(encoded)
        assertEquals("D", imported.card.name)
        assertEquals("png card", imported.card.description)
        assertNotNull(imported.avatar)
    }
}
