package com.iris.localai.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 一次解析出的工具调用。
 * [loose] 表示来自代码块或正文尾部裸 JSON 的宽松解析：调用方需要再核对工具名是否存在，
 * 避免把回答里的示例 JSON 当成调用执行。
 */
data class ParsedToolCall(
    val name: String,
    val arguments: JsonObject,
    val loose: Boolean = false,
)

/**
 * 解析模型输出的工具调用。除了约定的 `<tool_call>{...}</tool_call>`，
 * 还兼容部分模型输出的 DSML 形式：
 * `<｜｜DSML｜｜ invoke name="shell"><｜｜DSML｜｜ parameter name="command">...</...>`。
 * 解析与流式过滤放在一起，保证标签不会展示到回答正文里。
 */
object ToolCallParser {

    fun parse(text: String): ParsedToolCall? {
        parseToolCallJsonBlock(text)?.let { return it }
        parseBareJsonToolCall(text)?.let { return it }
        parseFencedToolCall(text)?.let { return it }
        parseTrailingJsonToolCall(text)?.let { return it }
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

    /**
     * 正文中最早出现的工具调用起始位置；用于流式阶段停止展示后续文本。
     */
    fun earliestStart(text: String): Int = earliestStartFrom(text, 0)

    /**
     * [earliestStart] 的增量版本：从 [fromIndex] 起扫描，供流式阶段按 delta 调用。
     * 上游维护游标（上一次扫描到的长度），回退 [SCAN_LOOKBACK] 字符覆盖跨 delta 的
     * 半截标签；正则只会扫到从 [fromIndex] 到结尾的窗口，避免每个 delta 全文扫描。
     */
    fun earliestStartFrom(text: CharSequence, fromIndex: Int): Int {
        var earliest = Int.MAX_VALUE
        TOOL_CALL_START_REGEX.find(text, fromIndex)?.let { earliest = it.range.first }
        DSML_MARKER.find(text, fromIndex)?.let { match ->
            earliest = minOf(earliest, match.range.first)
        }
        bareJsonHeadStart(text).takeIf { it >= 0 }?.let { earliest = minOf(earliest, it) }
        return if (earliest == Int.MAX_VALUE) -1 else earliest
    }

    /** 末尾可能是半个工具调用标签时先扣住不发，避免标签片段闪现在回答里。 */
    fun holdSuffixLength(text: CharSequence): Int {
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

    /** 结束生成时兜底清理残留标签。传入可用工具名时，只清理确实指向工具的 JSON。 */
    fun stripToolCalls(text: String, knownTools: Set<String>? = null): String {
        var result = removeToolCallBlocks(text)
        result = DSML_INVOKE.replace(result, "")
        result = DSML_TAG.replace(result, "")
        result = stripFencedToolCalls(result, knownTools)
        val trailing = trailingToolJsonStart(result, knownTools)
        if (trailing != null) result = result.substring(0, trailing)
        val cuts = buildList {
            TOOL_CALL_START_REGEX.find(result)?.range?.first?.let { add(it) }
            bareJsonHeadStart(result).takeIf { it >= 0 }?.let { add(it) }
            // 只清理标签残片；正文里的 JSON 已经由尾部/代码块判定处理，不能见 {"name" 就截断。
            STREAM_PREFIXES.filter { it.startsWith("<") }.mapNotNull { marker ->
                result.indexOf(marker, ignoreCase = true).takeIf { it >= 0 }
            }.forEach { add(it) }
        }
        val cut = cuts.minOrNull()
        if (cut != null) result = result.substring(0, cut)
        return result.trim()
    }

    /**
     * `<tool_call>` 的关闭标签并不总是标准形式（实际输出里出现过 `</｜｜DSML｜｜_call>`），
     * 因此这里不依赖关闭标签，直接从 `{` 开始做括号配对，取完整 JSON。
     */
    private fun parseToolCallJsonBlock(text: String): ParsedToolCall? {
        var from = 0
        while (true) {
            val match = TOOL_CALL_START_REGEX.find(text, from) ?: return null
            val start = match.range.first
            val tagEnd = match.range.last + 1
            val brace = text.indexOf('{', tagEnd)
            if (brace >= 0) {
                val end = jsonObjectEnd(text, brace)
                if (end > brace) {
                    parseJson(text.substring(brace, end + 1))?.let { return it }
                }
            }
            from = tagEnd
        }
    }

    private fun removeToolCallBlocks(text: String): String {
        val out = StringBuilder()
        var cursor = 0
        while (true) {
            val match = TOOL_CALL_START_REGEX.find(text, cursor)
            if (match == null) {
                out.append(text, cursor, text.length)
                break
            }
            val start = match.range.first
            val tagEnd = match.range.last + 1
            out.append(text, cursor, start)
            val brace = text.indexOf('{', tagEnd)
            val end = if (brace >= 0) jsonObjectEnd(text, brace) else -1
            if (end > brace) {
                var next = end + 1
                var probe = next
                while (probe < text.length && text[probe].isWhitespace()) probe++
                if (probe < text.length && text.startsWith("</", probe)) {
                    val gt = text.indexOf('>', probe)
                    if (gt >= 0) next = gt + 1
                }
                cursor = next
            } else {
                val close = text.indexOf("</", tagEnd)
                val gt = if (close >= 0) text.indexOf('>', close) else -1
                cursor = if (gt >= 0) gt + 1 else text.length
            }
        }
        return out.toString()
    }

    /** 模型偶尔省略标签，直接输出一个工具参数 JSON；只接受工具名或 command 字段明确的形态。 */
    private fun parseBareJsonToolCall(text: String): ParsedToolCall? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        val end = jsonObjectEnd(trimmed, 0)
        if (end <= 0) return null
        val rest = trimmed.substring(end + 1).trim()
        if (rest.isNotEmpty() && !rest.startsWith("</")) return null
        return parseLooseJsonCall(trimmed.substring(0, end + 1))
    }

    /** 模型把调用包在 ```json 代码块里：常见于把工具调用当成数据一次性输出。 */
    private fun parseFencedToolCall(text: String): ParsedToolCall? {
        for (match in FENCED_TOOL_BLOCK.findAll(text)) {
            parseLooseJsonCall(match.groupValues[1])?.let { return it }
        }
        return null
    }

    /** 正文之后跟一个独立 JSON 对象：模型先说明意图，再给参数而没有包标签。 */
    private fun parseTrailingJsonToolCall(text: String): ParsedToolCall? {
        val start = trailingToolJsonStart(text, null) ?: return null
        return parseLooseJsonCall(text.trimEnd().substring(start))
    }

    /**
     * 尾部裸 JSON 的起点：必须一直闭合到结尾、前导文字很短且没有第二个 JSON 对象，
     * 避免把回答里夹带的示例对象当成调用。
     */
    private fun trailingToolJsonStart(text: String, knownTools: Set<String>?): Int? {
        val trimmed = text.trimEnd()
        if (trimmed.length < 2 || !trimmed.endsWith("}")) return null
        val floor = (trimmed.length - TRAILING_JSON_WINDOW).coerceAtLeast(0)
        var index = trimmed.lastIndexOf('{')
        while (index >= floor) {
            val closed = jsonObjectEnd(trimmed, index) == trimmed.lastIndex
            if (closed) {
                val prefix = trimmed.substring(0, index).trimEnd()
                val usable = !prefix.contains("{") && !prefix.endsWith("```") && prefix.length <= TRAILING_PREFIX_CHARS
                if (usable) {
                    val call = parseLooseJsonCall(trimmed.substring(index))
                    if (call != null && (knownTools == null || call.name in knownTools)) return index
                }
            }
            if (index == 0) return null
            index = trimmed.lastIndexOf('{', index - 1)
        }
        return null
    }

    private fun stripFencedToolCalls(text: String, knownTools: Set<String>?): String {
        val ranges = FENCED_TOOL_BLOCK.findAll(text)
            .filter { match ->
                val call = parseLooseJsonCall(match.groupValues[1])
                call != null && (knownTools == null || call.name in knownTools)
            }
            .map { it.range }
            .toList()
        if (ranges.isEmpty()) return text
        return buildString {
            var cursor = 0
            for (range in ranges) {
                append(text, cursor, range.first)
                cursor = range.last + 1
            }
            append(text, cursor, text.length)
        }
    }

    /** 宽松 JSON 解析：结构必须完整，且带工具名、参数或 command 之类的字段。 */
    private fun parseLooseJsonCall(raw: String): ParsedToolCall? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        val end = jsonObjectEnd(trimmed, 0)
        if (end <= 0) return null
        val rest = trimmed.substring(end + 1).trim()
        if (rest.isNotEmpty() && !rest.startsWith("</")) return null
        val json = trimmed.substring(0, end + 1)
        if (!hasToolShape(json)) return null
        return parseJson(json)?.copy(loose = true)
    }

    private fun hasToolShape(raw: String): Boolean {
        val hasArgs = ARGUMENT_KEYS.any { key -> raw.contains("\"$key\"") || raw.contains("'$key'") }
        if (!hasArgs) return false
        return TOOL_NAME_KEYS.any { key -> raw.contains("\"$key\"") || raw.contains("'$key'") } ||
            raw.contains("\"command\"") || raw.contains("\"cmd\"") ||
            raw.contains("'command'") || raw.contains("'cmd'")
    }

    /**
     * 文本看起来是一次写错的工具调用：有工具标签，或有 arguments/parameters 加工具名的 JSON。
     * 用于给模型一次纠正机会，并在最终正文里明确提示“未执行”。
     */
    fun looksLikeMalformedToolCall(text: String): Boolean {
        if (text.isBlank()) return false
        if (TOOL_CALL_START_REGEX.containsMatchIn(text)) return true
        if (DSML_MARKER.containsMatchIn(text)) return true
        val sample = text.take(MALFORMED_SCAN_CHARS)
        if (!MALFORMED_ARGUMENT_KEY.containsMatchIn(sample)) return false
        return MALFORMED_TOOL_NAME.containsMatchIn(sample)
    }

    /** 输出开头是否为裸工具参数 JSON（如 `{"command":...}`）；有界手动扫描，避免每个 delta 对全文 trimStart/正则。 */
    private fun bareJsonHeadStart(text: CharSequence): Int {
        var i = 0
        while (i < text.length && text[i].isWhitespace()) {
            i++
            if (i > HEAD_WINDOW) return -1
        }
        if (i >= text.length || text[i] != '{') return -1
        var j = i + 1
        while (j < text.length && text[j].isWhitespace()) {
            j++
            if (j - i > HEAD_WINDOW) return -1
        }
        if (j >= text.length || text[j] != '"') return -1
        val keyStart = ++j
        while (j < text.length && text[j] != '"') {
            j++
            if (j - keyStart > HEAD_WINDOW) return -1
        }
        if (j >= text.length) return -1
        val key = text.substring(keyStart, j)
        j++
        while (j < text.length && text[j].isWhitespace()) {
            j++
            if (j - i > 2 * HEAD_WINDOW) return -1
        }
        if (j >= text.length || text[j] != ':') return -1
        return if (key in BARE_JSON_KEYS) i else -1
    }

    /** 输出里有 <tool_call> 起始但 JSON 没闭合，通常是被输出长度截断。 */
    fun hasUnclosedToolCall(text: String): Boolean {
        val last = TOOL_CALL_START_REGEX.findAll(text).lastOrNull() ?: return false
        val brace = text.indexOf('{', last.range.last + 1)
        if (brace < 0) return true
        return jsonObjectEnd(text, brace) <= brace
    }

    /** 整段回答只是一条命令（模型忘了包 <tool_call>），只用于提示，不自动执行。 */
    fun looksLikeBareCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 2000) return false
        if (TOOL_CALL_START_REGEX.containsMatchIn(trimmed) || trimmed.contains("```")) return false
        if ('\n' in trimmed) {
            val first = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return first.isNotEmpty() && BARE_COMMAND.matches(first) &&
                trimmed.lineSequence().count { it.isNotBlank() } >= 2
        }
        // 只把带路径、管道、分号、赋值或引号的单行当成命令，避开“cd 是切换目录”这类普通回答。
        if (trimmed.none { it == '/' || it == '|' || it == ';' || it == '=' || it == '\'' || it == '"' || it == '-' }) {
            return false
        }
        return BARE_COMMAND.matches(trimmed)
    }

    /**
     * 从正文里取出模型本来想执行的单行命令：
     * - 整段就是命令；
     * - 或唯一一个 ```sh / ```bash / ``` 代码块里只有一条命令。
     */
    fun bareShellCommand(text: String): String? {
        val trimmed = text.trim()
        if (looksLikeBareCommand(trimmed)) return trimmed
        val blocks = FENCED_COMMAND.findAll(trimmed).toList()
        if (blocks.size != 1) return null
        val command = blocks.first().groupValues[1].trim()
        return command.takeIf { looksLikeBareCommand(it) }
    }

    /** 是否有足够明确的命令特征；用于避免把普通说明句自动执行。 */
    fun isStrongShellCommand(command: String): Boolean {
        if (command.any { it == '/' || it == '|' || it == ';' || it == '>' || it == '&' }) return true
        val head = command.substringBefore(' ').lowercase()
        return head in STRONG_COMMANDS
    }

    private val BARE_COMMAND = Regex(
        "(?:cd|ls|grep|rg|find|cat|head|tail|sed|awk|cp|mv|rm|mkdir|touch|chmod|chown|git|python|python3|node|npm|sh|bash|pwd|mount|df|unzip|zip|tar|7z|curl|wget)\\s+[\\s\\S]*",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val FENCED_COMMAND = Regex("(?is)```(?:sh|bash|shell|zsh)?\\s*\n(.*?)```")

    private val STRONG_COMMANDS = setOf(
        "unzip", "zip", "tar", "7z", "curl", "wget", "git", "python", "python3", "node", "npm",
    )

    /**
     * 流式增量扫描的回退窗口：覆盖跨 delta 拼接的半截标签。
     * 假设是标记从出现到完整只需十几个字符；异常长的标签片段本就属于已有
     * holdSuffixLength 也接不住的路径，行为不劣于逐 token 全文扫描。
     */
    const val SCAN_LOOKBACK = 32

    private val BARE_JSON_KEYS = setOf("name", "tool", "tool_name", "command", "cmd")
    private const val HEAD_WINDOW = 32

    private val TOOL_NAME_KEYS = listOf("name", "tool", "tool_name")
    private val ARGUMENT_KEYS = listOf("arguments", "args", "parameters", "command", "cmd")
    private val FENCED_TOOL_BLOCK = Regex(
        "(?is)```(?:json|json5|tool|tool_call|tool_call_json)?[ \\t]*\\r?\\n(.*?)```",
    )
    private const val TRAILING_JSON_WINDOW = 20_000
    private const val TRAILING_PREFIX_CHARS = 400
    private const val MALFORMED_SCAN_CHARS = 4_000
    private val MALFORMED_ARGUMENT_KEY = Regex("""["'](?:arguments|args|parameters)["']\s*:""")
    private val MALFORMED_TOOL_NAME = Regex("""["'](?:name|tool|tool_name|command|cmd)["']\s*:""")

    private fun jsonObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return -1
    }

    private fun parseJson(raw: String): ParsedToolCall? = try {
        parseJsonStrict(raw)
    } catch (_: Exception) {
        runCatching { parseJsonStrict(repairJson(raw)) }.getOrNull()
    }

    /**
     * 只在严格解析失败后做最小修复：尾逗号、单引号 JSON、字符串里的裸换行。
     * 正常 JSON 走严格解析，不会被改写。
     */
    private fun repairJson(raw: String): String {
        var text = raw.replace(Regex(",\\s*([}\\]])"), "$1")
        if (text.contains('\'')) text = singleToDoubleQuotes(text)
        return escapeRawNewlines(text)
    }

    private fun singleToDoubleQuotes(raw: String): String = buildString(raw.length + 8) {
        var inDouble = false
        var inSingle = false
        var escaped = false
        for (char in raw) {
            when {
                escaped -> {
                    append(char)
                    escaped = false
                }
                char == '\\' -> {
                    append(char)
                    escaped = true
                }
                inDouble -> {
                    if (char == '"') inDouble = false
                    append(char)
                }
                inSingle -> when (char) {
                    '\'' -> {
                        inSingle = false
                        append('"')
                    }
                    '"' -> append("\\\"")
                    else -> append(char)
                }
                char == '"' -> {
                    inDouble = true
                    append(char)
                }
                char == '\'' -> {
                    inSingle = true
                    append('"')
                }
                else -> append(char)
            }
        }
    }

    private fun escapeRawNewlines(raw: String): String = buildString(raw.length + 8) {
        var inString = false
        var escaped = false
        for (char in raw) {
            when {
                escaped -> {
                    append(char)
                    escaped = false
                }
                char == '\\' -> {
                    append(char)
                    escaped = true
                }
                char == '"' -> {
                    inString = !inString
                    append(char)
                }
                inString && (char == '\n' || char == '\r') -> append("\\n")
                else -> append(char)
            }
        }
    }

    private fun parseJsonStrict(raw: String): ParsedToolCall {
        val json = Json.parseToJsonElement(raw).jsonObject
        val name = json["name"]?.jsonPrimitive?.contentOrNull
            ?: json["tool"]?.jsonPrimitive?.contentOrNull
            ?: json["tool_name"]?.jsonPrimitive?.contentOrNull
            ?: inferToolName(json)
            ?: error("缺少工具名")
        val argElement = json["arguments"] ?: json["args"] ?: json["parameters"]
        val arguments = when (argElement) {
            is JsonObject -> argElement
            is JsonPrimitive -> runCatching {
                Json.parseToJsonElement(elementContent(argElement)).jsonObject
            }.getOrElse {
                Json.parseToJsonElement(repairJson(elementContent(argElement))).jsonObject
            }
            else -> if (json.containsKey("name") || json.containsKey("tool") || json.containsKey("tool_name")) {
                JsonObject(json - setOf("name", "tool", "tool_name"))
            } else {
                json
            }
        }
        return ParsedToolCall(name, unwrapArguments(arguments))
    }

    /**
     * 模型偶尔把参数再包一层：`arguments={"arguments":"{\"command\":...}"}`。
     * 仅当外层只有一个 arguments/args/parameters 键时解包，避免误伤正常多参数工具。
     */
    private fun unwrapArguments(arguments: JsonObject): JsonObject {
        if (arguments.size != 1) return arguments
        val nested = arguments["arguments"] ?: arguments["args"] ?: arguments["parameters"] ?: return arguments
        return when (nested) {
            is JsonObject -> nested
            is JsonPrimitive -> runCatching {
                Json.parseToJsonElement(nested.content).jsonObject
            }.getOrNull() ?: arguments
            else -> arguments
        }
    }

    private fun elementContent(element: JsonPrimitive): String = element.content

    /** 模型常省略 name，直接给 shell 参数；只对明确的 command/cmd 做推断。 */
    private fun inferToolName(json: JsonObject): String? = when {
        json.containsKey("command") || json.containsKey("cmd") -> "shell"
        json.containsKey("op") -> "plugin_dev"
        else -> null
    }

    private val TOOL_CALL_START_REGEX = Regex(
        "<[|｜]?\\s*(?:tool_calls?|call_calls?|call_tools?|tools_call)\\b[^>]*>",
        setOf(RegexOption.IGNORE_CASE),
    )

    private const val DSML_BARS = "[|｜]{1,2}"
    private const val DSML_NAME = "$DSML_BARS\\s*DSML\\s*$DSML_BARS"
    private const val DSML_OPEN = "<$DSML_NAME"

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
        "<｜tool_call>",
        "<|tool_call>",
        "<｜tool_calls>",
        "<|tool_calls>",
        "<｜tool_call",
        "<|tool_call",
        "<｜tool_calls",
        "<|tool_calls",
        "<tool_call>",
        "<tool_calls>",
        "<call_call>",
        "<call_calls>",
        "<call_tool>",
        "<call_tools>",
        "<tools_call>",
        "<tool_call",
        "<tool_calls",
        "<call_call",
        "<call_tool",
        "<tools_call",
        "<｜｜DSML｜｜",
        "<|DSML|",
        "<||DSML||",
        "<｜DSML｜",
        "<|｜DSML｜|",
        "<DSML",
        "{\"command\"",
        "{\"cmd\"",
        "{\"name\"",
        "{\"tool\"",
        "{\"tool_name\"",
    )
}
