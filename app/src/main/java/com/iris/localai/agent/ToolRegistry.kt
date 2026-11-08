package com.iris.localai.agent

/** 工具注册表：AgentLoop 按名字查找，提示词按注册顺序生成工具清单（顺序稳定利于 KV 缓存）。 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()

    @Synchronized
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    @Synchronized
    fun unregister(name: String) {
        tools.remove(name)
    }

    @Synchronized
    fun find(name: String): Tool? = tools[name]

    @Synchronized
    fun all(): List<Tool> = tools.values.toList()

    fun describe(): String = all().joinToString("\n") { tool ->
        "- ${tool.name}: ${tool.description}"
    }
}
