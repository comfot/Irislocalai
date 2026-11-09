package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.motion.HastMotionStore
import com.iris.localai.motion.MotionBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 让 3D 角色（VRM/GLB）播放动作，走 MotionBridge → WebView 的 window.irisMotion。
 */
class MotionControlTool(private val clips: HastMotionStore) : Tool {

    override val name: String = "motion_control"

    override val description: String =
        "让 3D 角色（VRM/GLB）播放动作。必须带 arguments.action，调用示例：" +
            "<tool_call>{\"name\":\"motion_control\",\"arguments\":{\"action\":\"wave\"}}</tool_call>。" +
            "action 可选：idle（待机）、wave（挥手）、dance（跳舞），或 HAST-SEC 技能名（如 right-摆动手臂）。"

    override suspend fun execute(arguments: JsonObject): String {
        val raw = listOf("action", "motion", "gesture", "name", "动作", "名称")
            .asSequence()
            .mapNotNull { key -> arguments[key]?.jsonPrimitive?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        val requested = raw.ifBlank { "wave" }
        val alias = when (requested.lowercase()) {
            "idle", "待机", "stand" -> "idle"
            "wave", "挥手", "摆手", "打招呼" -> "right-摆动手臂"
            "dance", "跳舞" -> "body-自然活动"
            "nod", "点头" -> "body-自然活动"
            else -> raw
        }
        val catalog = runCatching { clips.catalog() }.getOrDefault(emptyList())
        val resolved = when {
            catalog.isEmpty() -> alias
            alias in catalog -> alias
            else -> catalog.firstOrNull { it.contains(alias) } ?: alias
        }
        MotionBridge.play(resolved)
        val hints = buildList {
            if (catalog.isEmpty()) add("动作库尚未就绪")
            if (!MotionBridge.hasActiveView()) add("当前没有显示中的 3D 角色，请切到生活空间")
            else if (!MotionBridge.hasMotionView()) add("当前 3D 视图未启用动作服务，请在 3D 控制里选择动作来源")
        }
        val suffix = if (hints.isEmpty()) "" else "（${hints.joinToString("；")}）"
        return "已向角色发送动作：$resolved$suffix"
    }
}
