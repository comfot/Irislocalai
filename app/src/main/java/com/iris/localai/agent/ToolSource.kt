package com.iris.localai.agent

/** 工具来源，仅用于设置页展示与归属说明，不参与工具名或权限主键。 */
sealed interface ToolSource {
    val label: String

    data object Builtin : ToolSource {
        override val label: String = "内置"
    }

    data class Plugin(
        val pluginId: String,
        val pluginName: String,
        val subpackageId: String = "",
    ) : ToolSource {
        override val label: String get() = "插件：${pluginName.ifBlank { pluginId }}"
    }

    data class Mcp(
        val serverId: String = "",
        val serverName: String = "",
    ) : ToolSource {
        override val label: String get() = "MCP" + serverName.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
    }
}

data class RegisteredTool(
    val tool: Tool,
    val source: ToolSource,
)
