package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.localai.data.AssetDownloadState
import com.iris.localai.data.AssetDownloadTask
import com.iris.localai.scene.StageAssetItem
import com.iris.localai.scene.StageAssetKind
import java.io.File
import java.util.Locale

@Composable
internal fun StageAssetList(
    items: List<StageAssetItem>,
    dir: File,
    keyPrefix: String,
    tasks: List<AssetDownloadTask>,
    actionLabel: String,
    onDownload: (StageAssetItem) -> Unit,
    onCancel: (StageAssetItem) -> Unit,
    onApply: (StageAssetItem) -> Unit,
) {
    SettingsCard(if (items.firstOrNull()?.kind == StageAssetKind.TEXTURE) "纹理素材" else "背景素材") {
        items.forEach { item ->
            val file = File(dir, item.fileName)
            val task = tasks.firstOrNull { it.key == "$keyPrefix-${item.id}" }
            val running = task?.state == AssetDownloadState.RUNNING
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = buildString {
                            append(formatSize(item.bytes))
                            append(" · ")
                            append(
                                when {
                                    running -> "下载中 ${(task.progress * 100).toInt()}%"
                                    file.isFile -> "已下载"
                                    task?.state == AssetDownloadState.FAILED -> task.error ?: "下载失败"
                                    else -> "未下载"
                                },
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (running) {
                        LinearProgressIndicator(
                            progress = { task.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
                if (running) {
                    TextButton(onClick = { onCancel(item) }) { Text("取消") }
                } else if (file.isFile) {
                    Button(onClick = { onApply(item) }) { Text(actionLabel) }
                } else {
                    OutlinedButton(onClick = { onDownload(item) }) { Text("下载") }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}
