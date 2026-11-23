package com.iris.moew.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.moew.agent.subagent.SubAgentDefinition
import com.iris.moew.agent.subagent.SubAgentToolMode

@Composable
internal fun SubAgentDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val agents by viewModel.subAgents.collectAsStateWithLifecycle()
    val state by viewModel.subAgentState.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(agents.firstOrNull()?.id.orEmpty()) }
    var showForm by remember { mutableStateOf(false) }
    var task by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(agents) {
        if (agents.none { it.id == selectedId }) {
            selectedId = agents.firstOrNull()?.id.orEmpty()
        }
    }
    val selected = agents.firstOrNull { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("子代理", modifier = Modifier.weight(1f))
                TextButton(onClick = { showForm = !showForm }) {
                    Text(if (showForm) "返回" else "新建")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showForm) {
                    SubAgentForm(
                        onSave = { name, description, prompt, mode ->
                            if (viewModel.saveSubAgent(name, description, prompt, mode)) {
                                showForm = false
                            }
                        },
                    )
                } else {
                    if (agents.isEmpty()) {
                        Text("还没有子代理，点右上角「新建」创建一个。")
                    }
                    agents.forEach { agent ->
                        SubAgentRow(
                            agent = agent,
                            selected = agent.id == selectedId,
                            onClick = { selectedId = agent.id },
                            onDelete = if (agent.builtin) null else ({ viewModel.deleteSubAgent(agent.id) }),
                        )
                    }
                    OutlinedTextField(
                        value = task,
                        onValueChange = { task = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("交给子代理的任务") },
                        placeholder = { Text("写清目标、已知信息、约束和预期产出") },
                        minLines = 2,
                        maxLines = 5,
                    )
                    if (state.running) {
                        Text(
                            "运行中：${state.agentName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            state.progress.ifBlank { "正在准备…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.error?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.result.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            WorkMarkdown(
                                text = state.result,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showForm) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else if (state.running) {
                TextButton(onClick = viewModel::stopSubAgent) { Text("停止") }
            } else {
                Button(
                    onClick = { viewModel.runSubAgent(selectedId, task) },
                    enabled = selected != null && task.isNotBlank(),
                ) {
                    Text("运行")
                }
            }
        },
        dismissButton = {
            if (!showForm) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun SubAgentRow(
    agent: SubAgentDefinition,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${agent.name} · ${agent.capabilities()}", style = MaterialTheme.typography.bodyMedium)
                if (agent.description.isNotBlank()) {
                    Text(
                        agent.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun SubAgentForm(
    onSave: (String, String, String, SubAgentToolMode) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(SubAgentToolMode.WORKSPACE) }
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
        label = { Text("用途说明") },
        singleLine = true,
    )
    OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("系统提示词") },
        minLines = 3,
        maxLines = 6,
    )
    Text("工具范围", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SubAgentToolMode.entries.forEach { entry ->
            AssistChip(
                onClick = { mode = entry },
                label = { Text(if (entry == mode) "✓ ${entry.label}" else entry.label) },
            )
        }
    }
    Button(
        onClick = { onSave(name, description, prompt, mode) },
        enabled = name.isNotBlank() && prompt.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("保存")
    }
}
