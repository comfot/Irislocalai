package com.iris.localai.integration

import com.iris.localai.agent.AgentEvent
import com.iris.localai.agent.AgentLoop
import com.iris.localai.agent.ChatMessage
import com.iris.localai.agent.ChatRole
import com.iris.localai.agent.ConversationStore
import com.iris.localai.data.SettingsRepository
import com.iris.localai.data.Space
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.skill.SkillStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把各平台消息转成一次 Agent 对话：按平台 + 聊天 ID 绑定一个综合空间会话，
 * 只暴露安全工具，避免外部消息触发文件/设备操作。
 */
class BotMessageRouter(
    private val agent: AgentLoop,
    private val conversations: ConversationStore,
    private val settings: SettingsRepository,
    private val skills: SkillStore,
) {
    private val mutex = Mutex()
    private val conversationIds = mutableMapOf<String, Long>()

    suspend fun reply(platform: String, chatId: String, displayName: String, text: String): String =
        mutex.withLock {
            val current = settings.current
            val key = "$platform:$chatId"
            val conversationId = conversationIds[key] ?: conversations.create(
                space = Space.COMPOSITE,
                title = "[$platform] ${displayName.ifBlank { chatId }}",
            ).id.also { conversationIds[key] = it }
            val conversation = conversations.conversations.value.firstOrNull { it.id == conversationId }
            val history = conversation?.messages.orEmpty()
                .filter { it.text.isNotBlank() }
                .takeLast(16)
                .mapNotNull { message ->
                    when (message.role) {
                        ChatRole.USER -> PromptMessage(PromptMessage.USER, message.text)
                        ChatRole.ASSISTANT -> PromptMessage(PromptMessage.ASSISTANT, message.text)
                        else -> null
                    }
                }
            conversations.appendMessage(
                conversationId,
                ChatMessage(
                    id = conversations.newMessageId(),
                    role = ChatRole.USER,
                    text = text,
                ),
            )
            val safeTools = if (current.agentMode) {
                current.enabledToolIds intersect SAFE_TOOLS
            } else {
                emptySet()
            }
            val skillBlock = skills.promptBlock(text, forAgent = true)
            val systemPrompt = buildString {
                appendLine("你正在通过 $platform 与用户聊天，回复要简洁、适合聊天窗口；不要输出工具调用块或思考过程。")
                if (skillBlock.isNotBlank()) appendLine(skillBlock)
            }
            val params = SamplingConfig(
                temperature = current.temperature,
                topP = current.topP,
                topK = current.topK,
                maxNewTokens = current.maxNewTokens,
                minP = current.minP,
                repeatPenalty = current.repeatPenalty,
                repeatLastN = current.repeatLastN,
                presencePenalty = current.presencePenalty,
                frequencyPenalty = current.frequencyPenalty,
                seed = if (current.seedEnabled) current.seedValue else null,
                disableThinking = !current.deepThinking,
            )
            val output = StringBuilder()
            var failure: String? = null
            agent.run(
                userInput = text,
                history = history,
                params = params,
                userSystemPrompt = systemPrompt,
                enabledTools = safeTools,
                toolPermissions = current.toolPermissions,
                maxStepsOverride = current.agentMaxSteps,
                planFirst = false,
            ).collect { event ->
                when (event) {
                    is AgentEvent.Delta -> output.append(event.text)
                    is AgentEvent.Failed -> failure = event.message
                    else -> Unit
                }
            }
            val reply = output.toString().trim().ifBlank { failure ?: "（没有生成回复）" }
            conversations.appendMessage(
                conversationId,
                ChatMessage(
                    id = conversations.newMessageId(),
                    role = ChatRole.ASSISTANT,
                    text = reply,
                ),
            )
            reply
        }

    private companion object {
        val SAFE_TOOLS = setOf(
            "current_time", "device_info", "random_pick", "use_skill", "fetch_url", "memory",
            "browser_open", "browser_read", "browser_snapshot", "browser_click", "browser_input",
            "browser_scroll", "browser_wait", "browser_navigate", "browser_back",
        )
    }
}
