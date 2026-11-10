package com.iris.localai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ModelProfile
import com.iris.localai.data.ProviderKind
import com.iris.localai.agent.AttachmentKind
import com.iris.localai.skill.Skill
import com.iris.localai.agent.Tool
import com.iris.localai.workspace.Workspace

@Composable
fun WorkSpaceScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelProfiles by viewModel.modelProfiles.collectAsStateWithLifecycle()
    val activeModelId by viewModel.activeModelProfileId.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val tools = viewModel.tools
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val activeWorkspace by viewModel.activeWorkspace.collectAsStateWithLifecycle()
    val pluginMenuItems by viewModel.pluginMenuItems.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadPluginMenuItems() }

    Column(modifier = modifier.imePadding()) {
        if (messages.isEmpty()) {
            WorkEmptyState(
                workspace = activeWorkspace,
                modifier = Modifier.weight(1f),
                onSuggestion = viewModel::onInputChange,
            )
        } else {
            WorkMessageList(
                messages = messages,
                running = running,
                pluginMenuItems = pluginMenuItems,
                onPluginMenu = { item, message, index ->
                    viewModel.runPluginMenuItem(item, message, index)
                },
                modifier = Modifier.weight(1f),
            )
        }
        WorkComposer(
            viewModel = viewModel,
            settings = settings,
            input = input,
            running = running,
            modelProfiles = modelProfiles,
            activeModelId = activeModelId,
            skills = skills,
            tools = tools,
            workspaces = workspaces,
            activeWorkspace = activeWorkspace,
        )
    }
}

private data class WorkSuggestion(
    val title: String,
    val prompt: String,
    val icon: ImageVector,
)

private val workSuggestions = listOf(
    WorkSuggestion(
        title = "查看工作区",
        prompt = "列出当前工作区里的文件，并说明这个项目的结构。",
        icon = Icons.Outlined.FolderOpen,
    ),
    WorkSuggestion(
        title = "解释代码",
        prompt = "挑出当前工作区里最重要的文件，解释它们的作用和调用关系。",
        icon = Icons.Outlined.Code,
    ),
    WorkSuggestion(
        title = "写脚本",
        prompt = "在当前工作区写一个可直接运行的 shell 脚本，并说明用法。",
        icon = Icons.Outlined.Terminal,
    ),
)

@Composable
private fun WorkEmptyState(
    workspace: Workspace?,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("工作空间", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "在工作区里读文件、跑命令，让模型把结果整理成可执行的回答。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workspace?.name ?: "未选择工作区",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = workspace?.path?.takeIf { it.isNotBlank() } ?: "在工作空间工具条中选择文件夹",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            workSuggestions.forEach { suggestion ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    onClick = { onSuggestion(suggestion.prompt) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = suggestion.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = suggestion.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "填入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkComposer(
    viewModel: ChatViewModel,
    settings: AppSettings,
    input: String,
    running: Boolean,
    modelProfiles: List<ModelProfile>,
    activeModelId: String,
    skills: List<Skill>,
    tools: List<Tool>,
    workspaces: List<Workspace>,
    activeWorkspace: Workspace?,
) {
    var showConfig by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentError by viewModel.attachmentError.collectAsStateWithLifecycle()

    val activeModel = modelProfiles.firstOrNull { it.id == activeModelId }

    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (running) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectorChip(
                    icon = Icons.Outlined.Folder,
                    label = activeWorkspace?.name ?: "选择工作区",
                    options = workspaces.map {
                        ChipOption(it.id, it.name, it.path.ifBlank { "应用内工作区" })
                    },
                    onSelect = { id -> viewModel.setActiveWorkspace(id) },
                )
                SelectorChip(
                    icon = Icons.Outlined.Memory,
                    label = activeModel?.name ?: "默认配置",
                    options = modelProfiles.map {
                        ChipOption(it.id, it.name, it.providerSummary())
                    },
                    onSelect = viewModel::setActiveModelProfile,
                )
                AssistChip(
                    onClick = {
                        viewModel.updateSettings { it.copy(agentMode = !it.agentMode) }
                    },
                    label = { Text(if (settings.agentMode) "工具模式" else "对话模式") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (settings.agentMode) {
                                Icons.Outlined.Bolt
                            } else {
                                Icons.Outlined.ChatBubbleOutline
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                AssistChip(
                    onClick = { showConfig = true },
                    label = {
                        Text(
                            "配置 " + (
                                skills.count { it.enabled } +
                                    tools.count { it.name in settings.enabledToolIds }
                                ),
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
            }

            Spacer(Modifier.height(8.dp))

            if (attachments.isNotEmpty() || attachmentError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        AssistChip(
                            onClick = { viewModel.removeAttachment(index) },
                            label = {
                                Text(
                                    text = attachment.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (attachment.kind == AttachmentKind.IMAGE) {
                                        Icons.Outlined.Image
                                    } else {
                                        Icons.Outlined.AttachFile
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "移除附件",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
                attachmentError?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        if (message.contains("识图") && activeModel?.provider?.kind == ProviderKind.CLOUD) {
                            TextButton(onClick = viewModel::enableVisionForActiveProfile) {
                                Text("开启识图", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("描述任务，点回形针附加文件") },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { showFiles = true }) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = "添加文件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showVoice = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "语音输入",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = {
                        if (running) viewModel.stop() else viewModel.send()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (running) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (running) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    ),
                ) {
                    Icon(
                        imageVector = if (running) {
                            Icons.Filled.Stop
                        } else {
                            Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = if (running) "停止" else "发送",
                    )
                }
            }
        }
    }

    if (showConfig) {
        OperationConfigDialog(
            settings = settings,
            skills = skills,
            tools = tools,
            onToggleSkill = { id, enabled -> viewModel.setSkillEnabled(id, enabled) },
            onToggleTool = { name, enabled ->
                viewModel.updateSettings {
                    it.copy(
                        enabledToolIds = if (enabled) {
                            it.enabledToolIds + name
                        } else {
                            it.enabledToolIds - name
                        },
                    )
                }
            },
            onDismiss = { showConfig = false },
        )
    }

    if (showVoice) {
        VoiceInputDialog(
            onDismiss = { showVoice = false },
            onInsert = { text ->
                viewModel.onInputChange(text)
                showVoice = false
            },
        )
    }

    if (showFiles) {
        WorkFilePickerDialog(
            workspace = activeWorkspace,
            onDismiss = { showFiles = false },
            onPickWorkspaceFile = { path, name -> viewModel.attachWorkspaceFile(path, name) },
            onPickDeviceFile = { uri -> viewModel.attachDeviceFile(uri) },
        )
    }
}

@Composable
private fun OperationConfigDialog(
    settings: AppSettings,
    skills: List<Skill>,
    tools: List<Tool>,
    onToggleSkill: (String, Boolean) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作配置") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "工具",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                tools.forEach { tool ->
                    CheckRow(
                        title = tool.name,
                        summary = tool.description.substringBefore('；'),
                        checked = tool.name in settings.enabledToolIds,
                        onToggle = { onToggleTool(tool.name, tool.name !in settings.enabledToolIds) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "技能",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                skills.forEach { skill ->
                    CheckRow(
                        title = skill.name,
                        summary = skill.description,
                        checked = skill.enabled,
                        onToggle = { onToggleSkill(skill.id, !skill.enabled) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun CheckRow(
    title: String,
    summary: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ModelProfile.providerSummary(): String = when (provider.kind) {
    ProviderKind.LOCAL -> "本地 · ${provider.modelName.ifBlank { "未选择模型" }}"
    ProviderKind.CLOUD -> "${provider.vendor.label} · ${provider.modelName}"
}
