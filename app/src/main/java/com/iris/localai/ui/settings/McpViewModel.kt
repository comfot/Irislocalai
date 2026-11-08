package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.mcp.McpServerConfig
import com.iris.localai.mcp.McpServerSettings
import com.iris.localai.mcp.McpToolInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    val servers = graph.mcp.servers
    val settings = graph.mcp.settings
    val state = graph.mcpServer.state
    val connected = graph.mcpConnections.connected
    val builtInTools = graph.tools.all()

    private val _tools = MutableStateFlow<Map<String, List<McpToolInfo>>>(emptyMap())
    val tools: StateFlow<Map<String, List<McpToolInfo>>> = _tools.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun addServer(config: McpServerConfig) {
        graph.mcp.add(config)
        _message.value = "已添加 MCP 服务"
    }

    fun updateServer(config: McpServerConfig) {
        graph.mcp.update(config)
        graph.mcpConnections.disconnect(config.id)
        _tools.update { it - config.id }
        _message.value = "已保存，需要重新接入工具"
    }

    fun deleteServer(id: String) {
        graph.mcpConnections.disconnect(id)
        _tools.update { it - id }
        graph.mcp.delete(id)
        _message.value = "已删除 MCP 服务"
    }

    fun connect(server: McpServerConfig) {
        viewModelScope.launch {
            graph.mcpConnections.connect(server)
                .onSuccess { list ->
                    _tools.update { it + (server.id to list) }
                    _message.value = "已接入 ${list.size} 个工具"
                    _error.value = null
                }
                .onFailure {
                    _error.value = "接入失败：${it.message ?: "连接失败"}"
                }
        }
    }

    fun disconnect(id: String) {
        graph.mcpConnections.disconnect(id)
        _tools.update { it - id }
        _message.value = "已断开 MCP 服务"
    }

    fun setServerEnabled(enabled: Boolean) {
        graph.mcp.updateSettings { it.copy(enabled = enabled) }
        if (enabled) graph.mcpServer.start() else graph.mcpServer.stop()
    }

    fun applyServerConfig(
        port: Int,
        apiKey: String,
        allowLan: Boolean,
    ) {
        graph.mcp.updateSettings {
            it.copy(
                port = port.coerceIn(1024, 65535),
                apiKey = apiKey,
                allowLan = allowLan,
            )
        }
        if (graph.mcp.settings.value.enabled) graph.mcpServer.restart()
    }

    fun updateServerSettings(transform: (McpServerSettings) -> McpServerSettings) {
        graph.mcp.updateSettings(transform)
    }

    fun clearMessage() {
        _message.value = null
        _error.value = null
    }
}
