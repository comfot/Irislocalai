package com.iris.localai.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.CloudVendor
import com.iris.localai.data.ConnectionTestState
import com.iris.localai.data.DownloadSource
import com.iris.localai.data.ModelCapabilities
import com.iris.localai.data.ModelProfile
import com.iris.localai.data.ModelProvider
import com.iris.localai.data.ModelRole
import com.iris.localai.data.ProviderKind
import com.iris.localai.data.ProviderModel
import com.iris.localai.data.RoleConfig

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ModelSettingsPage(
    settings: com.iris.localai.data.AppSettings,
    viewModel: SettingsViewModel,
    onOpenMarket: () -> Unit,
) {
    val profiles by viewModel.modelProfiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeModelId.collectAsStateWithLifecycle()
    val providers by viewModel.modelProviders.collectAsStateWithLifecycle()
    val testState by viewModel.connectionTest.collectAsStateWithLifecycle()
    val providerMessage by viewModel.providerMessage.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var profileMenu by remember { mutableStateOf(false) }
    var addProvider by remember { mutableStateOf(false) }
    var applyTargetsFor by remember { mutableStateOf<String?>(null) }
    var capabilityTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                                            text = profileSummary(profile, providers),
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

            if (active != null) {
                SwitchRow(
                    title = "运用全局配置",
                    summary = "所有空间与角色卡默认使用这套档案",
                    checked = active.globalEnabled,
                ) { enabled -> viewModel.setGlobalProfile(active.id, enabled) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("运用到", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = targetSummary(active, workspaces.map { it.id to it.name }, characters.map { it.id to it.card.charName }),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { applyTargetsFor = active.id },
                        enabled = !active.globalEnabled,
                    ) { Text("选择") }
                }
            }
        }

        ExpandableSettingsCard(
            title = "模型提供商",
            summary = "独立管理本地/云端提供商、拉取模型与模型能力",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addProvider = true }) { Text("添加提供商") }
                Text(
                    text = providerMessage.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            providers.forEach { provider ->
                ProviderCard(
                    provider = provider,
                    onUpdate = viewModel::updateModelProvider,
                    onDelete = { viewModel.deleteModelProvider(provider.id) },
                    onTest = { viewModel.testProvider(provider.id) },
                    onAddModel = { name -> viewModel.addProviderModel(provider.id, name) },
                    onFetchModels = { viewModel.fetchProviderModels(provider.id) },
                    onToggleModel = { name, enabled ->
                        viewModel.setProviderModelEnabled(provider.id, name, enabled)
                    },
                    onLongPressModel = { name -> capabilityTarget = provider.id to name },
                    onRemoveModel = { name -> viewModel.removeProviderModel(provider.id, name) },
                    onOpenMarket = onOpenMarket,
                )
            }
        }

        ExpandableSettingsCard(
            title = "模型配置",
            summary = "为语言、视觉、创作、Embedding 与动作模型选择提供商和模型",
        ) {
            if (active != null) {
                ModelRole.entries.forEach { role ->
                    if (role == ModelRole.ACTION) {
                        ActionModelPlaceholder()
                    } else {
                        RoleConfigCard(
                            profileId = active.id,
                            role = role,
                            config = active.role(role),
                            providers = providers.filter { it.supports(role) },
                            onUpdate = { config ->
                                viewModel.updateActiveModel {
                                    it.copy(roles = it.roles + (role to config))
                                }
                            },
                        )
                    }
                }
            } else {
                Text("先创建一个配置档案")
            }
        }

        ExpandableSettingsCard(
            title = "高级加载参数",
            summary = "视觉质量、下载源、Flash Attention、KV 缓存、批大小与 mmap",
        ) {
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
            text = { Text("「${current.name}」的角色参数会被删除；模型提供商不受影响。") },
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

    if (addProvider) {
        AddProviderDialog(
            onDismiss = { addProvider = false },
            onConfirm = { name, kind, roles ->
                viewModel.addModelProvider(name, kind, roles)
                addProvider = false
            },
        )
    }

    applyTargetsFor?.let { profileId ->
        val profile = profiles.firstOrNull { it.id == profileId }
        if (profile != null) {
            ApplyTargetsDialog(
                profile = profile,
                workspaces = workspaces.map { it.id to it.name },
                characters = characters.map { it.id to it.card.charName },
                onDismiss = { applyTargetsFor = null },
                onConfirm = { workspaceIds, characterIds ->
                    viewModel.setProfileTargets(profileId, workspaceIds, characterIds)
                    applyTargetsFor = null
                },
            )
        }
    }

    capabilityTarget?.let { (providerId, modelName) ->
        val model = providers.firstOrNull { it.id == providerId }?.model(modelName)
        if (model != null) {
            ModelCapabilitiesDialog(
                providerId = providerId,
                model = model,
                onDismiss = { capabilityTarget = null },
                onChange = { transform ->
                    viewModel.updateModelCapabilities(providerId, modelName, transform)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderCard(
    provider: ModelProvider,
    onUpdate: (ModelProvider) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onAddModel: (String) -> Unit,
    onFetchModels: () -> Unit,
    onToggleModel: (String, Boolean) -> Unit,
    onLongPressModel: (String) -> Unit,
    onRemoveModel: (String) -> Unit,
    onOpenMarket: () -> Unit,
) {
    var expanded by remember(provider.id) { mutableStateOf(false) }
    var name by remember(provider.id, provider.name) { mutableStateOf(provider.name) }
    var modelInput by remember(provider.id) { mutableStateOf("") }
    var vendorMenu by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { expanded = !expanded },
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = provider.kind.label + " · " +
                            provider.roles.joinToString("、") { it.label } + " · " +
                            "${provider.models.size} 个模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("提供商名称（不可重复）") },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(
                                onClick = { onUpdate(provider.copy(name = name.trim())) },
                                enabled = name.isNotBlank() && name.trim() != provider.name,
                            ) { Text("保存") }
                        },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProviderKind.entries.forEach { kind ->
                            FilterChip(
                                selected = provider.kind == kind,
                                onClick = {
                                    onUpdate(
                                        provider.copy(
                                            kind = kind,
                                            endpoint = if (kind == ProviderKind.CLOUD) {
                                                provider.endpoint.ifBlank { CloudVendor.OPENAI.defaultEndpoint }
                                            } else {
                                                ""
                                            },
                                        ),
                                    )
                                },
                                label = { Text(kind.label) },
                            )
                        }
                    }

                    Text("提供商类型", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModelRole.entries.forEach { role ->
                            FilterChip(
                                selected = role in provider.roles,
                                onClick = {
                                    val next = if (role in provider.roles) {
                                        provider.roles - role
                                    } else {
                                        provider.roles + role
                                    }
                                    onUpdate(provider.copy(roles = next))
                                },
                                label = { Text(role.label) },
                            )
                        }
                    }

                    Text(
                        text = "仅勾选类型的模型会出现在对应角色的模型配置里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

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
                                            val endpoint = if (
                                                provider.endpoint.isBlank() ||
                                                provider.endpoint == provider.vendor.defaultEndpoint
                                            ) {
                                                vendor.defaultEndpoint
                                            } else {
                                                provider.endpoint
                                            }
                                            onUpdate(provider.copy(vendor = vendor, endpoint = endpoint))
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
                    } else {
                        InfoRow("本地模型", provider.localModelPath.ifBlank { "未选择" })
                        Button(onClick = onOpenMarket) { Text("进入模型下载") }
                    }

                    Text("模型列表", style = MaterialTheme.typography.bodyMedium)
                    provider.models.forEach { model ->
                        ModelRow(
                            model = model,
                            onToggle = { enabled -> onToggleModel(model.name, enabled) },
                            onLongPress = { onLongPressModel(model.name) },
                            onRemove = { onRemoveModel(model.name) },
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = modelInput,
                            onValueChange = { modelInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("输入模型名称") },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                if (modelInput.isNotBlank()) {
                                    onAddModel(modelInput)
                                    modelInput = ""
                                }
                            },
                            enabled = modelInput.isNotBlank(),
                        ) { Text("添加") }
                    }

                    Text(
                        text = "长按模型名称可设置识图、音频、视频与联网搜索能力。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (provider.kind == ProviderKind.CLOUD) {
                            TextButton(onClick = onFetchModels) { Text("拉取模型") }
                        }
                        TextButton(onClick = onTest) { Text("测试连接") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "删除提供商",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelRow(
    model: ProviderModel,
    onToggle: (Boolean) -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onToggle(!model.enabled) }, onLongClick = onLongPress)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = model.enabled, onCheckedChange = onToggle)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val caps = capabilityLabels(model.capabilities)
            if (caps.isNotEmpty()) {
                Text(
                    text = caps.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "移除模型",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ActionModelPlaceholder() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(ModelRole.ACTION.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "暂时不支持，正在努力开发中～",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoleConfigCard(
    profileId: String,
    role: ModelRole,
    config: RoleConfig,
    providers: List<ModelProvider>,
    onUpdate: (RoleConfig) -> Unit,
) {
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var advanced by remember(profileId, role) { mutableStateOf(false) }
    var headersText by remember(profileId, role) {
        mutableStateOf(
            config.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
        )
    }
    val provider = providers.firstOrNull { it.id == config.providerId }
        ?: providers.firstOrNull()
    val models = provider?.models.orEmpty().filter { it.enabled }
    val selectedModel = config.modelName.ifBlank { models.firstOrNull()?.name.orEmpty() }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(role.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = role.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (role != ModelRole.LANGUAGE) {
                SwitchRow(
                    title = "启用该模型",
                    summary = "关闭后默认采用语言模型配置",
                    checked = config.enabled,
                ) { enabled -> onUpdate(config.copy(enabled = enabled)) }
            }

            Text("模型提供商", style = MaterialTheme.typography.bodyMedium)
            Box {
                AssistChip(
                    onClick = { providerMenu = true },
                    label = {
                        Text(
                            provider?.name ?: "无可用提供商",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    providers.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.name)
                                    Text(
                                        text = item.kind.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onUpdate(
                                    config.copy(
                                        providerId = item.id,
                                        modelName = item.firstModel()?.name.orEmpty(),
                                    ),
                                )
                                providerMenu = false
                            },
                        )
                    }
                }
            }

            Text("模型名称", style = MaterialTheme.typography.bodyMedium)
            Box {
                AssistChip(
                    onClick = { modelMenu = true },
                    label = {
                        Text(
                            selectedModel.ifBlank { "无可用模型" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    models.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.name)
                                    val caps = capabilityLabels(item.capabilities)
                                    if (caps.isNotEmpty()) {
                                        Text(
                                            text = caps.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onUpdate(config.copy(modelName = item.name))
                                modelMenu = false
                            },
                        )
                    }
                    if (models.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("先在模型提供商里添加并勾选模型") },
                            onClick = { modelMenu = false },
                        )
                    }
                }
            }

            if (models.isEmpty()) {
                OutlinedTextField(
                    value = config.modelName,
                    onValueChange = { onUpdate(config.copy(modelName = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("手动输入模型名称") },
                    singleLine = true,
                )
            }

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "收起高级配置" else "高级配置")
            }

            if (advanced) {
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
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, ProviderKind, Set<ModelRole>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProviderKind.CLOUD) }
    var roles by remember { mutableStateOf(ModelRole.entries.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加模型提供商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（不可重复）") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderKind.entries.forEach { item ->
                        FilterChip(
                            selected = kind == item,
                            onClick = { kind = item },
                            label = { Text(item.label) },
                        )
                    }
                }
                Text("模型类型", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModelRole.entries.forEach { role ->
                        FilterChip(
                            selected = role in roles,
                            onClick = {
                                roles = if (role in roles) roles - role else roles + role
                            },
                            label = { Text(role.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, kind, roles) },
                enabled = name.isNotBlank() && roles.isNotEmpty(),
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ApplyTargetsDialog(
    profile: ModelProfile,
    workspaces: List<Pair<String, String>>,
    characters: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, Set<String>) -> Unit,
) {
    var workspaceIds by remember(profile.id) { mutableStateOf(profile.applyWorkspaceIds) }
    var characterIds by remember(profile.id) { mutableStateOf(profile.applyCharacterIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运用到") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("工作空间", style = MaterialTheme.typography.titleSmall)
                workspaces.forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in workspaceIds,
                            onCheckedChange = { checked ->
                                workspaceIds = if (checked) workspaceIds + id else workspaceIds - id
                            },
                        )
                        Text(name)
                    }
                }
                Text("生活空间角色卡", style = MaterialTheme.typography.titleSmall)
                characters.forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in characterIds,
                            onCheckedChange = { checked ->
                                characterIds = if (checked) characterIds + id else characterIds - id
                            },
                        )
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(workspaceIds, characterIds) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ModelCapabilitiesDialog(
    providerId: String,
    model: ProviderModel,
    onDismiss: () -> Unit,
    onChange: ((ModelCapabilities) -> ModelCapabilities) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型能力 · ${model.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "提供商标识：$providerId",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CapabilitySwitch(
                    title = "支持识图",
                    summary = "允许读取图片与截图",
                    checked = model.capabilities.vision,
                ) { on -> onChange { it.copy(vision = on) } }
                CapabilitySwitch(
                    title = "支持音频解析",
                    summary = "语音输入与音频理解",
                    checked = model.capabilities.audio,
                ) { on -> onChange { it.copy(audio = on) } }
                CapabilitySwitch(
                    title = "支持视频解析",
                    summary = "视频帧与画面理解",
                    checked = model.capabilities.video,
                ) { on -> onChange { it.copy(video = on) } }
                CapabilitySwitch(
                    title = "支持网络搜索",
                    summary = "允许调用联网检索",
                    checked = model.capabilities.webSearch,
                ) { on -> onChange { it.copy(webSearch = on) } }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
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

private fun profileSummary(profile: ModelProfile, providers: List<ModelProvider>): String {
    val provider = providers.firstOrNull { it.id == profile.role(ModelRole.LANGUAGE).providerId }
        ?: providers.firstOrNull()
    val model = provider?.model(profile.role(ModelRole.LANGUAGE).modelName)?.name
        ?: provider?.firstModel()?.name
        ?: profile.role(ModelRole.LANGUAGE).modelName
    return "${provider?.name ?: "未选择提供商"} · ${model.ifBlank { "未选择模型" }}"
}

private fun targetSummary(
    profile: ModelProfile,
    workspaces: List<Pair<String, String>>,
    characters: List<Pair<String, String>>,
): String {
    if (profile.globalEnabled) return "全局生效，下方选择已停用"
    val ws = workspaces.filter { it.first in profile.applyWorkspaceIds }.map { it.second }
    val chars = characters.filter { it.first in profile.applyCharacterIds }.map { it.second }
    if (ws.isEmpty() && chars.isEmpty()) return "未指定，使用手动选中的档案"
    return buildList {
        if (ws.isNotEmpty()) add("工作空间：" + ws.joinToString("、"))
        if (chars.isNotEmpty()) add("角色卡：" + chars.joinToString("、"))
    }.joinToString("；")
}

private fun capabilityLabels(capabilities: ModelCapabilities): List<String> = buildList {
    if (capabilities.vision) add("识图")
    if (capabilities.audio) add("音频")
    if (capabilities.video) add("视频")
    if (capabilities.webSearch) add("联网")
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
