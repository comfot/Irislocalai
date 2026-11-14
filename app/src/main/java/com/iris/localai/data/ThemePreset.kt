package com.iris.localai.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 全局界面风格。 */
enum class ThemeStyle(
    val id: String,
    val label: String,
    val summary: String,
) {
    STANDARD("standard", "标准", "实色卡片，跟随所选强调色"),
    LIQUID_GLASS("glass", "液态玻璃", "半透明表面、亮边与渐变背景"),
    E_INK("eink", "墨水屏", "黑白灰高对比，弱化强调色与阴影"),
}

/** 强调色板；明暗两套色值。 */
enum class ThemeAccent(
    val id: String,
    val label: String,
    private val primaryLight: Long,
    private val onPrimaryLight: Long,
    private val containerLight: Long,
    private val onContainerLight: Long,
    private val primaryDark: Long,
    private val onPrimaryDark: Long,
    private val containerDark: Long,
    private val onContainerDark: Long,
) {
    TEAL(
        "teal", "青绿",
        0xFF0F766E, 0xFFFFFFFF, 0xFFDDF3EF, 0xFF0B5C55,
        0xFF5CC6B1, 0xFF072019, 0xFF173B36, 0xFF9FE7D8,
    ),
    BLUE(
        "blue", "海蓝",
        0xFF2563EB, 0xFFFFFFFF, 0xFFE3EDFF, 0xFF1D4ED8,
        0xFF8FBBFF, 0xFF06122B, 0xFF132B4D, 0xFFC7DCFF,
    ),
    VIOLET(
        "violet", "暮紫",
        0xFF7C3AED, 0xFFFFFFFF, 0xFFF0E9FF, 0xFF5B21B6,
        0xFFC4B5FD, 0xFF1B0B36, 0xFF2A1B4D, 0xFFE4DAFF,
    ),
    AMBER(
        "amber", "琥珀",
        0xFFB45309, 0xFFFFFFFF, 0xFFFFEED8, 0xFF8A3D00,
        0xFFFBBF24, 0xFF2A1600, 0xFF3A2405, 0xFFFFE6B8,
    ),
    ROSE(
        "rose", "玫瑰",
        0xFFBE123C, 0xFFFFFFFF, 0xFFFFE4E8, 0xFF9F0E33,
        0xFFFDA4AF, 0xFF3A0710, 0xFF4A1420, 0xFFFFD7DD,
    );

    fun primary(dark: Boolean): Long = if (dark) primaryDark else primaryLight

    fun onPrimary(dark: Boolean): Long = if (dark) onPrimaryDark else onPrimaryLight

    fun container(dark: Boolean): Long = if (dark) containerDark else containerLight

    fun onContainer(dark: Boolean): Long = if (dark) onContainerDark else onContainerLight
}

/** 聊天气泡样式。 */
enum class BubbleStyle(
    val id: String,
    val label: String,
    val summary: String,
) {
    ROUNDED("rounded", "圆角", "18dp 圆角气泡，默认样式"),
    SQUARE("square", "方角", "6dp 小圆角，更贴近清单"),
    OUTLINED("outlined", "描边", "透明底 + 1dp 细描边"),
    MINIMAL("minimal", "极简", "无气泡底色，只保留正文"),
}

/** 可导入/导出的主题预设。 */
data class ThemePreset(
    val name: String = "自定义主题",
    val style: ThemeStyle = ThemeStyle.STANDARD,
    val accent: ThemeAccent = ThemeAccent.TEAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bubbleStyle: BubbleStyle = BubbleStyle.ROUNDED,
    val roleChatCard: Boolean = true,
)

fun AppSettings.toThemePreset(name: String = "当前主题"): ThemePreset = ThemePreset(
    name = name,
    style = themeStyle,
    accent = themeAccent,
    themeMode = themeMode,
    bubbleStyle = bubbleStyle,
    roleChatCard = roleChatCardEnabled,
)

fun AppSettings.withThemePreset(preset: ThemePreset): AppSettings = copy(
    themeStyle = preset.style,
    themeAccent = preset.accent,
    themeMode = preset.themeMode,
    bubbleStyle = preset.bubbleStyle,
    roleChatCardEnabled = preset.roleChatCard,
)

/** 主题预设 JSON：schema 1，字段均为可读字符串，便于手工编辑与技能生成。 */
object ThemePresetCodec {

    const val SCHEMA = 1
    const val APP = "IRIS LocalAI"

    private val pretty = Json { prettyPrint = true }

    fun encode(preset: ThemePreset): String = buildJsonObject {
        put("app", APP)
        put("schema", SCHEMA)
        put("name", preset.name)
        put("style", preset.style.id)
        put("accent", preset.accent.id)
        put("themeMode", preset.themeMode.name.lowercase())
        put("bubbleStyle", preset.bubbleStyle.id)
        put("roleChatCard", preset.roleChatCard)
    }.let { pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }

    fun decode(text: String, fallback: ThemePreset = ThemePreset()): ThemePreset {
        val root = runCatching {
            Json.parseToJsonElement(text.trimStart('\uFEFF')).jsonObject
        }.getOrElse { throw IllegalArgumentException("预设不是有效的 JSON 对象") }

        val schema = root["schema"]?.jsonPrimitive?.intOrNull ?: SCHEMA
        require(schema in 1..SCHEMA) { "不支持的预设版本：$schema" }

        val styleId = root.string("style")
        val style = if (styleId == null) {
            fallback.style
        } else {
            ThemeStyle.entries.firstOrNull { it.id == styleId.lowercase() }
                ?: throw IllegalArgumentException("未知界面风格：$styleId")
        }
        val accentId = root.string("accent")
        val accent = if (accentId == null) {
            fallback.accent
        } else {
            ThemeAccent.entries.firstOrNull { it.id == accentId.lowercase() }
                ?: throw IllegalArgumentException("未知强调色：$accentId")
        }
        val modeId = root.string("themeMode")
        val mode = if (modeId == null) {
            fallback.themeMode
        } else {
            ThemeMode.entries.firstOrNull { it.name.equals(modeId, ignoreCase = true) }
                ?: throw IllegalArgumentException("未知明暗模式：$modeId")
        }
        val bubbleId = root.string("bubbleStyle")
        val bubble = if (bubbleId == null) {
            fallback.bubbleStyle
        } else {
            BubbleStyle.entries.firstOrNull { it.id == bubbleId.lowercase() }
                ?: throw IllegalArgumentException("未知气泡样式：$bubbleId")
        }
        val roleChat = root["roleChatCard"]?.jsonPrimitive?.booleanOrNull ?: fallback.roleChatCard
        return ThemePreset(
            name = root.string("name")?.takeIf { it.isNotBlank() } ?: "导入的主题",
            style = style,
            accent = accent,
            themeMode = mode,
            bubbleStyle = bubble,
            roleChatCard = roleChat,
        )
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        (this[key])?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}
