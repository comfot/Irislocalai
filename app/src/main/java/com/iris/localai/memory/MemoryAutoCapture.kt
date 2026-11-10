package com.iris.localai.memory

import android.content.Context
import com.iris.localai.agent.ChatRole
import com.iris.localai.agent.Conversation
import com.iris.localai.agent.ConversationStore
import com.iris.localai.data.SettingsRepository
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 对话结束后的自动记忆沉淀：
 * - 优先让当前引擎把最近几轮对话整理成结构化记忆；
 * - 模型不可用、返回失败或非 JSON 时，回退到用户陈述的规则提取；
 * - 每条记忆写入 MemoryStore 后会按共享标签/同文件夹自动连边。
 *
 * 每个会话记录最后处理的消息 id，避免同一条回复反复分析。
 */
class MemoryAutoCapture(
    context: Context,
    private val memory: MemoryStore,
    private val conversations: ConversationStore,
    private val engines: EngineManager,
    private val settings: SettingsRepository,
) {

    private val stateFile = File(context.applicationContext.filesDir, "memory/auto_state.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lastProcessed = ConcurrentHashMap<String, Long>().apply {
        loadState().forEach { (key, value) -> put(key, value) }
    }

    fun captureAsync(
        conversationId: Long,
        force: Boolean = false,
        snapshot: Conversation? = null,
    ) {
        scope.launch {
            try {
                capture(conversationId, force, snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Unit
            }
        }
    }

    suspend fun capture(
        conversationId: Long,
        force: Boolean = false,
        snapshot: Conversation? = null,
    ): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = settings.current
            if (!current.memoryEnabled) return@withContext 0
            if (!force && !current.memoryAutoSave) return@withContext 0
            val conversation = snapshot ?: conversations.find(conversationId) ?: return@withContext 0
            val key = conversationId.toString()
            val lastId = if (force) 0L else lastProcessed[key] ?: 0L
            val fresh = conversation.messages
                .filter { it.id > lastId && it.text.isNotBlank() }
                .takeLast(MAX_MESSAGES)
            if (fresh.none { it.role == ChatRole.USER && it.text.isNotBlank() }) {
                markProcessed(key, conversation)
                return@withContext 0
            }

            val modelResult = if (engines.isReady) {
                try {
                    extractWithModel(fresh, conversation)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val created = modelResult ?: extractHeuristic(fresh)
            markProcessed(key, conversation)
            created
        }
    }

    private suspend fun extractWithModel(
        messages: List<com.iris.localai.agent.ChatMessage>,
        conversation: Conversation,
    ): Int? {
        val transcript = messages.joinToString("\n\n") { message ->
            val role = if (message.role == ChatRole.USER) "用户" else "助手"
            "$role：${message.text.trim().take(MAX_MESSAGE_CHARS)}"
        }.take(MAX_TRANSCRIPT_CHARS)
        val existing = memory.memories.value
            .filter { it.enabled }
            .take(30)
            .joinToString("\n") { "- ${it.title.ifBlank { it.content.take(24) }}" }
            .ifBlank { "（暂无）" }
        val system = """
            你是一个长期记忆整理器。只提取值得跨会话保存的稳定信息：
            用户身份、长期偏好、习惯、明确事实、长期目标、重要事件、稳定人际关系。
            忽略寒暄、临时任务、助手客套、推测和敏感凭据。
            只输出严格 JSON，不要 Markdown 代码块，不要解释：
            {"memories":[{"title":"简短标题","content":"完整事实","folder":"分类路径","tags":["标签"],"importance":1到5,"links":[{"target":"已有记忆标题","type":"RELATED","weight":0.7,"description":"关系说明"}]}]}
            没有值得保存的内容时输出 {"memories":[]}。
            已有记忆标题：
            $existing
        """.trimIndent()
        val user = "请分析下面的对话（会话：${conversation.title}）：\n\n$transcript"
        val output = StringBuilder()
        var failure: String? = null
        engines.generate(
            listOf(
                PromptMessage(PromptMessage.SYSTEM, system),
                PromptMessage(PromptMessage.USER, user),
            ),
            SamplingConfig(
                temperature = 0.2f,
                topP = 0.9f,
                maxNewTokens = 700,
                disableThinking = true,
            ),
        ).collect { event ->
            when (event) {
                is GenEvent.Delta -> output.append(event.text)
                is GenEvent.Failed -> failure = event.message
                else -> Unit
            }
        }
        if (failure != null) return null
        val raw = output.toString().trim()
        if (raw.isBlank()) return null
        val jsonText = extractJsonObject(raw) ?: return null
        val root = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return null
        val entries = root["memories"]?.jsonArray ?: return 0
        var created = 0
        val pendingLinks = mutableListOf<Pair<String, JsonObject>>()
        for (element in entries) {
            if (created >= MAX_MEMORIES_PER_RUN) break
            val obj = element as? JsonObject ?: continue
            val content = obj.string("content").trim()
            if (content.length < MIN_CONTENT_CHARS) continue
            if (memory.memories.value.any { sameContent(it.content, content) }) continue
            val title = obj.string("title").trim().take(MAX_TITLE_CHARS)
                .ifBlank { content.take(MAX_TITLE_CHARS) }
            val folder = obj.string("folder").trim().take(MAX_FOLDER_CHARS)
            val tags = (obj["tags"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?.take(8)
                .orEmpty()
            val importance = obj.int("importance")?.coerceIn(1, 5) ?: 3
            memory.addMemory(
                MemoryItem(
                    type = MemoryType.FACT,
                    title = title,
                    content = content,
                    folder = folder,
                    source = "auto",
                    tags = tags,
                    importance = importance,
                ),
            )
            created += 1
            pendingLinks += title to obj
        }
        for ((sourceTitle, obj) in pendingLinks) {
            val links = obj["links"] as? kotlinx.serialization.json.JsonArray ?: continue
            for (linkElement in links) {
                val link = linkElement as? JsonObject ?: continue
                val target = link.string("target").trim()
                if (target.isBlank()) continue
                memory.linkByTitle(
                    sourceTitle = sourceTitle,
                    targetTitle = target,
                    type = link.string("type").ifBlank { "RELATED" },
                    weight = (link["weight"] as? JsonPrimitive)?.floatOrNull ?: 0.6f,
                    description = link.string("description"),
                )
            }
        }
        return created
    }

    private fun extractHeuristic(messages: List<com.iris.localai.agent.ChatMessage>): Int {
        val candidates = messages
            .filter { it.role == ChatRole.USER }
            .flatMap { message ->
                message.text.split(Regex("[。！？!?；;\\n]+"))
                    .map { it.trim() }
                    .filter { it.length in MIN_CONTENT_CHARS..MAX_HEURISTIC_CHARS }
                    .filter { HEURISTIC_PATTERN.containsMatchIn(it) }
            }
            .distinctBy { normalize(it) }
            .take(MAX_HEURISTIC_ITEMS)
        var created = 0
        candidates.forEach { content ->
            if (memory.memories.value.any { sameContent(it.content, content) }) return@forEach
            memory.addMemory(
                MemoryItem(
                    type = MemoryType.FACT,
                    title = content.take(MAX_TITLE_CHARS),
                    content = content,
                    folder = "自动沉淀",
                    source = "auto-rule",
                    tags = listOf("自动提取"),
                    importance = 3,
                ),
            )
            created += 1
        }
        return created
    }

    private fun markProcessed(key: String, conversation: Conversation) {
        val maxId = conversation.messages.maxOfOrNull { it.id } ?: 0L
        lastProcessed[key] = maxId
        persistState()
    }

    private fun sameContent(left: String, right: String): Boolean =
        normalize(left) == normalize(right)

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\s\\p{Punct}，。！？、；：]+"), "")

    private fun extractJsonObject(raw: String): String? {
        val withoutFence = raw
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return withoutFence.substring(start, end + 1)
    }

    private fun loadState(): Map<String, Long> {
        if (!stateFile.isFile) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(stateFile.readText()).jsonObject.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun persistState() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val json = lastProcessed.entries.joinToString(",", "{", "}") { (key, value) ->
                "\"${key.replace("\"", "\\\"")}\":$value"
            }
            stateFile.writeText(json)
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        const val MAX_MESSAGES = 10
        const val MAX_MESSAGE_CHARS = 1200
        const val MAX_TRANSCRIPT_CHARS = 6_000
        const val MIN_CONTENT_CHARS = 6
        const val MAX_HEURISTIC_CHARS = 240
        const val MAX_HEURISTIC_ITEMS = 2
        const val MAX_MEMORIES_PER_RUN = 8
        const val MAX_TITLE_CHARS = 40
        const val MAX_FOLDER_CHARS = 80
        val HEURISTIC_PATTERN = Regex(
            "记住|记一下|我叫|我是|我喜欢|我不喜欢|我讨厌|我住在|我有|我计划|我想要|我习惯|我擅长|我的",
            RegexOption.IGNORE_CASE,
        )
    }
}
