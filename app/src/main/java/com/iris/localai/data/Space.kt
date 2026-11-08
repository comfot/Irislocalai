package com.iris.localai.data

/** 四个空间：会话按空间隔离，侧栏与设置里的「空间管理」共用这份定义。 */
enum class Space(
    val id: String,
    val label: String,
    val description: String,
) {
    WORK("work", "工作空间", "工作区、命令与代码任务"),
    LIFE("life", "生活空间", "日常记录、灵感与陪伴"),
    OPERATE("operate", "操作空间", "系统操作、自动化与设备控制"),
    COMPOSITE("composite", "综合空间", "跨领域话题与默认会话"),
    ;

    companion object {
        fun of(id: String?): Space = entries.firstOrNull { it.id == id } ?: COMPOSITE
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

fun ThemeMode.resolveDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
