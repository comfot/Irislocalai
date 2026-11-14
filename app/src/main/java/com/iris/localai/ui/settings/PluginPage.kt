package com.iris.localai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.plugin.InstalledPlugin
import com.iris.localai.plugin.resolveUiModuleId
import kotlinx.coroutines.delay

@Composable
internal fun PluginPage(
    onOpenMarket: () -> Unit,
    viewModel: PluginViewModel = viewModel(),
) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val registrations by viewModel.registrations.collectAsStateWithLifecycle()
    val registrationErrors by viewModel.registrationErrors.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InstalledPlugin?>(null) }
    var envTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var openUi by remember { mutableStateOf<Triple<InstalledPlugin, String, String>?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importUri(context, uri)
    }

    LaunchedEffect(message, error) {
        if (message != null || error != null) {
            delay(4000)
            viewModel.clearMessage()
            viewModel.clearError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadRegistrations()
    }

    SettingsPage {
        SettingsCard("插件") {
            Text(
                text = "支持 Operit ToolPkg（manifest.json / manifest.hjson + main 注册 + 子包 METADATA）。导入后声明的工具会进入智能体工具表；加载时会自动转译 NativeInterface.callTool / toolCall 到宿主工具表，可直接调用已启用工具；同一插件内支持 CommonJS require、ToolPkg.ipc 与 WebAssembly 标量调用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { importer.launch("*/*") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("导入 ZIP") }
                Button(
                    onClick = { showUrlDialog = true },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("从 URL 安装") }
                Button(
                    onClick = onOpenMarket,
                    modifier = Modifier.weight(1f),
                ) { Text("插件市场") }
            }
            Text(
                text = "沙箱限制：插件只能读写自身目录/配置目录与访问公网 HTTP；Java 仅提供 File/Files/Base64/MessageDigest/集合等白名单兼容层，任意 Java 反射与接口代理不可用；Compose DSL UI 可通过兼容渲染器打开（常用组件支持，复杂组件会降级）。插件内嵌套调用自身工具暂不支持。请只安装可信来源的插件。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Text("正在处理插件包…", style = MaterialTheme.typography.labelSmall)
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        if (plugins.isEmpty()) {
            SettingsCard {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("尚未安装插件", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "从市场、文件或 URL 安装，或直接把 Operit ToolPkg 目录压缩为 ZIP 后导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        plugins.forEach { plugin ->
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = listOfNotNull(
                                plugin.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                                plugin.origin.label,
                                plugin.author.joinToString(" / ").takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusPill(if (plugin.enabled) "已启用" else "已停用")
                    IconButton(onClick = { envTarget = plugin }) {
                        Icon(Icons.Outlined.Key, contentDescription = "环境变量")
                    }
                    IconButton(onClick = { pendingDelete = plugin }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除插件")
                    }
                }
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = listOfNotNull(
                        "${plugin.toolCount} 个工具",
                        "${plugin.subpackages.size} 个子包",
                        plugin.apiVersion.takeIf { it.isNotBlank() }?.let { "API $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val registered = registrations[plugin.id]
                val registrationError = registrationErrors[plugin.id]
                if (registrationError != null) {
                    Text(
                        text = "main 注册失败：$registrationError",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (plugin.enabled && registered == null) {
                    Text(
                        text = "尚未读取到 main 注册；请确认 manifest.main 指向有效 JS 并导出 registerToolPkg。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (registered != null && !registered.isEmpty) {
                    Text(
                        text = "main 注册：${registered.hooks.size} 个 Hook · " +
                            "${registered.ui.size} 个 UI/导航 · ${registered.aiProviders.size} 个 Provider",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    registered.ui.forEach { ui ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "UI：${ui.title.ifBlank { ui.id }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = ui.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    openUi = Triple(plugin, registered.resolveUiModuleId(ui), ui.title.ifBlank { ui.id })
                                },
                                enabled = plugin.enabled,
                            ) { Text("打开") }
                        }
                    }
                    registered.aiProviders.forEach { provider ->
                        Text(
                            text = "AI Provider：$provider（已注册，未接入模型选择）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (plugin.requirements.isNotEmpty()) {
                    Text(
                        text = "依赖：" + plugin.requirements.joinToString("，") { requirement ->
                            requirement.id + requirement.minVersion?.let { " >= $it" }.orEmpty()
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = { viewModel.setEnabled(plugin, it) },
                    )
                }
                if (plugin.tools.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            expanded = if (plugin.id in expanded) expanded - plugin.id else expanded + plugin.id
                        },
                    ) {
                        Text(if (plugin.id in expanded) "收起工具" else "查看工具（${plugin.tools.size}）")
                    }
                    if (plugin.id in expanded) {
                        plugin.tools.forEach { tool ->
                            Column(modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = tool.description.ifBlank { "无描述" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (tool.parameters.isNotEmpty()) {
                                    Text(
                                        text = tool.parameters.joinToString("，") { parameter ->
                                            "${parameter.name}: ${parameter.type}" +
                                                if (parameter.required) "（必填）" else "（可选）"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        PluginUrlDialog(
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                showUrlDialog = false
                viewModel.installUrl(url)
            },
        )
    }

    openUi?.let { (plugin, moduleId, title) ->
        PluginUiDialog(
            plugin = plugin,
            moduleId = moduleId,
            title = title,
            viewModel = viewModel,
            onDismiss = { openUi = null },
        )
    }

    envTarget?.let { plugin ->
        PluginEnvDialog(
            plugin = plugin,
            viewModel = viewModel,
            onDismiss = { envTarget = null },
        )
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除插件") },
            text = { Text("确定删除「${plugin.name}」及其文件？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(plugin)
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
internal fun PluginUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 URL 安装插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("ZIP 地址") },
                    placeholder = { Text("https://…/plugin.zip") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "仅支持公网 HTTP/HTTPS，大小上限 64 MB。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank(),
            ) { Text("安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
