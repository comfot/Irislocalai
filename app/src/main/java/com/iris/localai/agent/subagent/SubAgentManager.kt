package com.iris.localai.agent.subagent

import com.iris.localai.agent.AgentEvent
import com.iris.localai.agent.AgentLoop
import com.iris.localai.agent.ToolRegistry
import com.iris.localai.data.ModelProfileStore
import com.iris.localai.data.SettingsRepository
import com.iris.localai.data.Space
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.workspace.WorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 子代理运行时：用独立上下文跑一整轮 [AgentLoop]，工具按定义裁剪，禁止再派发 task。
 * 本地模型同一时间只能生成一路，因此所有子代理串行执行。
 */
class SubAgentManager(
    private val engines: EngineManager,
    private val tools: ToolRegistry,
    private val models: ModelProfileStore,
    private val settings: SettingsRepository,
    private val workspaces: WorkspaceStore,
) {

    private val mutex = Mutex()

    val isBusy: Boolean get() = mutex.isLocked

    suspend fun run(
        definition: SubAgentDefinition,
        task: String,
        onProgress: suspend (String) -> Unit = {},
    ): SubAgentRunResult {
        val prompt = task.trim()
        if (prompt.isEmpty()) {
            return SubAgentRunResult(
                agentId = definition.id,
                agentName = definition.name,
                text = "",
                steps = 0,
                durationMs = 0,
                error = "子任务不能为空",
            )
        }
        return try {
            mutex.withLock {
                withTimeoutOrNull(TIMEOUT_MS) { execute(definition, prompt, onProgress) }
                    ?: SubAgentRunResult(
                        agentId = definition.id,
                        agentName = definition.name,
                        text = "",
                        steps = 0,
                        durationMs = TIMEOUT_MS,
                        error = "子代理执行超时",
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SubAgentRunResult(
                agentId = definition.id,
                agentName = definition.name,
                text = "",
                steps = 0,
                durationMs = 0,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private suspend fun execute(
        definition: SubAgentDefinition,
        task: String,
        onProgress: suspend (String) -> Unit,
    ): SubAgentRunResult {
        val profile = models.resolve(
            space = Space.WORK,
            workspaceId = workspaces.active()?.id.orEmpty(),
        )
        if (!engines.ensureLoaded(profile)) {
            return SubAgentRunResult(
                agentId = definition.id,
                agentName = definition.name,
                text = "",
                steps = 0,
                durationMs = 0,
                error = "模型加载失败",
            )
        }
        val allowedNames = definition.effectiveTools(tools.all().map { it.name })
        if (allowedNames.isEmpty()) {
            return SubAgentRunResult(
                agentId = definition.id,
                agentName = definition.name,
                text = "",
                steps = 0,
                durationMs = 0,
                error = "子代理没有可用工具",
            )
        }
        val registry = ToolRegistry()
        tools.allWithSource().forEach { registered ->
            val tool = registered.tool
            if (tool.name in allowedNames && tool.name != SubAgentDefinition.NESTED_TOOL) {
                registry.register(tool, registered.source)
            }
        }

        val config = settings.current
        val params = SamplingConfig(
            temperature = config.temperature,
            topP = config.topP,
            topK = config.topK,
            maxNewTokens = config.maxNewTokens,
            minP = config.minP,
            repeatPenalty = config.repeatPenalty,
            repeatLastN = config.repeatLastN,
            presencePenalty = config.presencePenalty,
            frequencyPenalty = config.frequencyPenalty,
            seed = if (config.seedEnabled) config.seedValue else null,
            disableThinking = !config.deepThinking,
        )
        val loop = AgentLoop(
            engines = engines,
            tools = registry,
            maxSteps = MAX_STEPS,
        )
        val startedAt = System.currentTimeMillis()
        val output = StringBuilder()
        var lastProgressAt = 0L
        var finalText = ""
        var steps = 0
        var error: String? = null
        val systemPrompt = buildString {
            appendLine(definition.systemPrompt.trim())
            appendLine()
            appendLine("你是被主代理派发的子代理，看不到主会话历史。不要调用 task 再派发子代理；")
            appendLine("完成后用简洁的自然语言给出结论、涉及文件和验证结果。")
        }
        try {
            loop.run(
                userInput = task,
                history = emptyList(),
                params = params,
                userSystemPrompt = systemPrompt,
                enabledTools = allowedNames.toSet(),
                toolPermissions = config.toolPermissions,
                maxStepsOverride = MAX_STEPS,
                contextLength = config.contextLength,
            ).collect { event ->
                when (event) {
                    is AgentEvent.Delta -> {
                        output.append(event.text)
                        if (output.length > MAX_OUTPUT_CHARS) {
                            output.delete(0, output.length - MAX_OUTPUT_CHARS)
                        }
                        // 每 delta 全量 toString 拷贝在长输出下是 O(n²)；限制快照频率。
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                            lastProgressAt = now
                            onProgress(output.toString())
                        }
                    }
                    is AgentEvent.StepFinished -> {
                        steps++
                        onProgress("正在执行第 $steps 步：${event.step.tool}…")
                    }
                    is AgentEvent.ToolProgress -> onProgress(event.text)
                    is AgentEvent.Finished -> {
                        finalText = event.text
                        onProgress(event.text)
                    }
                    is AgentEvent.Failed -> error = event.message
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        val text = finalText.ifBlank { output.toString() }.trim()
        return SubAgentRunResult(
            agentId = definition.id,
            agentName = definition.name,
            text = text,
            steps = steps,
            durationMs = System.currentTimeMillis() - startedAt,
            error = error ?: if (text.isEmpty() && steps == 0) "子代理没有返回内容" else null,
        )
    }

    private companion object {
        const val MAX_STEPS = 15
        const val TIMEOUT_MS = 20 * 60 * 1000L
        const val MAX_OUTPUT_CHARS = 20_000
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
