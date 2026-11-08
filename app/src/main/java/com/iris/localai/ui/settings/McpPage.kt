package com.iris.localai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.mcp.McpServerConfig
import com.iris.localai.mcp.McpState
import com.iris.localai.server.ApiAuth
import java.util.UUID

@Composable
internal fun McpPage(viewModel: McpViewModel = viewModel()) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var tab by remember { mutableStateOf(McpTab.CLIENT) }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<McpServerConfig?>(null) }
    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var keyText by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun copy(text: String, label: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        notice = "已复制$label"
    }

    SettingsPage {
        SettingsCard("MCP") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                McpTab.entries.forEach { item ->
                    FilterChip(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                    )
                }
            }
            Text(
                text = "客户端接入其它 MCP 服务；服务端把内置工具以 MCP 暴露给本机 AI 客户端。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message != null) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (error != null) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        when (tab) {
            McpTab.CLIENT -> {
                SettingsCard("MCP 客户端") {
                    Button(onClick = { adding = true }) { Text("添加 MCP 服务") }
                    if (servers.isEmpty()) {
                        Text(
                            text = "尚未配置 MCP 服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                servers.forEach { server ->
                    McpClientRow(
                        server = server,
                        connectedTools = tools[server.id].orEmpty(),
                        isConnected = (connected[server.id]?.size ?: 0) > 0,
                        onEnabled = { enabled ->
                            viewModel.updateServer(server.copy(enabled = enabled))
                        },
                        onConnect = { viewModel.connect(server) },
                        onDisconnect = { viewModel.disconnect(server.id) },
                        onEdit = { editing = server },
                        onDelete = { pendingDelete = server },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }

            McpTab.SERVER -> {
                SettingsCard("MCP 服务端") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用内置工具 MCP 服务", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = when (val current = state) {
                                    McpState.Stopped -> "已停止"
                                    is McpState.Running ->
                                        "运行中 · ${current.port} · ${if (current.lan) "局域网" else "仅本机"}"
                                    is McpState.Failed -> "启动失败：${current.message}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state is McpState.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = viewModel::setServerEnabled,
                        )
                    }
                    Text(
                        text = "工具：" + viewModel.builtInTools.joinToString { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SettingsCard("端口与网络") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { input ->
                                if (input.length <= 5 && input.all { it.isDigit() }) {
                                    portText = input
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { port ->
                                    viewModel.applyServerConfig(port, keyText, settings.allowLan)
                                    notice = "端口已应用"
                                }
                            },
                            enabled = portText.toIntOrNull() != null &&
                                portText != settings.port.toString(),
                        ) { Text("应用") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("允许局域网访问", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "需要 API Key，否则只监听 127.0.0.1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.allowLan,
                            onCheckedChange = { on ->
                                if (on && keyText.isBlank()) {
                                    val key = UUID.randomUUID().toString().replace("-", "").take(32)
                                    keyText = key
                                    viewModel.applyServerConfig(settings.port, key, true)
                                    notice = "已生成 Key 并开启局域网"
                                } else {
                                    viewModel.applyServerConfig(settings.port, keyText, on)
                                }
                            },
                        )
                    }
                }

                SettingsCard("MCP API Key") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = keyText,
                            onValueChange = { keyText = ApiAuth.sanitize(it) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Bearer / x-api-key") },
                            singleLine = true,
                            visualTransformation = if (showKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        )
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                val key = UUID.randomUUID().toString().replace("-", "").take(32)
                                keyText = key
                                viewModel.applyServerConfig(settings.port, key, settings.allowLan)
                                showKey = true
                                notice = "已生成新的 MCP Key"
                            },
                        ) { Text("生成") }
                        TextButton(
                            onClick = {
                                viewModel.applyServerConfig(settings.port, keyText, settings.allowLan)
                                notice = "MCP Key 已保存"
                            },
                        ) { Text("保存") }
                        TextButton(
                            onClick = { if (keyText.isNotBlank()) copy(keyText, "MCP Key") },
                            enabled = keyText.isNotBlank(),
                        ) { Text("复制") }
                        TextButton(
                            onClick = {
                                keyText = ""
                                viewModel.applyServerConfig(settings.port, "", false)
                                notice = "已清除 MCP Key 并关闭局域网"
                            },
                            enabled = keyText.isNotBlank() || settings.allowLan,
                        ) { Text("清除") }
                    }
                }

                if (state is McpState.Running) {
                    SettingsCard("MCP 端点") {
                        (state as McpState.Running).addresses.forEach { address ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = address,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { copy(address, "MCP 地址") }) {
                                    Text("复制")
                                }
                            }
                        }
                        Text(
                            text = "其它本机 AI 客户端可把该地址作为 MCP HTTP 服务接入。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (notice != null) {
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (adding || editing != null) {
        McpServerDialog(
            existing = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { config ->
                if (editing == null) viewModel.addServer(config) else viewModel.updateServer(config)
                adding = false
                editing = null
            },
        )
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 MCP 服务？") },
            text = { Text("「${server.name}」及其已接入工具会被移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteServer(server.id)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun McpClientRow(
    server: McpServerConfig,
    connectedTools: List<com.iris.localai.mcp.McpToolInfo>,
    isConnected: Boolean,
    onEnabled: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = server.enabled, onCheckedChange = onEnabled)
            }
            if (connectedTools.isNotEmpty()) {
                Text(
                    text = "已接入工具：" + connectedTools.joinToString { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isConnected) {
                    TextButton(onClick = onDisconnect) { Text("断开") }
                } else {
                    Button(onClick = onConnect) { Text("测试并接入") }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun McpServerDialog(
    existing: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "MCP 服务") }
    var url by remember(existing?.id) { mutableStateOf(existing?.url.orEmpty()) }
    var headers by remember(existing?.id) { mutableStateOf(existing?.headers.orEmpty()) }
    var enabled by remember(existing?.id) { mutableStateOf(existing?.enabled ?: true) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (existing == null) "添加 MCP 服务" else "编辑 MCP 服务",
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("MCP HTTP URL") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = headers,
                    onValueChange = { headers = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义请求头（每行 Key: Value）") },
                    minLines = 2,
                    maxLines = 4,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                (existing ?: McpServerConfig()).copy(
                                    name = name.trim().ifBlank { "MCP 服务" },
                                    url = url.trim(),
                                    headers = headers,
                                    enabled = enabled,
                                ),
                            )
                        },
                        enabled = url.isNotBlank(),
                    ) { Text("保存") }
                }
            }
        }
    }
}

private enum class McpTab(val label: String) {
    CLIENT("客户端"),
    SERVER("服务端"),
}
