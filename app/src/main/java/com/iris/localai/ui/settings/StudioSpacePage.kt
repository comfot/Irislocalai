package com.iris.localai.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.scene.StudioAvatar
import com.iris.localai.scene.StudioModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 综合空间 › 空间管理 › 综合空间：为每张角色卡配置「家」里的小人名字、造型、颜色与 YSM 模型。
 */
@Composable
internal fun StudioSpacePage() {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val characters by graph.characters.cards.collectAsStateWithLifecycle()
    val avatars by graph.studioAvatars.avatars.collectAsStateWithLifecycle()
    val models by graph.studioModels.models.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun runImport(block: suspend () -> Result<StudioModelEntry>) {
        if (importing) return
        importing = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            importing = false
            message = result.fold(
                onSuccess = { "已导入：${it.name}" },
                onFailure = { it.message ?: "导入失败" },
            )
        }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runImport {
                graph.studioModels.importFile(uri, displayName(context, uri))
            }
        }
    }
    val importTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runImport {
                graph.studioModels.importTree(uri, displayName(context, uri))
            }
        }
    }

    fun clearModelBinding(modelId: String) {
        characters.forEach { entry ->
            val avatar = avatars[entry.id] ?: StudioAvatar()
            if (avatar.modelId == modelId) {
                graph.studioAvatars.update(entry.id, avatar.copy(modelId = ""))
            }
        }
    }

    SettingsPage {
        SettingsCard("综合空间的小人") {
            Text(
                text = "「家」会按角色卡数量生成小人：每张角色卡对应一个居民。名字、造型与颜色会写入本地配置，" +
                    "重新进入综合空间后生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "当前角色卡 ${characters.size} 张",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingsCard("YSM 小人模型") {
            Text(
                text = "支持未加密的 YSM 模型（选择模型文件夹，或包含 ysm.json 的 ZIP），也支持直接导入加密的 .ysm 文件" +
                    "（在本机内存中解密，仅用于预览）。请确保你拥有该模型的使用授权。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        importFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    enabled = !importing,
                ) { Text("导入文件（ZIP/.ysm）") }
                OutlinedButton(
                    onClick = { importTree.launch(null) },
                    enabled = !importing,
                ) { Text("导入文件夹") }
            }
            if (importing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (models.isEmpty()) {
                Text(
                    text = "还没有 YSM 模型，导入后可在每张角色卡下方选择。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            models.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${model.sizeBytes / 1024 / 1024} MB · YSM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            clearModelBinding(model.id)
                            graph.studioModels.delete(model.id)
                            message = "已删除：${model.name}"
                        },
                    ) { Text("删除") }
                }
            }
        }
        characters.forEach { entry ->
            val avatar = avatars[entry.id] ?: StudioAvatar()
            SettingsCard(entry.card.charName) {
                OutlinedTextField(
                    value = avatar.name,
                    onValueChange = { value ->
                        graph.studioAvatars.update(entry.id, avatar.copy(name = value.take(24)))
                    },
                    label = { Text("小人名字") },
                    placeholder = { Text(entry.card.charName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("造型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudioAvatar.styles.forEach { (id, label) ->
                        FilterChip(
                            selected = avatar.style == id,
                            onClick = {
                                graph.studioAvatars.update(entry.id, avatar.copy(style = id))
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Text("颜色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    studioAvatarColors.forEach { hex ->
                        val selected = avatar.color.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(hex)))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable {
                                    graph.studioAvatars.update(entry.id, avatar.copy(color = hex))
                                },
                        )
                    }
                }
                Text("小人模型", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = avatar.modelId.isBlank(),
                        onClick = {
                            graph.studioAvatars.update(entry.id, avatar.copy(modelId = ""))
                        },
                        label = { Text("程序化小人") },
                    )
                    models.forEach { model ->
                        FilterChip(
                            selected = avatar.modelId == model.id,
                            onClick = {
                                graph.studioAvatars.update(entry.id, avatar.copy(modelId = model.id))
                            },
                            label = { Text(model.name) },
                        )
                    }
                }
                if (avatar.modelId.isNotBlank() && models.none { it.id == avatar.modelId }) {
                    Text(
                        text = "绑定的模型已不存在，将回退为程序化小人。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { graph.studioAvatars.reset(entry.id) }) { Text("恢复默认") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = avatar.color.ifBlank { "跟随角色卡强调色" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return (queried ?: uri.lastPathSegment.orEmpty()).ifBlank { "YSM 模型" }
}

private val studioAvatarColors = listOf(
    "#7DB3FF", "#5FD0B0", "#F2A65A", "#E27D9C",
    "#B39DDB", "#F6C177", "#8FD4FF", "#E8C3A5",
)
