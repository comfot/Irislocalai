package com.iris.localai.mcp

import android.content.Context
import com.iris.localai.storage.AppStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class McpTransportKind(val label: String) {
    STREAMABLE_HTTP("Streamable HTTP"),
    SSE("SSE"),
    STDIO("stdio"),
}

data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "MCP 服务",
    val transport: McpTransportKind = McpTransportKind.STREAMABLE_HTTP,
    val url: String = "",
    val headers: String = "",
    /** stdio 服务在 PRoot 容器内执行的命令；为空时回退到远程 HTTP。 */
    val command: String = "",
    val args: List<String> = emptyList(),
    /** 每行一条 KEY=VALUE。 */
    val env: String = "",
    val enabled: Boolean = true,
    /** OAuth 访问令牌；不写入外置 servers.json。 */
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scopes: String = "",
) {
    val isStdio: Boolean get() = transport == McpTransportKind.STDIO
}

data class McpServerSettings(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val apiKey: String = "",
    val allowLan: Boolean = false,
)

class McpStore(context: Context, private val storage: AppStorage) {

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

    /** 可在文件管理器中查看/编辑的 MCP 服务清单，位于 Android/data/<包名>/files/IRIS/mcp/servers.json。 */
    val configFile: File get() = storage.mcpServersFile

    init {
        if (!importIfNewer() && !configFile.isFile) export()
    }

    fun add(config: McpServerConfig) {
        _servers.update { it + config }
        persistServers()
        export()
    }

    fun update(config: McpServerConfig) {
        _servers.update { list -> list.map { if (it.id == config.id) config else it } }
        persistServers()
        export()
    }

    fun delete(id: String) {
        _servers.update { list -> list.filterNot { it.id == id } }
        persistServers()
        export()
    }

    fun updateTokens(id: String, accessToken: String, refreshToken: String) {
        _servers.update { list ->
            list.map { server ->
                if (server.id == id) server.copy(accessToken = accessToken, refreshToken = refreshToken) else server
            }
        }
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

    fun import(force: Boolean = false): Result<String> = runCatching {
        val target = configFile
        require(target.isFile) { "MCP 配置文件不存在：${target.path}" }
        require(target.length() <= MAX_BYTES) { "MCP 配置文件超过 1 MB，已拒绝导入" }
        if (!force && target.lastModified() <= prefs.getLong(KEY_FILE_WRITE_AT, 0L)) {
            return@runCatching "MCP 配置文件没有新改动"
        }
        val loaded = parseServers(target.readText())
        val merged = mergeSecrets(loaded)
        _servers.value = merged
        persistServers()
        prefs.edit().putLong(KEY_FILE_WRITE_AT, target.lastModified()).apply()
        "已从 ${target.name} 加载 ${merged.size} 个 MCP 服务"
    }

    fun export(): Result<String> = runCatching {
        val target = configFile
        val dir = target.parentFile ?: error("无法定位 MCP 配置目录")
        require(dir.isDirectory || dir.mkdirs()) { "无法创建 MCP 配置目录：${dir.path}" }
        val text = PRETTY.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJsonArray("servers") {
                    _servers.value.forEach { server ->
                        addJsonObject {
                            put("id", server.id)
                            put("name", server.name)
                            put("transport", server.transport.name)
                            put("url", server.url)
                            put("headers", server.headers)
                            put("command", server.command)
                            putJsonArray("args") { server.args.forEach { add(JsonPrimitive(it)) } }
                            put("env", server.env)
                            put("enabled", server.enabled)
                            put("tokenUrl", server.tokenUrl)
                            put("clientId", server.clientId)
                            put("scopes", server.scopes)
                        }
                    }
                }
            },
        )
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
        prefs.edit().putLong(KEY_FILE_WRITE_AT, target.lastModified()).apply()
        "已导出 MCP 配置到 ${target.path}"
    }

    private fun importIfNewer(): Boolean {
        val target = configFile
        if (!target.isFile || target.length() == 0L || target.length() > MAX_BYTES) return false
        if (target.lastModified() <= prefs.getLong(KEY_FILE_WRITE_AT, 0L)) return false
        return runCatching {
            _servers.value = mergeSecrets(parseServers(target.readText()))
            persistServers()
            prefs.edit().putLong(KEY_FILE_WRITE_AT, target.lastModified()).apply()
            true
        }.getOrElse {
            val backup = File(target.parentFile, "${target.name}.invalid-${System.currentTimeMillis()}")
            runCatching { target.renameTo(backup) }
            false
        }
    }

    private fun parseServers(raw: String): List<McpServerConfig> {
        val element = Json.parseToJsonElement(raw)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["servers"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val command = obj.string("command").orEmpty()
            val url = obj.string("url").orEmpty()
            val transport = runCatching {
                McpTransportKind.valueOf(obj.string("transport").orEmpty())
            }.getOrNull() ?: if (command.isNotBlank()) McpTransportKind.STDIO else McpTransportKind.STREAMABLE_HTTP
            if (transport == McpTransportKind.STDIO) {
                if (command.isBlank()) return@mapNotNull null
            } else if (url.isBlank()) {
                return@mapNotNull null
            }
            McpServerConfig(
                id = id,
                name = obj.string("name") ?: "MCP 服务",
                transport = transport,
                url = url,
                headers = obj.string("headers").orEmpty(),
                command = command,
                args = (obj["args"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
                env = obj.string("env").orEmpty(),
                enabled = obj.boolean("enabled") ?: true,
                accessToken = obj.string("accessToken").orEmpty(),
                refreshToken = obj.string("refreshToken").orEmpty(),
                tokenUrl = obj.string("tokenUrl").orEmpty(),
                clientId = obj.string("clientId").orEmpty(),
                clientSecret = obj.string("clientSecret").orEmpty(),
                scopes = obj.string("scopes").orEmpty(),
            )
        }.distinctBy { it.id }
    }

    private fun mergeSecrets(loaded: List<McpServerConfig>): List<McpServerConfig> {
        val existing = _servers.value.associateBy { it.id }
        return loaded.map { server ->
            val old = existing[server.id] ?: return@map server
            server.copy(
                accessToken = server.accessToken.ifBlank { old.accessToken },
                refreshToken = server.refreshToken.ifBlank { old.refreshToken },
                clientSecret = server.clientSecret.ifBlank { old.clientSecret },
            )
        }
    }

    private fun persistServers() {
        val json = buildJsonArray {
            _servers.value.forEach { server ->
                addJsonObject {
                    put("id", server.id)
                    put("name", server.name)
                    put("transport", server.transport.name)
                    put("url", server.url)
                    put("headers", server.headers)
                    put("command", server.command)
                    putJsonArray("args") { server.args.forEach { add(it) } }
                    put("env", server.env)
                    put("enabled", server.enabled)
                    put("accessToken", server.accessToken)
                    put("refreshToken", server.refreshToken)
                    put("tokenUrl", server.tokenUrl)
                    put("clientId", server.clientId)
                    put("clientSecret", server.clientSecret)
                    put("scopes", server.scopes)
                }
            }
        }.toString()
        prefs.edit().putString(KEY_SERVERS, json).apply()
    }

    private fun loadServers(): List<McpServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching { parseServers(raw) }.getOrElse { emptyList() }
    }

    private companion object {
        const val PREFS = "iris_mcp"
        const val KEY_SERVERS = "servers"
        const val KEY_ENABLED = "server_enabled"
        const val KEY_PORT = "server_port"
        const val KEY_KEY = "server_key"
        const val KEY_LAN = "server_lan"
        const val KEY_FILE_WRITE_AT = "servers_file_write_at"
        const val MAX_BYTES = 1L * 1024 * 1024

        val PRETTY = Json { prettyPrint = true }

        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.boolean(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    }
}
