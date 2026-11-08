package com.iris.localai.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.workspace.Workspace
import com.iris.localai.workspace.WorkspaceEntry
import com.iris.localai.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 对话栏附件选择：可直接从系统文件管理器选择文本文件，
 * 也可以浏览当前工作区目录；工作区文件会带上相对路径供工具继续访问。
 */
@Composable
internal fun WorkFilePickerDialog(
    workspace: Workspace?,
    onDismiss: () -> Unit,
    onPickWorkspaceFile: (path: String, name: String) -> Unit,
    onPickDeviceFile: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val devicePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(onPickDeviceFile)
            onDismiss()
        }
    }

    var path by remember(workspace?.id) { mutableStateOf("") }
    var entries by remember(workspace?.id) { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var loading by remember(workspace?.id) { mutableStateOf(false) }
    var errorText by remember(workspace?.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(workspace?.id, path) {
        val target = workspace
        if (target == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        errorText = null
        val result = withContext(Dispatchers.IO) {
            WorkspaceFs(context, target).entries(path)
        }
        result.onSuccess { entries = it }
            .onFailure {
                entries = emptyList()
                errorText = it.message ?: "读取目录失败"
            }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("添加文件")
                Text(
                    text = workspace?.name ?: "未选择工作区",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Button(
                    onClick = { devicePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("从设备选择")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "支持文本与图片；图片需在模型设置开启「支持识图」。单文件最多 8000 字符或 12 MB。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                if (workspace == null) {
                    Text(
                        text = "选择或新建工作区后，可在这里浏览工作区文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                Text(
                    text = "工作区：/${path}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                errorText?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (path.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { path = path.substringBeforeLast('/', "").trimEnd('/') }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("返回上级", style = MaterialTheme.typography.bodySmall)
                    }
                }
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDir) {
                                    path = entry.path
                                } else {
                                    onPickWorkspaceFile(entry.path, entry.name)
                                    onDismiss()
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                entry.isDir -> Icons.Outlined.Folder
                                isImageFile(entry.name) -> Icons.Outlined.Image
                                else -> Icons.Outlined.Description
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!entry.isDir) {
                            Text(
                                text = formatFileSize(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!loading && entries.isEmpty() && errorText == null) {
                    Text(
                        text = if (path.isBlank()) "工作区为空" else "目录为空",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private val imageFileExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

private fun isImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in imageFileExtensions
