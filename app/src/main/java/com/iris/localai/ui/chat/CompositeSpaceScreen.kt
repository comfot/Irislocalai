package com.iris.localai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.agent.Conversation
import com.iris.localai.data.Space
import com.iris.localai.engine.EngineState
import com.iris.localai.server.ApiState
import com.iris.localai.workspace.Workspace
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.delay
import java.io.File

internal enum class StudioPlace(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    STUDIO("studio", "工作室", "当前角色与工作区在这里待命。", Icons.Outlined.Business),
    CAMPUS("campus", "园区", "工作室和它周围的地方，点一栋楼进去看看。", Icons.Outlined.Park),
    LIBRARY(
        "library",
        "图书馆",
        "知识、记忆和日记都收在这里。",
        Icons.AutoMirrored.Outlined.MenuBook,
    ),
    SITE("site", "工地", "工作区里一个文件夹一个街区，一个文件一栋楼。", Icons.Outlined.Construction),
    DUTY("duty", "值班室", "API、MCP 与推理引擎在这里值班。", Icons.Outlined.Schedule),
    DESK("desk", "前台", "本机与配对的电脑在这里登记。", Icons.Outlined.Computer),
    ;

    companion object {
        fun of(id: String): StudioPlace = entries.firstOrNull { it.id == id } ?: STUDIO
    }
}

@Composable
fun CompositeSpaceScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allConversations by viewModel.conversations.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val conversations = allConversations.filter {
        it.space != Space.LIFE || it.characterId == settings.characterId
    }
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val workspace by viewModel.activeWorkspace.collectAsStateWithLifecycle()
    val character by viewModel.activeCharacter.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val apiState by viewModel.apiState.collectAsStateWithLifecycle()
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val knowledge by viewModel.knowledge.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpSettings by viewModel.mcpSettings.collectAsStateWithLifecycle()
    val placeId by viewModel.studioPlace.collectAsStateWithLifecycle()

    val place = StudioPlace.of(placeId)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = character?.card?.accent ?: 0xFF0D84FF
    val accentHex = String.format("#%06X", accent and 0xFFFFFFL)
    val fileCount = remember(workspace?.path) { countFiles(workspace) }
    val engineLabel = engineState.summary()
    val apiRunning = apiState is ApiState.Running
    val apiPort = (apiState as? ApiState.Running)?.port ?: settings.apiPort
    val controller = remember { Studio3DController() }
    val studioStatus by controller.status.collectAsStateWithLifecycle()
    var paused by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(controller) {
        delay(8_000)
        if (controller.status.value is StudioStatus.Loading) {
            controller.markFailed("3D 视图 8 秒内未完成初始化")
        }
    }

    val stateJson = buildJsonObject {
        put("place", place.id)
        put("accent", accentHex)
        put("dark", dark)
        putJsonObject("stats") {
            put("character", character?.card?.charName ?: "Agent")
            put("workspace", workspace?.name ?: "未选择工作区")
            put("files", fileCount)
            put("workspaceCount", workspaces.size)
            put("conversations", conversations.size)
            put("characters", characters.size)
            put("knowledge", knowledge.size)
            put("memories", memories.size)
            put("diary", diary.size)
            put("apiEnabled", apiRunning)
            put("apiPort", apiPort)
            put("mcpEnabled", mcpSettings.enabled)
            put("mcpPort", mcpSettings.port)
            put("mcpServers", mcpServers.size)
            put("engine", engineLabel)
            put("running", running)
        }
    }.toString()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Studio3DView(
            place = place.id,
            accentHex = accentHex,
            dark = dark,
            stateJson = stateJson,
            paused = paused,
            controller = controller,
            onPlaceSelected = viewModel::selectStudioPlace,
            modifier = Modifier.fillMaxSize(),
        )

        if (studioStatus is StudioStatus.Loading) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("正在加载 3D 场景…", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (studioStatus is StudioStatus.Failed) {
            StudioErrorCard(
                message = (studioStatus as StudioStatus.Failed).message,
                onRetry = controller::reload,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
            )
        }

        StudioHeader(
            place = place,
            dark = dark,
            paused = paused,
            onOpenDrawer = onOpenDrawer,
            onResetView = controller::resetView,
            onTogglePause = { paused = !paused },
            onSelectPlace = viewModel::selectStudioPlace,
            onSelectSpace = viewModel::selectSpace,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        StudioFooter(
            place = place,
            workspace = workspace,
            conversations = conversations,
            stats = statsFor(
                place = place,
                workspace = workspace,
                fileCount = fileCount,
                knowledgeCount = knowledge.size,
                memoryCount = memories.size,
                diaryCount = diary.size,
                conversations = conversations.size,
                characters = characters.size,
                apiRunning = apiRunning,
                apiPort = apiPort,
                mcpEnabled = mcpSettings.enabled,
                mcpPort = mcpSettings.port,
                engineLabel = engineLabel,
                running = running,
            ),
            onOpenConversation = viewModel::openConversation,
            onEnterWork = { viewModel.selectSpace(Space.WORK) },
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun StudioErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "3D 场景未能加载",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun StudioHeader(
    place: StudioPlace,
    dark: Boolean,
    paused: Boolean,
    onOpenDrawer: () -> Unit,
    onResetView: () -> Unit,
    onTogglePause: () -> Unit,
    onSelectPlace: (String) -> Unit,
    onSelectSpace: (Space) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        background.copy(alpha = 0.96f),
                        background.copy(alpha = 0.82f),
                        Color.Transparent,
                    ),
                ),
            )
            .statusBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "打开侧边栏")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = place.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onResetView) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "重置视角",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTogglePause) {
                Icon(
                    imageVector = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = if (paused) "继续动画" else "暂停动画",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SpaceSwitchButton(current = Space.COMPOSITE, onSelect = onSelectSpace)
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
        ) {
            items(StudioPlace.entries, key = { it.id }) { item ->
                PlaceChip(
                    place = item,
                    selected = item == place,
                    dark = dark,
                    onClick = { onSelectPlace(item.id) },
                )
            }
        }
    }
}

@Composable
private fun PlaceChip(
    place: StudioPlace,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) scheme.primary else scheme.surface.copy(alpha = if (dark) 0.72f else 0.86f),
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
        tonalElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else scheme.outline.copy(alpha = 0.18f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(place.icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(place.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private data class StudioStat(val label: String, val value: String)

private fun statsFor(
    place: StudioPlace,
    workspace: Workspace?,
    fileCount: Int,
    knowledgeCount: Int,
    memoryCount: Int,
    diaryCount: Int,
    conversations: Int,
    characters: Int,
    apiRunning: Boolean,
    apiPort: Int,
    mcpEnabled: Boolean,
    mcpPort: Int,
    engineLabel: String,
    running: Boolean,
): List<StudioStat> = when (place) {
    StudioPlace.STUDIO -> listOf(
        StudioStat("工作区", workspace?.name ?: "未选择"),
        StudioStat("会话", "$conversations 段"),
        StudioStat("状态", if (running) "正在生成" else "空闲"),
    )

    StudioPlace.CAMPUS -> listOf(
        StudioStat("工作室", if (running) "忙碌" else "空闲"),
        StudioStat("图书馆", "$knowledgeCount 份知识"),
        StudioStat("工地", "$fileCount 个文件"),
        StudioStat("角色", "$characters 位"),
    )

    StudioPlace.LIBRARY -> listOf(
        StudioStat("知识库", "$knowledgeCount 份"),
        StudioStat("记忆", "$memoryCount 条"),
        StudioStat("日记", "$diaryCount 篇"),
    )

    StudioPlace.SITE -> listOf(
        StudioStat("工作区", workspace?.name ?: "未选择"),
        StudioStat("文件", "$fileCount 个"),
        StudioStat("会话", "$conversations 段"),
    )

    StudioPlace.DUTY -> listOf(
        StudioStat("API", if (apiRunning) "运行中 :$apiPort" else "已停止"),
        StudioStat("MCP", if (mcpEnabled) "运行中 :$mcpPort" else "已停止"),
        StudioStat("引擎", engineLabel),
    )

    StudioPlace.DESK -> listOf(
        StudioStat("本机 API", ":$apiPort"),
        StudioStat("MCP", ":$mcpPort"),
        StudioStat("角色卡", "$characters 位"),
    )
}

@Composable
private fun StudioFooter(
    place: StudioPlace,
    workspace: Workspace?,
    conversations: List<Conversation>,
    stats: List<StudioStat>,
    onOpenConversation: (Conversation) -> Unit,
    onEnterWork: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        scheme.background.copy(alpha = 0.78f),
                        scheme.background.copy(alpha = 0.96f),
                    ),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surface.copy(alpha = 0.92f),
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = place.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (place) {
                            StudioPlace.CAMPUS -> "点一栋楼进去看看"
                            StudioPlace.STUDIO -> "拖动旋转 · 双指缩放"
                            else -> "拖动旋转 · 双指缩放"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stats, key = { it.label }) { stat ->
                        StatChip(stat)
                    }
                }
                if (conversations.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            conversations.sortedByDescending { it.updatedAt }.take(6),
                            key = { it.id },
                        ) { conversation ->
                            ConversationChip(
                                conversation = conversation,
                                onClick = { onOpenConversation(conversation) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onEnterWork) {
                        Text(if (place == StudioPlace.SITE) "进入工作空间" else "开始工作")
                    }
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("设置")
                    }
                    if (workspace != null) {
                        Text(
                            text = workspace.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(stat: StudioStat) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = stat.value, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ConversationChip(conversation: Conversation, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .width(190.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${conversation.space.label} · ${conversation.messages.size} 条消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun countFiles(workspace: Workspace?): Int {
    val path = workspace?.path?.takeIf { it.isNotBlank() } ?: return 0
    val folder = File(path)
    if (!folder.isDirectory) return 0
    return runCatching {
        folder.walkTopDown().take(2000).count { it.isFile }
    }.getOrDefault(0)
}

private fun EngineState.summary(): String = when (this) {
    EngineState.Idle -> "待机"
    is EngineState.Loading -> "加载中"
    is EngineState.Ready -> "就绪"
    is EngineState.Failed -> "异常"
}
