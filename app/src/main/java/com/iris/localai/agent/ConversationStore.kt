package com.iris.localai.agent

import android.content.Context
import com.iris.localai.data.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 会话存储。按空间隔离，以 JSON 存在 `filesDir/conversations.json`；
 * 流式生成会逐 token 调用 [updateMessage]，因此普通更新合并后防抖落盘，
 * 新建/删除会话立即落盘。
 */
class ConversationStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idLock = Any()
    private val scheduleLock = Any()
    private val writeLock = Any()
    private var persistJob: Job? = null

    private val _conversations = MutableStateFlow(load())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private var nextConversationId = 1L
    private var nextMessageId = 1L

    init {
        nextConversationId = (_conversations.value.maxOfOrNull { it.id } ?: 0L) + 1
        nextMessageId = (_conversations.value.flatMap { it.messages }.maxOfOrNull { it.id } ?: 0L) + 1
        if (_conversations.value.isEmpty() && !file.exists()) {
            _conversations.value = Space.entries.map { space ->
                Conversation(
                    id = nextConversationId++,
                    title = "新对话",
                    space = space,
                )
            }
            schedulePersist(immediate = true)
        }
    }

    fun create(space: Space, title: String = "新对话", workspaceId: String = ""): Conversation {
        val conversation = Conversation(
            id = synchronized(idLock) { nextConversationId++ },
            title = title,
            space = space,
            workspaceId = workspaceId,
        )
        _conversations.update { it + conversation }
        schedulePersist(immediate = true)
        return conversation
    }

    fun delete(id: Long) {
        _conversations.update { list -> list.filterNot { it.id == id } }
        schedulePersist(immediate = true)
    }

    fun newMessageId(): Long = synchronized(idLock) { nextMessageId++ }

    fun appendMessage(conversationId: Long, message: ChatMessage) {
        update(conversationId) { it.copy(messages = it.messages + message) }
    }

    fun updateMessage(conversationId: Long, messageId: Long, transform: (ChatMessage) -> ChatMessage) {
        update(conversationId) { conversation ->
            conversation.copy(
                messages = conversation.messages.map {
                    if (it.id == messageId) transform(it) else it
                },
            )
        }
    }

    fun rename(conversationId: Long, title: String) {
        update(conversationId) { it.copy(title = title) }
    }

    fun setWorkspace(conversationId: Long, workspaceId: String) {
        update(conversationId) { it.copy(workspaceId = workspaceId) }
    }

    fun find(id: Long?): Conversation? = _conversations.value.firstOrNull { it.id == id }

    fun latest(space: Space): Conversation? = _conversations.value
        .filter { it.space == space }
        .maxByOrNull { it.updatedAt }

    fun count(space: Space): Int = _conversations.value.count { it.space == space }

    private fun update(id: Long, transform: (Conversation) -> Conversation) {
        _conversations.update { list ->
            list.map {
                if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it
            }
        }
        schedulePersist()
    }

    /**
     * 合并写盘：普通更新在窗口内只保留一个任务，写盘时在锁内重新取快照，
     * 避免旧快照晚于新快照落盘；新建/删除则立即重排并写盘。
     */
    private fun schedulePersist(immediate: Boolean = false) {
        synchronized(scheduleLock) {
            if (!immediate && persistJob?.isActive == true) return
            persistJob?.cancel()
            persistJob = scope.launch {
                if (!immediate) delay(PERSIST_DELAY_MS)
                persist()
            }
        }
    }

    private fun persist() {
        synchronized(writeLock) {
            val json = buildJsonArray {
                _conversations.value.forEach { conversation ->
                    addJsonObject {
                        put("id", conversation.id)
                        put("title", conversation.title)
                        put("space", conversation.space.id)
                        put("workspaceId", conversation.workspaceId)
                        put("updatedAt", conversation.updatedAt)
                        put("messages", buildJsonArray {
                            conversation.messages.forEach { message ->
                                addJsonObject {
                                    put("id", message.id)
                                    put("role", message.role.name)
                                    put("text", message.text)
                                    put("reasoning", message.reasoning)
                                    put("attachments", buildJsonArray {
                                        message.attachments.forEach { attachment ->
                                            addJsonObject {
                                                put("name", attachment.name)
                                                put("path", attachment.path)
                                                put("content", attachment.content)
                                                put("truncated", attachment.truncated)
                                                put("kind", attachment.kind.name)
                                                put("mimeType", attachment.mimeType)
                                                put("imagePath", attachment.imagePath)
                                            }
                                        }
                                    })
                                    put("steps", buildJsonArray {
                                        message.steps.forEach { step ->
                                            addJsonObject {
                                                put("tool", step.tool)
                                                put("arguments", step.arguments)
                                                put("result", step.result)
                                                put("status", step.status.name)
                                                put("durationMs", step.durationMs)
                                            }
                                        }
                                    })
                                }
                            }
                        })
                    }
                }
            }.toString()
            runCatching {
                val temp = File(file.parentFile, "$FILE_NAME.tmp")
                temp.writeText(json)
                if (!temp.renameTo(file)) {
                    file.writeText(json)
                    temp.delete()
                }
            }
        }
    }

    private fun load(): List<Conversation> {
        if (!file.isFile) return emptyList()
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                Conversation(
                    id = obj.long("id") ?: return@mapNotNull null,
                    title = obj.string("title") ?: "新对话",
                    space = Space.of(obj.string("space")),
                    workspaceId = obj.string("workspaceId").orEmpty(),
                    messages = obj.messages(),
                    updatedAt = obj.long("updatedAt") ?: 0L,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun JsonObject.messages(): List<ChatMessage> {
        val array = this["messages"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ChatMessage(
                id = obj.long("id") ?: return@mapNotNull null,
                role = runCatching {
                    ChatRole.valueOf(obj.string("role").orEmpty())
                }.getOrDefault(ChatRole.USER),
                text = obj.string("text").orEmpty(),
                attachments = obj.attachments(),
                reasoning = obj.string("reasoning").orEmpty(),
                steps = obj.steps(),
            )
        }
    }

    private fun JsonObject.attachments(): List<ChatAttachment> {
        val array = this["attachments"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ChatAttachment(
                name = obj.string("name").orEmpty(),
                path = obj.string("path").orEmpty(),
                content = obj.string("content").orEmpty(),
                truncated = (obj["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false,
                kind = runCatching {
                    AttachmentKind.valueOf(obj.string("kind").orEmpty())
                }.getOrDefault(AttachmentKind.TEXT),
                mimeType = obj.string("mimeType").orEmpty(),
                imagePath = obj.string("imagePath").orEmpty(),
            )
        }
    }

    private fun JsonObject.steps(): List<AgentStep> {
        val array = this["steps"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            AgentStep(
                tool = obj.string("tool").orEmpty(),
                arguments = obj.string("arguments").orEmpty(),
                result = obj.string("result").orEmpty(),
                status = runCatching {
                    StepStatus.valueOf(obj.string("status").orEmpty())
                }.getOrDefault(StepStatus.OK),
                durationMs = obj.long("durationMs") ?: 0L,
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val FILE_NAME = "conversations.json"
        const val PERSIST_DELAY_MS = 500L
    }
}
