package com.iris.localai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.storage.BackupCategory
import com.iris.localai.storage.BackupOutcome
import com.iris.localai.storage.BackupPreview
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BackupPage(viewModel: SettingsViewModel) {
    val scope = rememberCoroutineScope()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val books by viewModel.worldBooks.collectAsStateWithLifecycle()
    val diary by viewModel.memoryDiary.collectAsStateWithLifecycle()
    val memories by viewModel.memoryItems.collectAsStateWithLifecycle()
    val knowledge by viewModel.memoryKnowledge.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(BackupCategory.entries.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val summaries = mapOf(
        BackupCategory.CHARACTERS to "${characters.size} 张",
        BackupCategory.WORLD_BOOKS to "${books.size} 本",
        BackupCategory.MEMORY to "${diary.size + memories.size + knowledge.size} 条",
        BackupCategory.SETTINGS to "偏好与模型档案",
    )

    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = selected
        busy = true
        scope.launch {
            viewModel.writeBackup(uri, picked)
                .onSuccess { notice = "已导出备份：${describe(it)}" }
                .onFailure { notice = "导出失败：${it.message ?: "未知错误"}" }
            busy = false
        }
    }

    SettingsPage {
        SettingsCard("备份内容") {
            Text(
                text = "勾选要写入备份的数据，导出为单个 ZIP 文件，可保存到本地或云盘。",
                style = MaterialTheme.typography.bodySmall,
            )
            BackupCategory.entries.forEach { category ->
                BackupCategoryRow(
                    category = category,
                    summary = summaries[category].orEmpty(),
                    checked = category in selected,
                    enabled = !busy,
                ) {
                    selected = if (category in selected) selected - category else selected + category
                }
            }
            Button(
                onClick = {
                    val name = "iris-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"
                    create.launch(name)
                },
                enabled = selected.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "正在导出…" else "选择保存位置并导出")
            }
        }
        SettingsCard("说明") {
            Text(
                text = "备份不含 API Key 与令牌、角色模型文件、对话记录、插件与 MCP 配置。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "恢复时角色卡按同 id 覆盖并保留其他卡；世界书与记忆会整体替换；设置恢复后立即生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        notice?.let { text ->
            SettingsCard("结果") { Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun RestorePage(viewModel: SettingsViewModel) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var selected by remember { mutableStateOf(emptySet<BackupCategory>()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            viewModel.inspectBackup(uri)
                .onSuccess { result ->
                    preview = result
                    pickedUri = uri
                    selected = result.categories
                    notice = if (result.categories.isEmpty()) "备份中没有可恢复的分类" else null
                }
                .onFailure {
                    preview = null
                    pickedUri = null
                    selected = emptySet()
                    notice = "无法读取备份：${it.message ?: "未知错误"}"
                }
            busy = false
        }
    }

    fun restore() {
        val uri = pickedUri ?: return
        val picked = selected
        busy = true
        scope.launch {
            viewModel.restoreBackup(uri, picked)
                .onSuccess { notice = "已恢复：${describe(it)}" }
                .onFailure { notice = "恢复失败：${it.message ?: "未知错误"}" }
            busy = false
        }
    }

    SettingsPage {
        SettingsCard("选择备份文件") {
            Text(
                text = "选择由「备份」页导出的 ZIP 文件。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    picker.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                        ),
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "正在读取…" else "选择备份文件")
            }
        }

        preview?.let { result ->
            SettingsCard("备份信息") {
                InfoRow("创建时间", formatTime(result.createdAt))
                InfoRow("包含分类", result.categories.joinToString("、") { it.label }.ifEmpty { "无" })
                result.categories.forEach { category ->
                    InfoRow(category.label, "${result.counts[category] ?: 0}")
                }
            }
            SettingsCard("恢复到本机") {
                Text(
                    text = "将覆盖本机所选分类的数据且无法撤销，建议先备份当前数据。",
                    style = MaterialTheme.typography.bodySmall,
                )
                result.categories.forEach { category ->
                    BackupCategoryRow(
                        category = category,
                        summary = "备份中有 ${result.counts[category] ?: 0} 项",
                        checked = category in selected,
                        enabled = !busy,
                    ) {
                        selected = if (category in selected) selected - category else selected + category
                    }
                }
                Button(
                    onClick = { showRestoreConfirm = true },
                    enabled = selected.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("恢复选中内容")
                }
            }
        }

        notice?.let { text ->
            SettingsCard("结果") { Text(text, style = MaterialTheme.typography.bodySmall) }
        }

        SettingsCard("恢复默认设置") {
            Text(
                text = "会清空模型路径、采样、主题、空间与用户名等偏好；角色卡与记忆不受影响。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { showResetConfirm = true }) {
                Text("恢复默认")
            }
        }
    }

    if (showRestoreConfirm) {
        val labels = selected.sortedBy { it.ordinal }.joinToString("、") { it.label }
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复备份？") },
            text = { Text("将用备份覆盖本机的：$labels。\n此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        restore()
                    },
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认设置？") },
            text = { Text("已保存的偏好会被清空，应用回到初始状态。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.reset()
                        showResetConfirm = false
                    },
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BackupCategoryRow(
    category: BackupCategory,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(category.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) {
        "未知"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

private fun describe(outcome: BackupOutcome): String {
    if (outcome.counts.isEmpty()) return "没有写入任何内容"
    val parts = outcome.counts.entries
        .sortedBy { it.key.ordinal }
        .joinToString("、") { "${it.key.label} ${it.value}" }
    return if (outcome.skipped > 0) "$parts（跳过 ${outcome.skipped} 项）" else parts
}
