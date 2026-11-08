package com.iris.localai.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.data.Space
import com.iris.localai.engine.EngineState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val space by viewModel.space.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val currentConversation by viewModel.currentConversation.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val activeCharacter by viewModel.activeCharacter.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val activeWorkspace by viewModel.activeWorkspace.collectAsStateWithLifecycle()
    val pluginSidebarEntries by viewModel.pluginSidebarEntries.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var lifeFullscreen by remember { mutableStateOf(false) }
    var sidebarPinned by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(space) {
        if (space != Space.LIFE) lifeFullscreen = false
    }
    LaunchedEffect(Unit) { viewModel.loadPluginSidebarEntries() }

    val character = activeCharacter

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !sidebarPinned || drawerState.isOpen,
        drawerContent = {
            Sidebar(
                conversations = conversations,
                currentId = currentId,
                space = space,
                workspaceId = activeWorkspace?.id.orEmpty(),
                workspaces = workspaces,
                characters = characters,
                characterId = settings.characterId,
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onSelectWorkspace = { id ->
                    viewModel.setActiveWorkspace(id)
                },
                onSelectCharacter = { id ->
                    viewModel.setActiveCharacter(id)
                    viewModel.selectSpace(Space.LIFE)
                },
                onNewConversation = {
                    viewModel.newConversation()
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = viewModel::deleteConversation,
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                pinned = sidebarPinned,
                onTogglePinned = { sidebarPinned = !sidebarPinned },
                pluginEntries = pluginSidebarEntries,
                onPluginEntry = { entry ->
                    viewModel.runPluginSidebarEntry(entry)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (space != Space.COMPOSITE && !(space == Space.LIFE && lifeFullscreen)) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "打开侧边栏")
                            }
                        },
                    title = {
                        Column {
                            Text(
                                text = titleFor(
                                    space = space,
                                    conversationTitle = currentConversation?.title,
                                    characterName = character?.card?.charName ?: "角色卡",
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subtitleFor(
                                    space = space,
                                    workspaceLabel = activeWorkspace?.name ?: "未选择工作区",
                                    engineLabel = engineState.label(),
                                    characterTagline = character?.card?.blurb(18) ?: "选择角色卡开始陪伴",
                                    characterModel = settings.characterModel.label,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                        actions = {
                            SpaceSwitchButton(
                                current = space,
                                onSelect = viewModel::selectSpace,
                            )
                        },
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (space) {
                    Space.WORK -> WorkSpaceScreen(viewModel, Modifier.fillMaxSize())
                    Space.LIFE -> LifeSpaceScreen(
                        viewModel = viewModel,
                        onFullscreenChange = { lifeFullscreen = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Space.OPERATE -> OperateSpaceScreen(viewModel, Modifier.fillMaxSize())
                    Space.COMPOSITE -> CompositeSpaceScreen(
                        viewModel = viewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SpaceSwitchButton(current: Space, onSelect: (Space) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = current.icon(),
                contentDescription = "切换空间",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Space.entries.forEach { space ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = space.icon(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(space.label)
                                Text(
                                    text = space.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(space)
                        expanded = false
                    },
                    trailingIcon = {
                        if (space == current) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

private fun titleFor(
    space: Space,
    conversationTitle: String?,
    characterName: String,
): String = when (space) {
    Space.WORK -> conversationTitle ?: "新对话"
    Space.LIFE -> characterName
    Space.OPERATE -> "手机操作"
    Space.COMPOSITE -> "工作室"
}

private fun subtitleFor(
    space: Space,
    workspaceLabel: String,
    engineLabel: String,
    characterTagline: String,
    characterModel: String,
): String = when (space) {
    Space.WORK -> "$workspaceLabel · $engineLabel"
    Space.LIFE -> "$characterModel 角色 · $characterTagline"
    Space.OPERATE -> "权限 · 界面操作预览"
    Space.COMPOSITE -> "园区与工作室"
}

private fun EngineState.label(): String = when (this) {
    EngineState.Idle -> "引擎待机"
    is EngineState.Loading -> "加载中 ${(progress * 100).toInt()}%"
    is EngineState.Ready -> "已就绪 · ${details.name}"
    is EngineState.Failed -> "失败：$message"
}
