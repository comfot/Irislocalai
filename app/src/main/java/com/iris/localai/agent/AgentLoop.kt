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
 * 工具结果以 tool 消息回填后再问一轮；[maxSteps] 为 0 时不限制步数，否则最多该步数。
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
        startInThink: Boolean = false,
    ): Flow<AgentEvent> = flow flow@ {
        val availableTools = tools.all().filter { enabledTools == null || it.name in enabledTools }
        val configuredLimit = maxStepsOverride ?: maxSteps
        val stepLimit = if (configuredLimit <= 0) null else configuredLimit.coerceIn(1, 100)
        val conversation = buildList {
            add(PromptMessage(PromptMessage.SYSTEM, systemPrompt(userSystemPrompt, availableTools, planFirst)))
            addAll(history)
            add(PromptMessage(PromptMessage.USER, userInput, userImages))
        }.toMutableList()

        val visible = StringBuilder()
        val tagParser = PerformanceTagParser()
        val thinkFilter = ThinkTagFilter(startInThink)
        var step = 0
        var finished = false
        var unclosedRetried = false
        var bareCommandRetried = false
        var bareCommandExecuted = false

        suspend fun emitTagged(text: String) {
            if (text.isEmpty()) return
            val feed = tagParser.feed(text)
            if (feed.text.isNotEmpty()) {
                visible.append(feed.text)
                this@flow.emit(AgentEvent.Delta(feed.text))
            }
            if (feed.tags.isNotEmpty()) {
                this@flow.emit(AgentEvent.Performance(feed.tags))
            }
        }

        suspend fun emitBody(text: String) {
            if (text.isEmpty()) return
            emitTagged(text)
        }

        while (stepLimit == null || step < stepLimit) {
            thinkFilter.beginTurn(startInThink)
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
                                val thought = thinkFilter.feed(piece)
                                if (thought.reasoning.isNotEmpty()) {
                                    this@flow.emit(AgentEvent.Reasoning(thought.reasoning))
                                }
                                if (thought.text.isNotEmpty()) {
                                    emitBody(thought.text)
                                }
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

            var call = ToolCallParser.parse(output.toString())
            if (call == null) {
                val text = output.toString()
                if (!unclosedRetried && ToolCallParser.hasUnclosedToolCall(text)) {
                    unclosedRetried = true
                    conversation += PromptMessage(PromptMessage.ASSISTANT, text)
                    conversation += PromptMessage(
                        PromptMessage.USER,
                        "上一条回复的工具调用没有闭合，通常是被输出长度截断。请重新输出一个完整的 " +
                            "<tool_call>{\"name\":\"工具名\",\"arguments\":{...}}</tool_call>，不要输出其他内容。",
                    )
                    continue
                }
                val bareCommand = ToolCallParser.bareShellCommand(text)
                if (!bareCommandRetried && bareCommand != null) {
                    bareCommandRetried = true
                    conversation += PromptMessage(PromptMessage.ASSISTANT, text)
                    conversation += PromptMessage(
                        PromptMessage.USER,
                        "请把上面的命令改用 shell 工具执行：只输出一个 " +
                            "<tool_call>{\"name\":\"shell\",\"arguments\":{\"command\":\"…\"}}</tool_call>，不要输出其他内容。",
                    )
                    continue
                }
                if (emitted < output.length) {
                    val tail = output.substring(emitted)
                    val thought = thinkFilter.feed(tail)
                    if (thought.reasoning.isNotEmpty()) {
                        this@flow.emit(AgentEvent.Reasoning(thought.reasoning))
                    }
                    if (thought.text.isNotEmpty()) {
                        emitBody(thought.text)
                    }
                }
                if (!bareCommandExecuted && bareCommand != null &&
                    ToolCallParser.isStrongShellCommand(bareCommand)
                ) {
                    val shell = availableTools.firstOrNull { it.name == "shell" }
                    val shellPermission = toolPermissions["shell"] ?: ToolPermission.ALLOW
                    if (shell != null && shellPermission != ToolPermission.DENY) {
                        bareCommandExecuted = true
                        call = ParsedToolCall(
                            name = "shell",
                            arguments = JsonObject(mapOf("command" to JsonPrimitive(bareCommand))),
                        )
                        val note = "\n\n（检测到命令写在正文里，已自动改用 shell 工具执行。）"
                        visible.append(note)
                        this@flow.emit(AgentEvent.Delta(note))
                    }
                }
                if (call == null) {
                    if (bareCommand != null) {
                        val note = "\n\n（检测到命令写在正文里，没有作为工具调用执行。需要执行时请重试。）"
                        visible.append(note)
                        this@flow.emit(AgentEvent.Delta(note))
                    }
                    finished = true
                    break
                }
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
            var toolFailed = false
            val result = when {
                tool == null -> "工具 ${call.name} 未启用或不存在"
                permission == ToolPermission.DENY -> "工具 ${call.name} 的权限为拒绝"
                blocked -> "工具 ${call.name} 被插件阻止：${intercept?.reason.orEmpty()}"
                else -> try {
                    tool.execute(call.arguments)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toolFailed = true
                    "工具执行失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000L

            val agentStep = AgentStep(
                tool = call.name,
                arguments = call.arguments.toString(),
                result = result.take(MAX_RESULT_CHARS),
                status = if (tool == null || permission == ToolPermission.DENY || blocked || toolFailed) {
                    StepStatus.ERROR
                } else {
                    StepStatus.OK
                },
                durationMs = durationMs,
            )
            this@flow.emit(AgentEvent.StepFinished(agentStep))

            val stepThought = thinkFilter.flush()
            if (stepThought.reasoning.isNotEmpty()) {
                this@flow.emit(AgentEvent.Reasoning(stepThought.reasoning))
            }
            if (stepThought.text.isNotEmpty()) {
                emitBody(stepThought.text)
            }

            conversation += PromptMessage(PromptMessage.ASSISTANT, output.toString())
            conversation += PromptMessage(PromptMessage.TOOL, "工具 ${call.name} 返回：\n$result")
            step++

            val separator = answerSeparator(visible)
            if (separator.isNotEmpty()) {
                visible.append(separator)
                this@flow.emit(AgentEvent.Delta(separator))
            }
        }

        if (!finished && stepLimit != null) {
            val note = "\n\n（已达到最多 $stepLimit 步工具调用）"
            visible.append(note)
            this@flow.emit(AgentEvent.Delta(note))
        }
        val thinkFlush = thinkFilter.flush()
        if (thinkFlush.reasoning.isNotEmpty()) {
            this@flow.emit(AgentEvent.Reasoning(thinkFlush.reasoning))
        }
        emitTagged(thinkFlush.text)
        val flushed = tagParser.flush()
        if (flushed.text.isNotEmpty()) {
            visible.append(flushed.text)
            this@flow.emit(AgentEvent.Delta(flushed.text))
        }
        if (flushed.tags.isNotEmpty()) {
            this@flow.emit(AgentEvent.Performance(flushed.tags))
        }
        val answer = visible.toString()
        val finalText = if (answer.isBlank() && thinkFilter.hasThought()) {
            "（模型只返回了思考过程，没有生成回答。请关闭「深度思考」或增大最大输出后重试。）"
        } else {
            cleanAnswer(answer)
        }
        this@flow.emit(AgentEvent.Finished(finalText))    }

    /** 模型文字与工具调用混在同一轮时，后续回答需要和上文拉开段落。 */
    private fun answerSeparator(visible: StringBuilder): String = when {
        visible.isEmpty() -> ""
        visible.endsWith("\n\n") -> ""
        visible.endsWith("\n") -> "\n"
        else -> "\n\n"
    }

    /** 兜底：模型偶尔会把未闭合的工具标签写进正文。 */
    private fun cleanAnswer(text: String): String =
        ToolCallParser.stripToolCalls(text)

    private fun systemPrompt(
        userSystemPrompt: String,
        availableTools: List<Tool>,
        planFirst: Boolean,
    ): String = buildString {
        appendLine("你是运行在 Android 上的本地 AI 助手。")
        appendLine("需要调用工具时，只输出一个工具调用块，不要附加解释：")
        appendLine("""<tool_call>{"name":"工具名","arguments":{...}}</tool_call>""")
        appendLine("除上述工具调用块外，不要使用 DSML、<invoke> 或其他标签。")
        appendLine("shell 等工具的命令必须放进上面的工具调用块；不要把命令只写在正文或代码块里，那样不会执行。")
        appendLine("不要在正文输出 <think>、</think> 或 Thinking Process 等思考标记。")
        appendLine("如需角色状态卡，可使用 <maintext>正文</maintext> 和 <Status_block>...</Status_block>，状态项写成“- 键：值”；不要使用其他 HTML/XML 标签。")
        appendLine("工具结果会以 tool 消息返回；拿到结果后直接用自然语言回答用户。")
        if (availableTools.isEmpty()) {
            appendLine("当前为对话模式，不要调用工具。")
        } else {
            appendLine("可用工具：")
            appendLine(availableTools.joinToString("\n") { "- ${it.name}: ${it.description}" })
            if (availableTools.any { it.name == "motion_control" }) {
                appendLine("当用户要求角色做动作、比手势、跳舞、打招呼时，调用 motion_control，不要声称自己无法控制模型。")
                appendLine("调用时必须带 arguments.action，例如 {\"action\":\"wave\"}；不确定有哪些动作时先传 action=list 查看完整清单。")
            }
            if (availableTools.any { it.name == "memory" }) {
                appendLine("用户提到“回忆”“上次”“之前”“曾经”等需要精确回忆的内容时，调用 memory 工具 op=recall；常规对话已经带有记忆摘要，不要主动展开所有记忆。")
            }
            if (availableTools.any { it.name == "read_file" }) {
                appendLine("文件操作建议：先用 list_files / search_files 定位，再用 read_file 确认原文；" +
                    "局部修改用 edit_file，整文件重写用 write_file。")
            }
            if (availableTools.any { it.name == "device_ui" }) {
                appendLine(
                    "操作空间流程：先用 device_ui 读取当前界面（主屏或虚拟屏）的控件树；device_screenshot 只返回文件路径，" +
                        "不能用来读取界面文字或价格。device_ui 返回 mode=ocr 时，用 lines 里的 text 判断内容，用 cx/cy 调 device_tap 点击。" +
                        "优先用 device_tap_text 按文字/描述点按，找不到目标时用 device_scroll 滚动查找；" +
                        "每次只做一个动作，操作后用 device_wait_text 或 device_ui 校验结果；不要连续盲点坐标，也不要一次调用多个操作工具。" +
                        "若 device_ui 同时没有控件树和 OCR 文字，先提醒用户检查无障碍/截图权限。",
                )
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
