package com.iris.moew.ui.settings

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
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Palette
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
import androidx.compose.runtime.remember
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
import com.iris.moew.agent.Conversation
import com.iris.moew.data.AppSettings
import com.iris.moew.engine.EngineState
import com.iris.moew.plugin.resolveUiModuleId
import com.iris.moew.plugin.uiRouteModuleId
import com.iris.moew.server.ApiState
import com.iris.moew.ui.theme.IrisCard

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
    val pluginRegistrations by viewModel.pluginRegistrations.collectAsStateWithLifecycle()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val pluginEntries = remember(pluginRegistrations, plugins, settings.pluginDevMode) {
        val enabled = plugins.filter { it.enabled }.associateBy { it.id }
        pluginRegistrations.values
            .flatMap { it.ui }
            .filter {
                it.type == "settings_entry" && enabled.containsKey(it.pluginId) &&
                    (it.pluginId != "iris.plugin_devkit" || settings.pluginDevMode)
            }
            .sortedWith(compareBy({ it.order }, { it.pluginName }, { it.id }))
            .mapNotNull { item ->
                val moduleId = pluginRegistrations[item.pluginId]?.resolveUiModuleId(item)
                    ?: item.uiRouteModuleId()
                if (moduleId.isBlank()) null
                else PluginSettingsEntry(
                    id = "plugin:${item.pluginId}:$moduleId",
                    pluginId = item.pluginId,
                    moduleId = moduleId,
                    title = item.title.ifBlank { item.id },
                    order = item.order,
                )
            }
    }

    var entryId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var marketSectionName by rememberSaveable { mutableStateOf(MarketSection.MODELS.name) }
    var showThemePreset by rememberSaveable { mutableStateOf(false) }
    val entry = SettingsCatalog.find(entryId)
        ?: pluginEntries.firstOrNull { it.id == entryId }?.let { item ->
            SettingsEntry(item.id, item.title, "插件", Icons.Outlined.Extension)
        }

    LaunchedEffect(navigationRequestId, initialEntryId) {
        if (!initialEntryId.isNullOrBlank()) {
            entryId = initialEntryId
            previousEntryId = when (initialEntryId) {
                "pet" -> "interaction"
                "studio-space" -> "space"
                else -> null
            }
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
            (entryId == "live2d-control" || entryId == "model3d-control" || entryId == "studio-space") && previous != null -> {
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
                actions = {
                    if (entry?.id == "theme") {
                        IconButton(onClick = { showThemePreset = true }) {
                            Icon(Icons.Outlined.Palette, contentDescription = "主题预设")
                        }
                    }
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
                    pluginEntries = pluginEntries,
                    onEntry = { entryId = it.id; previousEntryId = null },
                )
            } else if (entry.id.startsWith("plugin:")) {
                val target = pluginEntries.firstOrNull { it.id == entry.id }
                val plugin = plugins.firstOrNull { it.id == target?.pluginId }
                if (target != null && plugin != null) {
                    PluginSettingsPage(
                        plugin = plugin,
                        moduleId = target.moduleId,
                        title = target.title,
                        onClose = { goBack() },
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    PluginSlot("settings.page.${entry.id}")
                    Box(modifier = Modifier.weight(1f)) {
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
                    onOpenStudioSpace = {
                        previousEntryId = "space"
                        entryId = "studio-space"
                    },
                    onOpenThemePreset = { showThemePreset = true },
                )
                    }
                }
            }
        }
    }

    if (showThemePreset) {
        ThemePresetDialog(
            settings = settings,
            viewModel = viewModel,
            onDismiss = { showThemePreset = false },
        )
    }
}

@Composable
private fun SettingsHome(
    settings: AppSettings,
    engineState: EngineState,
    conversations: List<Conversation>,
    pluginEntries: List<PluginSettingsEntry>,
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
        if (pluginEntries.isNotEmpty()) {
            item(key = "plugin_settings") {
                SettingsSectionCard(
                    section = SettingsSection(
                        title = "插件",
                        entries = pluginEntries.map {
                            SettingsEntry(it.id, it.title, "插件设置", Icons.Outlined.Extension)
                        },
                    ),
                    onEntry = onEntry,
                )
            }
        }
        item {
            Text(
                text = "IrisMoew 0.1.0 · 本地 AI 工作台",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class PluginSettingsEntry(
    val id: String,
    val pluginId: String,
    val moduleId: String,
    val title: String,
    val order: Int,
)

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
                text = "IrisMoew",
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
    IrisCard(modifier = Modifier.fillMaxWidth()) {
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
    onOpenStudioSpace: () -> Unit,
    onOpenThemePreset: () -> Unit,
) {
    when (entry.id) {
        "model" -> ModelSettingsPage(settings, viewModel) { onOpenMarket(MarketSection.MODELS) }
        "backend" -> BackendPage(settings, engineState, viewModel)
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
        "network" -> NetworkPage(settings, viewModel)
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
        "developer-options" -> DeveloperOptionsPage()
        "market" -> MarketPage(initialSection = initialMarketSection)
        "theme" -> ThemePage(settings, viewModel, onOpenThemePreset)
        "component" -> ComponentPage(settings, viewModel)
        "space" -> SpacePage(
            settings,
            conversations,
            viewModel,
            onOpenStudioSpace = onOpenStudioSpace,
        )
        "integration" -> IntegrationPage()
        "studio-space" -> StudioSpacePage()
        "permission" -> PermissionPage(viewModel)
        "storage" -> StoragePage(viewModel)
        "about" -> AboutPage()
        "backup" -> BackupPage(viewModel)
        "restore" -> RestorePage(viewModel)
        else -> PlaceholderPage(entry)
    }
}
