package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 最小可用工具，演示 AgentLoop 的“模型请求 → 执行 → 结果回填”闭环。 */
class CurrentTimeTool : Tool {

    override val name: String = "current_time"
    override val description: String = "返回设备当前日期和时间，参数为空对象"

    override suspend fun execute(arguments: JsonObject): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
