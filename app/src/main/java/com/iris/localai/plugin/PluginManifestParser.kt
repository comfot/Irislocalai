package com.iris.localai.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object PluginManifestParser {

    fun parse(text: String): PluginManifest {
        val root = parseElement(text).jsonObject
        val schemaVersion = root.text("schema_version")?.toIntOrNull()
        if (schemaVersion != null && schemaVersion > 1) {
            error("不支持的清单版本：$schemaVersion")
        }
        val id = root.text("toolpkg_id")
            ?: root.text("id")
            ?: root.text("package")
            ?: error("清单缺少 toolpkg_id / id")
        val main = root.text("main") ?: root.text("entry") ?: ""
        val subpackages = (root["subpackages"] as? JsonArray)
            ?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val subId = obj.text("id") ?: return@mapNotNull null
                val entry = obj.text("entry") ?: return@mapNotNull null
                PluginSubpackage(subId, entry)
            }
            .orEmpty()
            .ifEmpty { if (main.isBlank()) emptyList() else listOf(PluginSubpackage("main", main)) }
        val requirements = (root["requires"] as? JsonArray)
            ?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val requiredId = obj.text("id") ?: return@mapNotNull null
                PluginRequirement(
                    id = requiredId,
                    description = localized(obj["description"]),
                    minVersion = obj.text("min_version"),
                    maxVersion = obj.text("max_version"),
                )
            }
            .orEmpty()
        val toolSpecs = parseToolArray(root["tools"])
        val resources = (root["resources"] as? JsonArray)
            ?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val key = obj.text("key") ?: return@mapNotNull null
                val path = obj.text("path") ?: return@mapNotNull null
                PluginResource(key = key, path = path, mime = obj.text("mime").orEmpty())
            }
            .orEmpty()
        val wasmModules = (root["wasm_modules"] as? JsonArray)
            ?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.text("id") ?: return@mapNotNull null
                val path = obj.text("path") ?: return@mapNotNull null
                PluginWasmModule(
                    id = id,
                    path = path,
                    exports = (obj["exports"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty(),
                    abi = obj.text("abi") ?: "assemblyscript",
                )
            }
            .orEmpty()
        val origin = if (root.text("toolpkg_id") != null) PluginOrigin.OPERIT else PluginOrigin.IRIS
        return PluginManifest(
            id = id,
            name = localized(root["display_name"] ?: root["name"]).ifBlank { id },
            version = root.text("version").orEmpty(),
            apiVersion = root.text("api_version").orEmpty(),
            description = localized(root["description"]),
            author = stringList(root["author"]),
            main = main,
            enabledByDefault = root.boolean("enabled_by_default", true),
            subpackages = subpackages,
            requirements = requirements,
            resources = resources,
            wasmModules = wasmModules,
            toolSpecs = toolSpecs,
            logo = root.text("logo"),
            origin = origin,
        )
    }

    // Operit 子包脚本顶部的 METADATA 块声明工具列表。
    fun parseMetadata(script: String): List<PluginToolSpec> {
        val start = script.indexOf("METADATA")
        if (start < 0) return emptyList()
        val bodyStart = script.indexOf('{', start)
        if (bodyStart < 0) return emptyList()
        val bodyEnd = script.indexOf("*/", bodyStart)
        if (bodyEnd < 0) return emptyList()
        val body = script.substring(bodyStart, bodyEnd).trim()
        val root = runCatching { parseElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return parseToolArray(root["tools"])
    }

    private fun parseToolArray(element: JsonElement?): List<PluginToolSpec> {
        val array = element as? JsonArray ?: return emptyList()
        return array.take(MAX_TOOLS).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.text("name") ?: return@mapNotNull null
            val parameters = (obj["parameters"] as? JsonArray)
                ?.mapNotNull { parameterElement ->
                    val parameter = parameterElement as? JsonObject ?: return@mapNotNull null
                    val parameterName = parameter.text("name") ?: return@mapNotNull null
                    PluginToolParameter(
                        name = parameterName,
                        description = localized(parameter["description"]),
                        type = parameter.text("type") ?: "string",
                        required = parameter.boolean("required", true),
                    )
                }
                .orEmpty()
                .take(MAX_PARAMETERS)
            PluginToolSpec(
                name = name,
                description = localized(obj["description"]),
                parameters = parameters,
                advice = obj.boolean("advice", false),
            )
        }
    }

    private fun parseElement(text: String): JsonElement {
        val trimmed = text.trim().trimStart('\uFEFF').trim()
        runCatching { return Json.parseToJsonElement(trimmed) }
        return LenientJsonParser(trimmed).parse()
    }

    private fun localized(element: JsonElement?): String = when (element) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonObject -> {
            val byLanguage = element.text("zh") ?: element.text("zh-CN") ?: element.text("en")
            byLanguage ?: element.values.firstOrNull { it is JsonPrimitive }
                ?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun stringList(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback

    private const val MAX_TOOLS = 100
    private const val MAX_PARAMETERS = 50
}

/**
 * HJSON 的常用子集：注释、无引号键、单/双引号与三引号字符串、可省略逗号。
 * Operit 允许 manifest.hjson，标准 JSON 解析失败时回退到这里。
 */
private class LenientJsonParser(private val source: String) {

    private var position = 0

    fun parse(): JsonElement {
        val value = readValue()
        skipTrivia()
        if (position < source.length) error("清单在偏移 $position 处有多余内容")
        return value
    }

    private fun readValue(): JsonElement {
        skipTrivia()
        if (position >= source.length) error("清单意外结束")
        return when (source[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"', '\'' -> JsonPrimitive(readString())
            else -> readScalar()
        }
    }

    private fun readObject(): JsonObject {
        expect('{')
        val values = LinkedHashMap<String, JsonElement>()
        while (true) {
            skipTrivia()
            if (peek('}')) {
                position++
                break
            }
            val key = readKey()
            skipTrivia()
            if (peek(':') || peek('=')) position++
            skipTrivia()
            val value = if (peek('}') || peek(',') || position >= source.length) {
                JsonNull
            } else {
                readValue()
            }
            values[key] = value
            skipTrivia()
            if (peek(',')) position++
        }
        return JsonObject(values)
    }

    private fun readArray(): JsonArray {
        expect('[')
        val values = ArrayList<JsonElement>()
        while (true) {
            skipTrivia()
            if (peek(']')) {
                position++
                break
            }
            values += readValue()
            skipTrivia()
            if (peek(',')) position++
        }
        return JsonArray(values)
    }

    private fun readKey(): String {
        skipTrivia()
        if (position >= source.length) error("清单缺少字段名")
        if (source[position] == '"' || source[position] == '\'') return readString()
        val start = position
        while (position < source.length && source[position] !in charArrayOf(':', '=', '\n', '\r', '}', ',')) {
            position++
        }
        return source.substring(start, position).trim()
    }

    private fun readString(): String {
        val quote = source[position]
        if (quote == '\'' && source.startsWith("'''", position)) {
            position += 3
            val end = source.indexOf("'''", position)
            if (end < 0) error("三引号字符串未闭合")
            val text = source.substring(position, end)
            position = end + 3
            return text.trimIndent().trim('\n')
        }
        position++
        val builder = StringBuilder()
        while (position < source.length) {
            val ch = source[position++]
            when {
                ch == quote -> return builder.toString()
                ch == '\\' && position < source.length -> {
                    val escaped = source[position++]
                    builder.append(
                        when (escaped) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'u' -> {
                                val hex = source.substring(position, (position + 4).coerceAtMost(source.length))
                                position += hex.length
                                hex.toIntOrNull(16)?.toChar() ?: error("非法 Unicode 转义")
                            }
                            else -> escaped
                        },
                    )
                }
                else -> builder.append(ch)
            }
        }
        error("字符串未闭合")
    }

    private fun readScalar(): JsonElement {
        val start = position
        while (position < source.length && source[position] !in charArrayOf(',', '}', ']', '\n', '\r')) {
            position++
        }
        val raw = stripInlineComment(source.substring(start, position))
        if (raw.isEmpty()) error("清单在偏移 $start 处缺少值")
        return when (raw) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            "null" -> JsonNull
            else -> raw.toLongOrNull()?.let { JsonPrimitive(it) }
                ?: raw.toDoubleOrNull()?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(raw)
        }
    }

    private fun stripInlineComment(value: String): String {
        for (index in value.indices) {
            val ch = value[index]
            val comment = ch == '#' ||
                (ch == '/' && index + 1 < value.length && value[index + 1] == '/')
            if (comment && (index == 0 || value[index - 1].isWhitespace())) {
                return value.substring(0, index).trim()
            }
        }
        return value.trim()
    }

    private fun skipTrivia() {
        while (position < source.length) {
            val ch = source[position]
            when {
                ch.isWhitespace() -> position++
                ch == '#' -> skipLine()
                ch == '/' && position + 1 < source.length && source[position + 1] == '/' -> skipLine()
                ch == '/' && position + 1 < source.length && source[position + 1] == '*' -> {
                    val end = source.indexOf("*/", position + 2)
                    position = if (end < 0) source.length else end + 2
                }
                else -> return
            }
        }
    }

    private fun skipLine() {
        while (position < source.length && source[position] != '\n') position++
    }

    private fun peek(ch: Char): Boolean = position < source.length && source[position] == ch

    private fun expect(ch: Char) {
        skipTrivia()
        if (!peek(ch)) error("清单在偏移 $position 处期望 '$ch'")
        position++
    }
}
