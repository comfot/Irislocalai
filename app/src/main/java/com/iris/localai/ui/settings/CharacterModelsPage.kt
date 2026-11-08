package com.iris.localai.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.character.CharacterEntry
import com.iris.localai.character.CharacterModelEntry
import com.iris.localai.character.CharacterModelType
import kotlin.math.roundToInt

@Composable
internal fun CharacterModelsPage(viewModel: CharacterModelsViewModel = viewModel()) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val bindings by viewModel.bindings.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val activeCharacterId by viewModel.activeCharacterId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<CharacterModelEntry?>(null) }
    var bindingModel by remember { mutableStateOf<CharacterModelEntry?>(null) }
    val active = models.firstOrNull { it.id == activeId }
    val activeCharacter = characters.firstOrNull { it.id == activeCharacterId }
    val boundModel = activeCharacter?.let { character ->
        models.firstOrNull { it.id == bindings[character.id] }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.import(uri, uri.lastPathSegment.orEmpty())
        }
    }

    SettingsPage {
        SettingsCard("当前角色模型") {
            if (active == null) {
                Text(
                    text = "尚未选择角色模型。导入后可设为当前模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelArt(active, 84)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(active.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${active.type.label} · ${formatBytes(active.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::clearActive) {
                            Text("取消当前模型")
                        }
                    }
                }
            }
        }

        if (active != null) {
            ModelOffsetCard(
                model = active,
                onOffsetChange = { x, y -> viewModel.updateOffset(active.id, x, y) },
            )
        }

        SettingsCard("角色绑定") {
            if (activeCharacter == null) {
                Text(
                    text = "没有可绑定的角色卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "当前角色：${activeCharacter.card.charName}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = boundModel?.let { "已绑定：${it.name}（${it.type.label}）" } ?: "尚未绑定角色模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (boundModel == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active != null && boundModel?.id != active.id) {
                        TextButton(onClick = { viewModel.bind(activeCharacter.id, active.id) }) {
                            Text("绑定当前模型")
                        }
                    }
                    if (boundModel != null) {
                        TextButton(onClick = { viewModel.unbind(activeCharacter.id) }) {
                            Text("解除绑定")
                        }
                    }
                }
                Text(
                    text = "也可以在下方模型条目点“绑定”，选择任意角色卡。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsCard("导入模型") {
            Button(onClick = { importer.launch("*/*") }) {
                Text("导入 Live2D / VRM / GLB / MMD")
            }
            Text(
                text = "Live2D 请把完整模型文件夹压缩为 ZIP（参考 AIRI）；VRM/GLB/MMD 可直接选择单个文件。",
                style = MaterialTheme.typography.bodySmall,
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

        if (models.isEmpty()) {
            Text(
                text = "还没有角色模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        models.forEach { model ->
            val boundCharacter = characters.firstOrNull { bindings[it.id] == model.id }
            ModelRow(
                model = model,
                active = model.id == activeId,
                boundCharacter = boundCharacter?.card?.charName,
                onUse = { viewModel.setActive(model.id) },
                onBind = { bindingModel = model },
                onDelete = { pendingDelete = model },
            )
            Spacer(Modifier.size(8.dp))
        }

        SettingsCard("关于渲染") {
            Text(
                text = "生活空间已接入本地 three.js + GLTFLoader（VRM / GLB）、Pixi + Cubism" +
                    "（Live2D）与 Babylon.js + babylon-mmd WASM（MMD），均支持自动适配、拖动与缩放；" +
                    "MMD 支持 PMX/PMD、VMD 动作、IK、表情与刚体物理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    bindingModel?.let { model ->
        BindingDialog(
            model = model,
            characters = characters,
            bindings = bindings,
            onBind = { characterId ->
                viewModel.bind(characterId, model.id)
                bindingModel = null
            },
            onDismiss = { bindingModel = null },
        )
    }

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型？") },
            text = { Text("「${model.name}」的文件会被删除，操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(model.id)
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
private fun ModelOffsetCard(
    model: CharacterModelEntry,
    onOffsetChange: (Float, Float) -> Unit,
) {
    var offsetX by remember(model.id) { mutableFloatStateOf(model.offsetX) }
    var offsetY by remember(model.id) { mutableFloatStateOf(model.offsetY) }
    LaunchedEffect(model.offsetX, model.offsetY) {
        offsetX = model.offsetX
        offsetY = model.offsetY
    }

    SettingsCard("模型位置") {
        Text(
            text = "调整当前模型在舞台中的位置，仅对「${model.name}」生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "左右偏移 ${(offsetX * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = offsetX,
            onValueChange = { offsetX = it },
            onValueChangeFinished = { onOffsetChange(offsetX, offsetY) },
            valueRange = -0.5f..0.5f,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("左", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("右", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = "上下偏移 ${(offsetY * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = offsetY,
            onValueChange = { offsetY = it },
            onValueChangeFinished = { onOffsetChange(offsetX, offsetY) },
            valueRange = -0.5f..0.5f,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("上", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("下", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = {
                offsetX = 0f
                offsetY = 0f
                onOffsetChange(0f, 0f)
            },
        ) {
            Text("重置位置")
        }
    }
}

@Composable
private fun BindingDialog(
    model: CharacterModelEntry,
    characters: List<CharacterEntry>,
    bindings: Map<String, String>,
    onBind: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("绑定「${model.name}」") },
        text = {
            if (characters.isEmpty()) {
                Text("没有可绑定的角色卡。")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(characters, key = { it.id }) { entry ->
                        val boundId = bindings[entry.id]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBind(entry.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.card.charName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = when {
                                        boundId == model.id -> "当前绑定此模型"
                                        boundId != null -> "已绑定其他模型"
                                        else -> "未绑定"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (boundId == model.id) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (boundId == model.id) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun ModelRow(
    model: CharacterModelEntry,
    active: Boolean,
    boundCharacter: String?,
    onUse: () -> Unit,
    onBind: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelArt(model, 58)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${model.type.label} · ${formatBytes(model.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (boundCharacter != null) {
                    Text(
                        text = "已绑定：$boundCharacter",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(onClick = onBind) { Text("绑定") }
            if (active) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "当前模型",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onUse) { Text("使用") }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun ModelArt(model: CharacterModelEntry, size: Int) {
    val bitmap = remember(model.thumbnail) {
        model.thumbnail?.let { path ->
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = model.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = when (model.type) {
                CharacterModelType.LIVE2D -> Color(0xFFFF9AA2)
                CharacterModelType.VRM -> Color(0xFF7DB3FF)
                CharacterModelType.GLB -> Color(0xFF34C759)
                CharacterModelType.MMD -> Color(0xFFAF52DE)
            },
            modifier = Modifier.size(size.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = model.type.label.take(2),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
