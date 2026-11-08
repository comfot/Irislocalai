package com.iris.localai.plugin

import com.iris.localai.agent.Tool
import com.iris.localai.agent.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class PluginTool(
    private val store: PluginStore,
    private val runtime: PluginRuntime,
    private val binding: PluginToolBinding,
) : Tool {

    override val name: String = store.runtimeName(binding)

    override val description: String = buildString {
        append(binding.tool.description.ifBlank { binding.tool.name }.take(2000))
        append("（插件：")
        append(binding.plugin.name)
        append("）")
        if (binding.tool.parameters.isNotEmpty()) {
            append("\n参数：")
            append(
                binding.tool.parameters.joinToString("，") { parameter ->
                    val required = if (parameter.required) "必填" else "可选"
                    "${parameter.name}(${parameter.type}, $required)"
                },
            )
        }
    }

    override suspend fun execute(arguments: JsonObject): String {
        if (binding.tool.advice) return binding.tool.description
        return runtime.callTool(
            plugin = binding.plugin,
            subpackageId = binding.subpackage.id,
            toolName = binding.tool.name,
            arguments = normalizeArguments(arguments),
        )
    }

    private fun normalizeArguments(arguments: JsonObject): JsonObject {
        val values = LinkedHashMap<String, JsonElement>(arguments)
        binding.tool.parameters.forEach { parameter ->
            val raw = values[parameter.name]
            if (raw == null) {
                if (parameter.required) error("缺少必填参数：${parameter.name}")
                return@forEach
            }
            val converted = convert(raw, parameter.type)
            if (converted == null && parameter.type.lowercase() in CONVERTED_TYPES) {
                error("参数 ${parameter.name} 不是合法的 ${parameter.type}")
            }
            if (converted != null) values[parameter.name] = converted
        }
        return JsonObject(values)
    }

    private fun convert(raw: JsonElement, type: String): JsonElement? {
        val text = (raw as? JsonPrimitive)?.contentOrNull ?: return raw
        return when (type.lowercase()) {
            "number" -> raw.jsonPrimitive.isString.let { isString ->
                if (!isString) raw else text.toDoubleOrNull()?.let { JsonPrimitive(it) }
            }
            "integer" -> raw.jsonPrimitive.isString.let { isString ->
                if (!isString) raw else text.toLongOrNull()?.let { JsonPrimitive(it) }
            }
            "boolean" -> raw.jsonPrimitive.isString.let { isString ->
                if (!isString) return raw
                when (text.lowercase()) {
                    "true", "1" -> JsonPrimitive(true)
                    "false", "0" -> JsonPrimitive(false)
                    else -> null
                }
            }
            "array" -> runCatching { Json.parseToJsonElement(text) }.getOrNull()
                ?.takeIf { it is JsonArray }
            "object" -> runCatching { Json.parseToJsonElement(text) }.getOrNull()
                ?.takeIf { it is JsonObject }
            else -> raw
        }
    }

    private companion object {
        val CONVERTED_TYPES = setOf("number", "integer", "boolean", "array", "object")
    }
}

class PluginToolInstaller(
    private val store: PluginStore,
    private val registry: ToolRegistry,
    private val runtime: PluginRuntime,
    private val onChanged: () -> Unit = {},
) {

    private val registered = linkedSetOf<String>()

    fun sync() {
        synchronized(this) {
            val bindings = store.enabledBindings()
            val names = bindings.map { store.runtimeName(it) }.toSet()
            registered.filterNot { it in names }.forEach(registry::unregister)
            bindings.forEach { binding -> registry.register(PluginTool(store, runtime, binding)) }
            registered.clear()
            registered.addAll(names)
            onChanged()
        }
    }
}
