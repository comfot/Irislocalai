package com.iris.localai.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.code.CodeImageCatalog
import com.iris.localai.code.CodeRuntime
import com.iris.localai.code.ToolAuthorization
import java.util.Locale

@Composable
internal fun CodeManagementPage(viewModel: CodeViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val proot by viewModel.prootState.collectAsStateWithLifecycle()
    var portText by remember(settings.sshPort) { mutableStateOf(settings.sshPort.toString()) }
    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestStorageAccess() {
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(perApp) }
            .recoverCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            .onFailure {
                Toast.makeText(context, "无法打开存储权限设置", Toast.LENGTH_SHORT).show()
            }
    }

    LaunchedEffect(settings.imageId) {
        viewModel.refreshProot()
    }

    SettingsPage {
        SettingsCard("编程管理") {
            CodeSwitch(
                title = "启用命令执行",
                summary = "仅作用于工作空间与综合空间；生活/操作空间不加载 Shell 工具",
                checked = settings.enabled,
            ) { on -> viewModel.update { it.copy(enabled = on) } }

            Text("运行时", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CodeRuntime.entries.forEach { runtime ->
                    FilterChip(
                        selected = settings.runtime == runtime,
                        onClick = { viewModel.update { it.copy(runtime = runtime) } },
                        label = { Text(runtime.label) },
                    )
                }
            }
            Text(
                text = "应用 Shell 与 PRoot 容器已接入；SSH 运行时仍待接入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("容器与镜像") {
            CodeImageCatalog.images.forEach { image ->
                val selected = settings.imageId == image.id
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.update { it.copy(imageId = image.id) }
                        viewModel.refreshProot()
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(image.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${image.version} · " +
                                    if (image.approximateMb > 0) {
                                        "约 ${image.approximateMb} MB"
                                    } else {
                                        "本地镜像"
                                    } + " · ${image.note}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Text(
                                text = "已选",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            OutlinedTextField(
                value = settings.imagePath,
                onValueChange = { path -> viewModel.update { it.copy(imagePath = path) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("本地 rootfs 压缩包（.tar.gz / .tar，优先使用）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = settings.imageUrl,
                onValueChange = { url -> viewModel.update { it.copy(imageUrl = url) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("镜像下载地址（留空使用内置 Alpine）") },
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "PRoot 启动器：" + when {
                    !proot.loaded -> "检查中…"
                    proot.prootAvailable -> "已内置（${proot.arch}）"
                    else -> "缺失"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (!proot.loaded || proot.prootAvailable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = "Rootfs：" + if (proot.installed) {
                    "已安装 · ${formatSize(proot.rootfsBytes)}"
                } else {
                    "未安装"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (proot.installed) {
                Text(
                    text = proot.rootfsPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (proot.installing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = proot.progressText.ifBlank { "正在安装…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = viewModel::cancelInstall) { Text("取消安装") }
            } else {
                Button(
                    onClick = viewModel::installProot,
                    enabled = proot.loaded && proot.prootAvailable,
                ) {
                    Text(if (proot.installed) "重新安装 rootfs" else "安装 rootfs")
                }
            }
            proot.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "PRoot 仅支持应用内工作区；SAF 文件夹请使用应用 Shell。" +
                    "使用前需开启「启用命令执行」，并把 Shell 执行授权设为允许。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("本机存储挂载") {
            Text(
                text = "PRoot 容器启动时会挂载以下目录：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow("共享存储", "/sdcard")
            InfoRow("应用外置目录", "/mnt/iris")
            Text(
                text = context.getExternalFilesDir(null)?.absolutePath ?: "应用外置目录不可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (storageGranted) "所有文件访问：已授权" else "所有文件访问：未授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (storageGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!storageGranted) {
                    TextButton(onClick = { requestStorageAccess() }) { Text("去授权") }
                }
            }
            Text(
                text = "未授权时 /sdcard 只能读写应用自身目录；Android 11+ 需授予「所有文件访问」才能读写下载、图片等共享目录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("命令执行通道") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodeNumberField(
                    label = "超时（秒）",
                    value = settings.timeoutSeconds,
                    modifier = Modifier.weight(1f),
                    onCommit = { value ->
                        viewModel.update { it.copy(timeoutSeconds = value.coerceIn(1, 300)) }
                    },
                )
                CodeNumberField(
                    label = "输出上限（字符）",
                    value = settings.outputLimit,
                    modifier = Modifier.weight(1f),
                    onCommit = { value ->
                        viewModel.update { it.copy(outputLimit = value.coerceIn(1024, 200_000)) }
                    },
                )
            }
            CodeSwitch(
                title = "允许网络访问",
                summary = "允许命令访问网络（当前仅记录配置）",
                checked = settings.networkEnabled,
            ) { on -> viewModel.update { it.copy(networkEnabled = on) } }

            if (settings.runtime == CodeRuntime.SSH) {
                OutlinedTextField(
                    value = settings.sshHost,
                    onValueChange = { host -> viewModel.update { it.copy(sshHost = host) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SSH Host") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { input ->
                            if (input.length <= 5 && input.all { it.isDigit() }) {
                                portText = input
                                input.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port ->
                                    viewModel.update { it.copy(sshPort = port) }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = settings.sshUser,
                        onValueChange = { user -> viewModel.update { it.copy(sshUser = user) } },
                        modifier = Modifier.weight(1f),
                        label = { Text("用户名") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = settings.sshKeyPath,
                    onValueChange = { path -> viewModel.update { it.copy(sshKeyPath = path) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SSH 私钥路径") },
                    singleLine = true,
                )
            }
        }

        SettingsCard("工具授权") {
            AuthorizationRow(
                title = "Shell 执行",
                description = "执行命令的首层授权",
                selected = settings.shellAuthorization,
                onSelect = { value -> viewModel.update { it.copy(shellAuthorization = value) } },
            )
            AuthorizationRow(
                title = "文件读写",
                description = "文件工具与编辑",
                selected = settings.fileAuthorization,
                onSelect = { value -> viewModel.update { it.copy(fileAuthorization = value) } },
            )
            AuthorizationRow(
                title = "网络访问",
                description = "联网搜索与 HTTP",
                selected = settings.networkAuthorization,
                onSelect = { value -> viewModel.update { it.copy(networkAuthorization = value) } },
            )
            AuthorizationRow(
                title = "代码运行",
                description = "脚本与构建命令",
                selected = settings.codeAuthorization,
                onSelect = { value -> viewModel.update { it.copy(codeAuthorization = value) } },
            )

            OutlinedTextField(
                value = settings.allowedPrefixes,
                onValueChange = { text -> viewModel.update { it.copy(allowedPrefixes = text) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("允许命令前缀（逗号或换行）") },
                placeholder = { Text("留空表示不限制前缀") },
                minLines = 2,
                maxLines = 4,
            )
            OutlinedTextField(
                value = settings.deniedPrefixes,
                onValueChange = { text -> viewModel.update { it.copy(deniedPrefixes = text) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("禁止命令前缀（逗号或换行）") },
                minLines = 2,
                maxLines = 4,
            )
            Text(
                text = "授权与命令前缀参考 AiCode 的逐工具授权；Shell 当前在应用沙盒中运行。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun AuthorizationRow(
    title: String,
    description: String,
    selected: ToolAuthorization,
    onSelect: (ToolAuthorization) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            AssistChip(
                onClick = { expanded = true },
                label = { Text(selected.label) },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ToolAuthorization.entries.forEach { authorization ->
                    DropdownMenuItem(
                        text = { Text(authorization.label) },
                        onClick = {
                            onSelect(authorization)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CodeNumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 7 && input.all { it.isDigit() }) {
                text = input
                input.toIntOrNull()?.let(onCommit)
            }
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
