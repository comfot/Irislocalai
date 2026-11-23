package com.iris.moew.agent

enum class StepStatus { OK, ERROR }

/** 一次工具调用的记录，聊天页按卡片展示。 */
data class AgentStep(
    val tool: String,
    val arguments: String,
    val result: String,
    val status: StepStatus,
    val durationMs: Long = 0L,
    /** 本轮工具调用前的思考内容，按轮次展示在步骤卡片上方。 */
    val reasoning: String = "",
    /** 调用该工具之前助手已经输出的正文，穿插在思考与工具卡之间。 */
    val reply: String = "",
    val reasoningMs: Long = 0L,
    /** 执行中的中间进度；最终结果写入 result 后不再展示。 */
    val progress: String = "",
)

sealed interface AgentEvent {
    /** 可直接展示给用户的正文增量；工具调用块不会出现在这里。 */
    data class Delta(val text: String) : AgentEvent
    /** 从正文剥离出的表演标签，用于驱动表情、动作、镜头等。 */
    data class Performance(val tags: List<PerformanceTag>) : AgentEvent
    /** 云端模型返回的推理增量，与正文分开保存，界面上折叠展示。 */
    data class Reasoning(val text: String) : AgentEvent
    data class StepFinished(val step: AgentStep) : AgentEvent
    /** 工具执行中的中间进度，用于步骤卡片实时展示。 */
    data class ToolProgress(val tool: String, val text: String) : AgentEvent
    data class Finished(val text: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}
