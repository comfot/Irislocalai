package com.iris.localai.agent.subagent

/** 子代理可用的工具范围；自定义子代理只做预设选择，避免误把任意工具给只读代理。 */
enum class SubAgentToolMode {
    READ_ONLY,
    WORKSPACE,
    ALL;

    fun allowedTools(): Set<String> = when (this) {
        READ_ONLY -> READ_ONLY_TOOLS
        WORKSPACE -> WORK_TOOLS
        ALL -> emptySet()
    }

    val label: String get() = when (this) {
        READ_ONLY -> "只读"
        WORKSPACE -> "开发"
        ALL -> "全部工具"
    }

    companion object {
        val READ_ONLY_TOOLS = setOf(
            "read_file", "list_files", "search_files", "fetch_url", "use_skill",
        )
        val WORK_TOOLS = READ_ONLY_TOOLS + setOf(
            "write_file", "edit_file", "workspace", "shell", "terminal",
        )
    }
}

/**
 * 一个子代理定义。子代理有独立上下文，看不到主会话历史；[allowedTools] 为空表示继承除
 * `task` 外的全部工具。
 */
data class SubAgentDefinition(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolMode: SubAgentToolMode = SubAgentToolMode.WORKSPACE,
    val allowedTools: Set<String> = toolMode.allowedTools(),
    val builtin: Boolean = false,
) {
    fun effectiveTools(parentTools: List<String>): List<String> {
        val base = if (allowedTools.isEmpty()) parentTools else parentTools.filter { it in allowedTools }
        return base.filterNot { it == NESTED_TOOL }
    }

    fun capabilities(): String = when (toolMode) {
        SubAgentToolMode.READ_ONLY -> "只读调研"
        SubAgentToolMode.WORKSPACE -> "可读写与执行命令"
        SubAgentToolMode.ALL -> "全部工具"
    }

    companion object {
        const val NESTED_TOOL = "task"

        val BUILTINS: List<SubAgentDefinition> = listOf(
            SubAgentDefinition(
                id = "builtin-explore",
                name = "探索者",
                description = "只读调研代码、定位实现和调用链，不改文件",
                systemPrompt = "你是只读探索子代理。快速定位相关文件与调用关系，给出文件路径、行号和结论；" +
                    "不要修改文件，也不要尝试执行命令。",
                toolMode = SubAgentToolMode.READ_ONLY,
                builtin = true,
            ),
            SubAgentDefinition(
                id = "builtin-worker",
                name = "执行者",
                description = "在独立上下文里完成边界清晰的修改并自测",
                systemPrompt = "你是执行子代理。按任务说明修改工作区文件，必要时运行命令验证；" +
                    "只做任务要求的事，完成后简要列出改动文件与验证结果。",
                toolMode = SubAgentToolMode.WORKSPACE,
                builtin = true,
            ),
            SubAgentDefinition(
                id = "builtin-reviewer",
                name = "审阅者",
                description = "独立审查改动，查找正确性、回归和遗漏",
                systemPrompt = "你是审阅子代理。根据任务给出的需求与文件路径独立审查，优先报告会导致错误行为的问题，" +
                    "说明依据（文件与行号）；不要修改文件。",
                toolMode = SubAgentToolMode.READ_ONLY,
                builtin = true,
            ),
        )
    }
}

data class SubAgentRunResult(
    val agentId: String,
    val agentName: String,
    val text: String,
    val steps: Int,
    val durationMs: Long,
    val error: String? = null,
) {
    val ok: Boolean get() = error.isNullOrBlank()

    fun summarize(): String = if (!ok) {
        "子代理「$agentName」失败：$error"
    } else {
        "子代理「$agentName」完成（$steps 步，${durationMs / 1000}s）\n$text"
    }
}
