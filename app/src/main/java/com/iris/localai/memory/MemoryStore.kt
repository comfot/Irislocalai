package com.iris.localai.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.util.UUID

enum class MemoryType(val label: String) {
    FACT("事实"),
    EPISODIC("事件"),
}

data class DiaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: String = LocalDate.now().toString(),
    val title: String = "",
    val content: String = "",
    val mood: String = "",
    val tags: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val type: MemoryType = MemoryType.FACT,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val importance: Int = 3,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

data class KnowledgeDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val source: String = "",
    val tags: List<String> = emptyList(),
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 记忆三库：日记、记忆库、知识库。
 * 以 JSON 存在 `filesDir/memory/`，模板阶段先做本地检索与提示词注入。
 */
class MemoryStore(context: Context) {

    private val dir = File(context.filesDir, "memory").apply { mkdirs() }

    private val _diary = MutableStateFlow(loadDiary())
    val diary: StateFlow<List<DiaryEntry>> = _diary.asStateFlow()

    private val _memories = MutableStateFlow(loadMemories())
    val memories: StateFlow<List<MemoryItem>> = _memories.asStateFlow()

    private val _knowledge = MutableStateFlow(loadKnowledge())
    val knowledge: StateFlow<List<KnowledgeDocument>> = _knowledge.asStateFlow()

    fun addDiary(entry: DiaryEntry) {
        _diary.update { (it + entry).sortedByDescending { item -> item.date } }
        persistDiary()
    }

    fun updateDiary(entry: DiaryEntry) {
        _diary.update { list ->
            list.map { if (it.id == entry.id) entry.copy(updatedAt = System.currentTimeMillis()) else it }
        }
        persistDiary()
    }

    fun deleteDiary(id: String) {
        _diary.update { list -> list.filterNot { it.id == id } }
        persistDiary()
    }

    fun addMemory(item: MemoryItem) {
        _memories.update { it + item }
        persistMemories()
    }

    fun updateMemory(item: MemoryItem) {
        _memories.update { list -> list.map { if (it.id == item.id) item else it } }
        persistMemories()
    }

    fun deleteMemory(id: String) {
        _memories.update { list -> list.filterNot { it.id == id } }
        persistMemories()
    }

    fun addKnowledge(document: KnowledgeDocument) {
        _knowledge.update { it + document }
        persistKnowledge()
    }

    fun updateKnowledge(document: KnowledgeDocument) {
        _knowledge.update { list ->
            list.map { if (it.id == document.id) document.copy(updatedAt = System.currentTimeMillis()) else it }
        }
        persistKnowledge()
    }

    fun deleteKnowledge(id: String) {
        _knowledge.update { list -> list.filterNot { it.id == id } }
        persistKnowledge()
    }

    fun importKnowledge(text: String, fileName: String): KnowledgeDocument {
        val title = fileName.substringBeforeLast('.').ifBlank { "导入文档" }
        val document = KnowledgeDocument(
            title = title,
            content = text,
            source = fileName,
        )
        addKnowledge(document)
        return document
    }

    fun searchKnowledge(query: String): List<KnowledgeDocument> {
        val tokens = query.lowercase()
            .split(Regex("[\\s,，。.;；:：]+"))
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) return _knowledge.value.filter { it.enabled }
        return _knowledge.value
            .filter { it.enabled }
            .sortedByDescending { doc ->
                tokens.count { token ->
                    doc.title.lowercase().contains(token) ||
                        doc.content.lowercase().contains(token) ||
                        doc.tags.any { tag -> tag.lowercase().contains(token) }
                }
            }
            .filter { doc ->
                tokens.any { token ->
                    doc.title.lowercase().contains(token) ||
                        doc.content.lowercase().contains(token) ||
                        doc.tags.any { tag -> tag.lowercase().contains(token) }
                }
            }
    }

    fun promptContext(
        query: String,
        maxChars: Int = 2400,
        includeMemory: Boolean = true,
        includeKnowledge: Boolean = true,
    ): String {
        val blocks = mutableListOf<String>()

        if (includeMemory) {
            val recentDiary = _diary.value.take(3)
            if (recentDiary.isNotEmpty()) {
                val text = recentDiary.joinToString("\n") { entry ->
                    "【${entry.date}】${entry.title.ifBlank { "日记" }}：${entry.content.take(180)}"
                }
                blocks += "日记：\n$text"
            }

            val memories = _memories.value
                .filter { it.enabled && it.content.isNotBlank() }
                .sortedWith(compareByDescending<MemoryItem> { it.importance }.thenByDescending { it.createdAt })
                .take(8)
            if (memories.isNotEmpty()) {
                blocks += "记忆：\n" + memories.joinToString("\n") {
                    "- (${it.type.label}) ${it.content.take(160)}"
                }
            }
        }

        if (includeKnowledge) {
            val docs = searchKnowledge(query).take(2)
            if (docs.isNotEmpty()) {
                blocks += "知识库：\n" + docs.joinToString("\n\n") { doc ->
                    "【${doc.title}】\n${doc.content.take(320)}"
                }
            }
        }

        var budget = maxChars
        return blocks.takeWhile { block ->
            if (budget <= 0) return@takeWhile false
            budget -= block.length
            true
        }.joinToString("\n\n")
    }

    private fun persistDiary() {
        val json = buildJsonArray {
            _diary.value.forEach { entry ->
                addJsonObject {
                    put("id", entry.id)
                    put("date", entry.date)
                    put("title", entry.title)
                    put("content", entry.content)
                    put("mood", entry.mood)
                    put("tags", entry.tags)
                    put("updatedAt", entry.updatedAt)
                }
            }
        }.toString()
        File(dir, DIARY).writeText(json)
    }

    private fun persistMemories() {
        val json = buildJsonArray {
            _memories.value.forEach { item ->
                addJsonObject {
                    put("id", item.id)
                    put("type", item.type.name)
                    put("content", item.content)
                    put("tags", item.tags)
                    put("importance", item.importance)
                    put("enabled", item.enabled)
                    put("createdAt", item.createdAt)
                }
            }
        }.toString()
        File(dir, MEMORIES).writeText(json)
    }

    private fun persistKnowledge() {
        val json = buildJsonArray {
            _knowledge.value.forEach { doc ->
                addJsonObject {
                    put("id", doc.id)
                    put("title", doc.title)
                    put("content", doc.content)
                    put("source", doc.source)
                    put("tags", doc.tags)
                    put("enabled", doc.enabled)
                    put("updatedAt", doc.updatedAt)
                }
            }
        }.toString()
        File(dir, KNOWLEDGE).writeText(json)
    }

    private fun loadDiary(): List<DiaryEntry> {
        val text = File(dir, DIARY).takeIf { it.isFile }?.readText().orEmpty()
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                DiaryEntry(
                    id = obj.string("id") ?: return@mapNotNull null,
                    date = obj.string("date") ?: LocalDate.now().toString(),
                    title = obj.string("title").orEmpty(),
                    content = obj.string("content").orEmpty(),
                    mood = obj.string("mood").orEmpty(),
                    tags = obj.stringList("tags"),
                    updatedAt = obj.long("updatedAt") ?: 0L,
                )
            }.sortedByDescending { it.date }
        }.getOrElse { emptyList() }
    }

    private fun loadMemories(): List<MemoryItem> {
        val text = File(dir, MEMORIES).takeIf { it.isFile }?.readText().orEmpty()
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                MemoryItem(
                    id = obj.string("id") ?: return@mapNotNull null,
                    type = runCatching {
                        MemoryType.valueOf(obj.string("type") ?: "")
                    }.getOrDefault(MemoryType.FACT),
                    content = obj.string("content").orEmpty(),
                    tags = obj.stringList("tags"),
                    importance = obj.int("importance") ?: 3,
                    enabled = obj.bool("enabled") ?: true,
                    createdAt = obj.long("createdAt") ?: 0L,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun loadKnowledge(): List<KnowledgeDocument> {
        val text = File(dir, KNOWLEDGE).takeIf { it.isFile }?.readText().orEmpty()
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                KnowledgeDocument(
                    id = obj.string("id") ?: return@mapNotNull null,
                    title = obj.string("title").orEmpty(),
                    content = obj.string("content").orEmpty(),
                    source = obj.string("source").orEmpty(),
                    tags = obj.stringList("tags"),
                    enabled = obj.bool("enabled") ?: true,
                    updatedAt = obj.long("updatedAt") ?: 0L,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    private fun kotlinx.serialization.json.JsonObjectBuilder.put(
        key: String,
        values: List<String>,
    ) {
        put(key, JsonArray(values.map { JsonPrimitive(it) }))
    }

    private companion object {
        const val DIARY = "diary.json"
        const val MEMORIES = "memories.json"
        const val KNOWLEDGE = "knowledge.json"
    }
}
