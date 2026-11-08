package com.iris.localai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.skill.Skill
import com.iris.localai.skill.SkillSettings

@Composable
internal fun SkillPage(viewModel: SkillViewModel = viewModel()) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var editor by remember { mutableStateOf<SkillEditor?>(null) }
    var pendingDelete by remember { mutableStateOf<Skill?>(null) }
    var showDownload by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val data = input.readBytes()
                    if (data.size > 2 * 1024 * 1024) null else data
                }
            }.getOrNull()
            if (bytes == null) {
                viewModel.clearMessage()
            } else {
                viewModel.import(bytes, uri.lastPathSegment.orEmpty())
            }
        }
    }

    SettingsPage {
        SettingsCard("技能") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { importer.launch("*/*") },
                    modifier = Modifier.weight(1f),
                ) { Text("导入技能") }
                Button(
                    onClick = { showDownload = true },
                    modifier = Modifier.weight(1f),
                ) { Text("下载技能") }
                Button(
                    onClick = { editor = SkillEditor.Create },
                    modifier = Modifier.weight(1f),
                ) { Text("创建技能") }
            }
            Text(
                text = "SKILL.md 支持 YAML frontmatter（name、description、triggers、alwaysOn）；也支持 ZIP 内的 .md。",
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

        SettingsCard("激活配置") {
            SwitchRow(
                title = "自动触发",
                summary = "按触发词匹配对话；关闭后启用技能全部注入",
                checked = settings.autoTrigger,
            ) { on -> viewModel.updateSettings { it.copy(autoTrigger = on) } }
            SwitchRow(
                title = "注入对话",
                summary = "在聊天提示词中附加命中技能的指令",
                checked = settings.injectToChat,
            ) { on -> viewModel.updateSettings { it.copy(injectToChat = on) } }
            SwitchRow(
                title = "注入智能体",
                summary = "智能体任务可使用技能指令",
                checked = settings.injectToAgent,
            ) { on -> viewModel.updateSettings { it.copy(injectToAgent = on) } }
            SkillNumberField(
                label = "注入预算（字符）",
                value = settings.maxChars,
                onCommit = { value ->
                    viewModel.updateSettings { it.copy(maxChars = value.coerceIn(512, 20000)) }
                },
            )
        }

        if (skills.isEmpty()) {
            Text(
                text = "还没有技能。可导入 SKILL.md 或从推荐源下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        skills.forEach { skill ->
            SkillRow(
                skill = skill,
                onEnabled = { enabled -> viewModel.setEnabled(skill.id, enabled) },
                onEdit = { editor = SkillEditor.Edit(skill) },
                onDelete = { pendingDelete = skill },
            )
            Spacer(Modifier.width(8.dp))
        }
    }

    when (val state = editor) {
        null -> Unit
        SkillEditor.Create -> SkillEditorDialog(
            existing = null,
            onDismiss = { editor = null },
            onSave = { skill ->
                viewModel.save(skill, new = true)
                editor = null
            },
        )
        is SkillEditor.Edit -> SkillEditorDialog(
            existing = state.skill,
            onDismiss = { editor = null },
            onSave = { skill ->
                viewModel.save(skill)
                editor = null
            },
        )
    }

    if (showDownload) {
        SkillDownloadDialog(
            onDismiss = { showDownload = false },
            onDownload = { url ->
                viewModel.download(url)
                showDownload = false
            },
        )
    }

    pendingDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除技能？") },
            text = { Text("「${skill.name}」及 SKILL.md 会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(skill.id)
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
private fun SkillRow(
    skill: Skill,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, style = MaterialTheme.typography.bodyLarge)
                    if (skill.alwaysOn) {
                        Text(
                            text = " · 常驻",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (skill.triggers.isNotEmpty()) {
                    Text(
                        text = "触发：" + skill.triggers.joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(checked = skill.enabled, onCheckedChange = onEnabled)
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    existing: Skill?,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var triggers by remember(existing?.id) { mutableStateOf(existing?.triggers?.joinToString(", ").orEmpty()) }
    var instructions by remember(existing?.id) { mutableStateOf(existing?.instructions.orEmpty()) }
    var alwaysOn by remember(existing?.id) { mutableStateOf(existing?.alwaysOn ?: false) }
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
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (existing == null) "创建技能" else "编辑技能",
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
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("描述") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = triggers,
                    onValueChange = { triggers = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("触发词（逗号分隔）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("指令正文（Markdown）") },
                    minLines = 8,
                    maxLines = 14,
                )
                SwitchRow(
                    title = "常驻注入",
                    summary = "不依赖触发词，始终进入提示词",
                    checked = alwaysOn,
                ) { alwaysOn = it }
                SwitchRow(
                    title = "启用",
                    summary = "关闭后不激活",
                    checked = enabled,
                ) { enabled = it }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val skill = (existing ?: Skill(name = "新技能")).copy(
                                name = name.trim().ifBlank { "新技能" },
                                description = description.trim(),
                                triggers = triggers.split(',', '，')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() },
                                instructions = instructions,
                                alwaysOn = alwaysOn,
                                enabled = enabled,
                            )
                            onSave(skill)
                        },
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun SkillDownloadDialog(
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit,
) {
    var url by remember {
        mutableStateOf(
            "https://raw.githubusercontent.com/WhitePepperLambSoup/agent-workspace-android/" +
                "main/for%20Android/skills/official/skills/meeting-minutes/SKILL.md",
        )
    }
    val recommendations = listOf(
        "会议纪要" to "https://raw.githubusercontent.com/WhitePepperLambSoup/agent-workspace-android/main/for%20Android/skills/official/skills/meeting-minutes/SKILL.md",
        "文档摘要" to "https://raw.githubusercontent.com/WhitePepperLambSoup/agent-workspace-android/main/for%20Android/skills/official/skills/document-summary/SKILL.md",
        "账单分摊" to "https://raw.githubusercontent.com/WhitePepperLambSoup/agent-workspace-android/main/for%20Android/skills/official/skills/bill-split/SKILL.md",
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("下载技能", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SKILL.md URL") },
                    minLines = 2,
                    maxLines = 4,
                )
                Text("推荐来源", style = MaterialTheme.typography.labelLarge)
                recommendations.forEach { (label, link) ->
                    TextButton(onClick = { url = link }) {
                        Text("$label · Agent Workspace")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onDownload(url.trim()) },
                        enabled = url.isNotBlank(),
                    ) { Text("下载") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
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
private fun SkillNumberField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 5 && input.all { it.isDigit() }) {
                text = input
                input.toIntOrNull()?.let(onCommit)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private sealed interface SkillEditor {
    data object Create : SkillEditor
    data class Edit(val skill: Skill) : SkillEditor
}
