package com.iris.moew.agent.tools

import com.iris.moew.agent.Tool
import com.iris.moew.agent.subagent.SubAgentManager
import com.iris.moew.agent.subagent.SubAgentStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 子代理派发工具：主代理用独立上下文执行一段调研、修改或审查任务。
 * 子代理串行执行，不能再次调用 task。
 */
class TaskTool(
    private val manager: SubAgentManager,
    private val store: SubAgentStore,
) : Tool {

    override val name: String = "task"

    override val description: String
        get() = buildString {
            appendLine("把独立子任务派给子代理执行。子代理看不到主会话历史，需要写清目标、已知信息和预期产出。")
            appendLine("参数：{\"agent\":\"builtin-explore\",\"prompt\":\"...\"}；agent 省略时用执行者。子代理串行运行，不能再次派发子代理。")
            appendLine("可用子代理：")
            store.definitions.value.forEach { definition ->
                appendLine("- ${definition.id}（${definition.name}，${definition.capabilities()}）：${definition.description}")
            }
        }

    override suspend fun execute(arguments: JsonObject): String = executeStreaming(arguments) { }

    override suspend fun executeStreaming(
        arguments: JsonObject,
        onProgress: suspend (String) -> Unit,
    ): String {
        val action = arguments.text("action")?.lowercase() ?: "run"
        if (action == "list") {
            return store.definitions.value.joinToString("\n") { definition ->
                "- ${definition.id}（${definition.name}，${definition.capabilities()}）：${definition.description}"
            }
        }
        if (action != "run" && action != "create" && action != "start") {
            return "不支持的 action：$action（可用 run/list）"
        }
        val prompt = arguments.text("prompt").orEmpty()
        if (prompt.isBlank()) return "缺少 prompt 参数（要交给子代理的完整任务说明）"
        val wanted = arguments.text("agent") ?: arguments.text("agent_id")
        val definition = if (wanted != null) {
            store.find(wanted) ?: return "找不到子代理：$wanted（可用：${
                store.definitions.value.joinToString("、") { it.id }
            }）"
        } else {
            store.find("builtin-worker") ?: return "没有可用的子代理"
        }
        val result = manager.run(definition, prompt) { progress ->
            onProgress(progress.takeLast(300))
        }
        return result.summarize().take(MAX_RESULT_CHARS)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key])?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_RESULT_CHARS = 12_000
    }
}
