package com.iris.localai.data

enum class PermissionMode(val label: String, val description: String) {
    WORKSPACE_ONLY("仅工作区", "只能读写工作区内的文件"),
    ASK_EVERY_TIME("每次询问", "敏感操作前弹出确认"),
    FULL_ACCESS("完全访问", "允许执行命令与系统操作"),
}

enum class CharacterModel(val label: String) {
    TWO_D("2D"),
    THREE_D("3D"),
}

data class SkillProfile(val id: String, val label: String, val description: String)

data class McpProfile(val id: String, val label: String, val description: String)

data class CharacterCard(
    val id: String,
    val name: String,
    val tagline: String,
    val greeting: String,
    val accent: Long,
)

/** 内置的可选档案；接入真实数据源时替换为仓库/配置读取即可。 */
object ProfileCatalog {

    val skills = listOf(
        SkillProfile("file-ops", "文件操作", "读写、移动与搜索工作区文件"),
        SkillProfile("web-search", "联网搜索", "抓取网页并总结"),
        SkillProfile("code-runner", "代码执行", "在沙盒中运行脚本"),
        SkillProfile("scheduler", "定时任务", "按时间或间隔触发"),
        SkillProfile("knowledge", "知识库", "检索本地文档与记忆"),
    )

    val mcpServers = listOf(
        McpProfile("local-files", "本地文件 MCP", "暴露工作区文件能力"),
        McpProfile("github", "GitHub MCP", "仓库、Issue 与 PR"),
        McpProfile("browser", "浏览器 MCP", "网页自动化与截图"),
    )

    val characters = listOf(
        CharacterCard("xiaoman", "小满", "温柔的生活搭子", "今天也辛苦啦，想聊点什么？", 0xFFFF9AA2L),
        CharacterCard("lan", "岚", "冷静的事务助手", "我在。列个清单，我们一件件来。", 0xFF7DB3FFL),
        CharacterCard("che", "阿澈", "元气的行动派", "出发！今天想完成什么？", 0xFF7FE0C3L),
    )

    fun character(id: String?): CharacterCard =
        characters.firstOrNull { it.id == id } ?: characters.first()
}
