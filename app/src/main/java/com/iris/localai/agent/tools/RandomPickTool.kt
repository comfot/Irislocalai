package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class RandomPickTool : Tool {

    override val name: String = "random_pick"
    override val description: String = "从 options 数组中随机选一项，参数示例：{\"options\":[\"A\",\"B\"]}"

    override suspend fun execute(arguments: JsonObject): String {
        val options = (arguments["options"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (options.isEmpty()) {
            return "请提供非空的 options 数组"
        }
        return options.random()
    }
}
