package com.iris.localai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun StoragePage(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val storage = viewModel.storage
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    var statusTick by remember { mutableIntStateOf(0) }
    val mirrorFile = storage.settingsFile
    val mirrorTime = remember(statusTick, settings, mirrorFile) {
        mirrorFile.takeIf { it.isFile }
            ?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it.lastModified())) }
            ?: "尚未生成"
    }

    fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("IRIS 路径", text))
        Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
    }

    fun export() {
        viewModel.exportSettingsMirror()
            .onSuccess { notice = it; statusTick++ }
            .onFailure { notice = "导出失败：${it.message}" }
    }

    fun reload() {
        viewModel.reloadSettingsFromMirror()
            .onSuccess { notice = it; statusTick++ }
            .onFailure { notice = "重新加载失败：${it.message}" }
    }

    SettingsPage {
        SettingsCard("外置存储") {
            InfoRow("状态", if (storage.isExternal) "已启用" else "不可用，已回退私有目录")
            InfoRow("根目录", storage.root.absolutePath)
            InfoRow("旧数据目录", storage.legacyRoot.absolutePath)
            Text(
                text = "新下载或导入的模型、素材、角色卡写入外置根目录；旧的私有目录数据不迁移，仍会参与读取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { copy(storage.root.absolutePath) },
                    modifier = Modifier.weight(1f),
                ) { Text("复制根目录") }
                OutlinedButton(
                    onClick = { statusTick++ },
                    modifier = Modifier.weight(1f),
                ) { Text("刷新状态") }
            }
            Text(
                text = "部分 Android 11+ 的文件管理器会隐藏 Android/data；若看不到，请使用支持 Shizuku/ADB 的文件管理器或连接电脑查看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("目录") {
            listOf(
                "本地模型" to storage.models,
                "角色卡" to storage.characters,
                "角色模型" to storage.characterModels,
                "桌宠模型" to storage.petModels,
                "语音包" to storage.voiceModels,
                "动作素材" to storage.motionAssets,
                "舞台素材" to storage.stageAssets,
                "场景" to storage.scenes,
            ).forEach { (label, dir) ->
                InfoRow(label, dir.absolutePath)
            }
            OutlinedButton(
                onClick = { copy(storage.root.absolutePath) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("复制根目录路径") }
        }

        SettingsCard("设置镜像") {
            InfoRow("文件", mirrorFile.absolutePath)
            InfoRow("最近写入", mirrorTime)
            Text(
                text = "settings.json 只包含普通设置，api_key、token、云语音密钥不会写入。应用运行时修改的文件会在下次设置变更时被覆盖；建议关闭应用后再编辑，然后重启或点“重新加载”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::export, modifier = Modifier.weight(1f)) {
                    Text("立即导出")
                }
                OutlinedButton(onClick = ::reload, modifier = Modifier.weight(1f)) {
                    Text("重新加载")
                }
            }
            OutlinedButton(
                onClick = { copy(mirrorFile.absolutePath) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("复制设置文件路径") }
        }

        notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
