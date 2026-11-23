package com.iris.moew

import android.content.Context
import com.iris.moew.agent.AgentLoop
import com.iris.moew.agent.ToolConfirmation
import com.iris.moew.agent.ConversationStore
import com.iris.moew.agent.PlanStore
import com.iris.moew.agent.ToolRegistry
import com.iris.moew.agent.tools.DeviceBackTool
import com.iris.moew.agent.tools.DeviceHomeTool
import com.iris.moew.agent.tools.DeviceEnterTool
import com.iris.moew.agent.tools.DeviceInputTextTool
import com.iris.moew.agent.tools.DeviceLongPressTool
import com.iris.moew.agent.tools.DeviceNotificationsTool
import com.iris.moew.agent.tools.DeviceOpenAppTool
import com.iris.moew.agent.tools.DeviceRecentsTool
import com.iris.moew.agent.tools.DeviceScreenshotTool
import com.iris.moew.agent.tools.DeviceScrollTool
import com.iris.moew.agent.tools.DeviceSwipeTool
import com.iris.moew.agent.tools.DeviceTapTextTool
import com.iris.moew.agent.tools.DeviceTapTool
import com.iris.moew.agent.tools.DeviceUiTool
import com.iris.moew.agent.tools.DeviceWaitTextTool
import com.iris.moew.agent.tools.BrowserBackTool
import com.iris.moew.agent.tools.BrowserClickTool
import com.iris.moew.agent.tools.BrowserInputTool
import com.iris.moew.agent.tools.BrowserOpenTool
import com.iris.moew.agent.tools.BrowserNavigateTool
import com.iris.moew.agent.tools.BrowserReadTool
import com.iris.moew.agent.tools.BrowserScreenshotTool
import com.iris.moew.agent.tools.BrowserScrollTool
import com.iris.moew.agent.tools.BrowserSnapshotTool
import com.iris.moew.agent.tools.BrowserWaitTool
import com.iris.moew.agent.tools.CurrentTimeTool
import com.iris.moew.agent.tools.DeviceInfoTool
import com.iris.moew.agent.tools.EditFileTool
import com.iris.moew.agent.tools.FetchUrlTool
import com.iris.moew.agent.tools.ListFilesTool
import com.iris.moew.agent.tools.MemoryTool
import com.iris.moew.agent.tools.McpConfigTool
import com.iris.moew.agent.tools.MotionControlTool
import com.iris.moew.agent.tools.ExpressionControlTool
import com.iris.moew.agent.tools.RandomPickTool
import com.iris.moew.agent.tools.ReadFileTool
import com.iris.moew.agent.tools.SearchFilesTool
import com.iris.moew.agent.tools.PlanTool
import com.iris.moew.agent.tools.UseSkillTool
import com.iris.moew.agent.tools.WorkspaceTool
import com.iris.moew.agent.tools.PluginDevTool
import com.iris.moew.agent.tools.WriteFileTool
import com.iris.moew.browser.BrowserController
import com.iris.moew.character.CharacterCardStore
import com.iris.moew.code.CodeSettingsStore
import com.iris.moew.agent.subagent.SubAgentManager
import com.iris.moew.agent.subagent.SubAgentStore
import com.iris.moew.agent.tools.TaskTool
import com.iris.moew.agent.tools.TerminalTool
import com.iris.moew.code.ShellTool
import com.iris.moew.code.TerminalSessions
import com.iris.moew.character.CharacterModelStore
import com.iris.moew.data.HuggingFaceClient
import com.iris.moew.data.AssetDownloadManager
import com.iris.moew.data.ModelDownloadManager
import com.iris.moew.data.ModelProfile
import com.iris.moew.data.ModelProfileStore
import com.iris.moew.data.ModelRole
import com.iris.moew.data.ProviderKind
import com.iris.moew.data.PluginAiProviderRef
import com.iris.moew.data.SettingsRepository
import com.iris.moew.data.ToolPermission
import com.iris.moew.engine.Backend
import com.iris.moew.engine.EngineManager
import com.iris.moew.engine.InferenceEngine
import com.iris.moew.engine.OpenAiCompatibleEngine
import com.iris.moew.engine.PluginAiEngine
import com.iris.moew.engine.StubEngine
import com.iris.moew.integration.BotMessageRouter
import com.iris.moew.integration.IntegrationManager
import com.iris.moew.integration.IntegrationStore
import com.iris.moew.engine.llama.LlamaEngine
import com.iris.moew.lorebook.WorldBookStore
import com.iris.moew.mcp.McpConnectionManager
import com.iris.moew.mcp.McpServerService
import com.iris.moew.mcp.McpStore
import com.iris.moew.mcp.McpTransportKind
import com.iris.moew.mcp.SseTransport
import com.iris.moew.mcp.StdioTransport
import com.iris.moew.mcp.StreamableHttpTransport
import com.iris.moew.motion.HastMotionStore
import com.iris.moew.motion.GitHubAssetClient
import com.iris.moew.motion.MotionAssetStore
import com.iris.moew.motion.VrmaMotionStore
import com.iris.moew.motion.VrmaGenerationEnvironment
import com.iris.moew.motion.VrmaGenerateTool
import com.iris.moew.motion.MotionBindingStore
import com.iris.moew.motion.ExpressionBindingStore
import com.iris.moew.memory.MemoryStore
import com.iris.moew.memory.MemoryAutoCapture
import com.iris.moew.notify.ChatNotifier
import com.iris.moew.permission.DeviceActionExecutor
import com.iris.moew.permission.DevicePermissionStore
import com.iris.moew.permission.VirtualDisplayController
import com.iris.moew.plugin.PluginRuntime
import com.iris.moew.plugin.HostActionRegistry
import com.iris.moew.plugin.PluginDevService
import com.iris.moew.plugin.PluginStore
import com.iris.moew.plugin.PluginTerminalHost
import com.iris.moew.plugin.PluginToolInstaller
import com.iris.moew.scene.SceneStore
import com.iris.moew.scene.StudioAvatarStore
import com.iris.moew.scene.StudioModelStore
import com.iris.moew.skill.SkillStore
import com.iris.moew.storage.AppStorage
import com.iris.moew.storage.BackupService
import com.iris.moew.server.ApiService
import com.iris.moew.voice.TtsController
import com.iris.moew.voice.CloudVoiceClient
import com.iris.moew.voice.LocalVoiceRuntime
import com.iris.moew.voice.VoiceDownloadManager
import com.iris.moew.voice.VoiceModelStore
import com.iris.moew.workspace.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 手动装配的依赖图。项目不引入 Koin/Hilt，与 IRIDIS 的做法一致；
 * 接入真实推理时只改这里的 [engineFactory]。
 */
class AppGraph(
    context: Context,
    engineFactory: ((Backend, ModelProfile) -> InferenceEngine)? = null,
) {
    val storage = AppStorage(context)
    val settings = SettingsRepository(context, storage)
    val notifier = ChatNotifier(context, settings)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pluginRuntimeRef: PluginRuntime? = null
    val models = ModelProfileStore(context)
    val conversations = ConversationStore(context)
    val plans = PlanStore(context)
    val characters = CharacterCardStore(context, storage)
    val characterModels = CharacterModelStore(context, storage)
    val petModels = CharacterModelStore(
        context = context,
        storage = storage,
        modelsDir = "pet_models",
        prefsName = "iris_pet_models",
    )
    val voiceModels = VoiceModelStore(context, storage)
    val worldBooks = WorldBookStore(context)
    val memory = MemoryStore(
        context = context,
        conversations = conversations,
        profileProvider = { models.active() },
        providerProvider = { profile -> models.providerFor(profile, ModelRole.EMBEDDING) },
    )
    val backup = BackupService(context, settings, models, characters, worldBooks, memory)
    val scenes = SceneStore(context, storage)
    val studioAvatars = StudioAvatarStore(context)
    val studioModels = StudioModelStore(context, storage)
    val skills = SkillStore(context)
    val code = CodeSettingsStore(context)
    val workspaces = WorkspaceStore(context)
    val huggingFace = HuggingFaceClient { settings.current.mirrorEnabled }
    val assetDownloads = AssetDownloadManager(context, ioScope)
    val motionAssets = MotionAssetStore(context, storage)
    val vrmaMotions = VrmaMotionStore(context, storage)
    val vrmaGeneration = VrmaGenerationEnvironment(context, models, code, vrmaMotions)
    val motionBindings = MotionBindingStore(context)
    val expressionBindings = ExpressionBindingStore(context)
    val githubAssets = GitHubAssetClient(settings)
    val downloads = ModelDownloadManager(context, ioScope, huggingFace, storage)
    val voiceDownloads = VoiceDownloadManager(context, ioScope, huggingFace, voiceModels)
    val localVoice = LocalVoiceRuntime(context, voiceModels, settings)
    val tts = TtsController(context, localVoice)
    val cloudVoice = CloudVoiceClient()
    val permissions = DevicePermissionStore(context)
    val virtualDisplay = VirtualDisplayController(context)
    val deviceActions = DeviceActionExecutor(context, permissions, settings, virtualDisplay)
    val engines = EngineManager(
        settings,
        engineFactory ?: { _, profile ->
            val provider = models.providerFor(profile, ModelRole.LANGUAGE)
            when {
                provider.kind == ProviderKind.LOCAL -> LlamaEngine(context)
                provider.kind == ProviderKind.PLUGIN -> {
                    val runtime = pluginRuntimeRef
                    if (runtime == null || provider.pluginProviderId.isBlank()) {
                        StubEngine()
                    } else {
                        PluginAiEngine(
                            runtime = runtime,
                            pluginId = provider.pluginId,
                            providerId = provider.pluginProviderId,
                            modelName = models.modelNameFor(profile, ModelRole.LANGUAGE),
                            contextLength = settings.current.contextLength,
                        )
                    }
                }
                provider.apiKey.isNotBlank() -> OpenAiCompatibleEngine(
                    provider = provider,
                    role = profile.role(ModelRole.LANGUAGE),
                    visionProvider = models.providerFor(profile, ModelRole.VISION),
                    visionRole = profile.role(ModelRole.VISION),
                )
                else -> StubEngine()
            }
        },
        modelPathProvider = { profile ->
            val provider = models.providerFor(profile, ModelRole.LANGUAGE)
            if (provider.kind == ProviderKind.LOCAL) provider.localModelPath else settings.current.modelPath
        },
    )
    val memoryAutoCapture = MemoryAutoCapture(context, memory, conversations, engines, settings) { conversation ->
        val profile = models.resolve(
            space = conversation.space,
            characterId = conversation.characterId,
            workspaceId = conversation.workspaceId,
        )
        models.providerFor(profile, ModelRole.LANGUAGE).kind == ProviderKind.CLOUD
    }
    val mcp = McpStore(context, storage)
    val toolClips = HastMotionStore.get(context) { settings.current.mirrorEnabled }
    val terminalSessions = TerminalSessions(context, code, workspaces)
    val integrationStore = IntegrationStore(context)
    val browser = BrowserController(context) { integrationStore.settings.value.browser }
    val subAgentStore = SubAgentStore(context)
    val tools = ToolRegistry()
    val subAgentManager = SubAgentManager(engines, tools, models, settings, workspaces)
    init {
    tools.apply {
        register(CurrentTimeTool())
        register(DeviceInfoTool())
        register(RandomPickTool())
        register(UseSkillTool(skills))
        register(PlanTool(plans))
        register(WorkspaceTool(context, code, workspaces))
        register(ReadFileTool(context, code, workspaces))
        register(WriteFileTool(context, code, workspaces))
        register(EditFileTool(context, code, workspaces))
        register(ListFilesTool(context, code, workspaces))
        register(SearchFilesTool(context, code, workspaces))
        register(FetchUrlTool())
        register(MemoryTool(memory) { settings.settings.value.memoryEnabled })
        register(MotionControlTool(toolClips, motionAssets, motionBindings, characterModels, vrmaMotions, settings))
        register(ExpressionControlTool(expressionBindings, characterModels, settings))
        register(ShellTool(code, workspaces, context))
        register(TerminalTool(terminalSessions))
        register(TaskTool(subAgentManager, subAgentStore))
        register(DeviceOpenAppTool(deviceActions))
        register(DeviceTapTool(deviceActions))
        register(DeviceSwipeTool(deviceActions))
        register(DeviceBackTool(deviceActions))
        register(DeviceHomeTool(deviceActions))
        register(DeviceInputTextTool(deviceActions))
        register(DeviceScreenshotTool(deviceActions))
        register(DeviceUiTool(deviceActions))
        register(DeviceTapTextTool(deviceActions))
        register(DeviceScrollTool(deviceActions))
        register(DeviceWaitTextTool(deviceActions))
        register(DeviceRecentsTool(deviceActions))
        register(DeviceNotificationsTool(deviceActions))
        register(DeviceLongPressTool(deviceActions))
        register(DeviceEnterTool(deviceActions))
        register(BrowserOpenTool(browser))
        register(BrowserReadTool(browser))
        register(BrowserSnapshotTool(browser))
        register(BrowserClickTool(browser))
        register(BrowserInputTool(browser))
        register(BrowserScrollTool(browser))
        register(BrowserWaitTool(browser))
        register(BrowserNavigateTool(browser))
        register(BrowserBackTool(browser))
        register(BrowserScreenshotTool(browser))
    }
    }
    val mcpConnections = McpConnectionManager(
        registry = tools,
        transportFactory = { server ->
            when (server.transport) {
                McpTransportKind.STREAMABLE_HTTP -> StreamableHttpTransport(server) { access, refresh ->
                    mcp.updateTokens(server.id, access, refresh)
                }
                McpTransportKind.SSE -> SseTransport(server) { access, refresh ->
                    mcp.updateTokens(server.id, access, refresh)
                }
                McpTransportKind.STDIO -> StdioTransport(context, server, code, workspaces)
            }
        },
    ) {
        settings.syncToolCatalog(tools.all().map { it.name }.toSet())
    }.also {
        tools.register(McpConfigTool(mcp, it, tools))
    }
    val mcpServer = McpServerService(mcp, tools)
    val plugins = PluginStore(context)
    init {
        tools.register(
            VrmaGenerateTool(vrmaGeneration, vrmaMotions) {
                plugins.plugins.value.any { it.id == "iris.vrma_generator" && it.enabled }
            },
        )
    }
    val pluginTerminal = PluginTerminalHost(context, code)
    val hostActions = HostActionRegistry(context, tts, memory, characterModels, settings)
    val pluginRuntime = PluginRuntime(
        context = context,
        pluginsProvider = { plugins.plugins.value },
        tools = tools,
        terminal = pluginTerminal,
        permissionOf = { name ->
            val permissions = settings.current.toolPermissions
            permissions[name]
                ?: name.removePrefix("compat:").takeIf { name.startsWith("compat:") }
                    ?.let { permissions[it] }
                ?: ToolPermission.ALLOW
        },
        hostActions = hostActions,
    )
    val pluginTools = PluginToolInstaller(
        store = plugins,
        registry = tools,
        runtime = pluginRuntime,
    ) {
        settings.syncToolCatalog(tools.all().map { it.name }.toSet())
    }.also { installer ->
        plugins.onToolsChanged = {
            installer.sync()
            pluginRuntime.onPluginsChanged(plugins.plugins.value)
        }
        installer.sync()
    }
    val pluginDev = PluginDevService(context, plugins, pluginRuntime, settings) { id ->
        pluginRuntime.close(id)
        pluginTools.sync()
    }
    init {
        tools.register(PluginDevTool(pluginDev))
        pluginRuntimeRef = pluginRuntime
        ioScope.launch {
            pluginRuntime.registrations.collect { registrations ->
                models.syncPluginProviders(
                    registrations.values.flatMap { entry ->
                        entry.aiProviders.map { providerId ->
                            PluginAiProviderRef(entry.pluginId, entry.pluginName, providerId)
                        }
                    },
                )
            }
        }
        // 工具目录在全部注册完成后统一同步一次。
        settings.syncToolCatalog(tools.all().map { it.name }.toSet())
    }
    val toolConfirmation = ToolConfirmation()
    val agent = AgentLoop(engines, tools, pluginRuntime = pluginRuntime, confirmation = toolConfirmation)
    val integrations = IntegrationManager(
        store = integrationStore,
        router = BotMessageRouter(agent, conversations, settings, skills),
    )
    val api = ApiService(engines, settings, models) {
        val current = settings.current
        val profile = models.resolve(
            space = current.space,
            characterId = current.characterId,
            workspaceId = workspaces.activeId.value,
        )
        models.modelNameFor(profile, ModelRole.LANGUAGE)
    }
}
