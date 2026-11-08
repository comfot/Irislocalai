package com.iris.localai.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.character.CardSpec
import com.iris.localai.character.CharacterCardData
import com.iris.localai.character.CharacterEntry
import com.iris.localai.data.CharacterLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterCardsPage(viewModel: CharacterCardsViewModel = viewModel()) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var favoritesOnly by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var pendingDelete by remember { mutableStateOf<CharacterEntry?>(null) }
    var exportPayload by remember { mutableStateOf<ExportPayload?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                viewModel.reportError("读取文件失败")
            } else {
                viewModel.importCard(bytes, uri.lastPathSegment.orEmpty())
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val payload = exportPayload
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.bytes) }
            }.onFailure { viewModel.reportError("导出失败：${it.message}") }
        }
        exportPayload = null
    }

    fun export(entry: CharacterEntry, png: Boolean) {
        val bytes = if (png) viewModel.exportPng(entry.id) else viewModel.exportJson(entry.id)
        if (bytes == null) {
            viewModel.reportError("导出失败")
            return
        }
        val name = safeFileName(entry.card.charName) + if (png) ".png" else ".json"
        exportPayload = ExportPayload(name, bytes)
        exporter.launch(name)
    }

    val visible = cards.filter { !favoritesOnly || it.favorite }

    SettingsPage {
        SettingsCard("角色卡") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { importer.launch("*/*") },
                    modifier = Modifier.weight(1f),
                ) { Text("导入角色卡") }
                Button(
                    onClick = { editor = EditorState.Create },
                    modifier = Modifier.weight(1f),
                ) { Text("创建角色卡") }
            }
            Text(
                text = "支持 SillyTavern V1/V2/V3、PNG 内嵌卡、JSON，以及春水/Pygmalion 的 char_* 字段。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CharacterLayout.entries.forEach { layout ->
                    FilterChip(
                        selected = settings.characterLayout == layout,
                        onClick = { viewModel.setLayout(layout) },
                        label = { Text(layout.label) },
                    )
                }
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = !favoritesOnly },
                    label = { Text("只看收藏") },
                )
            }

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

        if (visible.isEmpty()) {
            Text(
                text = if (cards.isEmpty()) "还没有角色卡，先导入或创建一张。" else "没有收藏的角色卡。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (settings.characterLayout == CharacterLayout.GRID) {
            visible.chunked(2).forEach { rowCards ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowCards.forEach { entry ->
                        CharacterGridCard(
                            entry = entry,
                            active = entry.id == settings.characterId,
                            modifier = Modifier.weight(1f),
                            onOpen = { viewModel.setActive(entry.id) },
                            onFavorite = { viewModel.toggleFavorite(entry.id) },
                            onEdit = { editor = EditorState.Edit(entry) },
                            onExportJson = { export(entry, png = false) },
                            onExportPng = { export(entry, png = true) },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                    if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            visible.forEach { entry ->
                CharacterListRow(
                    entry = entry,
                    active = entry.id == settings.characterId,
                    onOpen = { viewModel.setActive(entry.id) },
                    onFavorite = { viewModel.toggleFavorite(entry.id) },
                    onEdit = { editor = EditorState.Edit(entry) },
                    onExportJson = { export(entry, png = false) },
                    onExportPng = { export(entry, png = true) },
                    onDelete = { pendingDelete = entry },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    editor?.let { state ->
        val existing = (state as? EditorState.Edit)?.entry
        CharacterEditorDialog(
            existing = existing,
            onDismiss = { editor = null },
            onSave = { card, avatar ->
                viewModel.saveCard(card, existing?.id, avatar)
                editor = null
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除角色卡？") },
            text = { Text("「${entry.card.charName}」及头像文件会被删除，操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCard(entry.id)
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
private fun CharacterGridCard(
    entry: CharacterEntry,
    active: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onExportJson: () -> Unit,
    onExportPng: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = 1.dp,
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            CharacterArt(
                entry = entry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clickable(onClick = onOpen),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.card.charName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onFavorite) {
                    Icon(
                        imageVector = if (entry.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "收藏",
                        tint = if (entry.favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                text = entry.card.blurb(32),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                }
                CharacterActionMenu(
                    onExportJson = onExportJson,
                    onExportPng = onExportPng,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun CharacterListRow(
    entry: CharacterEntry,
    active: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onExportJson: () -> Unit,
    onExportPng: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterArt(
                entry = entry,
                modifier = Modifier.size(58.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = entry.card.charName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.card.blurb(40),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (entry.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "收藏",
                    tint = if (entry.favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            CharacterActionMenu(
                onExportJson = onExportJson,
                onExportPng = onExportPng,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun CharacterActionMenu(
    onExportJson: () -> Unit,
    onExportPng: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("导出 JSON") },
                onClick = {
                    expanded = false
                    onExportJson()
                },
            )
            DropdownMenuItem(
                text = { Text("导出 PNG") },
                onClick = {
                    expanded = false
                    onExportPng()
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun CharacterArt(entry: CharacterEntry, modifier: Modifier = Modifier) {
    val bitmap = remember(entry.avatar?.absolutePath) {
        entry.avatar?.absolutePath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = entry.card.charName,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(entry.card.accent ?: 0xFF8E7CC3),
            modifier = modifier,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = entry.card.charName.take(1),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CharacterEditorDialog(
    existing: CharacterEntry?,
    onDismiss: () -> Unit,
    onSave: (CharacterCardData, ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    val card = existing?.card
    var name by remember(existing?.id) { mutableStateOf(card?.name.orEmpty()) }
    var description by remember(existing?.id) { mutableStateOf(card?.description.orEmpty()) }
    var personality by remember(existing?.id) { mutableStateOf(card?.personality.orEmpty()) }
    var scenario by remember(existing?.id) { mutableStateOf(card?.scenario.orEmpty()) }
    var firstMes by remember(existing?.id) { mutableStateOf(card?.firstMes.orEmpty()) }
    var mesExample by remember(existing?.id) { mutableStateOf(card?.mesExample.orEmpty()) }
    var tags by remember(existing?.id) { mutableStateOf(card?.tags?.joinToString(", ").orEmpty()) }
    var creatorNotes by remember(existing?.id) { mutableStateOf(card?.creatorNotes.orEmpty()) }
    var systemPrompt by remember(existing?.id) { mutableStateOf(card?.systemPrompt.orEmpty()) }
    var avatarBytes by remember(existing?.id) { mutableStateOf<ByteArray?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avatarBytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        }
    }
    val preview = remember(avatarBytes, existing?.avatar?.absolutePath) {
        avatarBytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            ?: existing?.avatar?.absolutePath?.let {
                runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
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
                Text(
                    text = if (existing == null) "创建角色卡" else "编辑角色卡",
                    style = MaterialTheme.typography.titleLarge,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "角色头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(72.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("无头像", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    TextButton(onClick = { picker.launch("image/*") }) {
                        Text("选择头像")
                    }
                }

                EditorField("名称", name) { name = it }
                EditorField("描述", description, minLines = 3) { description = it }
                EditorField("性格", personality) { personality = it }
                EditorField("场景", scenario) { scenario = it }
                EditorField("开场白", firstMes, minLines = 2) { firstMes = it }
                EditorField("示例对话", mesExample, minLines = 2) { mesExample = it }
                EditorField("标签（逗号分隔）", tags) { tags = it }
                EditorField("作者备注", creatorNotes) { creatorNotes = it }
                EditorField("系统提示词", systemPrompt, minLines = 2) { systemPrompt = it }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val tagList = tags.split(',').map { it.trim() }
                                .filter { it.isNotEmpty() }
                            val tagJson = JsonArray(tagList.map { JsonPrimitive(it) })
                            val result = if (existing != null) {
                                existing.card.withData(
                                    "name" to JsonPrimitive(name.trim()),
                                    "description" to JsonPrimitive(description),
                                    "personality" to JsonPrimitive(personality),
                                    "scenario" to JsonPrimitive(scenario),
                                    "first_mes" to JsonPrimitive(firstMes),
                                    "mes_example" to JsonPrimitive(mesExample),
                                    "tags" to tagJson,
                                    "creator_notes" to JsonPrimitive(creatorNotes),
                                    "system_prompt" to JsonPrimitive(systemPrompt),
                                )
                            } else {
                                CardSpec.normalize(
                                    buildJsonObject {
                                        put("name", name.trim())
                                        put("description", description)
                                        put("personality", personality)
                                        put("scenario", scenario)
                                        put("first_mes", firstMes)
                                        put("mes_example", mesExample)
                                        put("tags", tagJson)
                                        put("creator_notes", creatorNotes)
                                        put("system_prompt", systemPrompt)
                                    },
                                )
                            }
                            onSave(result, avatarBytes)
                        },
                        enabled = name.isNotBlank(),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = minLines,
        maxLines = if (minLines > 1) 6 else 1,
    )
}

private sealed interface EditorState {
    data object Create : EditorState
    data class Edit(val entry: CharacterEntry) : EditorState
}

private data class ExportPayload(val name: String, val bytes: ByteArray)

private fun safeFileName(name: String): String = name.trim()
    .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
    .joinToString("")
    .ifBlank { "character" }
