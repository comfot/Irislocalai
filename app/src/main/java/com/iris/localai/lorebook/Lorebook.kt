package com.iris.localai.lorebook

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

enum class SelectiveLogic(val label: String, val official: Int) {
    AND_ANY("主键 + 任一二级键", 0),
    NOT_ALL("主键 + 二级键不全命中", 1),
    NOT_ANY("主键 + 二级键全不命中", 2),
    AND_ALL("主键 + 全部二级键", 3),
    OR_ANY("任一关键词命中（扩展）", 4),
}

enum class LorebookPosition(val label: String, val official: Int) {
    BEFORE_CHAR("角色定义之前", 0),
    AFTER_CHAR("角色定义之后", 1),
    BEFORE_EM("示例对话之前", 2),
    AFTER_EM("示例对话之后", 3),
    AUTHOR_NOTE("作者注释", 4),
    AT_DEPTH("按深度插入", 5),
}

data class LorebookEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val logic: SelectiveLogic = SelectiveLogic.AND_ANY,
    val matchWholeWords: Boolean = false,
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val scanDepth: Int? = null,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val group: String = "",
    val groupWeight: Int = 100,
    val groupOverride: Boolean = false,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val order: Int = 100,
    val position: LorebookPosition = LorebookPosition.AFTER_CHAR,
    val depth: Int = 4,
    val extensions: JsonObject = JsonObject(emptyMap()),
)

data class Lorebook(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val globalEnabled: Boolean = true,
    val entries: List<LorebookEntry> = emptyList(),
)

data class WorldBookSettings(
    val scanDepth: Int = 2,
    val tokenBudget: Int = 2048,
    val minActivations: Int = 0,
    val maxRecursion: Int = 0,
    val includeConstant: Boolean = true,
)

/** 简化版世界书扫描：常驻 + 关键词 + 二级逻辑 + 概率 + Token 预算。 */
object LorebookResolver {

    fun resolve(
        books: List<Lorebook>,
        text: String,
        history: List<String>,
        settings: WorldBookSettings,
    ): List<LorebookEntry> {
        val historyWindow = history.takeLast(settings.scanDepth.coerceAtLeast(0))
        val active = mutableListOf<LorebookEntry>()

        books.filter { it.globalEnabled }.forEach { book ->
            book.entries.filter { it.enabled }.forEach { entry ->
                if (entry.constant) {
                    if (settings.includeConstant) active += entry
                    return@forEach
                }
                if (entry.keys.isEmpty()) return@forEach
                val window = if (entry.scanDepth != null) {
                    (history.takeLast(entry.scanDepth) + text).joinToString("\n")
                } else {
                    (historyWindow + text).joinToString("\n")
                }
                if (!matches(window, entry)) return@forEach
                if (entry.useProbability && Random.nextInt(100) >= entry.probability.coerceIn(0, 100)) {
                    return@forEach
                }
                active += entry
            }
        }

        var budget = max(64, settings.tokenBudget) * 4
        return active
            .sortedBy { it.order }
            .takeWhile { entry ->
                if (budget <= 0) return@takeWhile false
                budget -= entry.content.length + 16
                true
            }
    }

    private fun matches(haystack: String, entry: LorebookEntry): Boolean {
        val primary = entry.keys.any { hit(haystack, it, entry) }
        if (!primary) return false
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return true
        val secondary = entry.secondaryKeys.map { hit(haystack, it, entry) }
        return when (entry.logic) {
            SelectiveLogic.AND_ANY -> secondary.any { it }
            SelectiveLogic.AND_ALL -> secondary.all { it }
            SelectiveLogic.NOT_ANY -> secondary.none { it }
            SelectiveLogic.NOT_ALL -> !secondary.all { it }
            SelectiveLogic.OR_ANY -> true
        }
    }

    private fun hit(haystack: String, key: String, entry: LorebookEntry): Boolean {
        if (key.isBlank()) return false
        if (entry.useRegex) {
            val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return runCatching { Regex(key, options).containsMatchIn(haystack) }.getOrDefault(false)
        }
        if (entry.matchWholeWords) {
            val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return runCatching {
                Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])", options)
                    .containsMatchIn(haystack)
            }.getOrDefault(false)
        }
        return haystack.contains(key, ignoreCase = !entry.caseSensitive)
    }

    fun promptBlock(entries: List<LorebookEntry>): String =
        entries.filter { it.content.isNotBlank() }
            .joinToString("\n\n") { "【世界书·${it.title.ifBlank { "条目" }}】\n${it.content}" }

    fun keysOf(element: kotlinx.serialization.json.JsonElement?): List<String> =
        when (element) {
            is kotlinx.serialization.json.JsonArray ->
                element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
            is JsonPrimitive -> element.contentOrNull()
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            else -> emptyList()
        }
}

private fun JsonPrimitive.contentOrNull(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
