package com.iris.localai.agent

/** 从模型流式输出中剥离出的表演标签；标签本身不会展示给用户或交给 TTS。 */
sealed interface PerformanceTag {
    data class Emotion(val name: String, val intensity: Float) : PerformanceTag
    data class Action(val name: String) : PerformanceTag
    data class Dance(val name: String) : PerformanceTag
    data class Camera(val name: String) : PerformanceTag
    data class Intent(val name: String) : PerformanceTag
    data class Motion(val group: String, val index: Int) : PerformanceTag
    data class Expression(val name: String) : PerformanceTag
    data class Stand(val name: String) : PerformanceTag
    data class Outfit(val name: String, val enabled: Boolean) : PerformanceTag
    data class Param(val id: String, val value: Float) : PerformanceTag
    data class Preset(val name: String) : PerformanceTag
}

data class PerformanceFeed(
    val text: String,
    val tags: List<PerformanceTag>,
)

/**
 * 流式标签解析：跨 delta 缓存未闭合的标签，只有已知前缀才会被当作标签剥离，
 * 普通文本里的 `[笑]` 等方括号内容会原样保留。
 */
class PerformanceTagParser {

    private val pending = StringBuilder()

    fun feed(chunk: String): PerformanceFeed {
        if (chunk.isEmpty() && pending.isEmpty()) return PerformanceFeed("", emptyList())
        val buffer = pending.toString() + chunk
        pending.clear()
        val text = StringBuilder()
        val tags = mutableListOf<PerformanceTag>()
        var index = 0
        while (index < buffer.length) {
            val open = buffer.indexOf('[', index)
            if (open < 0) {
                text.append(buffer, index, buffer.length)
                break
            }
            text.append(buffer, index, open)
            val close = buffer.indexOf(']', open + 1)
            if (close < 0) {
                val suffix = buffer.substring(open)
                if (couldBeTagPrefix(suffix)) {
                    pending.append(suffix)
                } else {
                    text.append(suffix)
                }
                break
            }
            val raw = buffer.substring(open, close + 1)
            val inner = buffer.substring(open + 1, close)
            val tag = parseTag(inner)
            if (tag != null) tags += tag else text.append(raw)
            index = close + 1
        }
        return PerformanceFeed(text.toString(), tags)
    }

    fun flush(): PerformanceFeed {
        if (pending.isEmpty()) return PerformanceFeed("", emptyList())
        val text = pending.toString()
        pending.clear()
        return PerformanceFeed(text, emptyList())
    }

    private fun couldBeTagPrefix(suffix: String): Boolean {
        if (!suffix.startsWith("[")) return false
        val body = suffix.substring(1)
        if (body.isEmpty()) return true
        return PREFIXES.any { prefix ->
            body.startsWith(prefix, ignoreCase = true) || prefix.startsWith(body, ignoreCase = true)
        }
    }

    private fun parseTag(inner: String): PerformanceTag? {
        val colon = inner.indexOf(':')
        if (colon <= 0) return null
        val prefix = inner.substring(0, colon).trim().lowercase()
        val value = inner.substring(colon + 1).trim()
        return when (prefix) {
            "emo" -> {
                val parts = value.split(':', limit = 2)
                val name = parts.getOrNull(0)?.trim().orEmpty()
                if (name.isEmpty()) return null
                val intensity = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.85f
                PerformanceTag.Emotion(name, intensity)
            }
            "act" -> PerformanceTag.Action(value).takeIf { value.isNotEmpty() }
            "dance" -> PerformanceTag.Dance(value)
            "cam" -> PerformanceTag.Camera(value)
            "intent" -> PerformanceTag.Intent(value).takeIf { value.isNotEmpty() }
            "motion" -> {
                val separator = value.lastIndexOf(':')
                if (separator <= 0) return null
                val group = value.substring(0, separator).trim()
                val index = value.substring(separator + 1).toIntOrNull() ?: return null
                PerformanceTag.Motion(group, index)
            }
            "expr" -> PerformanceTag.Expression(value).takeIf { value.isNotEmpty() }
            "stand" -> PerformanceTag.Stand(value).takeIf { value.isNotEmpty() }
            "outfit" -> {
                val separator = value.lastIndexOf(':')
                if (separator <= 0) return null
                val name = value.substring(0, separator).trim()
                val state = value.substring(separator + 1).trim().lowercase()
                PerformanceTag.Outfit(name, state == "on" || state == "true")
            }
            "param" -> {
                val separator = value.indexOf('=')
                if (separator <= 0) return null
                val id = value.substring(0, separator).trim()
                val number = value.substring(separator + 1).toFloatOrNull() ?: return null
                PerformanceTag.Param(id, number)
            }
            "preset" -> PerformanceTag.Preset(value).takeIf { value.isNotEmpty() }
            else -> null
        }
    }

    private companion object {
        val PREFIXES = listOf(
            "emo:", "act:", "dance:", "cam:", "intent:", "motion:",
            "expr:", "stand:", "outfit:", "param:", "preset:",
        )
    }
}
