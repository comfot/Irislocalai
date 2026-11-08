package com.iris.localai.agent

import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.GenEvent
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.data.ToolPermission
import com.iris.localai.plugin.PluginRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 最小 ReAct 循环：模型要么直接回答，要么调用工具；除约定的
 * `<tool_call>{...}</tool_call>` 外，也兼容部分模型原生的 DSML 工具标签。
 * 工具结果以 tool 消息回填后再问一轮，最多 [maxSteps] 次工具调用。
 *
 * 提示词与工具清单保持固定顺序，方便后续接入 KV 前缀缓存。
 */
class AgentLoop(
    private val engines: EngineManager,
    private val tools: ToolRegistry,
    private val maxSteps: Int = 4,
    private val pluginRuntime: PluginRuntime? = null,
) {

    fun run(
        userInput: String,
        userImages: List<String> = emptyList(),
        history: List<PromptMessage> = emptyList(),
        params: SamplingConfig,
        userSystemPrompt: String = "",
        enabledTools: Set<String>? = null,
        toolPermissions: Map<String, ToolPermission> = emptyMap(),
        maxStepsOverride: Int? = null,
        planFirst: Boolean = false,
    ): Flow<AgentEvent> = flow flow@ {
        val availableTools = tools.all().filter { enabledTools == null || it.name in enabledTools }
        val stepLimit = (maxStepsOverride ?: maxSteps).coerceIn(1, 20)
        val conversation = buildList {
            add(PromptMessage(PromptMessage.SYSTEM, systemPrompt(userSystemPrompt, availableTools, planFirst)))
            addAll(history)
            add(PromptMessage(PromptMessage.USER, userInput, userImages))
        }.toMutableList()

        val visible = StringBuilder()
        var step = 0
        var finished = false

        while (step < stepLimit) {
            val output = StringBuilder()
            var emitted = 0
            var showDelta = true
            var failure: String? = null

            engines.generate(conversation, params).collect { event ->
                when (event) {
                    is GenEvent.Delta -> {
                        output.append(event.text)
                        if (showDelta) {
                            val marker = ToolCallParser.earliestStart(output.toString())
                            val end = if (marker >= 0) {
                                marker
                            } else {
                                output.length - ToolCallParser.holdSuffixLength(output.toString())
                            }
                            if (end > emitted) {
                                val piece = output.substring(emitted, end)
                                visible.append(piece)
                                this@flow.emit(AgentEvent.Delta(piece))
                                emitted = end
                            }
                            if (marker >= 0) showDelta = false
                        }
                    }
                    is GenEvent.Reasoning -> this@flow.emit(AgentEvent.Reasoning(event.text))
                    is GenEvent.Failed -> failure = event.message
                    GenEvent.Done -> Unit
                }
            }

            val error = failure
            if (error != null) {
                this@flow.emit(AgentEvent.Failed(error))
                return@flow
            }

            val call = ToolCallParser.parse(output.toString())
            if (call == null) {
                if (emitted < output.length) {
                    val tail = output.substring(emitted)
                    visible.append(tail)
                    this@flow.emit(AgentEvent.Delta(tail))
                }
                finished = true
                break
            }

            val tool = availableTools.firstOrNull { it.name == call.name }
            val permission = toolPermissions[call.name] ?: ToolPermission.ALLOW
            val intercept = if (tool != null) {
                pluginRuntime?.interceptTool(tool.name, tool.description, call.arguments.stringParameters())
            } else {
                null
            }
            val blocked = intercept?.blocked == true
            val startedAt = System.nanoTime()
            val result = when {
                tool == null -> "工具 ${call.name} 未启用或不存在"
                permission == ToolPermission.DENY -> "工具 ${call.name} 的权限为拒绝"
                blocked -> "工具 ${call.name} 被插件阻止：${intercept?.reason.orEmpty()}"
                else -> try {
                    tool.execute(call.arguments)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "工具执行失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000L

            val agentStep = AgentStep(
                tool = call.name,
                arguments = call.arguments.toString(),
                result = result.take(MAX_RESULT_CHARS),
                status = if (tool == null || permission == ToolPermission.DENY || blocked) {
                    StepStatus.ERROR
                } else {
                    StepStatus.OK
                },
                durationMs = durationMs,
            )
            this@flow.emit(AgentEvent.StepFinished(agentStep))

            conversation += PromptMessage(PromptMessage.ASSISTANT, output.toString())
            conversation += PromptMessage(PromptMessage.TOOL, "工具 ${call.name} 返回：\n$result")
            step++

            val separator = answerSeparator(visible)
            if (separator.isNotEmpty()) {
                visible.append(separator)
                this@flow.emit(AgentEvent.Delta(separator))
            }
        }

        if (!finished) {
            val note = "\n\n（已达到最多 $stepLimit 步工具调用）"
            visible.append(note)
            this@flow.emit(AgentEvent.Delta(note))
        }
        this@flow.emit(AgentEvent.Finished(cleanAnswer(visible.toString())))    }

    /** 模型文字与工具调用混在同一轮时，后续回答需要和上文拉开段落。 */
    private fun answerSeparator(visible: StringBuilder): String = when {
        visible.isEmpty() -> ""
        visible.endsWith("\n\n") -> ""
        visible.endsWith("\n") -> "\n"
        else -> "\n\n"
    }

    /** 兜底：模型偶尔会把未闭合的工具标签写进正文。 */
    private fun cleanAnswer(text: String): String = ToolCallParser.stripToolCalls(text)

    private fun systemPrompt(
        userSystemPrompt: String,
        availableTools: List<Tool>,
        planFirst: Boolean,
    ): String = buildString {
        appendLine("你是运行在 Android 上的本地 AI 助手。")
        appendLine("需要调用工具时，只输出一个工具调用块，不要附加解释：")
        appendLine("""<tool_call>{"name":"工具名","arguments":{...}}</tool_call>""")
        appendLine("不要使用 DSML、XML 或 <invoke> 标签。")
        appendLine("工具结果会以 tool 消息返回；拿到结果后直接用自然语言回答用户。")
        if (availableTools.isEmpty()) {
            appendLine("当前为对话模式，不要调用工具。")
        } else {
            appendLine("可用工具：")
            appendLine(availableTools.joinToString("\n") { "- ${it.name}: ${it.description}" })
            if (availableTools.any { it.name == "read_file" }) {
                appendLine("文件操作建议：先用 list_files / search_files 定位，再用 read_file 确认原文；" +
                    "局部修改用 edit_file，整文件重写用 write_file。")
            }
        }
        if (planFirst) {
            appendLine("调用工具前，先用一句话说明你打算做什么。")
        }
        if (userSystemPrompt.isNotBlank()) {
            appendLine()
            appendLine(userSystemPrompt)
        }
    }

    private companion object {
        const val MAX_RESULT_CHARS = 4000
    }

    private fun JsonObject.stringParameters(): Map<String, String> = entries.associate { (key, value) ->
        key to ((value as? JsonPrimitive)?.contentOrNull ?: value.toString())
    }
}
