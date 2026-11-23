package com.iris.moew.agent.subagent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 子代理定义存储：内置定义常驻，自定义定义保存在 filesDir/subagents.json。 */
class SubAgentStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val _definitions = MutableStateFlow(load())
    val definitions: StateFlow<List<SubAgentDefinition>> = _definitions.asStateFlow()

    fun find(id: String): SubAgentDefinition? =
        _definitions.value.firstOrNull { it.id == id || it.name == id }

    fun save(definition: SubAgentDefinition): SubAgentDefinition {
        val saved = if (definition.builtin) {
            definition
        } else {
            definition.copy(
                id = definition.id.ifBlank { "subagent-${UUID.randomUUID()}" },
                builtin = false,
            )
        }
        val custom = _definitions.value.filterNot { it.builtin }
        val next = custom.filterNot { it.id == saved.id } + saved
        _definitions.value = SubAgentDefinition.BUILTINS + next
        persist(next)
        return saved
    }

    fun delete(id: String): Boolean {
        if (_definitions.value.any { it.id == id && it.builtin }) return false
        val custom = _definitions.value.filterNot { it.builtin || it.id == id }
        _definitions.value = SubAgentDefinition.BUILTINS + custom
        persist(custom)
        return true
    }

    private fun load(): List<SubAgentDefinition> {
        val custom = runCatching {
            if (!file.isFile) emptyList() else {
                Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
                    (element as? JsonObject)?.toDefinition()
                }
            }
        }.getOrElse { emptyList() }
        return SubAgentDefinition.BUILTINS + custom
    }

    private fun persist(custom: List<SubAgentDefinition>) {
        runCatching {
            val json = buildJsonArray {
                custom.filterNot { it.builtin }.forEach { definition ->
                    add(buildJsonObject {
                        put("id", definition.id)
                        put("name", definition.name)
                        put("description", definition.description)
                        put("systemPrompt", definition.systemPrompt)
                        put("toolMode", definition.toolMode.name)
                    })
                }
            }.toString()
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
    }

    private fun JsonObject.toDefinition(): SubAgentDefinition? {
        val id = string("id") ?: return null
        val name = string("name")?.trim().orEmpty().ifBlank { return null }
        val mode = runCatching {
            SubAgentToolMode.valueOf(string("toolMode").orEmpty())
        }.getOrDefault(SubAgentToolMode.WORKSPACE)
        return SubAgentDefinition(
            id = id,
            name = name,
            description = string("description").orEmpty(),
            systemPrompt = string("systemPrompt").orEmpty(),
            toolMode = mode,
            allowedTools = mode.allowedTools(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val FILE_NAME = "subagents.json"
    }
}
