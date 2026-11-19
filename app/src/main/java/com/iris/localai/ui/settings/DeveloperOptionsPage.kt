package com.iris.localai.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.IrisApp

@Composable
internal fun DeveloperOptionsPage(
    viewModel: SettingsViewModel = viewModel(),
    skillViewModel: SkillViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    var exportDir by remember(settings.pluginExportDir) {
        mutableStateOf(settings.pluginExportDir.ifBlank { graph.pluginDev.exportDir().absolutePath })
    }
    var repo by remember(settings.hostSourceRepo) { mutableStateOf(settings.hostSourceRepo) }
    var branch by remember(settings.hostSourceBranch) { mutableStateOf(settings.hostSourceBranch) }
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

    SettingsPage {
        SettingsCard("开发者选项") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("插件开发模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "打开后向智能体暴露 plugin_dev 工具。套件面板请在侧栏插件区或 设置 › 插件 中打开，用法见 Skill「插件开发套件」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.pluginDevMode,
                    onCheckedChange = { enabled -> viewModel.update { it.copy(pluginDevMode = enabled) } },
                )
            }
            if (!settings.pluginDevMode) {
                Text(
                    "插件开发模式已关闭，以下入口暂不显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (settings.pluginDevMode) {
            SettingsCard("插件开发") {
                Text(
                    "工程目录 filesDir/plugin_dev；ZIP 上限 256 MB，解压 512 MB，条目 5000。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { skillViewModel.rebuildBundledSkills() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("恢复内置技能（覆盖未删除的内置项）") }
            }

            SettingsCard("导出与源码") {
                OutlinedTextField(
                    value = exportDir,
                    onValueChange = { exportDir = it },
                    label = { Text("ZIP 导出文件夹") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.update { it.copy(pluginExportDir = exportDir.trim()) }
                            Toast.makeText(context, "已保存导出文件夹", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存目录") }
                    Button(
                        onClick = {
                            exportDir = graph.pluginDev.exportDir().absolutePath
                            viewModel.update { it.copy(pluginExportDir = exportDir) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("使用默认目录") }
                }
                Text(
                    "当前实际目录：${graph.pluginDev.exportDir().absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (storageGranted) "已授予所有文件访问" else "未授予所有文件访问（会回退到应用外置目录）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (!storageGranted) {
                        Button(onClick = {
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
                        }) { Text("授权") }
                    }
                }
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("GitHub 仓库 owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    label = { Text("分支") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        viewModel.update {
                            it.copy(hostSourceRepo = repo.trim(), hostSourceBranch = branch.trim())
                        }
                        Toast.makeText(context, "已保存源码地址", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存源码地址") }
                Text(
                    "源码通过 raw.githubusercontent.com 读取，plugin_dev 的 host_source 会缓存 6 小时。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
