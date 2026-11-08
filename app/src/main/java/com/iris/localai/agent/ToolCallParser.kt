package com.iris.localai.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 一次解析出的工具调用。 */
data class ParsedToolCall(val name: String, val arguments: JsonObject)

/**
 * 解析模型输出的工具调用。除了约定的 `<tool_call>{...}</tool_call>`，
 * 还兼容部分模型输出的 DSML 形式：
 * `<｜｜DSML｜｜ invoke name="shell"><｜｜DSML｜｜ parameter name="command">...</...>`。
 * 解析与流式过滤放在一起，保证标签不会展示到回答正文里。
 */
object ToolCallParser {

    fun parse(text: String): ParsedToolCall? {
        TOOL_CALL_JSON.find(text)?.let { match ->
            parseJson(match.groupValues[1])?.let { return it }
        }
        val invoke = DSML_INVOKE.find(text) ?: return null
        val name = invoke.groupValues[1].trim()
        if (name.isEmpty()) return null
        val arguments = buildJsonObject {
            DSML_PARAMETER.findAll(invoke.groupValues[2]).forEach { parameter ->
                val key = parameter.groupValues[1].trim()
                if (key.isNotEmpty()) {
                    put(key, parameter.groupValues[2].trim())
                }
            }
        }
        return ParsedToolCall(name, arguments)
    }

    /** 正文中最早出现的工具调用起始位置；用于流式阶段停止展示后续文本。 */
    fun earliestStart(text: String): Int {
        var earliest = text.indexOf(TOOL_CALL_START).takeIf { it >= 0 } ?: Int.MAX_VALUE
        DSML_MARKER.find(text)?.let { match ->
            earliest = minOf(earliest, match.range.first)
        }
        return if (earliest == Int.MAX_VALUE) -1 else earliest
    }

    /** 末尾可能是半个工具调用标签时先扣住不发，避免标签片段闪现在回答里。 */
    fun holdSuffixLength(text: String): Int {
        var max = 0
        STREAM_PREFIXES.forEach { prefix ->
            val limit = minOf(prefix.length - 1, text.length)
            for (length in limit downTo 1) {
                if (text.regionMatches(text.length - length, prefix, 0, length, ignoreCase = true)) {
                    max = maxOf(max, length)
                    break
                }
            }
        }
        return max
    }

    /** 结束生成时兜底清理残留标签。 */
    fun stripToolCalls(text: String): String {
        var result = TOOL_CALL_JSON.replace(text, "")
        result = DSML_INVOKE.replace(result, "")
        result = DSML_TAG.replace(result, "")
        val dangling = listOf(TOOL_CALL_START) + STREAM_PREFIXES
        val cut = dangling.mapNotNull { marker ->
            result.indexOf(marker, ignoreCase = true).takeIf { it >= 0 }
        }.minOrNull()
        if (cut != null) result = result.substring(0, cut)
        return result.trim()
    }

    private fun parseJson(raw: String): ParsedToolCall? = try {
        val json = Json.parseToJsonElement(raw).jsonObject
        val name = json["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val arguments = when (val element = json["arguments"]) {
            is JsonObject -> element
            is JsonPrimitive -> runCatching {
                Json.parseToJsonElement(element.content).jsonObject
            }.getOrElse { buildJsonObject { } }
            else -> buildJsonObject { }
        }
        ParsedToolCall(name, arguments)
    } catch (_: Exception) {
        null
    }

    private const val TOOL_CALL_START = "<tool_call>"
    private const val DSML_BARS = "[|｜]{1,2}"
    private const val DSML_NAME = "$DSML_BARS\\s*DSML\\s*$DSML_BARS"
    private const val DSML_OPEN = "<$DSML_NAME"

    private val TOOL_CALL_JSON = Regex(
        pattern = """<tool_call>\s*(\{.*?\})\s*</tool_call>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val DSML_INVOKE = Regex(
        pattern = "$DSML_OPEN\\s*invoke\\s+name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)" +
            "</$DSML_NAME\\s*invoke\\s*>",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val DSML_PARAMETER = Regex(
        pattern = "$DSML_OPEN\\s*parameter\\s+name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)" +
            "</$DSML_NAME\\s*parameter\\s*>",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val DSML_MARKER = Regex(
        pattern = "<[|｜]{0,2}\\s*DSML",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    private val DSML_TAG = Regex(
        pattern = "</?$DSML_NAME[^>]*>",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    private val STREAM_PREFIXES = listOf(
        TOOL_CALL_START,
        "<｜｜DSML｜｜",
        "<|DSML|",
        "<||DSML||",
        "<｜DSML｜",
        "<|｜DSML｜|",
        "<DSML",
    )
}
