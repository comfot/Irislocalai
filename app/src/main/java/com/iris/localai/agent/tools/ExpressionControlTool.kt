package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.character.CharacterModelStore
import com.iris.localai.character.CharacterModelType
import com.iris.localai.data.SettingsRepository
import com.iris.localai.motion.ExpressionBindingStore
import com.iris.localai.motion.MotionBridge
import com.iris.localai.motion.Model3DKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 独立的表情管理工具：按当前模型类型选择表情绑定，再发送给渲染层。
 */
class ExpressionControlTool(
    private val bindings: ExpressionBindingStore,
    private val characters: CharacterModelStore,
    private val settings: SettingsRepository,
) : Tool {

    override val name: String = "expression_control"

    override val description: String =
        "控制 3D 角色的表情，按当前模型类型（VRM/MMD）使用对应表情绑定。" +
            "必须带 arguments.action，调用示例：" +
            "<tool_call>{\"name\":\"expression_control\",\"arguments\":{\"action\":\"开心\"}}</tool_call>。" +
            "action 可为绑定别名（开心/难过/生气/惊讶/放松/思考）、实际表情名，或 list 查看绑定。"

    override suspend fun execute(arguments: JsonObject): String {
        val raw = listOf("action", "expression", "emotion", "mood", "name", "表情", "情绪", "名称")
            .asSequence()
            .mapNotNull { key -> arguments[key]?.jsonPrimitive?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        val requested = raw.ifBlank { "neutral" }
        val kind = currentKind()
        if (requested.equals("list", ignoreCase = true) || requested == "列表") {
            return buildString {
                appendLine("当前模型类型：${kind.label}")
                appendLine("表情绑定（别名 → 表情）：")
                bindings.bindings(kind).entries.take(30).forEach { (alias, expression) ->
                    appendLine("- $alias → $expression")
                }
                appendLine("可添加表情：${bindings.actions(kind).joinToString("、")}")
            }.trim()
        }
        val expression = bindings.resolve(kind, requested) ?: requested
        MotionBridge.playExpression(expression)
        val hint = when {
            !MotionBridge.hasActiveView() -> "（当前没有显示中的 3D 角色，请切到生活空间）"
            kind == Model3DKind.VRM && !MotionBridge.hasMotionView() -> "（当前不是 VRM/GLB 模型）"
            kind == Model3DKind.MMD && !MotionBridge.hasMmdView() -> "（当前不是 MMD 模型）"
            else -> ""
        }
        return "已发送表情：$expression$hint"
    }

    /** 与生活空间一致：角色卡绑定的模型优先，其次全局激活项，最后看舞台上注册的视图。 */
    private fun currentKind(): Model3DKind =
        characters.boundModel(settings.current.characterId)?.type.toKind()
            ?: characters.active()?.type.toKind()
            ?: MotionBridge.activeKind()
            ?: Model3DKind.VRM

    private fun CharacterModelType?.toKind(): Model3DKind? = when (this) {
        CharacterModelType.MMD -> Model3DKind.MMD
        CharacterModelType.VRM, CharacterModelType.GLB -> Model3DKind.VRM
        else -> null
    }
}
