package com.iris.localai.agent

enum class StepStatus { OK, ERROR }

/** 一次工具调用的记录，聊天页按卡片展示。 */
data class AgentStep(
    val tool: String,
    val arguments: String,
    val result: String,
    val status: StepStatus,
    val durationMs: Long = 0L,
)

sealed interface AgentEvent {
    /** 可直接展示给用户的正文增量；工具调用块不会出现在这里。 */
    data class Delta(val text: String) : AgentEvent
    /** 从正文剥离出的表演标签，用于驱动表情、动作、镜头等。 */
    data class Performance(val tags: List<PerformanceTag>) : AgentEvent
    /** 云端模型返回的推理增量，与正文分开保存，界面上折叠展示。 */
    data class Reasoning(val text: String) : AgentEvent
    data class StepFinished(val step: AgentStep) : AgentEvent
    data class Finished(val text: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}
