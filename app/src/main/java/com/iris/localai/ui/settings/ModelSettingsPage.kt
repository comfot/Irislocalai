package com.iris.localai.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.AppSettings
import com.iris.localai.data.CloudVendor
import com.iris.localai.data.ConnectionTestState
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.ModelCapabilities
import com.iris.localai.data.ModelProfile
import com.iris.localai.data.ModelRole
import com.iris.localai.data.ProviderConfig
import com.iris.localai.data.ProviderKind
import com.iris.localai.data.RoleConfig
import com.iris.localai.engine.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSettingsPage(
    settings: AppSettings,
    engineState: EngineState,
    viewModel: SettingsViewModel,
    onOpenMarket: () -> Unit,
) {
    val profiles by viewModel.modelProfiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeModelId.collectAsStateWithLifecycle()
    val testState by viewModel.connectionTest.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var profileMenu by remember { mutableStateOf(false) }

    SettingsPage {
        SettingsCard("配置档案") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    AssistChip(
                        onClick = { profileMenu = true },
                        label = {
                            Text(
                                text = active?.name ?: "无配置",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = profileMenu,
                        onDismissRequest = { profileMenu = false },
                    ) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(profile.name)
                                        Text(
                                            text = profile.provider.summary(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectModelProfile(profile.id)
                                    profileMenu = false
                                },
                                trailingIcon = {
                                    if (profile.id == activeId) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                TextButton(
                    onClick = {
                        nameInput = "新配置"
                        dialog = ProfileDialog.Create
                    },
                ) { Text("新建") }
                TextButton(
                    onClick = {
                        active?.let {
                            nameInput = it.name
                            dialog = ProfileDialog.Rename(it.id)
                        }
                    },
                    enabled = active != null,
                ) { Text("重命名") }
                TextButton(
                    onClick = {
                        active?.let { dialog = ProfileDialog.Delete(it.id, it.name) }
                    },
                    enabled = active != null && profiles.size > 1,
                ) { Text("删除") }
                TextButton(
                    onClick = viewModel::testActiveConnection,
                    enabled = active != null && testState !is ConnectionTestState.Testing,
                ) { Text("测试连接") }
            }

            when (val state = testState) {
                ConnectionTestState.Idle -> Unit
                ConnectionTestState.Testing -> Text(
                    text = "正在测试连接…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ConnectionTestState.Done -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        if (active != null) {
            ProviderSettingsCard(
                provider = active.provider,
                onUpdate = { provider ->
                    viewModel.updateActiveModel { it.copy(provider = provider) }
                },
                onOpenMarket = onOpenMarket,
            )

            GlobalSettingsCard(
                profile = active,
                onUpdate = { viewModel.updateActiveModel(it) },
            )

            ModelRole.entries.forEach { role ->
                RoleConfigCard(
                    profileId = active.id,
                    role = role,
                    config = active.role(role),
                    onUpdate = { config ->
                        viewModel.updateActiveModel {
                            it.copy(roles = it.roles + (role to config))
                        }
                    },
                )
            }
        }

        SettingsCard("加载选项") {
            Text("视觉质量（图片最长边）", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(512, 768, 1024, 1280).forEach { side ->
                    FilterChip(
                        selected = settings.visionQualitySide == side,
                        onClick = { viewModel.update { it.copy(visionQualitySide = side) } },
                        label = { Text("$side px") },
                    )
                }
            }

            Text("下载源", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DownloadSource.entries.forEach { source ->
                    FilterChip(
                        selected = settings.downloadSource == source,
                        onClick = { viewModel.update { it.copy(downloadSource = source) } },
                        label = { Text(source.label) },
                    )
                }
            }

        }

        ExpandableSettingsCard(
            title = "高级加载参数",
            summary = "Flash Attention、KV 缓存、批大小与 mmap",
        ) {
            CapabilitySwitch(
                title = "Flash Attention",
                summary = "降低长上下文显存/内存占用",
                checked = settings.flashAttention,
            ) { on -> viewModel.update { it.copy(flashAttention = on) } }

            Text("KV 缓存类型", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("F16", "Q8_0", "Q4_0").forEachIndexed { index, label ->
                    FilterChip(
                        selected = settings.kvCacheType == index,
                        onClick = { viewModel.update { it.copy(kvCacheType = index) } },
                        label = { Text(label) },
                    )
                }
            }

            SliderRow(
                title = "批大小",
                value = settings.batchSize.toFloat(),
                range = 0f..2048f,
                steps = 31,
                format = { if (it <= 0f) "自动" else "${it.toInt()}" },
                onCommit = { value ->
                    viewModel.update { it.copy(batchSize = (value / 64).toInt() * 64) }
                },
            )

            CapabilitySwitch(
                title = "使用 mmap",
                summary = "按需映射模型文件，降低常驻内存",
                checked = settings.useMmap,
            ) { on -> viewModel.update { it.copy(useMmap = on) } }

            Text(
                text = "这些参数在下次加载模型时生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("本地推理引擎") {
            Text(engineState.display(), style = MaterialTheme.typography.bodyLarge)
            val details = (engineState as? EngineState.Ready)?.details
            if (details != null) {
                InfoRow("已加载", details.name)
                InfoRow("上下文", details.contextLength.toString())
                InfoRow("后端", details.backend.name)
            }
            Button(
                onClick = viewModel::reloadEngine,
                enabled = engineState !is EngineState.Loading,
            ) { Text("重新加载引擎") }
            Text(
                text = "模型档案负责 API 与角色参数；本地推理仍由 AppGraph 的 engineFactory 决定。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (val current = dialog) {
        null -> Unit
        ProfileDialog.Create -> NameDialog(
            title = "新建配置",
            value = nameInput,
            onValueChange = { nameInput = it },
            onConfirm = {
                viewModel.createModelProfile(nameInput)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ProfileDialog.Rename -> NameDialog(
            title = "重命名配置",
            value = nameInput,
            onValueChange = { nameInput = it },
            onConfirm = {
                viewModel.renameModelProfile(current.id, nameInput)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is ProfileDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("删除配置？") },
            text = { Text("「${current.name}」及其提供商与角色参数会被删除，操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModelProfile(current.id)
                        dialog = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettingsCard(
    provider: ProviderConfig,
    onUpdate: (ProviderConfig) -> Unit,
    onOpenMarket: () -> Unit,
) {
    var vendorMenu by remember { mutableStateOf(false) }

    SettingsCard("模型提供商") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderKind.entries.forEach { kind ->
                FilterChip(
                    selected = provider.kind == kind,
                    onClick = { onUpdate(provider.copy(kind = kind)) },
                    label = { Text(kind.label) },
                )
            }
        }

        if (provider.kind == ProviderKind.CLOUD) {
            Box {
                AssistChip(
                    onClick = { vendorMenu = true },
                    label = { Text(provider.vendor.label) },
                )
                DropdownMenu(
                    expanded = vendorMenu,
                    onDismissRequest = { vendorMenu = false },
                ) {
                    CloudVendor.entries.forEach { vendor ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(vendor.label)
                                    Text(
                                        text = vendor.defaultEndpoint.ifBlank { "自定义地址" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onUpdate(
                                    provider.copy(
                                        vendor = vendor,
                                        endpoint = if (
                                            provider.endpoint.isBlank() ||
                                            provider.endpoint == provider.vendor.defaultEndpoint
                                        ) {
                                            vendor.defaultEndpoint
                                        } else {
                                            provider.endpoint
                                        },
                                        modelName = if (
                                            provider.modelName.isBlank() ||
                                            provider.modelName == provider.vendor.defaultModel
                                        ) {
                                            vendor.defaultModel
                                        } else {
                                            provider.modelName
                                        },
                                    ),
                                )
                                vendorMenu = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = provider.apiKey,
                onValueChange = { onUpdate(provider.copy(apiKey = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = provider.endpoint,
                onValueChange = { onUpdate(provider.copy(endpoint = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Endpoint") },
                singleLine = true,
            )
            OutlinedTextField(
                value = provider.modelName,
                onValueChange = { onUpdate(provider.copy(modelName = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型名称") },
                singleLine = true,
            )
        } else {
            InfoRow(
                "本地模型",
                provider.localModelPath.ifBlank { "未选择" },
            )
            Button(onClick = onOpenMarket) { Text("进入模型下载") }
            OutlinedTextField(
                value = provider.modelName,
                onValueChange = { onUpdate(provider.copy(modelName = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型名称") },
                singleLine = true,
            )
        }

        CapabilitySwitch(
            title = "支持识图",
            summary = "允许读取图片与截图",
            checked = provider.capabilities.vision,
        ) { onUpdate(provider.copy(capabilities = provider.capabilities.copy(vision = it))) }
        CapabilitySwitch(
            title = "支持音频解析",
            summary = "语音输入与音频理解",
            checked = provider.capabilities.audio,
        ) { onUpdate(provider.copy(capabilities = provider.capabilities.copy(audio = it))) }
        CapabilitySwitch(
            title = "支持视频解析",
            summary = "视频帧与画面理解",
            checked = provider.capabilities.video,
        ) { onUpdate(provider.copy(capabilities = provider.capabilities.copy(video = it))) }
        CapabilitySwitch(
            title = "支持网络搜索",
            summary = "允许调用联网检索",
            checked = provider.capabilities.webSearch,
        ) { onUpdate(provider.copy(capabilities = provider.capabilities.copy(webSearch = it))) }
    }
}

@Composable
private fun GlobalSettingsCard(
    profile: ModelProfile,
    onUpdate: ((ModelProfile) -> ModelProfile) -> Unit,
) {
    SettingsCard("应用全局配置") {
        SwitchRow(
            title = "应用全局配置",
            summary = "所有空间默认使用这套配置",
            checked = profile.globalEnabled,
        ) { enabled -> onUpdate { it.copy(globalEnabled = enabled) } }
        SwitchRow(
            title = "使用 API 秘钥池",
            summary = "多 Key 轮询与失败切换（界面开关）",
            checked = profile.useApiKeyPool,
        ) { enabled -> onUpdate { it.copy(useApiKeyPool = enabled) } }
    }
}

@Composable
private fun RoleConfigCard(
    profileId: String,
    role: ModelRole,
    config: RoleConfig,
    onUpdate: (RoleConfig) -> Unit,
) {
    var headersText by remember(profileId, role) {
        mutableStateOf(
            config.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
        )
    }

    SettingsCard(role.label) {
        Text(
            text = role.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            title = "启用该角色",
            summary = "关闭后该角色回退到语言模型",
            checked = config.enabled,
        ) { enabled -> onUpdate(config.copy(enabled = enabled)) }

        OutlinedTextField(
            value = config.modelName,
            onValueChange = { onUpdate(config.copy(modelName = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型名称（留空跟随提供商）") },
            singleLine = true,
        )

        NumberField(
            label = "上下文长度",
            value = config.contextLength,
            range = 256..200_000,
            resetKey = "$profileId-$role-context",
            onCommit = { onUpdate(config.copy(contextLength = it)) },
        )

        if (role != ModelRole.EMBEDDING) {
            NumberField(
                label = "最大输出",
                value = config.maxOutputTokens,
                range = 0..32_000,
                resetKey = "$profileId-$role-max",
                onCommit = { onUpdate(config.copy(maxOutputTokens = it)) },
            )
        }

        SliderRow(
            title = "温度",
            value = config.temperature,
            range = 0f..2f,
            steps = 19,
            format = { "%.2f".format(it) },
            onCommit = { onUpdate(config.copy(temperature = it)) },
        )
        SliderRow(
            title = "Top-P",
            value = config.topP,
            range = 0.1f..1f,
            steps = 17,
            format = { "%.2f".format(it) },
            onCommit = { onUpdate(config.copy(topP = it)) },
        )

        if (role == ModelRole.LANGUAGE) {
            SwitchRow(
                title = "总结设置",
                summary = "长对话滚动压缩与摘要",
                checked = config.summaryEnabled,
            ) { onUpdate(config.copy(summaryEnabled = it)) }
            if (config.summaryEnabled) {
                OutlinedTextField(
                    value = config.summaryModel,
                    onValueChange = { onUpdate(config.copy(summaryModel = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("总结模型（留空跟随语言模型）") },
                    singleLine = true,
                )
            }

            SwitchRow(
                title = "思考配置",
                summary = "启用推理/思考段",
                checked = config.thinkingEnabled,
            ) { onUpdate(config.copy(thinkingEnabled = it)) }
            if (config.thinkingEnabled) {
                NumberField(
                    label = "思考预算（tokens）",
                    value = config.thinkingBudget,
                    range = 0..32_000,
                    resetKey = "$profileId-$role-thinking",
                    onCommit = { onUpdate(config.copy(thinkingBudget = it)) },
                )
            }

            OutlinedTextField(
                value = headersText,
                onValueChange = { text ->
                    headersText = text
                    onUpdate(config.copy(customHeaders = parseHeaders(text)))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("自定义请求头（每行 Key: Value）") },
                minLines = 2,
                maxLines = 4,
            )

            SwitchRow(
                title = "启用工具调用",
                summary = "允许模型调用 Agent 工具",
                checked = config.toolCalling,
            ) { onUpdate(config.copy(toolCalling = it)) }
        }
    }
}

@Composable
private fun CapabilitySwitch(
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
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) = CapabilitySwitch(title = title, summary = summary, checked = checked, onChange = onChange)

@Composable
private fun NumberField(
    label: String,
    value: Int,
    range: IntRange,
    resetKey: Any?,
    onCommit: (Int) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 7 && input.all { it.isDigit() }) {
                text = input
                input.toIntOrNull()?.takeIf { it in range }?.let(onCommit)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun NameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private sealed interface ProfileDialog {
    data object Create : ProfileDialog
    data class Rename(val id: String) : ProfileDialog
    data class Delete(val id: String, val name: String) : ProfileDialog
}

private fun ProviderConfig.summary(): String = when (kind) {
    ProviderKind.LOCAL -> "本地 · ${modelName.ifBlank { "未选择模型" }}"
    ProviderKind.CLOUD -> "${vendor.label} · ${modelName}"
}

private fun parseHeaders(text: String): Map<String, String> =
    text.lineSequence()
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key.isEmpty()) null else key to value
        }
        .toMap()
