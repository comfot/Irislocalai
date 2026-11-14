package com.iris.localai.lorebook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

class WorldBookStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _books = MutableStateFlow(decodeBooks(prefs.getString(KEY_BOOKS, null)))
    val books: StateFlow<List<Lorebook>> = _books.asStateFlow()

    private val _settings = MutableStateFlow(
        WorldBookSettings(
            scanDepth = prefs.getInt(KEY_SCAN_DEPTH, 2),
            tokenBudget = prefs.getInt(KEY_TOKEN_BUDGET, 2048),
            minActivations = prefs.getInt(KEY_MIN_ACTIVATIONS, 0),
            maxRecursion = prefs.getInt(KEY_MAX_RECURSION, 0),
            includeConstant = prefs.getBoolean(KEY_INCLUDE_CONSTANT, true),
        ),
    )
    val settings: StateFlow<WorldBookSettings> = _settings.asStateFlow()

    fun create(name: String): Lorebook {
        val book = Lorebook(name = name.ifBlank { "新世界书" })
        _books.update { it + book }
        persist()
        return book
    }

    fun update(book: Lorebook) {
        _books.update { list -> list.map { if (it.id == book.id) book else it } }
        persist()
    }

    fun delete(id: String) {
        _books.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun updateSettings(transform: (WorldBookSettings) -> WorldBookSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putInt(KEY_SCAN_DEPTH, next.scanDepth)
            .putInt(KEY_TOKEN_BUDGET, next.tokenBudget)
            .putInt(KEY_MIN_ACTIVATIONS, next.minActivations)
            .putInt(KEY_MAX_RECURSION, next.maxRecursion)
            .putBoolean(KEY_INCLUDE_CONSTANT, next.includeConstant)
            .apply()
    }

    fun importJson(text: String, fileName: String = ""): Result<Lorebook> = runCatching {
        val book = parseBook(text, fileName)
        _books.update { it + book }
        persist()
        book
    }

    fun exportJson(id: String): String? {
        val book = _books.value.firstOrNull { it.id == id } ?: return null
        return buildJsonObject {
            put("name", book.name)
            if (book.description.isNotBlank()) put("description", book.description)
            putJsonObject("entries") {
                book.entries.forEachIndexed { index, entry ->
                    putJsonObject(index.toString()) {
                        put("uid", index)
                        put("comment", entry.title)
                        putJsonArray("key") { entry.keys.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("keysecondary") {
                            entry.secondaryKeys.forEach { add(JsonPrimitive(it)) }
                        }
                        put("content", entry.content)
                        put("disable", !entry.enabled)
                        put("constant", entry.constant)
                        put("selective", entry.selective)
                        put("selectiveLogic", entry.logic.official)
                        put("order", entry.order)
                        put("position", entry.position.official)
                        put("depth", entry.depth)
                        put("probability", entry.probability)
                        put("useProbability", entry.useProbability)
                        put("group", entry.group)
                        put("groupWeight", entry.groupWeight)
                        put("groupOverride", entry.groupOverride)
                        put("sticky", entry.sticky)
                        put("cooldown", entry.cooldown)
                        if (entry.scanDepth != null) {
                            put("scanDepth", entry.scanDepth)
                        } else {
                            put("scanDepth", "same_as_global")
                        }
                        put("matchWholeWords", entry.matchWholeWords)
                        put("key_case_sensitive", entry.caseSensitive)
                        put("key_regex", entry.useRegex)
                        if (entry.extensions.isNotEmpty()) put("extensions", entry.extensions)
                    }
                }
            }
        }.toString()
    }

    /** 备份用的完整世界书数组（保留 id 与全部条目字段）。 */
    fun snapshotJson(): String = encodeBooks(_books.value)

    /** 用备份内容整体替换本机世界书与全局扫描设置。 */
    fun restoreFromBackup(booksJson: String, settings: WorldBookSettings) {
        val books = decodeBooks(booksJson)
        _books.value = books
        _settings.value = settings
        prefs.edit()
            .putString(KEY_BOOKS, encodeBooks(books))
            .putInt(KEY_SCAN_DEPTH, settings.scanDepth)
            .putInt(KEY_TOKEN_BUDGET, settings.tokenBudget)
            .putInt(KEY_MIN_ACTIVATIONS, settings.minActivations)
            .putInt(KEY_MAX_RECURSION, settings.maxRecursion)
            .putBoolean(KEY_INCLUDE_CONSTANT, settings.includeConstant)
            .apply()
    }

    private fun parseBook(text: String, fileName: String): Lorebook {
        val root = Json.parseToJsonElement(text.trimStart('\uFEFF')).jsonObject
        val name = root.string("name")
            ?: fileName.substringBeforeLast('.').ifBlank { "导入的世界书" }
        val entriesNode = root["entries"]
            ?: (root["data"] as? JsonObject)?.get("entries")
            ?: root["data"]
        val entries = when (entriesNode) {
            is JsonObject -> entriesNode.values.mapNotNull { parseEntry(it as? JsonObject) }
            is JsonArray -> entriesNode.mapNotNull { parseEntry(it as? JsonObject) }
            else -> emptyList()
        }
        if (entries.isEmpty()) throw IllegalArgumentException("没有找到世界书条目")
        return Lorebook(
            name = name,
            description = root.string("description").orEmpty(),
            entries = entries,
        )
    }

    private fun parseEntry(obj: JsonObject?): LorebookEntry? {
        obj ?: return null
        val content = obj.string("content") ?: return null
        val rawLogic = obj["selectiveLogic"]?.jsonPrimitive?.intOrNull
            ?: obj["selective_logic"]?.jsonPrimitive?.intOrNull
        val logic = SelectiveLogic.entries.firstOrNull { it.official == rawLogic }
            ?: when (obj.string("selective_logic") ?: obj.string("logic")) {
                "and_all" -> SelectiveLogic.AND_ALL
                "not_any" -> SelectiveLogic.NOT_ANY
                "not_all" -> SelectiveLogic.NOT_ALL
                "or_any" -> SelectiveLogic.OR_ANY
                else -> SelectiveLogic.AND_ANY
            }
        val rawPosition = obj["position"]?.jsonPrimitive?.intOrNull ?: 1
        val positionName = obj.string("position")
        val enabled = when {
            obj.containsKey("disable") -> !(obj["disable"]?.jsonPrimitive?.booleanOrNull ?: false)
            obj.containsKey("enabled") -> obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            else -> true
        }
        val scanDepth = when (val value = obj["scanDepth"] ?: obj["scan_depth"]) {
            is JsonPrimitive -> value.intOrNull
            else -> null
        }
        return LorebookEntry(
            id = obj.string("id") ?: obj.string("uid")?.let { "entry-$it" } ?: UUID.randomUUID().toString(),
            title = obj.string("comment") ?: obj.string("name") ?: obj.string("title").orEmpty(),
            keys = LorebookResolver.keysOf(obj["key"] ?: obj["keys"]),
            secondaryKeys = LorebookResolver.keysOf(
                obj["keysecondary"] ?: obj["secondary_keys"] ?: obj["secondaryKeys"],
            ),
            content = content,
            enabled = enabled,
            constant = obj.bool("constant") ?: false,
            selective = obj.bool("selective") ?: false,
            logic = logic,
            matchWholeWords = obj.bool("matchWholeWords")
                ?: obj.bool("match_whole_words") ?: false,
            caseSensitive = obj.bool("key_case_sensitive")
                ?: obj.bool("caseSensitive") ?: false,
            useRegex = obj.bool("key_regex") ?: obj.bool("useRegex") ?: false,
            scanDepth = scanDepth,
            probability = obj.int("probability") ?: 100,
            useProbability = obj.bool("useProbability") ?: true,
            group = obj.string("group").orEmpty(),
            groupWeight = obj.int("groupWeight") ?: obj.int("group_weight") ?: 100,
            groupOverride = obj.bool("groupOverride")
                ?: obj.bool("group_override") ?: false,
            sticky = obj.int("sticky") ?: 0,
            cooldown = obj.int("cooldown") ?: 0,
            order = obj.int("order") ?: obj.int("insertion_order") ?: 100,
            position = LorebookPosition.entries.firstOrNull {
                it.official == rawPosition || it.name == positionName
            } ?: LorebookPosition.AFTER_CHAR,
            depth = obj.int("depth") ?: 4,
            extensions = (obj["extensions"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
    }

    private fun persist() {
        prefs.edit().putString(KEY_BOOKS, encodeBooks(_books.value)).apply()
    }

    private fun encodeBooks(books: List<Lorebook>): String = buildJsonArray {
        books.forEach { book ->
            addJsonObject {
                put("id", book.id)
                put("name", book.name)
                put("description", book.description)
                put("globalEnabled", book.globalEnabled)
                putJsonArray("entries") {
                    book.entries.forEach { entry ->
                        addJsonObject {
                            put("id", entry.id)
                            put("title", entry.title)
                            putJsonArray("keys") { entry.keys.forEach { add(JsonPrimitive(it)) } }
                            putJsonArray("secondaryKeys") {
                                entry.secondaryKeys.forEach { add(JsonPrimitive(it)) }
                            }
                            put("content", entry.content)
                            put("enabled", entry.enabled)
                            put("constant", entry.constant)
                            put("selective", entry.selective)
                            put("logic", entry.logic.name)
                            put("matchWholeWords", entry.matchWholeWords)
                            put("caseSensitive", entry.caseSensitive)
                            put("useRegex", entry.useRegex)
                            if (entry.scanDepth != null) put("scanDepth", entry.scanDepth)
                            put("probability", entry.probability)
                            put("useProbability", entry.useProbability)
                            put("group", entry.group)
                            put("groupWeight", entry.groupWeight)
                            put("groupOverride", entry.groupOverride)
                            put("sticky", entry.sticky)
                            put("cooldown", entry.cooldown)
                            put("order", entry.order)
                            put("position", entry.position.name)
                            put("depth", entry.depth)
                            if (entry.extensions.isNotEmpty()) put("extensions", entry.extensions)
                        }
                    }
                }
            }
        }
    }.toString()

    private fun decodeBooks(raw: String?): List<Lorebook> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val entries = (obj["entries"] as? JsonArray)
                    ?.mapNotNull { parseEntry(it as? JsonObject) }
                    ?: emptyList()
                Lorebook(
                    id = obj.string("id") ?: UUID.randomUUID().toString(),
                    name = obj.string("name") ?: "未命名世界书",
                    description = obj.string("description").orEmpty(),
                    globalEnabled = obj.bool("globalEnabled") ?: true,
                    entries = entries,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val PREFS = "iris_worldbooks"
        const val KEY_BOOKS = "books"
        const val KEY_SCAN_DEPTH = "scan_depth"
        const val KEY_TOKEN_BUDGET = "token_budget"
        const val KEY_MIN_ACTIVATIONS = "min_activations"
        const val KEY_MAX_RECURSION = "max_recursion"
        const val KEY_INCLUDE_CONSTANT = "include_constant"
    }
}
