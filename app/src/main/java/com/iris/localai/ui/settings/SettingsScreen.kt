package com.iris.localai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.agent.Conversation
import com.iris.localai.data.AppSettings
import com.iris.localai.engine.EngineState
import com.iris.localai.server.ApiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    initialEntryId: String? = null,
    navigationRequestId: Long = 0L,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val apiState by viewModel.apiState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    var entryId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var marketSectionName by rememberSaveable { mutableStateOf(MarketSection.MODELS.name) }
    val entry = SettingsCatalog.find(entryId)

    LaunchedEffect(navigationRequestId, initialEntryId) {
        if (!initialEntryId.isNullOrBlank()) {
            entryId = initialEntryId
            previousEntryId = if (initialEntryId == "pet") "interaction" else null
        }
    }

    fun goBack() {
        val previous = previousEntryId
        when {
            entryId == "market" && previous != null -> {
                entryId = previous
                previousEntryId = null
            }
            entryId == "pet" && previous != null -> {
                entryId = previous
                previousEntryId = null
            }
            (entryId == "live2d-control" || entryId == "model3d-control") && previous != null -> {
                entryId = previous
                previousEntryId = null
            }
            entry != null -> entryId = null
            else -> onBack()
        }
    }

    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = entry?.title ?: "设置",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (entry == null) {
                SettingsHome(
                    settings = settings,
                    engineState = engineState,
                    conversations = conversations,
                    onEntry = { entryId = it.id; previousEntryId = null },
                )
            } else {
                SettingsDetail(
                    entry = entry,
                    settings = settings,
                    engineState = engineState,
                    apiState = apiState,
                    conversations = conversations,
                    viewModel = viewModel,
                    initialMarketSection = MarketSection.valueOf(marketSectionName),
                    onOpenMarket = { section ->
                        marketSectionName = section.name
                        previousEntryId = entryId
                        entryId = "market"
                    },
                    onOpenPet = {
                        previousEntryId = entryId
                        entryId = "pet"
                    },
                    onOpenLive2D = {
                        previousEntryId = entryId
                        entryId = "live2d-control"
                    },
                    onOpen3D = {
                        previousEntryId = entryId
                        entryId = "model3d-control"
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsHome(
    settings: AppSettings,
    engineState: EngineState,
    conversations: List<Conversation>,
    onEntry: (SettingsEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsHeader(
                settings = settings,
                engineState = engineState,
                conversationCount = conversations.count { it.space == settings.space },
            )
        }
        SettingsCatalog.sections.forEach { section ->
            item(key = section.title) {
                SettingsSectionCard(section = section, onEntry = onEntry)
            }
        }
        item {
            Text(
                text = "IRIS LocalAI 0.1.0 · 界面模板",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingsHeader(
    settings: AppSettings,
    engineState: EngineState,
    conversationCount: Int,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "IRIS LocalAI",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "本地推理 · Agent 工作台",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(settings.space.label)
                StatusPill("$conversationCount 个会话")
                StatusPill(engineState.display())
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: SettingsSection,
    onEntry: (SettingsEntry) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            section.entries.filterNot { it.hidden }.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )
                }
                SettingsRow(entry = entry, onClick = { onEntry(entry) })
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsDetail(
    entry: SettingsEntry,
    settings: AppSettings,
    engineState: EngineState,
    apiState: ApiState,
    conversations: List<Conversation>,
    viewModel: SettingsViewModel,
    initialMarketSection: MarketSection,
    onOpenMarket: (MarketSection) -> Unit,
    onOpenPet: () -> Unit,
    onOpenLive2D: () -> Unit,
    onOpen3D: () -> Unit,
) {
    when (entry.id) {
        "model" -> ModelSettingsPage(settings, engineState, viewModel) { onOpenMarket(MarketSection.MODELS) }
        "backend" -> BackendPage(settings, viewModel)
        "generation" -> GenerationPage(settings, viewModel)
        "performance" -> PerformancePage(settings, viewModel)
        "agent" -> AgentPage(settings, viewModel)
        "context" -> ContextPage(settings, viewModel)
        "user" -> UserPage(settings, viewModel)
        "character-card" -> CharacterCardsPage()
        "character-model" -> CharacterModelsPage()
        "voice" -> VoiceSettingsPage()
        "scene" -> ScenePage()
        "interaction" -> InteractionPage(
            settings,
            viewModel,
            onOpenPet = onOpenPet,
            onOpenLive2D = onOpenLive2D,
            onOpen3D = onOpen3D,
        )
        "live2d-control" -> Live2DControlPage(settings, viewModel)
        "model3d-control" -> Model3DControlPage(settings, viewModel)
        "notification" -> NotificationPage(settings, viewModel)
        "pet" -> PetSettingsPage(settings, viewModel)
        "skill" -> SkillPage()
        "code" -> CodeManagementPage()
        "lorebook" -> WorldBookPage()
        "memory" -> MemoryPage()
        "api" -> ApiServicePage()
        "mcp" -> McpPage()
        "plugin" -> PluginPage(onOpenMarket = { onOpenMarket(MarketSection.PLUGINS) })
        "market" -> MarketPage(initialSection = initialMarketSection)
        "theme" -> ThemePage(settings, viewModel)
        "space" -> SpacePage(settings, conversations, viewModel)
        "permission" -> PermissionPage(viewModel)
        "about" -> AboutPage()
        "backup" -> BackupPage(settings, viewModel)
        "restore" -> RestorePage(viewModel)
        else -> PlaceholderPage(entry)
    }
}
