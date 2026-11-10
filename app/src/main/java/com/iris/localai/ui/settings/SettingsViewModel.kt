package com.iris.localai.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.agent.Tool
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ConnectionTestResult
import com.iris.localai.data.ConnectionTestState
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.ModelCapabilities
import com.iris.localai.data.ModelConnectionTester
import com.iris.localai.data.ModelProfile
import com.iris.localai.data.OperationSurfaceMode
import com.iris.localai.data.ToolPermission
import com.iris.localai.server.ApiAuth
import com.iris.localai.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    val settings = graph.settings.settings
    val engineState = graph.engines.state
    val apiState = graph.api.state
    val conversations = graph.conversations.conversations
    val tools: List<Tool> get() = graph.tools.all()
    val skills = graph.skills.skills
    val workspaces = graph.workspaces.workspaces
    val characters = graph.characters.cards
    val activeWorkspaceId = graph.workspaces.activeId
    val storage = graph.storage

    val modelProfiles = graph.models.profiles
    val activeModelId = graph.models.activeId
    val modelProviders = graph.models.providers

    val permissions = graph.permissions.state

    private val _connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _providerMessage = MutableStateFlow<String?>(null)
    val providerMessage: StateFlow<String?> = _providerMessage.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) = graph.settings.update(transform)

    fun exportSettingsMirror(): Result<String> = graph.settings.exportSettingsMirror()

    fun reloadSettingsFromMirror(): Result<String> = graph.settings.reloadSettingsFromMirror()

    fun refreshPermissions() = graph.permissions.refresh()

    fun requestShizukuPermission(): Boolean = graph.permissions.requestShizukuPermission()

    fun enableAccessibilityViaShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            graph.permissions.enableAccessibilityViaShizuku()
            graph.permissions.refresh()
        }
    }

    fun openShizuku(): Boolean = graph.permissions.openShizuku()

    fun setOperateMode(background: Boolean, shizuku: Boolean) {
        update { it.copy(operateBackgroundMode = background, operateShizukuMode = shizuku) }
        if (shizuku || background) {
            val state = graph.permissions.state.value
            when {
                !state.shizukuInstalled -> Unit
                !state.shizukuBinderConnected -> graph.permissions.openShizuku()
                !state.shizukuAuthorized -> graph.permissions.requestShizukuPermission()
                !state.accessibilityEnabled -> enableAccessibilityViaShizuku()
            }
        }
        graph.permissions.refresh()
    }

    fun setToolPermission(tool: String, permission: ToolPermission) {
        update { it.copy(toolPermissions = it.toolPermissions + (tool to permission)) }
    }

    fun setOperationSurfaceMode(mode: OperationSurfaceMode) {
        update {
            it.copy(
                operationSurfaceMode = mode,
                operateBackgroundMode = if (mode == OperationSurfaceMode.NORMAL) {
                    it.operateBackgroundMode
                } else {
                    true
                },
            )
        }
        if (mode != OperationSurfaceMode.NORMAL) {
            val state = graph.permissions.state.value
            if (state.shizukuInstalled && !state.shizukuBinderConnected) graph.permissions.openShizuku()
            if (state.shizukuBinderConnected && !state.shizukuAuthorized) graph.permissions.requestShizukuPermission()
        }
        graph.permissions.refresh()
    }

    fun createModelProfile(name: String): ModelProfile = graph.models.create(name)

    fun deleteModelProfile(id: String): Boolean = graph.models.delete(id)

    fun renameModelProfile(id: String, name: String) = graph.models.rename(id, name)

    fun selectModelProfile(id: String) = graph.models.setActive(id)

    fun updateActiveModel(transform: (ModelProfile) -> ModelProfile) =
        graph.models.updateActive(transform)

    fun setGlobalProfile(id: String, enabled: Boolean) = graph.models.setGlobalEnabled(id, enabled)

    fun setProfileTargets(id: String, workspaceIds: Set<String>, characterIds: Set<String>) =
        graph.models.setApplyTargets(id, workspaceIds, characterIds)

    fun addModelProvider(name: String, kind: com.iris.localai.data.ProviderKind, roles: Set<com.iris.localai.data.ModelRole>) =
        graph.models.addProvider(name, kind, roles).also { provider ->
            _providerMessage.value = if (provider == null) "提供商名称已存在" else "已添加 ${provider.name}"
        }

    fun updateModelProvider(provider: com.iris.localai.data.ModelProvider) {
        val ok = graph.models.updateProvider(provider)
        _providerMessage.value = if (ok) "已保存 ${provider.name}" else "提供商名称已存在"
    }

    fun deleteModelProvider(id: String) {
        _providerMessage.value = if (graph.models.deleteProvider(id)) null else "至少保留一个提供商"
    }

    fun addProviderModel(providerId: String, name: String) {
        _providerMessage.value = if (graph.models.addModel(providerId, name)) {
            null
        } else {
            "模型名称为空或已存在"
        }
    }

    fun setProviderModelEnabled(providerId: String, name: String, enabled: Boolean) =
        graph.models.setModelEnabled(providerId, name, enabled)

    fun removeProviderModel(providerId: String, name: String) =
        graph.models.removeModel(providerId, name)

    fun updateModelCapabilities(
        providerId: String,
        modelName: String,
        transform: (ModelCapabilities) -> ModelCapabilities,
    ) = graph.models.updateModelCapabilities(providerId, modelName, transform)

    fun fetchProviderModels(providerId: String) {
        viewModelScope.launch {
            val provider = graph.models.provider(providerId) ?: return@launch
            _providerMessage.value = "正在拉取模型…"
            val result = ModelConnectionTester.listModels(provider)
            _providerMessage.value = result.fold(
                onSuccess = { names ->
                    val added = graph.models.addModels(providerId, names)
                    "拉取到 ${names.size} 个模型，新增 $added 个"
                },
                onFailure = { error -> "拉取失败：${error.message ?: "未知错误"}" },
            )
        }
    }

    fun testProvider(providerId: String) {
        viewModelScope.launch {
            _providerMessage.value = "正在测试连接…"
            val provider = graph.models.provider(providerId) ?: return@launch
            _providerMessage.value = when (val result = ModelConnectionTester.test(provider)) {
                is ConnectionTestResult.Success -> "${provider.name}：${result.message}"
                is ConnectionTestResult.Failure -> "${provider.name}：${result.message}"
            }
        }
    }

    fun resetGeneration() = graph.settings.update {
        it.copy(
            temperature = 0.7f,
            topP = 0.95f,
            maxNewTokens = 512,
            topK = 40,
            minP = 0f,
            repeatPenalty = 1.1f,
            repeatLastN = 64,
            presencePenalty = 0f,
            frequencyPenalty = 0f,
            seedEnabled = false,
            seedValue = 42,
            deepThinking = true,
            thinkingBudget = 1024,
        )
    }

    fun resetContext() = graph.settings.update {
        it.copy(
            contextLength = 4096,
            contextStrategy = ContextStrategy.SLIDING_WINDOW,
            contextKeepTurns = 8,
            summaryTriggerPercent = 80,
            autoCompact = true,
        )
    }

    fun testActiveConnection() {
        viewModelScope.launch {
            _connectionTest.value = ConnectionTestState.Testing
            val provider = graph.models.providerFor(graph.models.active(), com.iris.localai.data.ModelRole.LANGUAGE)
            _connectionTest.value = when (val result = ModelConnectionTester.test(provider)) {
                is ConnectionTestResult.Success -> ConnectionTestState.Done(true, result.message)
                is ConnectionTestResult.Failure -> ConnectionTestState.Done(false, result.message)
            }
        }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        graph.skills.setEnabled(id, enabled)
    }

    fun importWorkspace(uri: Uri): Result<Workspace> = graph.workspaces.importTree(uri)

    fun createWorkspace(name: String): Workspace = graph.workspaces.createAppFolder(name)

    fun renameWorkspace(id: String, name: String) = graph.workspaces.rename(id, name)

    fun deleteWorkspace(id: String) = graph.workspaces.remove(id)

    fun setActiveWorkspace(id: String) = graph.workspaces.setActive(id)

    fun toggleApi() = graph.api.toggle()

    fun setApiEnabled(enabled: Boolean) {
        graph.settings.update { it.copy(apiEnabled = enabled) }
        if (enabled) graph.api.start() else graph.api.stop()
    }

    fun applyApiConfig(port: Int, allowLan: Boolean, key: String) {
        graph.settings.update {
            it.copy(
                apiPort = port.coerceIn(1024, 65535),
                apiAllowLan = allowLan,
                apiKey = ApiAuth.sanitize(key),
            )
        }
        if (graph.settings.current.apiEnabled) graph.api.restart()
    }

    fun reset() = graph.settings.reset()

    fun reloadEngine() {
        viewModelScope.launch {
            graph.engines.unload()
            val current = graph.settings.current
            graph.engines.ensureLoaded(
                graph.models.resolve(
                    space = current.space,
                    characterId = current.characterId,
                    workspaceId = graph.workspaces.activeId.value,
                ),
            )
        }
    }

    fun backupJson(): String {
        val settings = graph.settings.current
        return buildJsonObject {
            put("app", "IRIS LocalAI")
            put("schema", 1)
            putJsonObject("settings") {
                put("modelPath", settings.modelPath)
                put("backend", settings.backend.name)
                put("contextLength", settings.contextLength)
                put("threads", settings.threads)
                put("temperature", settings.temperature)
                put("topP", settings.topP)
                put("maxNewTokens", settings.maxNewTokens)
                put("apiPort", settings.apiPort)
                put("systemPrompt", settings.systemPrompt)
                put("space", settings.space.id)
                put("themeMode", settings.themeMode.name)
                put("userName", settings.userName)
                put("userAvatarPath", settings.userAvatarPath)
                put("userNarrative", settings.userNarrative)
                put("userVisualTags", settings.userVisualTags)
                put("modelProfileId", graph.models.active().id)
                put("modelProfileName", graph.models.active().name)
                put("permissionMode", settings.permissionMode.name)
                put("operateBackgroundMode", settings.operateBackgroundMode)
                put("operateShizukuMode", settings.operateShizukuMode)
                put("mcp", settings.enabledMcpIds.joinToString(","))
                put("characterId", settings.characterId)
                put("characterModel", settings.characterModel.name)
                put("characterLayout", settings.characterLayout.name)
                put("autoLoadLastModel", settings.autoLoadLastModel)
                put("visionQualitySide", settings.visionQualitySide)
                put("downloadSource", settings.downloadSource.name)
                put("flashAttention", settings.flashAttention)
                put("kvCacheType", settings.kvCacheType)
                put("batchSize", settings.batchSize)
                put("useMmap", settings.useMmap)
                put("warmup", settings.warmup)
                put("backendAuto", settings.backendAuto)
                put("backendFallback", settings.backendFallback)
                put("deepThinking", settings.deepThinking)
                put("thinkingBudget", settings.thinkingBudget)
                put("topK", settings.topK)
                put("minP", settings.minP)
                put("repeatPenalty", settings.repeatPenalty)
                put("repeatLastN", settings.repeatLastN)
                put("presencePenalty", settings.presencePenalty)
                put("frequencyPenalty", settings.frequencyPenalty)
                put("seedEnabled", settings.seedEnabled)
                put("seedValue", settings.seedValue)
                put("useProfileGenerationParams", settings.useProfileGenerationParams)
                put("resourceTier", settings.resourceTier)
                put("idleUnloadMinutes", settings.idleUnloadMinutes)
                put("backgroundGeneration", settings.backgroundGeneration)
                put("thermalLimitC", settings.thermalLimitC)
                put("thermalCutoffC", settings.thermalCutoffC)
                put("keepScreenOn", settings.keepScreenOn)
                put("perfOverlay", settings.perfOverlay)
                put("agentMode", settings.agentMode)
                put("agentMaxSteps", settings.agentMaxSteps)
                put("planFirst", settings.planFirst)
                put("lazyTools", settings.lazyTools)
                put("enabledToolIds", settings.enabledToolIds.joinToString(","))
                put(
                    "toolPermissions",
                    settings.toolPermissions.entries.joinToString(",") {
                        "${it.key}=${it.value.name}"
                    },
                )
                put("workspaceLocation", settings.workspaceLocation.name)
                put("customWorkspacePath", settings.customWorkspacePath)
                put("contextStrategy", settings.contextStrategy.name)
                put("contextKeepTurns", settings.contextKeepTurns)
                put("summaryTriggerPercent", settings.summaryTriggerPercent)
                put("autoCompact", settings.autoCompact)
                put("knowledgeInChat", settings.knowledgeInChat)
                put("memoryEnabled", settings.memoryEnabled)
                put("memoryInjectChat", settings.memoryInjectChat)
                put("memoryInjectAgent", settings.memoryInjectAgent)
                put("memorySuggest", settings.memorySuggest)
                put("memoryAutoDiary", settings.memoryAutoDiary)
                put("memoryAutoSave", settings.memoryAutoSave)
                put("ttsEngine", settings.ttsEngine.name)
                put("ttsLocalModelId", settings.ttsLocalModelId)
                put("ttsSpeed", settings.ttsSpeed)
                put("autoSpeak", settings.autoSpeak)
                put("sttEngine", settings.sttEngine.name)
                put("sttLocalModelId", settings.sttLocalModelId)
                put("sttLanguage", settings.sttLanguage)
                put("ttsCloudProvider", settings.ttsCloudProvider.name)
                put("ttsCloudEndpoint", settings.ttsCloudEndpoint)
                put("ttsCloudModel", settings.ttsCloudModel)
                put("ttsCloudVoice", settings.ttsCloudVoice)
                put("ttsCloudStyle", settings.ttsCloudStyle)
                put("ttsCloudVoiceSample", settings.ttsCloudVoiceSample)
                put("sttCloudProvider", settings.sttCloudProvider.name)
                put("sttCloudEndpoint", settings.sttCloudEndpoint)
                put("sttCloudModel", settings.sttCloudModel)
            }
        }.toString()
    }
}
