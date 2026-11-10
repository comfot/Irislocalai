package com.iris.localai

import android.content.Context
import com.iris.localai.agent.AgentLoop
import com.iris.localai.agent.ConversationStore
import com.iris.localai.agent.ToolRegistry
import com.iris.localai.agent.tools.DeviceBackTool
import com.iris.localai.agent.tools.DeviceHomeTool
import com.iris.localai.agent.tools.DeviceInputTextTool
import com.iris.localai.agent.tools.DeviceOpenAppTool
import com.iris.localai.agent.tools.DeviceScreenshotTool
import com.iris.localai.agent.tools.DeviceSwipeTool
import com.iris.localai.agent.tools.DeviceTapTool
import com.iris.localai.agent.tools.CurrentTimeTool
import com.iris.localai.agent.tools.DeviceInfoTool
import com.iris.localai.agent.tools.EditFileTool
import com.iris.localai.agent.tools.FetchUrlTool
import com.iris.localai.agent.tools.ListFilesTool
import com.iris.localai.agent.tools.MemoryTool
import com.iris.localai.agent.tools.McpConfigTool
import com.iris.localai.agent.tools.MotionControlTool
import com.iris.localai.agent.tools.ExpressionControlTool
import com.iris.localai.agent.tools.RandomPickTool
import com.iris.localai.agent.tools.ReadFileTool
import com.iris.localai.agent.tools.SearchFilesTool
import com.iris.localai.agent.tools.UseSkillTool
import com.iris.localai.agent.tools.WorkspaceTool
import com.iris.localai.agent.tools.WriteFileTool
import com.iris.localai.character.CharacterCardStore
import com.iris.localai.code.CodeSettingsStore
import com.iris.localai.code.ShellTool
import com.iris.localai.character.CharacterModelStore
import com.iris.localai.data.HuggingFaceClient
import com.iris.localai.data.AssetDownloadManager
import com.iris.localai.data.ModelDownloadManager
import com.iris.localai.data.ModelProfileStore
import com.iris.localai.data.ModelRole
import com.iris.localai.data.ProviderKind
import com.iris.localai.data.SettingsRepository
import com.iris.localai.engine.Backend
import com.iris.localai.engine.EngineManager
import com.iris.localai.engine.InferenceEngine
import com.iris.localai.engine.OpenAiCompatibleEngine
import com.iris.localai.engine.StubEngine
import com.iris.localai.engine.llama.LlamaEngine
import com.iris.localai.lorebook.WorldBookStore
import com.iris.localai.mcp.McpConnectionManager
import com.iris.localai.mcp.McpServerService
import com.iris.localai.mcp.McpStore
import com.iris.localai.mcp.McpTransportKind
import com.iris.localai.mcp.SseTransport
import com.iris.localai.mcp.StdioTransport
import com.iris.localai.mcp.StreamableHttpTransport
import com.iris.localai.motion.HastMotionStore
import com.iris.localai.motion.GitHubAssetClient
import com.iris.localai.motion.MotionAssetStore
import com.iris.localai.motion.VrmaMotionStore
import com.iris.localai.motion.MotionBindingStore
import com.iris.localai.motion.ExpressionBindingStore
import com.iris.localai.memory.MemoryStore
import com.iris.localai.memory.MemoryAutoCapture
import com.iris.localai.notify.ChatNotifier
import com.iris.localai.permission.DeviceActionExecutor
import com.iris.localai.permission.DevicePermissionStore
import com.iris.localai.permission.VirtualDisplayController
import com.iris.localai.plugin.PluginRuntime
import com.iris.localai.plugin.PluginStore
import com.iris.localai.plugin.PluginToolInstaller
import com.iris.localai.scene.SceneStore
import com.iris.localai.skill.SkillStore
import com.iris.localai.storage.AppStorage
import com.iris.localai.server.ApiService
import com.iris.localai.voice.TtsController
import com.iris.localai.voice.VoiceDownloadManager
import com.iris.localai.voice.VoiceModelStore
import com.iris.localai.workspace.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动装配的依赖图。项目不引入 Koin/Hilt，与 IRIDIS 的做法一致；
 * 接入真实推理时只改这里的 [engineFactory]。
 */
class AppGraph(
    context: Context,
    engineFactory: ((Backend) -> InferenceEngine)? = null,
) {
    val storage = AppStorage(context)
    val settings = SettingsRepository(context, storage)
    val notifier = ChatNotifier(context, settings)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val models = ModelProfileStore(context)
    val conversations = ConversationStore(context)
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
    val memory = MemoryStore(context, conversations) { models.active() }
    val scenes = SceneStore(context, storage)
    val skills = SkillStore(context)
    val code = CodeSettingsStore(context)
    val workspaces = WorkspaceStore(context)
    val huggingFace = HuggingFaceClient()
    val assetDownloads = AssetDownloadManager(context, ioScope)
    val motionAssets = MotionAssetStore(context, storage)
    val vrmaMotions = VrmaMotionStore(context, storage)
    val motionBindings = MotionBindingStore(context)
    val expressionBindings = ExpressionBindingStore(context)
    val githubAssets = GitHubAssetClient(settings)
    val downloads = ModelDownloadManager(context, ioScope, huggingFace, storage)
    val voiceDownloads = VoiceDownloadManager(context, ioScope, huggingFace, voiceModels)
    val tts = TtsController(context)
    val permissions = DevicePermissionStore(context)
    val virtualDisplay = VirtualDisplayController(context)
    val deviceActions = DeviceActionExecutor(context, permissions, settings, virtualDisplay)
    val engines = EngineManager(
        settings,
        engineFactory ?: { _ ->
            val profile = models.active()
            val provider = profile.provider
            when {
                provider.kind == ProviderKind.LOCAL -> LlamaEngine()
                provider.kind == ProviderKind.CLOUD && provider.apiKey.isNotBlank() -> OpenAiCompatibleEngine(
                    provider = provider,
                    role = profile.role(ModelRole.LANGUAGE),
                    visionRole = profile.role(ModelRole.VISION),
                )
                else -> StubEngine()
            }
        },
        modelPathProvider = {
            val provider = models.active().provider
            if (provider.kind == ProviderKind.LOCAL) provider.localModelPath else settings.current.modelPath
        },
    )
    val memoryAutoCapture = MemoryAutoCapture(context, memory, conversations, engines, settings)
    val mcp = McpStore(context, storage)
    val toolClips = HastMotionStore.get(context)
    val tools = ToolRegistry().apply {
        register(CurrentTimeTool())
        register(DeviceInfoTool())
        register(RandomPickTool())
        register(UseSkillTool(skills))
        register(WorkspaceTool(context, code, workspaces))
        register(ReadFileTool(context, code, workspaces))
        register(WriteFileTool(context, code, workspaces))
        register(EditFileTool(context, code, workspaces))
        register(ListFilesTool(context, code, workspaces))
        register(SearchFilesTool(context, code, workspaces))
        register(FetchUrlTool())
        register(MemoryTool(memory))
        register(MotionControlTool(toolClips, motionAssets, motionBindings, characterModels, vrmaMotions, settings))
        register(ExpressionControlTool(expressionBindings, characterModels))
        register(ShellTool(code, workspaces, context))
        register(DeviceOpenAppTool(deviceActions))
        register(DeviceTapTool(deviceActions))
        register(DeviceSwipeTool(deviceActions))
        register(DeviceBackTool(deviceActions))
        register(DeviceHomeTool(deviceActions))
        register(DeviceInputTextTool(deviceActions))
        register(DeviceScreenshotTool(deviceActions))
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
        settings.syncToolCatalog(tools.all().map { it.name }.toSet())
    }
    val mcpServer = McpServerService(mcp, tools)
    val plugins = PluginStore(context)
    val pluginRuntime = PluginRuntime(context) { plugins.plugins.value }
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
    val agent = AgentLoop(engines, tools, pluginRuntime = pluginRuntime)
    val api = ApiService(engines, settings) { models.active().name }
}
