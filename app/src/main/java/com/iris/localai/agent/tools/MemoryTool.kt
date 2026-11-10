package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemoryStore
import com.iris.localai.memory.MemoryType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 长期记忆的检索与写入。常规对话只注入摘要，模型在需要精确回忆时调用 recall，
 * 由 MemoryStore 做向量 + 关键词混合检索，覆盖记忆库、日记与知识库。
 */
class MemoryTool(private val memory: MemoryStore) : Tool {

    override val name: String = "memory"

    override val description: String =
        "长期记忆工具。op=recall 详细检索（记忆/日记/知识库），op=save 保存事实。" +
            "常规对话已有记忆摘要；只有用户要求回忆、确认细节或提到上次/之前时，才用 recall。 " +
            "示例：{\"op\":\"recall\",\"query\":\"上次说的猫\"} 或 " +
            "{\"op\":\"save\",\"title\":\"咖啡偏好\",\"content\":\"用户喜欢冰美式\",\"tags\":[\"生活\"],\"importance\":4}"

    override suspend fun execute(arguments: JsonObject): String =
        when (val op = arguments.string("op").lowercase()) {
            "recall", "search" -> recall(arguments)
            "save" -> save(arguments)
            "" -> "缺少 op 参数：recall 或 save"
            else -> "不支持的操作「$op」：可用 recall / save"
        }

    private suspend fun recall(arguments: JsonObject): String {
        val query = arguments.string("query").trim()
        val limit = arguments.int("limit")?.coerceIn(1, 12) ?: 6
        val includeDiary = arguments.boolean("include_diary") ?: true
        val includeKnowledge = arguments.boolean("include_knowledge") ?: true
        val result = memory.recall(
            query = query,
            limit = limit,
            includeDiary = includeDiary,
            includeKnowledge = includeKnowledge,
        )
        return memory.formatRecall(result)
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
        val title = arguments.string("title").trim().take(80)
            .ifBlank { content.take(24) }
        val folder = arguments.string("folder").trim().take(80)
        val importance = arguments.int("importance")?.coerceIn(1, 5) ?: 3
        memory.addMemory(
            MemoryItem(
                type = MemoryType.FACT,
                title = title,
                content = content,
                folder = folder,
                source = "tool",
                tags = tags,
                importance = importance,
            ),
        )
        return "已保存到长期记忆：${title.ifBlank { content.take(80) }}"
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private companion object {
        const val MAX_CONTENT_CHARS = 2000
    }
}
