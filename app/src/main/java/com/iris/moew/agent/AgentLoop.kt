package com.iris.moew.agent

import com.iris.moew.engine.EngineManager
import com.iris.moew.engine.GenEvent
import com.iris.moew.engine.PromptMessage
import com.iris.moew.engine.SamplingConfig
import com.iris.moew.data.ToolPermission
import com.iris.moew.plugin.PluginRuntime
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
    private val confirmation: ToolConfirmation? = null,
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
        planMode: Boolean = false,
        startInThink: Boolean = false,
        chatId: Long? = null,
        contextLength: Int = 0,
    ): Flow<AgentEvent> = flow flow@ {
        val availableTools = tools.all().filter { enabledTools == null || it.name in enabledTools }
        val toolNames = availableTools.map { it.name }.toSet()
        val toolPromptOverride = pluginRuntime?.composeToolPrompt(chatId, availableTools, planFirst)
        val baseSystemPrompt = systemPrompt(userSystemPrompt, availableTools, planFirst, planMode, toolPromptOverride)
        val finalSystemPrompt = pluginRuntime?.finalizePrompt(chatId, baseSystemPrompt) ?: baseSystemPrompt
        val estimatedSystemPrompt = pluginRuntime?.estimateFinalPrompt(chatId, finalSystemPrompt) ?: finalSystemPrompt
        val configuredLimit = maxStepsOverride ?: maxSteps
        val stepLimit = if (configuredLimit <= 0) null else configuredLimit.coerceIn(1, 100)
        val userMessage = PromptMessage(PromptMessage.USER, userInput, userImages)
        val trimmedHistory = trimHistoryForContext(
            history = history,
            systemPrompt = estimatedSystemPrompt,
            userMessage = userMessage,
            maxNewTokens = params.maxNewTokens,
            contextLength = contextLength,
        )
        val conversation = buildList {
            add(PromptMessage(PromptMessage.SYSTEM, estimatedSystemPrompt))
            if (trimmedHistory.size < history.size) {
                add(
                    PromptMessage(
                        PromptMessage.SYSTEM,
                        "【提示】较早的 ${history.size - trimmedHistory.size} 条历史消息因上下文长度限制已省略。",
                    ),
                )
            }
            addAll(trimmedHistory)
            add(userMessage)
        }.toMutableList()

        val visible = StringBuilder()
        val tagParser = PerformanceTagParser()
        val thinkFilter = ThinkTagFilter(startInThink)
        var step = 0
        var finished = false
        var unclosedRetries = 0
        var bareCommandRetries = 0
        var bareCommandExecuted = false
        var malformedRetries = 0
        var malformedSeen = false
        // 工具调用被输出长度截断时，原上限重试很容易二次截断；只在重试时临时放大。
        var currentParams = withWindowScaledOutput(params, contextLength)
        // 同名工具连续失败计数：达到阈值后在回填消息中提醒模型换方法，避免无限步空转。
        val failureStreaks = mutableMapOf<String, Int>()
        // 上一段正文的起点：工具调用把整轮回复切成多段，Finished 只返回最后一段。
        var segmentStart = 0

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

        while (stepLimit == null || step < stepLimit) {
            thinkFilter.beginTurn(startInThink)
            val output = StringBuilder()
            var scanFrom = 0
            var emitted = 0
            var showDelta = true
            var failure: String? = null

            engines.generate(conversation, currentParams).collect { event ->
                when (event) {
                    is GenEvent.Delta -> {
                        output.append(event.text)
                        if (showDelta) {
                            val marker = ToolCallParser.earliestStartFrom(
                                output,
                                (scanFrom - ToolCallParser.SCAN_LOOKBACK).coerceAtLeast(0),
                            )
                            val end = if (marker >= 0) {
                                marker
                            } else {
                                scanFrom = output.length
                                output.length - ToolCallParser.holdSuffixLength(output)
                            }
                            if (end > emitted) {
                                val piece = output.substring(emitted, end)
                                val thought = thinkFilter.feed(piece)
                                if (thought.reasoning.isNotEmpty()) {
                                    this@flow.emit(AgentEvent.Reasoning(thought.reasoning))
                                }
                                if (thought.text.isNotEmpty()) {
                                    emitTagged(thought.text)
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
            if (call != null && call.loose && call.name !in toolNames) {
                // 宽松解析出的只是一段普通 JSON（例如回答里的示例），不当作工具调用。
                malformedSeen = true
                call = null
            }
            if (call == null) {
                val text = output.toString()
                if (unclosedRetries < MAX_RETRIES && ToolCallParser.hasUnclosedToolCall(text)) {
                    unclosedRetries++
                    currentParams = expandedOutputParams(currentParams, contextLength)
                    conversation += PromptMessage(PromptMessage.ASSISTANT, text)
                    conversation += PromptMessage(
                        PromptMessage.USER,
                        "上一条回复的工具调用没有闭合，通常是被输出长度截断。请重新输出一个完整的 " +
                            "<tool_call>{\"name\":\"工具名\",\"arguments\":{...}}</tool_call>，不要输出其他内容。",
                    )
                    continue
                }
                if (malformedRetries < MALFORMED_RETRIES && ToolCallParser.looksLikeMalformedToolCall(text)) {
                    malformedRetries++
                    malformedSeen = true
                    currentParams = expandedOutputParams(currentParams, contextLength)
                    conversation += PromptMessage(PromptMessage.ASSISTANT, text)
                    conversation += PromptMessage(
                        PromptMessage.USER,
                        "上一条回复的工具调用格式不正确，无法执行。请只输出一个标准工具调用块：" +
                            "<tool_call>{\"name\":\"工具名\",\"arguments\":{...}}</tool_call>；" +
                            "不要用 ``` 代码块、裸 JSON、XML 参数或其他标签，也不要附加解释。",
                    )
                    continue
                }
                val bareCommand = ToolCallParser.bareShellCommand(text)
                if (bareCommandRetries < MAX_RETRIES && bareCommand != null) {
                    bareCommandRetries++
                    conversation += PromptMessage(PromptMessage.ASSISTANT, text)
                    conversation += PromptMessage(
                        PromptMessage.USER,
                        "请把上面的命令改用 shell 工具执行：只输出一个 " +
                            "<tool_call>{\"name\":\"shell\",\"arguments\":{\"command\":\"…\"}}</tool_call>，不要输出其他内容。",
                    )
                    continue
                }
                // 正文已展示到 marker 处，尾巴只可能是残缺的工具调用；
                // 重试次数用尽时不把半截标签和 JSON 冲进正文，直接丢弃。
                if (emitted < output.length && !ToolCallParser.hasUnclosedToolCall(text)) {
                    val tail = output.substring(emitted)
                    val thought = thinkFilter.feed(tail)
                    if (thought.reasoning.isNotEmpty()) {
                        this@flow.emit(AgentEvent.Reasoning(thought.reasoning))
                    }
                    if (thought.text.isNotEmpty()) {
                        emitTagged(thought.text)
                    }
                }
                if (!bareCommandExecuted && bareCommand != null &&
                    ToolCallParser.isStrongShellCommand(bareCommand)
                ) {
                    val shell = availableTools.firstOrNull { it.name == "shell" }
                    val shellPermission = toolPermissions["shell"] ?: ToolPermission.ALLOW
                    if (shell != null && shellPermission == ToolPermission.ALLOW) {
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
                    } else if (malformedSeen) {
                        val note = "\n\n（工具调用格式有误，未执行。请让模型按 <tool_call>{\"name\":...}</tool_call> 重新输出。）"
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
            val confirmed = if (tool != null && permission == ToolPermission.ASK && !blocked) {
                confirmation?.await(call.name, call.arguments.toString()) == true
            } else {
                true
            }
            val executed = tool != null && !blocked &&
                (permission == ToolPermission.ALLOW || (permission == ToolPermission.ASK && confirmed))
            if (executed) {
                this@flow.emit(AgentEvent.ToolProgress(tool.name, "正在执行…"))
                pluginRuntime?.notifyToolLifecycle(
                    event = "tool_execution_started",
                    toolName = tool.name,
                    parameters = call.arguments.stringParameters(),
                    description = tool.description,
                )
            }
            val startedAt = System.nanoTime()
            var toolFailed = false
            val result = when {
                tool == null -> "[ERROR] 工具 ${call.name} 未启用或不存在"
                permission == ToolPermission.DENY -> "[ERROR] 工具 ${call.name} 的权限为拒绝"
                permission == ToolPermission.ASK && !confirmed ->
                    if (confirmation == null) {
                        "[ERROR] 工具 ${call.name} 需要用户确认后才能执行（当前没有确认界面，已阻止）"
                    } else {
                        "[ERROR] 用户拒绝了工具 ${call.name} 的执行"
                    }
                blocked -> "[ERROR] 工具 ${call.name} 被插件阻止：${intercept.reason.orEmpty()}"
                else -> try {
                    tool.executeStreaming(call.arguments) { progress ->
                        if (progress.isNotBlank()) {
                            this@flow.emit(AgentEvent.ToolProgress(tool.name, progress.takeLast(300)))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toolFailed = true
                    val detail = e.message?.takeIf { it.isNotBlank() }
                    "[ERROR] 工具执行失败：${e.javaClass.simpleName}${detail?.let { ": $it" }.orEmpty()}"
                }
            }
            // 未执行成功（含异常）都算失败结果，计入连续失败；成功则清零。
            val outcomeFailed = !executed || toolFailed
            val streak = if (outcomeFailed) {
                val current = (failureStreaks[call.name] ?: 0) + 1
                failureStreaks[call.name] = current
                current
            } else {
                failureStreaks.remove(call.name)
                0
            }
            val streakHint = if (streak >= FAILURE_STREAK_HINT) {
                "\n[提示] 工具 ${call.name} 已连续失败 $streak 次：请修正参数、换一种方法或向用户说明，不要以相同参数重试。"
            } else {
                ""
            }
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000L
            if (executed) {
                pluginRuntime?.notifyToolLifecycle(
                    event = if (toolFailed) "tool_execution_error" else "tool_execution_result",
                    toolName = tool.name,
                    parameters = call.arguments.stringParameters(),
                    description = tool.description,
                    success = !toolFailed,
                    resultText = if (toolFailed) null else result,
                    errorMessage = if (toolFailed) result else null,
                )
                pluginRuntime?.notifyToolLifecycle(
                    event = "tool_execution_finished",
                    toolName = tool.name,
                    parameters = call.arguments.stringParameters(),
                    description = tool.description,
                    success = !toolFailed,
                )
            }

            val agentStep = AgentStep(
                tool = call.name,
                arguments = call.arguments.toString(),
                result = displayResult(result),
                status = if (tool == null || permission == ToolPermission.DENY || blocked || toolFailed ||
                    (permission == ToolPermission.ASK && !confirmed)
                ) {
                    StepStatus.ERROR
                } else {
                    StepStatus.OK
                },
                durationMs = durationMs,
                reply = cleanAnswer(visible.substring(segmentStart.coerceIn(0, visible.length)), toolNames).trim(),
            )
            this@flow.emit(AgentEvent.StepFinished(agentStep))

            val stepThought = thinkFilter.flush()
            if (stepThought.reasoning.isNotEmpty()) {
                this@flow.emit(AgentEvent.Reasoning(stepThought.reasoning))
            }
            if (stepThought.text.isNotEmpty()) {
                emitTagged(stepThought.text)
            }
            segmentStart = visible.length

            conversation += PromptMessage(PromptMessage.ASSISTANT, output.toString())
            conversation += PromptMessage(
                PromptMessage.TOOL,
                "工具 ${call.name} 返回：\n${promptResult(result)}$streakHint",
            )
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
        val answer = visible.toString().substring(segmentStart.coerceIn(0, visible.length))
        val finalText = if (answer.isBlank() && thinkFilter.hasThought()) {
            "（模型只返回了思考过程，没有生成回答。请关闭「深度思考」或增大最大输出后重试。）"
        } else {
            cleanAnswer(answer, toolNames)
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
    private fun cleanAnswer(text: String, knownTools: Set<String>): String =
        ToolCallParser.stripToolCalls(text, knownTools)

    private fun trimHistoryForContext(
        history: List<PromptMessage>,
        systemPrompt: String,
        userMessage: PromptMessage,
        maxNewTokens: Int,
        contextLength: Int,
    ): List<PromptMessage> {
        if (contextLength <= 0 || history.isEmpty()) return history
        val reserved = estimatePromptTokens(systemPrompt) + userMessage.tokenCost() +
            maxNewTokens.coerceAtMost(contextLength / 2) + CONTEXT_SAFETY_TOKENS
        val budget = contextLength - reserved
        if (budget <= 0) return trimPromptHistory(history, (contextLength / 4).coerceAtLeast(256))
        return trimPromptHistory(history, budget)
    }

    /** 重试时放大输出上限；上限受上下文长度约束，避免挤压 prompt。 */
    private fun expandedOutputParams(params: SamplingConfig, contextLength: Int): SamplingConfig {
        val ceiling = if (contextLength > 0) {
            (contextLength / 2).coerceAtMost(MAX_RETRY_OUTPUT_TOKENS).coerceAtLeast(256)
        } else {
            MAX_RETRY_OUTPUT_TOKENS
        }
        val target = (params.maxNewTokens * 2).coerceAtMost(ceiling)
        return if (target > params.maxNewTokens) params.copy(maxNewTokens = target) else params
    }

    /**
     * 输出上限随上下文窗口放大（窗口 3/8，限 1536–8192）：默认 512 的全局上限写
     * 大文件经常被截断成半截 JSON；用户配置的更大值保持不变。
     */
    private fun withWindowScaledOutput(params: SamplingConfig, contextLength: Int): SamplingConfig {
        if (contextLength <= 0) return params
        val windowCap = ((contextLength * 3) / 8).coerceIn(1536, 8192)
        val target = maxOf(params.maxNewTokens, windowCap)
        return if (target > params.maxNewTokens) params.copy(maxNewTokens = target) else params
    }

    private fun systemPrompt(
        userSystemPrompt: String,
        availableTools: List<Tool>,
        planFirst: Boolean,
        planMode: Boolean = false,
        toolPromptOverride: String? = null,
    ): String = buildString {
        appendLine("你是运行在 Android 上的本地 AI 助手。")
        appendLine("需要调用工具时，只输出一个工具调用块，不要附加解释：")
        appendLine("""<tool_call>{"name":"工具名","arguments":{...}}</tool_call>""")
        appendLine("除上述工具调用块外，不要使用 DSML、<invoke> 或其他标签。")
        appendLine("shell 等工具的命令必须放进上面的工具调用块；不要把命令只写在正文或代码块里，那样不会执行。")
        appendLine("不要在正文输出 <think>、</think> 或 Thinking Process 等思考标记。")
        appendLine("如需角色状态卡，可使用 <maintext>正文</maintext> 和 <Status_block>...</Status_block>，状态项写成“- 键：值”；除上述标签以及后续提示词明确允许的标签外，不要使用其他 HTML/XML 标签。")
        appendLine("工具结果会以 tool 消息返回；拿到结果后直接用自然语言回答用户。")
        if (availableTools.isEmpty()) {
            appendLine("当前为对话模式，不要调用工具。")
        } else {
            appendLine("可用工具：")
            appendLine(toolPromptOverride?.takeIf { it.isNotBlank() }
                ?: availableTools.joinToString("\n") { "- ${it.name}: ${it.description}" })
            if (availableTools.any { it.name == "motion_control" }) {
                appendLine("当用户要求角色做动作、比手势、跳舞、打招呼时，调用 motion_control，不要声称自己无法控制模型。")
                appendLine("调用时必须带 arguments.action，例如 {\"action\":\"wave\"}；不确定有哪些动作时先传 action=list 查看完整清单。")
            }
            if (availableTools.any { it.name == "terminal" }) {
                appendLine(
                    "耗时任务、构建、转写或需要持续运行的服务用 terminal 工具 action=start 启动常驻会话，" +
                        "再用 action=read 查看输出、action=stop 结束；不要在 shell 命令里用 nohup 或结尾 &。",
                )
            }
            if (availableTools.any { it.name == "task" }) {
                appendLine(
                    "需要独立上下文的调研、边界清晰的修改或复核时，用 task 工具派发子代理；" +
                        "子代理看不到主会话历史，prompt 要写全目标、已知信息与预期产出。子代理串行执行，不能嵌套。",
                )
            }
            if (availableTools.any { it.name == "plan" }) {
            appendLine(
                "多步骤任务先用 plan op=create 列出计划（title + steps）；开始/完成步骤用 plan op=update，" +
                    "用户取消或放弃任务时用 plan op=cancel；不要只在正文里列计划而不登记。",
            )
        }
        if (availableTools.any { it.name == "memory" }) {
                appendLine("用户提到“回忆”“上次”“之前”“曾经”等需要精确回忆的内容时，调用 memory 工具 op=recall；常规对话已经带有记忆摘要，不要主动展开所有记忆。")
            }
            if (availableTools.any { it.name == "read_file" }) {
                appendLine("读取应用自身目录（插件、技能、工作区等）时，read_file/write_file/list_files 的 path 用 app://private/... 或 app://external/...。")
                appendLine("PRoot 中可直接访问：/mnt/iris-private（应用私有）、/mnt/skills、/mnt/plugins、/mnt/attachments、/mnt/iris（外置 IRIS）、/workspace。")
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
        if (planMode) {
            appendLine("当前处于计划模式：只做只读探索与方案设计，禁止调用写入、删除、执行命令、修改工作区或其他会产生副作用的工具。")
            appendLine("先输出一份完整计划（目标、步骤、涉及文件/命令、风险），等待用户批准；不要声称已经执行。用户批准后会退出计划模式再执行。")
        }
        if (userSystemPrompt.isNotBlank()) {
            appendLine()
            appendLine(userSystemPrompt)
        }
    }

    private companion object {
        const val MAX_RESULT_CHARS = 20_000
        const val MAX_PROMPT_RESULT_CHARS = 6_000
        /** 未闭合工具调用与裸命令的重发请求上限；与 maxSteps 相互独立，防止无限重试。 */
        const val MAX_RETRIES = 2
        /** 工具调用格式错误（代码块/裸 JSON/参数标签写错）时的纠正次数。 */
        const val MALFORMED_RETRIES = 1
        /** 重试时输出上限最多放大到该值；最终仍受 contextLength/2 约束。 */
        const val MAX_RETRY_OUTPUT_TOKENS = 2048
        /** 给 chat 模板、BOS/EOS 与工具回填预留的余量。 */
        const val CONTEXT_SAFETY_TOKENS = 256
        /** 同名工具连续失败达到该次数后，在回填消息中提醒模型换方法。 */
        const val FAILURE_STREAK_HINT = 3
    }

    /** 回填给模型的工具结果需要限长：本地 4k 上下文中一条 2 万字符输出会挤掉系统提示与历史。 */
    private fun promptResult(result: String): String = truncateToolResult(result, MAX_PROMPT_RESULT_CHARS)

    private fun displayResult(result: String): String =
        if (result.length > MAX_RESULT_CHARS) {
            result.take(MAX_RESULT_CHARS) +
                "\n…（工具输出过长，仅显示前 $MAX_RESULT_CHARS 字符）"
        } else {
            result
        }

    private fun JsonObject.stringParameters(): Map<String, String> = entries.associate { (key, value) ->
        key to ((value as? JsonPrimitive)?.contentOrNull ?: value.toString())
    }
}

/** 工具结果回填限长：保留头尾，尾部常有错误信息与汇总。 */
internal fun truncateToolResult(result: String, limit: Int): String {
    if (result.length <= limit) return result
    val head = limit * 3 / 4
    val tail = limit - head
    val omitted = result.length - head - tail
    return result.take(head) +
        "\n…（中间省略 $omitted 字符，需要更多请缩小读取范围或分段读取）…" +
        result.takeLast(tail)
}
