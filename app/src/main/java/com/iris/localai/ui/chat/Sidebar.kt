package com.iris.localai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iris.localai.agent.AssistantMessageParser
import com.iris.localai.agent.ChatRole
import com.iris.localai.agent.Conversation
import com.iris.localai.character.CharacterEntry
import com.iris.localai.data.Space
import com.iris.localai.plugin.PluginHookDescriptor
import com.iris.localai.workspace.Workspace
import com.iris.localai.workspace.WorkspaceEntry
import com.iris.localai.workspace.WorkspaceFs
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Sidebar(
    conversations: List<Conversation>,
    currentId: Long?,
    space: Space,
    workspaceId: String,
    workspaces: List<Workspace>,
    characters: List<CharacterEntry>,
    characterId: String,
    onSelectConversation: (Long) -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onSelectCharacter: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onArchiveConversation: (Long) -> Unit,
    onRestoreConversation: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    pluginEntries: List<PluginHookDescriptor> = emptyList(),
    onPluginEntry: (PluginHookDescriptor) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingRename by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var workspaceExpanded by rememberSaveable { mutableStateOf(false) }
    var filesWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }
    val activeWorkspace = workspaces.firstOrNull { it.id == workspaceId }
    val activeCharacterName = characters.firstOrNull { it.id == characterId }?.card?.charName.orEmpty()
    val spaceLabel = if (space == Space.LIFE && activeCharacterName.isNotBlank()) {
        "${space.label} · $activeCharacterName"
    } else {
        space.label
    }
    val inSpace = conversations
        .filter {
            it.archivedAt == 0L &&
                it.space == space && (space != Space.LIFE || it.characterId == characterId)
        }
        .sortedByDescending { it.updatedAt }
    val archivedInSpace = conversations
        .filter {
            it.archivedAt > 0L &&
                it.space == space && (space != Space.LIFE || it.characterId == characterId)
        }
        .sortedByDescending { it.archivedAt }

    ModalDrawerSheet(
        modifier = Modifier.width(240.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("IRIS LocalAI", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "$spaceLabel · ${inSpace.size} 个会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onTogglePinned) {
                    Icon(
                        imageVector = if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (pinned) "取消固定侧边栏" else "固定侧边栏",
                        tint = if (pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        workspaceExpanded = !workspaceExpanded
                        if (workspaceExpanded && filesWorkspaceId == null) {
                            filesWorkspaceId = workspaceId.takeIf { id -> workspaces.any { it.id == id } }
                                ?: workspaces.firstOrNull()?.id
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "工作区",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = activeWorkspace?.name ?: "未选择",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (workspaceExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (workspaceExpanded) "收起工作区" else "展开工作区",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (workspaceExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    workspaces.forEach { workspace ->
                        WorkspaceRow(
                            workspace = workspace,
                            selected = workspace.id == workspaceId,
                            filesExpanded = filesWorkspaceId == workspace.id,
                            onClick = { onSelectWorkspace(workspace.id) },
                            onToggleFiles = {
                                filesWorkspaceId = if (filesWorkspaceId == workspace.id) {
                                    null
                                } else {
                                    workspace.id
                                }
                            },
                        )
                        if (filesWorkspaceId == workspace.id) {
                            WorkspaceFileTree(workspace = workspace)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SidebarLabel("角色卡")
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(characters, key = { it.id }) { entry ->
                    CharacterChip(
                        entry = entry,
                        selected = entry.id == characterId,
                        onClick = { onSelectCharacter(entry.id) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (pluginEntries.isNotEmpty()) {
                SidebarLabel("插件")
                pluginEntries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPluginEntry(entry) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title.ifBlank { entry.id },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = entry.pluginName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            TextButton(
                onClick = onNewConversation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("新建对话")
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (inSpace.isEmpty()) {
                    item {
                        Text(
                            text = "当前空间还没有会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(inSpace, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == currentId,
                        archived = false,
                        onClick = { onSelectConversation(conversation.id) },
                        onRename = {
                            pendingRename = conversation
                            renameText = conversation.title
                        },
                        onArchive = { onArchiveConversation(conversation.id) },
                        onRestore = {},
                        onDelete = { pendingDelete = conversation },
                    )
                }
                if (archivedInSpace.isNotEmpty()) {
                    item(key = "archived-header") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { archivedExpanded = !archivedExpanded }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "已归档（${archivedInSpace.size}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (archivedExpanded) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = if (archivedExpanded) "收起已归档" else "展开已归档",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (archivedExpanded) {
                        items(archivedInSpace, key = { "archived-${it.id}" }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                selected = false,
                                archived = true,
                                onClick = { onRestoreConversation(conversation.id) },
                                onRename = {
                                    pendingRename = conversation
                                    renameText = conversation.title
                                },
                                onArchive = {},
                                onRestore = { onRestoreConversation(conversation.id) },
                                onDelete = { pendingDelete = conversation },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text("设置", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话？") },
            text = { Text("「${conversation.title}」将从当前空间移除，操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(conversation.id)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    pendingRename?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(40) },
                    singleLine = true,
                    label = { Text("会话名称") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        onRenameConversation(conversation.id, renameText)
                        pendingRename = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SidebarLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    selected: Boolean,
    filesExpanded: Boolean,
    onClick: () -> Unit,
    onToggleFiles: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = workspace.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onToggleFiles),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (filesExpanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = if (filesExpanded) "收起文件" else "展开文件",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 工作区文件树：按目录逐级展开，在侧栏内只读浏览。 */
@Composable
private fun WorkspaceFileTree(workspace: Workspace) {
    val context = LocalContext.current
    val fs = remember(workspace.id) { WorkspaceFs(context, workspace) }
    WorkspaceDirContents(fs = fs, path = "", depth = 1)
}

@Composable
private fun WorkspaceDirContents(fs: WorkspaceFs, path: String, depth: Int) {
    var entries by remember(path) { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) { fs.entries(path) }
        result.onSuccess { entries = it }
            .onFailure { error = it.message ?: "读取目录失败" }
    }
    val indent = Modifier.padding(start = (4 + depth * 10).dp, top = 2.dp, bottom = 2.dp, end = 4.dp)
    when {
        error != null -> Text(
            text = error!!,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = indent,
        )
        entries == null -> Text(
            text = "加载中…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = indent,
        )
        entries!!.isEmpty() -> Text(
            text = if (path.isEmpty()) "工作区为空" else "空目录",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = indent,
        )
        else -> entries!!.forEach { entry ->
            WorkspaceFileRow(fs = fs, entry = entry, depth = depth)
        }
    }
}

@Composable
private fun WorkspaceFileRow(fs: WorkspaceFs, entry: WorkspaceEntry, depth: Int) {
    var expanded by remember(entry.path) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDir) { expanded = !expanded }
            .padding(start = (4 + depth * 10).dp, top = 3.dp, bottom = 3.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                !entry.isDir -> Icons.Outlined.Description
                expanded -> Icons.Outlined.FolderOpen
                else -> Icons.Outlined.Folder
            },
            contentDescription = null,
            tint = if (entry.isDir) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.isDir) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDir) {
            Text(
                text = formatEntrySize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (entry.isDir && expanded) {
        WorkspaceDirContents(fs = fs, path = entry.path, depth = depth + 1)
    }
}

private fun formatEntrySize(size: Long): String = when {
    size < 0 -> ""
    size >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", size / (1L shl 30).toDouble())
    size >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", size / (1L shl 20).toDouble())
    size >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", size / (1L shl 10).toDouble())
    else -> "$size B"
}

@Composable
private fun CharacterChip(
    entry: CharacterEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CharacterAvatar(entry = entry, size = 46.dp, selected = selected)
        Text(
            text = entry.card.charName,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val last = conversation.messages.lastOrNull()
    val subtitleSource = if (last?.role == ChatRole.ASSISTANT) {
        AssistantMessageParser.mainText(last.text)
    } else {
        last?.text
    }
    val subtitle = subtitleSource
        ?.replace("\n", " ")
        ?.take(28)
        ?.ifBlank { null }
        ?: "暂无消息"

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (archived) "已归档 · $subtitle" else subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "会话操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    if (archived) {
                        DropdownMenuItem(
                            text = { Text("恢复") },
                            onClick = {
                                menu = false
                                onRestore()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("归档") },
                            onClick = {
                                menu = false
                                onArchive()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
