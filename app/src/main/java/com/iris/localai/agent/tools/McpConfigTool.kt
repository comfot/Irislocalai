package com.iris.localai.agent.tools

import com.iris.localai.agent.Tool
import com.iris.localai.agent.ToolRegistry
import com.iris.localai.mcp.McpConnectionManager
import com.iris.localai.mcp.McpServerConfig
import com.iris.localai.mcp.McpStore
import com.iris.localai.mcp.McpTransportKind
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 让 Agent 直接管理 MCP 客户端配置。工具名以 mcp_ 开头，MCP 服务端对外暴露时会过滤掉，
 * 避免外部客户端通过本机 MCP 服务反改配置。
 */
class McpConfigTool(
    private val store: McpStore,
    private val connections: McpConnectionManager,
    private val registry: ToolRegistry,
) : Tool {

    override val name: String = "mcp_config"

    override val description: String =
        "管理 MCP 客户端服务。op=list 列出已配置服务；op=add 添加（name、transport、url 或 command/args）；" +
            "op=update 修改（id 或 name，加要改的字段）；op=remove 删除；op=connect 接入；" +
            "op=disconnect 断开；op=tools 查看已接入工具；op=path 查看配置文件；" +
            "op=export 立即写出；op=reload 从配置文件重新加载。" +
            "transport 可选 streamable_http（默认）/ sse / stdio；stdio 在 PRoot 容器内执行 command，" +
            "args 为字符串数组，env 每行一条 KEY=VALUE。OAuth 可传 tokenUrl/clientId/clientSecret/scopes/accessToken/refreshToken。" +
            "配置会自动镜像到 Android/data/com.iris.localai/files/IRIS/mcp/servers.json；" +
            "headers 每行一条 Key: Value；不会回显请求头或 OAuth 密钥。"

    override suspend fun execute(arguments: JsonObject): String {
        val op = arguments.string("op").lowercase().ifBlank { "list" }
        return when (op) {
            "list", "ls" -> listServers()
            "add", "create" -> add(arguments)
            "update", "edit" -> update(arguments)
            "remove", "delete" -> remove(arguments)
            "connect" -> connect(arguments)
            "disconnect" -> disconnect(arguments)
            "tools" -> listTools(arguments)
            "path" -> "MCP 配置文件：${store.configFile.path}"
            "export" -> store.export().fold(
                onSuccess = { it },
                onFailure = { "导出 MCP 配置失败：${it.message ?: "未知错误"}" },
            )
            "reload", "import" -> reload()
            else -> "不支持的操作「$op」，可用 list / add / update / remove / connect / disconnect / tools / path / export / reload"
        }
    }

    private fun listServers(): String {
        val servers = store.servers.value
        if (servers.isEmpty()) {
            return "尚未配置 MCP 服务。可用 op=add 添加，例如：" +
                """{"op":"add","name":"files","url":"http://127.0.0.1:9000/mcp"}""" +
                "\n配置文件：${store.configFile.path}"
        }
        val connected = connections.connected.value
        return buildString {
            appendLine("已配置 MCP 服务：")
            servers.forEach { server ->
                val count = connected[server.id]?.size
                appendLine(
                    "- ${server.name}（id: ${server.id}）${endpointSummary(server)} · " +
                        if (count == null) "未接入" else "已接入 $count 个工具",
                )
            }
            appendLine("使用 op=connect 接入并注册工具，接入后可直接调用 mcp_ 前缀的远端工具。")
            appendLine("配置文件：${store.configFile.path}")
        }
    }

    private suspend fun add(arguments: JsonObject): String {
        val transport = parseTransport(arguments.string("transport")) ?: return "transport 只能是 streamable_http / sse / stdio"
        val command = arguments.string("command")
        val url = arguments.string("url")
        if (transport == McpTransportKind.STDIO) {
            if (command.isBlank()) {
                return """stdio 需要 command。示例：{"op":"add","name":"files","transport":"stdio","command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/workspace"]}"""
            }
        } else {
            if (url.isBlank()) return """缺少 url。示例：{"op":"add","name":"files","url":"http://127.0.0.1:9000/mcp"}"""
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "url 需要以 http:// 或 https:// 开头"
            }
        }
        val id = arguments.string("id").ifBlank { UUID.randomUUID().toString() }
        if (store.servers.value.any { it.id == id }) return "已存在相同 id 的 MCP 服务：$id"
        val server = McpServerConfig(
            id = id,
            name = arguments.string("name").ifBlank { "MCP 服务" },
            transport = transport,
            url = url,
            headers = arguments.string("headers"),
            command = command,
            args = argsOf(arguments),
            env = arguments.string("env"),
            accessToken = arguments.string("accessToken"),
            refreshToken = arguments.string("refreshToken"),
            tokenUrl = arguments.string("tokenUrl"),
            clientId = arguments.string("clientId"),
            clientSecret = arguments.string("clientSecret"),
            scopes = arguments.string("scopes"),
        )
        store.add(server)
        val message = "已添加 ${server.name}（id: ${server.id}）。"
        if (arguments.boolean("connect") != false) return message + connectServer(server)
        return message + "需要时调用 op=connect。"
    }

    private fun update(arguments: JsonObject): String {
        val server = find(arguments) ?: return notFound(arguments)
        val transport = if (arguments.containsKey("transport")) {
            parseTransport(arguments.string("transport")) ?: return "transport 只能是 streamable_http / sse / stdio"
        } else {
            server.transport
        }
        val command = if (arguments.containsKey("command")) arguments.string("command") else server.command
        val url = if (arguments.containsKey("url")) arguments.string("url") else server.url
        if (transport == McpTransportKind.STDIO) {
            if (command.isBlank()) return "stdio 需要 command"
        } else if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return "url 需要以 http:// 或 https:// 开头"
        }
        val updated = server.copy(
            name = if (arguments.containsKey("name")) {
                arguments.string("name").ifBlank { server.name }
            } else {
                server.name
            },
            transport = transport,
            url = url,
            headers = if (arguments.containsKey("headers")) arguments.string("headers") else server.headers,
            command = command,
            args = if (arguments.containsKey("args")) argsOf(arguments) else server.args,
            env = if (arguments.containsKey("env")) arguments.string("env") else server.env,
            enabled = arguments.boolean("enabled") ?: server.enabled,
            accessToken = if (arguments.containsKey("accessToken")) arguments.string("accessToken") else server.accessToken,
            refreshToken = if (arguments.containsKey("refreshToken")) arguments.string("refreshToken") else server.refreshToken,
            tokenUrl = if (arguments.containsKey("tokenUrl")) arguments.string("tokenUrl") else server.tokenUrl,
            clientId = if (arguments.containsKey("clientId")) arguments.string("clientId") else server.clientId,
            clientSecret = if (arguments.containsKey("clientSecret")) arguments.string("clientSecret") else server.clientSecret,
            scopes = if (arguments.containsKey("scopes")) arguments.string("scopes") else server.scopes,
        )
        store.update(updated)
        connections.disconnect(server.id)
        return "已更新 ${updated.name}（id: ${updated.id}），配置变更后已断开，需要重新 op=connect。"
    }

    private fun remove(arguments: JsonObject): String {
        val server = find(arguments) ?: return notFound(arguments)
        connections.disconnect(server.id)
        store.delete(server.id)
        return "已删除 ${server.name}（id: ${server.id}）。"
    }

    private suspend fun connect(arguments: JsonObject): String {
        val server = find(arguments) ?: return notFound(arguments)
        return connectServer(server)
    }

    private suspend fun connectServer(server: McpServerConfig): String {
        val result = connections.connect(server)
        return result.fold(
            onSuccess = { tools ->
                val names = connections.connected.value[server.id].orEmpty()
                val list = names.ifEmpty { tools.map { it.name } }
                "已接入 ${server.name} 的 ${list.size} 个工具：${list.joinToString("、")}"
            },
            onFailure = { "接入 ${server.name} 失败：${it.message ?: "连接失败"}" },
        )
    }

    private fun disconnect(arguments: JsonObject): String {
        val server = find(arguments) ?: return notFound(arguments)
        connections.disconnect(server.id)
        return "已断开 ${server.name}（id: ${server.id}）。"
    }

    private fun reload(): String {
        connections.connected.value.keys.toList().forEach { connections.disconnect(it) }
        return store.import(force = true).fold(
            onSuccess = { "$it；当前连接已断开，需要重新 op=connect。" },
            onFailure = { "读取 MCP 配置文件失败：${it.message ?: "未知错误"}" },
        )
    }

    private fun listTools(arguments: JsonObject): String {
        val query = arguments.string("id").ifBlank { arguments.string("name") }
        val targets = if (query.isBlank()) store.servers.value else listOfNotNull(find(arguments))
        if (targets.isEmpty()) return notFound(arguments)
        val connected = connections.connected.value
        return buildString {
            targets.forEach { server ->
                val names = connected[server.id]
                if (names == null) {
                    appendLine("- ${server.name}（id: ${server.id}）：未接入")
                } else {
                    appendLine("- ${server.name}（id: ${server.id}）：${names.size} 个工具")
                    names.forEach { name ->
                        val summary = registry.find(name)?.description?.take(120).orEmpty()
                        appendLine("  · $name${if (summary.isBlank()) "" else "：$summary"}")
                    }
                }
            }
        }.trimEnd()
    }

    private fun find(arguments: JsonObject): McpServerConfig? {
        val query = arguments.string("id").ifBlank { arguments.string("name") }.trim()
        if (query.isBlank()) return null
        val servers = store.servers.value
        return servers.firstOrNull { it.id.equals(query, ignoreCase = true) }
            ?: servers.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: servers.firstOrNull { it.name.contains(query, ignoreCase = true) }
    }

    private fun notFound(arguments: JsonObject): String {
        val query = arguments.string("id").ifBlank { arguments.string("name") }
        val available = store.servers.value.joinToString("、") { "${it.name}（${it.id}）" }
        return if (query.isBlank()) {
            "缺少 id 或 name。当前服务：${available.ifBlank { "无" }}"
        } else {
            "没有找到「$query」。当前服务：${available.ifBlank { "无" }}"
        }
    }

    private fun safeUrl(url: String): String {
        val withoutQuery = url.substringBefore('?')
        return withoutQuery.replace(Regex("//[^/@]+@"), "//")
    }

    private fun endpointSummary(server: McpServerConfig): String =
        if (server.isStdio) {
            "${server.transport.label} · ${server.command} ${server.args.joinToString(" ")}".trim()
        } else {
            "${server.transport.label} · ${safeUrl(server.url)}"
        }

    private fun parseTransport(raw: String): McpTransportKind? {
        if (raw.isBlank()) return McpTransportKind.STREAMABLE_HTTP
        return when (raw.trim().lowercase().replace('-', '_')) {
            "http", "streamable", "streamable_http" -> McpTransportKind.STREAMABLE_HTTP
            "sse" -> McpTransportKind.SSE
            "stdio" -> McpTransportKind.STDIO
            else -> null
        }
    }

    private fun argsOf(arguments: JsonObject): List<String> {
        val array = arguments["args"] as? JsonArray
        if (array != null) return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        val raw = arguments.string("args")
        if (raw.isBlank()) return emptyList()
        return raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
