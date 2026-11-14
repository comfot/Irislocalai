package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.localai.plugin.InstalledPlugin
import kotlinx.coroutines.launch

private data class EnvRow(val key: String, val value: String)

@Composable
internal fun PluginEnvDialog(
    plugin: InstalledPlugin,
    viewModel: PluginViewModel,
    onDismiss: () -> Unit,
) {
    val rows = remember(plugin.id) { mutableStateListOf<EnvRow>() }
    var loading by remember(plugin.id) { mutableStateOf(true) }
    var saving by remember(plugin.id) { mutableStateOf(false) }
    var status by remember(plugin.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(plugin.id) {
        val values = runCatching { viewModel.loadPluginEnv(plugin.id) }.getOrDefault(emptyMap())
        val known = runCatching { viewModel.loadPluginEnvKeys(plugin.id) }.getOrDefault(emptyList())
        val merged = LinkedHashMap<String, String>()
        known.forEach { key -> merged[key] = "" }
        values.forEach { (key, value) -> merged[key] = value }
        rows.clear()
        merged.entries.sortedBy { it.key }.forEach { (key, value) -> rows += EnvRow(key, value) }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${plugin.name} · 环境变量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "对应插件 env.json，用于 API Key、Voice ID 等配置；插件侧通过 getEnv / ctx.getEnv 读取。已列出插件代码中出现的键，留空表示不写入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rows.forEachIndexed { index, row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedTextField(
                                    value = row.key,
                                    onValueChange = { value -> rows[index] = rows[index].copy(key = value) },
                                    label = { Text("键") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = row.value,
                                    onValueChange = { value -> rows[index] = rows[index].copy(value = value) },
                                    label = { Text("值") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.4f),
                                )
                                IconButton(onClick = { rows.removeAt(index) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "删除环境变量")
                                }
                            }
                        }
                        TextButton(onClick = { rows += EnvRow("", "") }) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text("新增环境变量")
                        }
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && !saving,
                onClick = {
                    val values = LinkedHashMap<String, String>()
                    rows.forEach { row ->
                        val key = row.key.trim()
                        if (key.isNotBlank() && row.value.isNotBlank()) values[key] = row.value
                    }
                    saving = true
                    scope.launch {
                        runCatching { viewModel.savePluginEnv(plugin.id, values) }
                            .onSuccess {
                                status = "已保存 ${values.size} 项"
                                onDismiss()
                            }
                            .onFailure { status = "保存失败：${it.message}" }
                        saving = false
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
