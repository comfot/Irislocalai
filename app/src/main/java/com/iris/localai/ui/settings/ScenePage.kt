package com.iris.localai.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.scene.SceneBackgroundType
import com.iris.localai.scene.SceneConfig
import com.iris.localai.scene.StageBaseStyle

@Composable
internal fun ScenePage(viewModel: SceneViewModel = viewModel()) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<SceneConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<SceneConfig?>(null) }

    SettingsPage {
        SettingsCard("场景") {
            Button(
                onClick = {
                    editing = viewModel.create()
                },
            ) { Text("新建场景") }
            Text(
                text = "场景用于生活空间：名称、介绍、背景图/视频，以及 3D 模型的底座样式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        scenes.forEach { scene ->
            SceneRow(
                scene = scene,
                active = scene.id == activeId,
                onUse = { viewModel.setActive(scene.id) },
                onEdit = { editing = scene },
                onDelete = { pendingDelete = scene },
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    editing?.let { scene ->
        SceneEditorDialog(
            scene = scene,
            onDismiss = { editing = null },
            onImport = { uri, type -> viewModel.importBackground(uri, scene.id, type) },
            onSave = { updated ->
                viewModel.update(updated)
                editing = null
            },
        )
    }

    pendingDelete?.let { scene ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除场景？") },
            text = { Text("「${scene.name}」及其背景文件会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(scene.id)
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
private fun SceneRow(
    scene: SceneConfig,
    active: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScenePreview(scene, Modifier.size(64.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(scene.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${scene.backgroundType.label} · ${scene.baseStyle.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (scene.intro.isNotBlank()) {
                    Text(
                        text = scene.intro,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (active) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "当前场景",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onUse) { Text("使用") }
            }
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
private fun ScenePreview(scene: SceneConfig, modifier: Modifier = Modifier) {
    val bitmap = remember(scene.backgroundPath) {
        scene.backgroundPath.takeIf { it.isNotBlank() && scene.backgroundType == SceneBackgroundType.IMAGE }
            ?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap()
            }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = scene.name,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = modifier,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (scene.backgroundType == SceneBackgroundType.VIDEO) "视频" else "场景",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SceneEditorDialog(
    scene: SceneConfig,
    onDismiss: () -> Unit,
    onImport: (android.net.Uri, SceneBackgroundType) -> Result<String>,
    onSave: (SceneConfig) -> Unit,
) {
    var name by remember(scene.id) { mutableStateOf(scene.name) }
    var intro by remember(scene.id) { mutableStateOf(scene.intro) }
    var type by remember(scene.id) { mutableStateOf(scene.backgroundType) }
    var backgroundPath by remember(scene.id) { mutableStateOf(scene.backgroundPath) }
    var baseStyle by remember(scene.id) { mutableStateOf(scene.baseStyle) }
    var baseColor by remember(scene.id) { mutableStateOf(scene.baseColor) }
    var notice by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onImport(uri, SceneBackgroundType.IMAGE)
                .onSuccess { backgroundPath = it; notice = "已导入背景图" }
                .onFailure { notice = "导入失败：${it.message}" }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onImport(uri, SceneBackgroundType.VIDEO)
                .onSuccess { backgroundPath = it; notice = "已导入背景视频" }
                .onFailure { notice = "导入失败：${it.message}" }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("编辑场景", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("场景名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = intro,
                    onValueChange = { intro = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("场景介绍") },
                    minLines = 2,
                    maxLines = 4,
                )

                Text("背景类型", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneBackgroundType.entries.forEach { item ->
                        FilterChip(
                            selected = type == item,
                            onClick = { type = item },
                            label = { Text(item.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScenePreview(
                        scene = scene.copy(backgroundType = type, backgroundPath = backgroundPath),
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (type == SceneBackgroundType.VIDEO) {
                                videoPicker.launch("video/*")
                            } else {
                                imagePicker.launch("image/*")
                            }
                        },
                    ) { Text(if (backgroundPath.isBlank()) "选择背景" else "更换背景") }
                }
                if (notice != null) {
                    Text(
                        text = notice.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (backgroundPath.isNotBlank()) {
                    Text(
                        text = backgroundPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text("3D 模型底座", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StageBaseStyle.entries.forEach { style ->
                        FilterChip(
                            selected = baseStyle == style,
                            onClick = { baseStyle = style },
                            label = { Text(style.label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        0xFF7DB3FF to "蓝",
                        0xFFFF9AA2 to "粉",
                        0xFF7FE0C3 to "薄荷",
                        0xFFAF52DE to "紫",
                        0xFFFF9F0A to "橙",
                    ).forEach { (color, label) ->
                        FilterChip(
                            selected = baseColor == color,
                            onClick = { baseColor = color },
                            label = { Text(label) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                scene.copy(
                                    name = name.trim().ifBlank { "场景" },
                                    intro = intro.trim(),
                                    backgroundType = type,
                                    backgroundPath = backgroundPath,
                                    baseStyle = baseStyle,
                                    baseColor = baseColor,
                                ),
                            )
                        },
                    ) { Text("保存") }
                }
            }
        }
    }
}

private fun StageBaseStyle.hint(): String = when (this) {
    StageBaseStyle.NONE -> "不绘制底座"
    StageBaseStyle.ROUND -> "圆形舞台"
    StageBaseStyle.GRID -> "网格平台"
    StageBaseStyle.GLOW -> "光晕底座"
}
