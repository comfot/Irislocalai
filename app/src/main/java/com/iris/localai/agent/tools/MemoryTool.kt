package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemoryStore
import com.iris.localai.memory.MemoryType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 长期记忆的检索与写入；检索同时覆盖记忆库、日记与知识库。 */
class MemoryTool(private val memory: MemoryStore) : Tool {

    override val name: String = "memory"

    override val description: String =
        "长期记忆，参数：{\"op\":\"search\",\"query\":\"关键词\"} 或 " +
            "{\"op\":\"save\",\"content\":\"要记住的事实\",\"tags\":[\"工作\"]}。"

    override suspend fun execute(arguments: JsonObject): String =
        when (val op = arguments.string("op").lowercase()) {
            "search" -> search(arguments.string("query"))
            "save" -> save(arguments)
            "" -> "缺少 op 参数：search 或 save"
            else -> "不支持的操作「$op」：可用 search / save"
        }

    private fun search(rawQuery: String): String {
        val keyword = rawQuery.trim()
        val memories = memory.memories.value
            .filter { it.enabled && it.content.isNotBlank() }
            .filter { item ->
                keyword.isEmpty() ||
                    item.content.contains(keyword, ignoreCase = true) ||
                    item.tags.any { it.contains(keyword, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<MemoryItem> { it.importance }.thenByDescending { it.createdAt },
            )
            .take(5)
        val diary = memory.diary.value
            .filter { entry ->
                keyword.isEmpty() ||
                    entry.title.contains(keyword, ignoreCase = true) ||
                    entry.content.contains(keyword, ignoreCase = true)
            }
            .sortedByDescending { it.date }
            .take(2)
        val docs = if (keyword.isEmpty()) emptyList() else memory.searchKnowledge(keyword).take(2)

        if (memories.isEmpty() && diary.isEmpty() && docs.isEmpty()) {
            return if (keyword.isEmpty()) "记忆库为空" else "没有找到与「$keyword」相关的记忆"
        }
        return buildString {
            if (memories.isNotEmpty()) {
                appendLine("记忆：")
                memories.forEach { appendLine("- (${it.type.label}) ${it.content.take(200)}") }
            }
            if (diary.isNotEmpty()) {
                appendLine("日记：")
                diary.forEach { entry ->
                    appendLine("- ${entry.date} ${entry.title.ifBlank { "日记" }}：${entry.content.take(160)}")
                }
            }
            if (docs.isNotEmpty()) {
                appendLine("知识库：")
                docs.forEach { appendLine("- 【${it.title}】${it.content.take(240)}") }
            }
        }.trimEnd()
    }

    private fun save(arguments: JsonObject): String {
        val content = arguments.string("content").trim()
        if (content.isEmpty()) return "缺少 content 参数"
        if (content.length > MAX_CONTENT_CHARS) return "content 过长（上限 $MAX_CONTENT_CHARS 字）"
        if (memory.memories.value.any { it.content.trim().equals(content, ignoreCase = true) }) {
            return "记忆中已存在相同内容"
        }
        val tags = (arguments["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        memory.addMemory(MemoryItem(type = MemoryType.FACT, content = content, tags = tags))
        return "已保存到长期记忆：${content.take(80)}"
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        const val MAX_CONTENT_CHARS = 2000
    }
}
