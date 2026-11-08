package com.iris.localai.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.util.UUID

data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "MCP 服务",
    val url: String = "",
    val headers: String = "",
    val enabled: Boolean = true,
)

data class McpServerSettings(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val apiKey: String = "",
    val allowLan: Boolean = false,
)

class McpStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    private val _settings = MutableStateFlow(
        McpServerSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            port = prefs.getInt(KEY_PORT, 8765),
            apiKey = prefs.getString(KEY_KEY, "") ?: "",
            allowLan = prefs.getBoolean(KEY_LAN, false),
        ),
    )
    val settings: StateFlow<McpServerSettings> = _settings.asStateFlow()

    fun add(config: McpServerConfig) {
        _servers.update { it + config }
        persistServers()
    }

    fun update(config: McpServerConfig) {
        _servers.update { list -> list.map { if (it.id == config.id) config else it } }
        persistServers()
    }

    fun delete(id: String) {
        _servers.update { list -> list.filterNot { it.id == id } }
        persistServers()
    }

    fun updateSettings(transform: (McpServerSettings) -> McpServerSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putInt(KEY_PORT, next.port)
            .putString(KEY_KEY, next.apiKey)
            .putBoolean(KEY_LAN, next.allowLan)
            .apply()
    }

    private fun persistServers() {
        val json = buildJsonArray {
            _servers.value.forEach { server ->
                addJsonObject {
                    put("id", server.id)
                    put("name", server.name)
                    put("url", server.url)
                    put("headers", server.headers)
                    put("enabled", server.enabled)
                }
            }
        }.toString()
        prefs.edit().putString(KEY_SERVERS, json).apply()
    }

    private fun loadServers(): List<McpServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                McpServerConfig(
                    id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                        ?: return@mapNotNull null,
                    name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "MCP 服务",
                    url = (obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    headers = (obj["headers"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    enabled = (obj["enabled"] as? JsonPrimitive)?.contentOrNull != "false",
                )
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        const val PREFS = "iris_mcp"
        const val KEY_SERVERS = "servers"
        const val KEY_ENABLED = "server_enabled"
        const val KEY_PORT = "server_port"
        const val KEY_KEY = "server_key"
        const val KEY_LAN = "server_lan"
    }
}
