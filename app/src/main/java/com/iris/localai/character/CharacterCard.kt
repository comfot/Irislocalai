package com.iris.localai.character

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 角色卡数据层：始终以 V3 JSON 保存（[raw]），未知字段原样保留，导入导出可往返。
 * 兼容 SillyTavern V1/V2/V3，以及 Pygmalion / 春水 等 char_* 别名字段。
 */
data class CharacterCardData(val raw: JsonObject) {

    val data: JsonObject get() = raw["data"] as? JsonObject ?: JsonObject(emptyMap())

    private fun str(key: String): String =
        (data[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun strs(key: String): List<String> =
        (data[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    val name: String get() = str("name")
    val nickname: String get() = str("nickname")
    val description: String get() = str("description")
    val personality: String get() = str("personality")
    val scenario: String get() = str("scenario")
    val firstMes: String get() = str("first_mes")
    val mesExample: String get() = str("mes_example")
    val creatorNotes: String get() = str("creator_notes")
    val systemPrompt: String get() = str("system_prompt")
    val postHistory: String get() = str("post_history_instructions")
    val creator: String get() = str("creator")
    val alternateGreetings: List<String> get() = strs("alternate_greetings")
    val tags: List<String> get() = strs("tags")
    val characterBook: JsonObject? get() = data["character_book"] as? JsonObject

    val charName: String get() = nickname.ifBlank { name }
    val greeting: String get() = firstMes.ifBlank { "你好，我是$charName。" }

    private val iris: JsonObject
        get() = ((data["extensions"] as? JsonObject)?.get("iris") as? JsonObject)
            ?: JsonObject(emptyMap())

    val accent: Long?
        get() = (iris["accent"] as? JsonPrimitive)?.longOrNull

    fun blurb(max: Int = 60): String {
        val src = creatorNotes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                .orEmpty()
        return if (src.length <= max) src else src.take(max) + "…"
    }

    fun withData(vararg pairs: Pair<String, JsonElement?>): CharacterCardData {
        val map = LinkedHashMap(data)
        for ((key, value) in pairs) {
            if (value == null) map.remove(key) else map[key] = value
        }
        val top = LinkedHashMap(raw)
        top["data"] = JsonObject(map)
        return CharacterCardData(JsonObject(top))
    }

    fun toV2(): JsonObject {
        val top = LinkedHashMap(raw)
        top["spec"] = JsonPrimitive(CardSpec.V2)
        top["spec_version"] = JsonPrimitive("2.0")
        return JsonObject(top)
    }

    fun toJson(pretty: Boolean = true): String =
        (if (pretty) CardSpec.prettyJson else CardSpec.json)
            .encodeToString(JsonObject.serializer(), raw)

    companion object {
        fun blank(name: String = ""): CharacterCardData =
            CardSpec.normalize(JsonObject(mapOf("name" to JsonPrimitive(name))))

        fun text(value: String): JsonElement = JsonPrimitive(value)

        fun list(values: List<String>): JsonElement =
            JsonArray(values.map { JsonPrimitive(it) })
    }
}

object CardSpec {
    const val V2 = "chara_card_v2"
    const val V3 = "chara_card_v3"

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val STRING_FIELDS = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "creator",
        "character_version",
    )
    private val LIST_FIELDS = listOf("alternate_greetings", "tags", "group_only_greetings")

    private val V1_KEYS = setOf(
        "name", "char_name", "description", "char_persona", "persona", "personality",
        "scenario", "world_scenario", "first_mes", "char_greeting", "greeting",
        "mes_example", "example_dialogue",
    )

    fun normalize(source: JsonObject): CharacterCardData {
        val src = unwrap(source)
        val spec = (src["spec"] as? JsonPrimitive)?.contentOrNull
        val nested = src["data"] as? JsonObject
        val data: JsonObject = when {
            nested != null && (spec == V2 || spec == V3 || spec == null) -> nested
            src.keys.any { it in V1_KEYS } -> src
            else -> throw IllegalArgumentException("not a character card")
        }

        fun value(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key ->
                (data[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }.orEmpty()

        val name = value("name", "char_name")
        if (name.isBlank()) throw IllegalArgumentException("not a character card")

        val map = LinkedHashMap<String, JsonElement>(data)
        map["name"] = JsonPrimitive(name)
        map["description"] = JsonPrimitive(value("description", "char_persona", "persona"))
        map["personality"] = JsonPrimitive(value("personality"))
        map["scenario"] = JsonPrimitive(value("scenario", "world_scenario"))
        map["first_mes"] = JsonPrimitive(value("first_mes", "char_greeting", "greeting"))
        map["mes_example"] = JsonPrimitive(value("mes_example", "example_dialogue"))
        map["creator_notes"] = JsonPrimitive(value("creator_notes"))
        map["system_prompt"] = JsonPrimitive(value("system_prompt"))
        map["post_history_instructions"] = JsonPrimitive(value("post_history_instructions"))
        map["creator"] = JsonPrimitive(value("creator"))
        map["character_version"] = JsonPrimitive(value("character_version"))

        val tags = data["tags"]
        map["tags"] = when (tags) {
            is JsonArray -> tags
            is JsonPrimitive -> JsonArray(
                tags.contentOrNull.orEmpty().split(',').map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { JsonPrimitive(it) },
            )
            else -> JsonArray(emptyList())
        }
        for (field in LIST_FIELDS) {
            if (map[field] !is JsonArray) map[field] = JsonArray(emptyList())
        }
        if (map["extensions"] !is JsonObject) map["extensions"] = JsonObject(emptyMap())

        val top = LinkedHashMap<String, JsonElement>()
        if (nested != null) top.putAll(src)
        top["spec"] = JsonPrimitive(V3)
        top["spec_version"] = JsonPrimitive("3.0")
        top["data"] = JsonObject(map)
        return CharacterCardData(JsonObject(top))
    }

    fun parse(text: String): CharacterCardData {
        val trimmed = text.trimStart('\uFEFF').trim()
        val element = json.parseToJsonElement(trimmed)
        val obj = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
                ?: throw IllegalArgumentException("not a character card")
            else -> throw IllegalArgumentException("not a character card")
        }
        return normalize(obj)
    }

    /** 春水 / 酒馆工具常见的 `{"character":{…}}`、`{"card":{…}}` 包装。 */
    private fun unwrap(source: JsonObject): JsonObject {
        if (source["data"] is JsonObject && source.keys.any { it == "spec" || it == "data" }) {
            return source
        }
        for (key in listOf("character", "card")) {
            val nested = source[key] as? JsonObject
            if (nested != null && nested.keys.any { it in V1_KEYS || it == "data" }) {
                return nested
            }
        }
        return source
    }

    fun isBlank(value: JsonElement?): Boolean = value == null || value is JsonNull
}
