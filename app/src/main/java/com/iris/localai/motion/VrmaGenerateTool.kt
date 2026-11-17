package com.iris.localai.motion

import com.iris.localai.agent.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 生活空间插件调用：用 Text-To-VRMA 生成动作，并立即在当前舞台播放。 */
class VrmaGenerateTool(
    private val environment: VrmaGenerationEnvironment,
    private val store: VrmaMotionStore,
    private val pluginEnabled: () -> Boolean,
) : Tool {

    override val name: String = "vrma_generate"

    override val description: String =
        "根据动作描述生成 VRMA 动作并播放。参数 prompt 为动作描述，可选 duration 为 1-15 秒。"

    override suspend fun execute(arguments: JsonObject): String {
        if (!pluginEnabled()) error("VRMA动作生成插件未启用")
        val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isBlank()) error("缺少 prompt 动作描述")
        val duration = arguments["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val asset = environment.generate(prompt, duration)
        MotionBridge.playVrma(store.virtualUrl(asset), holdIdle = asset.name.contains("坐"))
        return "已生成 VRMA 动作：${asset.name}"
    }
}
