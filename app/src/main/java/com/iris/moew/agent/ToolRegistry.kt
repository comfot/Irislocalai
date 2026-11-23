package com.iris.moew.agent

/** 工具注册表：AgentLoop 按名字查找，提示词按注册顺序生成工具清单（顺序稳定利于 KV 缓存）。 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, RegisteredTool>()

    @Synchronized
    fun register(tool: Tool) = register(tool, ToolSource.Builtin)

    @Synchronized
    fun register(tool: Tool, source: ToolSource) {
        tools[tool.name] = RegisteredTool(tool, source)
    }

    @Synchronized
    fun unregister(name: String) {
        tools.remove(name)
    }

    @Synchronized
    fun find(name: String): Tool? = tools[name]?.tool

    @Synchronized
    fun all(): List<Tool> = tools.values.map { it.tool }

    @Synchronized
    fun allWithSource(): List<RegisteredTool> = tools.values.toList()

    @Synchronized
    fun sourceOf(name: String): ToolSource? = tools[name]?.source

    fun describe(): String = all().joinToString("\n") { tool ->
        "- ${tool.name}: ${tool.description}"
    }
}
