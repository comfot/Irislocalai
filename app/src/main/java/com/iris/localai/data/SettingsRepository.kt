package com.iris.localai.data

import android.content.Context
import com.iris.localai.engine.Backend
import com.iris.localai.storage.AppStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences 持久化 + StateFlow 暴露。项目不引入 DI 框架，由 AppGraph 手动装配，
 * 与 IRIDIS 的 SettingsRepository 用法一致。
 */
class SettingsRepository(
    context: Context,
    storage: AppStorage? = null,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mirror = storage?.let { SettingsMirror(it, prefs, context.applicationContext) }

    private val _settings: MutableStateFlow<AppSettings>
    val settings: StateFlow<AppSettings>
    val current: AppSettings get() = _settings.value

    init {
        mirror?.importIfNewer()
        _settings = MutableStateFlow(read())
        settings = _settings.asStateFlow()
        persist(_settings.value)
        mirror?.export()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current)
        _settings.value = next
        persist(next)
        mirror?.export()
    }

    fun exportSettingsMirror(): Result<String> =
        mirror?.export() ?: Result.failure(IllegalStateException("设置镜像未启用"))

    fun reloadSettingsFromMirror(): Result<String> = runCatching {
        val active = mirror ?: error("设置镜像未启用")
        active.import(force = true).getOrThrow()
        _settings.value = read()
        persist(_settings.value)
        active.export().getOrThrow()
    }

    private fun persist(next: AppSettings) {
        prefs.edit()
            .putString(KEY_MODEL_PATH, next.modelPath)
            .putString(KEY_BACKEND, next.backend.name)
            .putInt(KEY_CONTEXT, next.contextLength)
            .putInt(KEY_THREADS, next.threads)
            .putFloat(KEY_TEMPERATURE, next.temperature)
            .putFloat(KEY_TOP_P, next.topP)
            .putInt(KEY_MAX_TOKENS, next.maxNewTokens)
            .putInt(KEY_API_PORT, next.apiPort)
            .putBoolean(KEY_API_ENABLED, next.apiEnabled)
            .putBoolean(KEY_API_LAN, next.apiAllowLan)
            .putString(KEY_API_KEY, next.apiKey)
            .putString(KEY_SYSTEM_PROMPT, next.systemPrompt)
            .putString(KEY_SPACE, next.space.id)
            .putString(KEY_THEME, next.themeMode.name)
            .putString(KEY_USER_NAME, next.userName)
            .putString(KEY_USER_AVATAR, next.userAvatarPath)
            .putString(KEY_USER_NARRATIVE, next.userNarrative)
            .putString(KEY_USER_VISUAL_TAGS, next.userVisualTags)
            .putString(KEY_PERMISSION, next.permissionMode.name)
            .putBoolean(KEY_OPERATE_BACKGROUND, next.operateBackgroundMode)
            .putBoolean(KEY_OPERATE_SHIZUKU, next.operateShizukuMode)
            .putString(KEY_OPERATE_SURFACE, next.operationSurfaceMode.name)
            .putString(KEY_MCP, next.enabledMcpIds.joinToString(","))
            .putString(KEY_CHARACTER, next.characterId)
            .putString(KEY_CHARACTER_MODEL, next.characterModel.name)
            .putString(KEY_CHARACTER_LAYOUT, next.characterLayout.name)
            .putBoolean(KEY_PET_OVERLAY, next.petOverlayEnabled)
            .putBoolean(KEY_PET_CUTE, next.petCuteAvatar)
            .putString(KEY_PET_MODEL, next.petModelId)
            .putString(KEY_PET_TAP, next.petTapAction.name)
            .putString(KEY_PET_LONG_PRESS, next.petLongPressAction.name)
            .putBoolean(KEY_PET_DRAGGABLE, next.petDraggable)
            .putBoolean(KEY_PET_IDLE, next.petIdleAnimation)
            .putFloat(KEY_PET_SCALE, next.petScale)
            .putBoolean(KEY_PET_FEED, next.petFeedEnabled)
            .putBoolean(KEY_PET_SING, next.petSingEnabled)
            .putBoolean(KEY_PET_WAKE, next.petWakeEnabled)
            .putBoolean(KEY_NOTIFY_REPLIES, next.notifyReplies)
            .putBoolean(KEY_NOTIFY_LOCKSCREEN, next.notifyLockScreen)
            .putBoolean(KEY_NOTIFY_HEADS_UP, next.notifyHeadsUp)
            .putBoolean(KEY_NOTIFY_ISLAND, next.notifyIsland)
            .putBoolean(KEY_L2D_EXPRESSIONS, next.l2dExpressionsEnabled)
            .putBoolean(KEY_L2D_MOTIONS, next.l2dMotionsEnabled)
            .putBoolean(KEY_L2D_IDLE, next.l2dIdleMotionEnabled)
            .putBoolean(KEY_L2D_TAP, next.l2dTapMotionEnabled)
            .putBoolean(KEY_L2D_MOUTH, next.l2dMouthSyncEnabled)
            .putString(KEY_VIDEO_CALL_STAGE, next.videoCallStage.name)
            .putFloat(KEY_VIDEO_CALL_AR_HEIGHT, next.videoCallArHeight)
            .putFloat(KEY_VIDEO_CALL_AR_OFFSET_X, next.videoCallArOffsetX)
            .putFloat(KEY_VIDEO_CALL_AR_OFFSET_Y, next.videoCallArOffsetY)
            .putBoolean(KEY_VIDEO_CALL_AR_GRID, next.videoCallArGrid)
            .putFloat(KEY_VIDEO_CALL_AR_CAMERA_HEIGHT, next.videoCallArCameraHeight)
            .putFloat(KEY_VIDEO_CALL_AR_FOOT, next.videoCallArFootOffset)
            .putBoolean(KEY_VIDEO_CALL_AR_VISUAL, next.videoCallArVisualTracking)
            .putString(KEY_MOTION_SOURCE, next.motionSource.name)
            .putString(KEY_VRM_MOTION_ENGINE, next.vrmMotionEngine.name)
            .putString(KEY_MOTION_ENDPOINT, next.motionEndpoint)
            .putString(KEY_MOTION_KEY, next.motionApiKey)
            .putInt(KEY_MOTION_DURATION, next.motionDurationSeconds)
            .putBoolean(KEY_MOTION_AUTO_IDLE, next.motionAutoIdle)
            .putBoolean(KEY_MOTION_IN_CALL, next.motionInCall)
            .putInt(KEY_MOTION_AXIS, next.motionAxisFix)
            .putBoolean(KEY_MOTION_AUTO_START, next.motionServiceAutoStart)
            .putString(KEY_ASSET_REPO, next.assetRepo)
            .putString(KEY_ASSET_BRANCH, next.assetBranch)
            .putString(KEY_ASSET_TOKEN, next.assetToken)
            .putString(KEY_IDLE_MOTION_ASSET, next.idleMotionAsset)
            .putBoolean(KEY_DANCE_BGM, next.danceBgmEnabled)
            .putFloat(KEY_MMD_RENDER_SCALE, next.mmdRenderScale)
            .putBoolean(KEY_AUTO_LOAD, next.autoLoadLastModel)
            .putInt(KEY_VISION_SIDE, next.visionQualitySide)
            .putBoolean(KEY_MIRROR_ENABLED, next.mirrorEnabled)
            .putString(KEY_DOWNLOAD_SOURCE, next.downloadSource.name)
            .putBoolean(KEY_FLASH_ATTENTION, next.flashAttention)
            .putInt(KEY_KV_TYPE, next.kvCacheType)
            .putInt(KEY_BATCH_SIZE, next.batchSize)
            .putBoolean(KEY_USE_MMAP, next.useMmap)
            .putBoolean(KEY_WARMUP, next.warmup)
            .putBoolean(KEY_BACKEND_AUTO, next.backendAuto)
            .putBoolean(KEY_BACKEND_FALLBACK, next.backendFallback)
            .putBoolean(KEY_DEEP_THINKING, next.deepThinking)
            .putInt(KEY_THINKING_BUDGET, next.thinkingBudget)
            .putInt(KEY_TOP_K, next.topK)
            .putFloat(KEY_MIN_P, next.minP)
            .putFloat(KEY_REPEAT_PENALTY, next.repeatPenalty)
            .putInt(KEY_REPEAT_LAST_N, next.repeatLastN)
            .putFloat(KEY_PRESENCE_PENALTY, next.presencePenalty)
            .putFloat(KEY_FREQUENCY_PENALTY, next.frequencyPenalty)
            .putBoolean(KEY_SEED_ENABLED, next.seedEnabled)
            .putInt(KEY_SEED_VALUE, next.seedValue)
            .putBoolean(KEY_USE_PROFILE_GENERATION, next.useProfileGenerationParams)
            .putInt(KEY_RESOURCE_TIER, next.resourceTier)
            .putInt(KEY_IDLE_UNLOAD, next.idleUnloadMinutes)
            .putBoolean(KEY_BACKGROUND_GENERATION, next.backgroundGeneration)
            .putInt(KEY_THERMAL_LIMIT, next.thermalLimitC)
            .putInt(KEY_THERMAL_CUTOFF, next.thermalCutoffC)
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .putBoolean(KEY_PERF_OVERLAY, next.perfOverlay)
            .putBoolean(KEY_AGENT_MODE, next.agentMode)
            .putInt(KEY_AGENT_MAX_STEPS, next.agentMaxSteps)
            .putBoolean(KEY_PLAN_FIRST, next.planFirst)
            .putBoolean(KEY_LAZY_TOOLS, next.lazyTools)
            .putString(KEY_ENABLED_TOOLS, next.enabledToolIds.joinToString(","))
            .putString(KEY_TOOL_PERMISSIONS, encodePermissions(next.toolPermissions))
            .putString(KEY_WORKSPACE_LOCATION, next.workspaceLocation.name)
            .putString(KEY_CUSTOM_WORKSPACE, next.customWorkspacePath)
            .putString(KEY_CONTEXT_STRATEGY, next.contextStrategy.name)
            .putInt(KEY_CONTEXT_KEEP_TURNS, next.contextKeepTurns)
            .putInt(KEY_SUMMARY_TRIGGER, next.summaryTriggerPercent)
            .putBoolean(KEY_AUTO_COMPACT, next.autoCompact)
            .putBoolean(KEY_KNOWLEDGE_IN_CHAT, next.knowledgeInChat)
            .putBoolean(KEY_MEMORY_ENABLED, next.memoryEnabled)
            .putBoolean(KEY_MEMORY_INJECT_CHAT, next.memoryInjectChat)
            .putBoolean(KEY_MEMORY_INJECT_AGENT, next.memoryInjectAgent)
            .putBoolean(KEY_MEMORY_SUGGEST, next.memorySuggest)
            .putBoolean(KEY_MEMORY_AUTO_DIARY, next.memoryAutoDiary)
            .putBoolean(KEY_MEMORY_AUTO_SAVE, next.memoryAutoSave)
            .putString(KEY_TTS_ENGINE, next.ttsEngine.name)
            .putString(KEY_TTS_LOCAL_MODEL, next.ttsLocalModelId)
            .putFloat(KEY_TTS_SPEED, next.ttsSpeed)
            .putBoolean(KEY_AUTO_SPEAK, next.autoSpeak)
            .putString(KEY_STT_ENGINE, next.sttEngine.name)
            .putString(KEY_STT_LOCAL_MODEL, next.sttLocalModelId)
            .putString(KEY_STT_LANGUAGE, next.sttLanguage)
            .putString(KEY_TTS_CLOUD_PROVIDER, next.ttsCloudProvider.name)
            .putString(KEY_TTS_CLOUD_ENDPOINT, next.ttsCloudEndpoint)
            .putString(KEY_TTS_CLOUD_KEY, next.ttsCloudKey)
            .putString(KEY_TTS_CLOUD_MODEL, next.ttsCloudModel)
            .putString(KEY_TTS_CLOUD_VOICE, next.ttsCloudVoice)
            .putString(KEY_TTS_CLOUD_STYLE, next.ttsCloudStyle)
            .putString(KEY_TTS_CLOUD_VOICE_SAMPLE, next.ttsCloudVoiceSample)
            .putString(KEY_STT_CLOUD_PROVIDER, next.sttCloudProvider.name)
            .putString(KEY_STT_CLOUD_ENDPOINT, next.sttCloudEndpoint)
            .putString(KEY_STT_CLOUD_KEY, next.sttCloudKey)
            .putString(KEY_STT_CLOUD_MODEL, next.sttCloudModel)
            .apply()
    }

    /** 数据管理 › 恢复：清空偏好并回到默认值（会话为内存数据，不在备份范围内）。 */
    fun reset() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
        persist(_settings.value)
        mirror?.export()
    }

    private fun read(): AppSettings = AppSettings(
        modelPath = prefs.getString(KEY_MODEL_PATH, "") ?: "",
        backend = enumOf(prefs.getString(KEY_BACKEND, null), Backend.CPU),
        contextLength = prefs.getInt(KEY_CONTEXT, 4096),
        threads = prefs.getInt(KEY_THREADS, 4),
        temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f),
        topP = prefs.getFloat(KEY_TOP_P, 0.95f),
        maxNewTokens = prefs.getInt(KEY_MAX_TOKENS, 512),
        apiPort = prefs.getInt(KEY_API_PORT, 8686),
        apiEnabled = prefs.getBoolean(KEY_API_ENABLED, false),
        apiAllowLan = prefs.getBoolean(KEY_API_LAN, false),
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: "",
        space = Space.of(prefs.getString(KEY_SPACE, Space.COMPOSITE.id)),
        themeMode = enumOf(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
        userName = prefs.getString(KEY_USER_NAME, "你") ?: "你",
        userAvatarPath = prefs.getString(KEY_USER_AVATAR, "") ?: "",
        userNarrative = prefs.getString(KEY_USER_NARRATIVE, "") ?: "",
        userVisualTags = prefs.getString(KEY_USER_VISUAL_TAGS, "") ?: "",
        permissionMode = enumOf(prefs.getString(KEY_PERMISSION, null), PermissionMode.ASK_EVERY_TIME),
        operateBackgroundMode = prefs.getBoolean(KEY_OPERATE_BACKGROUND, false),
        operateShizukuMode = prefs.getBoolean(KEY_OPERATE_SHIZUKU, false),
        operationSurfaceMode = enumOf(
            prefs.getString(KEY_OPERATE_SURFACE, null),
            OperationSurfaceMode.NORMAL,
        ),
        enabledMcpIds = decodeSet(prefs.getString(KEY_MCP, "local-files") ?: "local-files"),
        characterId = prefs.getString(KEY_CHARACTER, "xiaoman") ?: "xiaoman",
        characterModel = enumOf(prefs.getString(KEY_CHARACTER_MODEL, null), CharacterModel.TWO_D),
        characterLayout = enumOf(prefs.getString(KEY_CHARACTER_LAYOUT, null), CharacterLayout.GRID),
        petOverlayEnabled = prefs.getBoolean(KEY_PET_OVERLAY, false),
        petCuteAvatar = prefs.getBoolean(KEY_PET_CUTE, true),
        petModelId = prefs.getString(KEY_PET_MODEL, "") ?: "",
        petTapAction = enumOf(prefs.getString(KEY_PET_TAP, null), PetTapAction.BUBBLE),
        petLongPressAction = enumOf(prefs.getString(KEY_PET_LONG_PRESS, null), PetLongPressAction.MENU),
        petDraggable = prefs.getBoolean(KEY_PET_DRAGGABLE, true),
        petIdleAnimation = prefs.getBoolean(KEY_PET_IDLE, true),
        petScale = prefs.getFloat(KEY_PET_SCALE, 1f),
        petFeedEnabled = prefs.getBoolean(KEY_PET_FEED, true),
        petSingEnabled = prefs.getBoolean(KEY_PET_SING, true),
        petWakeEnabled = prefs.getBoolean(KEY_PET_WAKE, true),
        notifyReplies = prefs.getBoolean(KEY_NOTIFY_REPLIES, true),
        notifyLockScreen = prefs.getBoolean(KEY_NOTIFY_LOCKSCREEN, true),
        notifyHeadsUp = prefs.getBoolean(KEY_NOTIFY_HEADS_UP, false),
        notifyIsland = prefs.getBoolean(KEY_NOTIFY_ISLAND, false),
        l2dExpressionsEnabled = prefs.getBoolean(KEY_L2D_EXPRESSIONS, true),
        l2dMotionsEnabled = prefs.getBoolean(KEY_L2D_MOTIONS, true),
        l2dIdleMotionEnabled = prefs.getBoolean(KEY_L2D_IDLE, true),
        l2dTapMotionEnabled = prefs.getBoolean(KEY_L2D_TAP, true),
        l2dMouthSyncEnabled = prefs.getBoolean(KEY_L2D_MOUTH, true),
        videoCallStage = enumOf(
            prefs.getString(KEY_VIDEO_CALL_STAGE, null),
            VideoCallStage.ROOM,
        ),
        videoCallArHeight = prefs.getFloat(KEY_VIDEO_CALL_AR_HEIGHT, 1.6f),
        videoCallArOffsetX = prefs.getFloat(KEY_VIDEO_CALL_AR_OFFSET_X, 0f),
        videoCallArOffsetY = prefs.getFloat(KEY_VIDEO_CALL_AR_OFFSET_Y, 0f),
        videoCallArGrid = prefs.getBoolean(KEY_VIDEO_CALL_AR_GRID, true),
        videoCallArCameraHeight = prefs.getFloat(KEY_VIDEO_CALL_AR_CAMERA_HEIGHT, 1.35f),
        videoCallArFootOffset = prefs.getFloat(KEY_VIDEO_CALL_AR_FOOT, 0f),
        videoCallArVisualTracking = prefs.getBoolean(KEY_VIDEO_CALL_AR_VISUAL, false),
        motionSource = enumOf(prefs.getString(KEY_MOTION_SOURCE, null), MotionSource.NONE),
        vrmMotionEngine = enumOf(
            prefs.getString(KEY_VRM_MOTION_ENGINE, null),
            VrmMotionEngine.HAST_SEC,
        ),
        motionEndpoint = prefs.getString(KEY_MOTION_ENDPOINT, "") ?: "",
        motionApiKey = prefs.getString(KEY_MOTION_KEY, "") ?: "",
        motionDurationSeconds = prefs.getInt(KEY_MOTION_DURATION, 5),
        motionAutoIdle = prefs.getBoolean(KEY_MOTION_AUTO_IDLE, false),
        motionInCall = prefs.getBoolean(KEY_MOTION_IN_CALL, true),
        motionAxisFix = prefs.getInt(KEY_MOTION_AXIS, 1),
        motionServiceAutoStart = prefs.getBoolean(KEY_MOTION_AUTO_START, true),
        assetRepo = prefs.getString(KEY_ASSET_REPO, "comfot/self-resources") ?: "comfot/self-resources",
        assetBranch = prefs.getString(KEY_ASSET_BRANCH, "main") ?: "main",
        assetToken = prefs.getString(KEY_ASSET_TOKEN, "") ?: "",
        idleMotionAsset = prefs.getString(KEY_IDLE_MOTION_ASSET, "") ?: "",
        danceBgmEnabled = prefs.getBoolean(KEY_DANCE_BGM, true),
        mmdRenderScale = prefs.getFloat(KEY_MMD_RENDER_SCALE, 1f),
        autoLoadLastModel = prefs.getBoolean(KEY_AUTO_LOAD, true),
        visionQualitySide = prefs.getInt(KEY_VISION_SIDE, 1024),
        mirrorEnabled = prefs.getBoolean(KEY_MIRROR_ENABLED, true),
        downloadSource = enumOf(prefs.getString(KEY_DOWNLOAD_SOURCE, null), DownloadSource.AUTO),
        flashAttention = prefs.getBoolean(KEY_FLASH_ATTENTION, true),
        kvCacheType = prefs.getInt(KEY_KV_TYPE, 0),
        batchSize = prefs.getInt(KEY_BATCH_SIZE, 512),
        useMmap = prefs.getBoolean(KEY_USE_MMAP, true),
        warmup = prefs.getBoolean(KEY_WARMUP, true),
        backendAuto = prefs.getBoolean(KEY_BACKEND_AUTO, true),
        backendFallback = prefs.getBoolean(KEY_BACKEND_FALLBACK, true),
        deepThinking = prefs.getBoolean(KEY_DEEP_THINKING, true),
        thinkingBudget = prefs.getInt(KEY_THINKING_BUDGET, 1024),
        topK = prefs.getInt(KEY_TOP_K, 40),
        minP = prefs.getFloat(KEY_MIN_P, 0f),
        repeatPenalty = prefs.getFloat(KEY_REPEAT_PENALTY, 1.1f),
        repeatLastN = prefs.getInt(KEY_REPEAT_LAST_N, 64),
        presencePenalty = prefs.getFloat(KEY_PRESENCE_PENALTY, 0f),
        frequencyPenalty = prefs.getFloat(KEY_FREQUENCY_PENALTY, 0f),
        seedEnabled = prefs.getBoolean(KEY_SEED_ENABLED, false),
        seedValue = prefs.getInt(KEY_SEED_VALUE, 42),
        useProfileGenerationParams = prefs.getBoolean(KEY_USE_PROFILE_GENERATION, true),
        resourceTier = prefs.getInt(KEY_RESOURCE_TIER, 1),
        idleUnloadMinutes = prefs.getInt(KEY_IDLE_UNLOAD, 15),
        backgroundGeneration = prefs.getBoolean(KEY_BACKGROUND_GENERATION, false),
        thermalLimitC = prefs.getInt(KEY_THERMAL_LIMIT, 0),
        thermalCutoffC = prefs.getInt(KEY_THERMAL_CUTOFF, 0),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
        perfOverlay = prefs.getBoolean(KEY_PERF_OVERLAY, false),
        agentMode = prefs.getBoolean(KEY_AGENT_MODE, true),
        agentMaxSteps = prefs.getInt(KEY_AGENT_MAX_STEPS, 4),
        planFirst = prefs.getBoolean(KEY_PLAN_FIRST, false),
        lazyTools = prefs.getBoolean(KEY_LAZY_TOOLS, false),
        enabledToolIds = readEnabledTools(),
        toolPermissions = decodePermissions(prefs.getString(KEY_TOOL_PERMISSIONS, null)),
        workspaceLocation = enumOf(prefs.getString(KEY_WORKSPACE_LOCATION, null), WorkspaceLocation.APP_PRIVATE),
        customWorkspacePath = prefs.getString(KEY_CUSTOM_WORKSPACE, "") ?: "",
        contextStrategy = enumOf(prefs.getString(KEY_CONTEXT_STRATEGY, null), ContextStrategy.SLIDING_WINDOW),
        contextKeepTurns = prefs.getInt(KEY_CONTEXT_KEEP_TURNS, 8),
        summaryTriggerPercent = prefs.getInt(KEY_SUMMARY_TRIGGER, 80),
        autoCompact = prefs.getBoolean(KEY_AUTO_COMPACT, true),
        knowledgeInChat = prefs.getBoolean(KEY_KNOWLEDGE_IN_CHAT, true),
        memoryEnabled = prefs.getBoolean(KEY_MEMORY_ENABLED, true),
        memoryInjectChat = prefs.getBoolean(KEY_MEMORY_INJECT_CHAT, true),
        memoryInjectAgent = prefs.getBoolean(KEY_MEMORY_INJECT_AGENT, true),
        memorySuggest = prefs.getBoolean(KEY_MEMORY_SUGGEST, true),
        memoryAutoDiary = prefs.getBoolean(KEY_MEMORY_AUTO_DIARY, true),
        memoryAutoSave = prefs.getBoolean(KEY_MEMORY_AUTO_SAVE, true),
        ttsEngine = enumOf(prefs.getString(KEY_TTS_ENGINE, null), VoiceEngineType.SYSTEM),
        ttsLocalModelId = prefs.getString(KEY_TTS_LOCAL_MODEL, "") ?: "",
        ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.0f),
        autoSpeak = prefs.getBoolean(KEY_AUTO_SPEAK, false),
        sttEngine = enumOf(prefs.getString(KEY_STT_ENGINE, null), VoiceEngineType.SYSTEM),
        sttLocalModelId = prefs.getString(KEY_STT_LOCAL_MODEL, "") ?: "",
        sttLanguage = prefs.getString(KEY_STT_LANGUAGE, "zh-CN") ?: "zh-CN",
        ttsCloudProvider = enumOf(
            prefs.getString(KEY_TTS_CLOUD_PROVIDER, null),
            CloudVoiceProvider.OPENAI,
        ),
        ttsCloudEndpoint = prefs.getString(KEY_TTS_CLOUD_ENDPOINT, "") ?: "",
        ttsCloudKey = prefs.getString(KEY_TTS_CLOUD_KEY, "") ?: "",
        ttsCloudModel = prefs.getString(KEY_TTS_CLOUD_MODEL, "") ?: "",
        ttsCloudVoice = prefs.getString(KEY_TTS_CLOUD_VOICE, "") ?: "",
        ttsCloudStyle = prefs.getString(KEY_TTS_CLOUD_STYLE, "") ?: "",
        ttsCloudVoiceSample = prefs.getString(KEY_TTS_CLOUD_VOICE_SAMPLE, "") ?: "",
        sttCloudProvider = enumOf(
            prefs.getString(KEY_STT_CLOUD_PROVIDER, null),
            CloudVoiceProvider.OPENAI,
        ),
        sttCloudEndpoint = prefs.getString(KEY_STT_CLOUD_ENDPOINT, "") ?: "",
        sttCloudKey = prefs.getString(KEY_STT_CLOUD_KEY, "") ?: "",
        sttCloudModel = prefs.getString(KEY_STT_CLOUD_MODEL, "") ?: "",
    )

    private fun readEnabledTools(): Set<String> {
        val stored = prefs.getString(KEY_ENABLED_TOOLS, null)
            ?.let { decodeSet(it) }
            ?: AppSettings.defaultEnabledTools
        val version = prefs.getInt(KEY_TOOL_CATALOG, 0)
        if (version >= TOOL_CATALOG_VERSION) return stored
        val merged = stored + AppSettings.addedToolCatalog
        // 迁移后立即写回，否则下次启动仍读到旧的工具集合。
        prefs.edit()
            .putString(KEY_ENABLED_TOOLS, merged.joinToString(","))
            .putInt(KEY_TOOL_CATALOG, TOOL_CATALOG_VERSION)
            .apply()
        return merged
    }

    /**
     * 以实际注册的工具名为准补开新工具：旧版本只存了少数几个 id，
     * 单靠设置默认值不会生效。记录上次同步的清单，避免用户手动关闭的工具被重新打开。
     */
    fun syncToolCatalog(available: Set<String>) {
        if (available.isEmpty()) return
        val current = _settings.value
        val recorded = decodeSet(prefs.getString(KEY_TOOL_CATALOG_NAMES, "").orEmpty())
        val added = available - recorded
        if (added.isEmpty() && available == recorded) return
        val enabled = current.enabledToolIds + added
        _settings.value = current.copy(enabledToolIds = enabled)
        prefs.edit()
            .putString(KEY_ENABLED_TOOLS, enabled.joinToString(","))
            .putString(KEY_TOOL_CATALOG_NAMES, available.joinToString(","))
            .apply()
        mirror?.export()
    }

    private fun decodeSet(value: String): Set<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun decodePermissions(raw: String?): Map<String, ToolPermission> =
        raw.orEmpty().split(',').mapNotNull { item ->
            val parts = item.split('=')
            if (parts.size != 2) return@mapNotNull null
            val permission = runCatching { ToolPermission.valueOf(parts[1]) }.getOrNull()
            if (permission == null) null else parts[0] to permission
        }.toMap()

    private fun encodePermissions(map: Map<String, ToolPermission>): String =
        map.entries.joinToString(",") { "${it.key}=${it.value.name}" }

    private inline fun <reified T : Enum<T>> enumOf(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private companion object {
        const val FILE = "iris_settings"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_BACKEND = "backend"
        const val KEY_CONTEXT = "context_length"
        const val KEY_THREADS = "threads"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_MAX_TOKENS = "max_new_tokens"
        const val KEY_API_PORT = "api_port"
        const val KEY_API_ENABLED = "api_enabled"
        const val KEY_API_LAN = "api_allow_lan"
        const val KEY_API_KEY = "api_key"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_SPACE = "space"
        const val KEY_THEME = "theme"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_AVATAR = "user_avatar"
        const val KEY_USER_NARRATIVE = "user_narrative"
        const val KEY_USER_VISUAL_TAGS = "user_visual_tags"
        const val KEY_PERMISSION = "permission"
        const val KEY_OPERATE_BACKGROUND = "operate_background"
        const val KEY_OPERATE_SHIZUKU = "operate_shizuku"
        const val KEY_OPERATE_SURFACE = "operate_surface_mode"
        const val KEY_MCP = "mcp"
        const val KEY_CHARACTER = "character"
        const val KEY_CHARACTER_MODEL = "character_model"
        const val KEY_CHARACTER_LAYOUT = "character_layout"
        const val KEY_PET_OVERLAY = "pet_overlay_enabled"
        const val KEY_PET_CUTE = "pet_cute_avatar"
        const val KEY_PET_MODEL = "pet_model_id"
        const val KEY_PET_TAP = "pet_tap_action"
        const val KEY_PET_LONG_PRESS = "pet_long_press_action"
        const val KEY_PET_DRAGGABLE = "pet_draggable"
        const val KEY_PET_IDLE = "pet_idle_animation"
        const val KEY_PET_SCALE = "pet_scale"
        const val KEY_PET_FEED = "pet_feed_enabled"
        const val KEY_PET_SING = "pet_sing_enabled"
        const val KEY_PET_WAKE = "pet_wake_enabled"
        const val KEY_NOTIFY_REPLIES = "notify_replies"
        const val KEY_NOTIFY_LOCKSCREEN = "notify_lockscreen"
        const val KEY_NOTIFY_HEADS_UP = "notify_heads_up"
        const val KEY_NOTIFY_ISLAND = "notify_island"
        const val KEY_L2D_EXPRESSIONS = "l2d_expressions"
        const val KEY_L2D_MOTIONS = "l2d_motions"
        const val KEY_L2D_IDLE = "l2d_idle_motion"
        const val KEY_L2D_TAP = "l2d_tap_motion"
        const val KEY_L2D_MOUTH = "l2d_mouth_sync"
        const val KEY_VIDEO_CALL_STAGE = "video_call_stage"
        const val KEY_VIDEO_CALL_AR_HEIGHT = "video_call_ar_height"
        const val KEY_VIDEO_CALL_AR_OFFSET_X = "video_call_ar_offset_x"
        const val KEY_VIDEO_CALL_AR_OFFSET_Y = "video_call_ar_offset_y"
        const val KEY_VIDEO_CALL_AR_GRID = "video_call_ar_grid"
        const val KEY_VIDEO_CALL_AR_CAMERA_HEIGHT = "video_call_ar_camera_height"
        const val KEY_VIDEO_CALL_AR_FOOT = "video_call_ar_foot_offset"
        const val KEY_VIDEO_CALL_AR_VISUAL = "video_call_ar_visual_tracking"
        const val KEY_MOTION_SOURCE = "motion_source"
        const val KEY_VRM_MOTION_ENGINE = "vrm_motion_engine"
        const val KEY_MOTION_ENDPOINT = "motion_endpoint"
        const val KEY_MOTION_KEY = "motion_api_key"
        const val KEY_MOTION_DURATION = "motion_duration"
        const val KEY_MOTION_AUTO_IDLE = "motion_auto_idle"
        const val KEY_MOTION_IN_CALL = "motion_in_call"
        const val KEY_MOTION_AXIS = "motion_axis_fix"
        const val KEY_MOTION_AUTO_START = "motion_auto_start"
        const val KEY_ASSET_REPO = "asset_repo"
        const val KEY_ASSET_BRANCH = "asset_branch"
        const val KEY_ASSET_TOKEN = "asset_token"
        const val KEY_IDLE_MOTION_ASSET = "idle_motion_asset"
        const val KEY_DANCE_BGM = "dance_bgm_enabled"
        const val KEY_MMD_RENDER_SCALE = "mmd_render_scale"
        const val KEY_AUTO_LOAD = "auto_load_last_model"
        const val KEY_VISION_SIDE = "vision_quality_side"
        const val KEY_DOWNLOAD_SOURCE = "download_source"
        const val KEY_MIRROR_ENABLED = "mirror_enabled"
        const val KEY_FLASH_ATTENTION = "flash_attention"
        const val KEY_KV_TYPE = "kv_cache_type"
        const val KEY_BATCH_SIZE = "batch_size"
        const val KEY_USE_MMAP = "use_mmap"
        const val KEY_WARMUP = "warmup"
        const val KEY_BACKEND_AUTO = "backend_auto"
        const val KEY_BACKEND_FALLBACK = "backend_fallback"
        const val KEY_DEEP_THINKING = "deep_thinking"
        const val KEY_THINKING_BUDGET = "thinking_budget"
        const val KEY_TOP_K = "top_k"
        const val KEY_MIN_P = "min_p"
        const val KEY_REPEAT_PENALTY = "repeat_penalty"
        const val KEY_REPEAT_LAST_N = "repeat_last_n"
        const val KEY_PRESENCE_PENALTY = "presence_penalty"
        const val KEY_FREQUENCY_PENALTY = "frequency_penalty"
        const val KEY_SEED_ENABLED = "seed_enabled"
        const val KEY_SEED_VALUE = "seed_value"
        const val KEY_USE_PROFILE_GENERATION = "use_profile_generation"
        const val KEY_RESOURCE_TIER = "resource_tier"
        const val KEY_IDLE_UNLOAD = "idle_unload_minutes"
        const val KEY_BACKGROUND_GENERATION = "background_generation"
        const val KEY_THERMAL_LIMIT = "thermal_limit_c"
        const val KEY_THERMAL_CUTOFF = "thermal_cutoff_c"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_PERF_OVERLAY = "perf_overlay"
        const val KEY_AGENT_MODE = "agent_mode"
        const val KEY_AGENT_MAX_STEPS = "agent_max_steps"
        const val KEY_PLAN_FIRST = "plan_first"
        const val KEY_LAZY_TOOLS = "lazy_tools"
        const val KEY_ENABLED_TOOLS = "enabled_tools"
        const val KEY_TOOL_CATALOG = "tool_catalog_version"
        const val KEY_TOOL_CATALOG_NAMES = "tool_catalog_names"
        const val TOOL_CATALOG_VERSION = 1
        const val KEY_TOOL_PERMISSIONS = "tool_permissions"
        const val KEY_WORKSPACE_LOCATION = "workspace_location"
        const val KEY_CUSTOM_WORKSPACE = "custom_workspace"
        const val KEY_CONTEXT_STRATEGY = "context_strategy"
        const val KEY_CONTEXT_KEEP_TURNS = "context_keep_turns"
        const val KEY_SUMMARY_TRIGGER = "summary_trigger_percent"
        const val KEY_AUTO_COMPACT = "auto_compact"
        const val KEY_KNOWLEDGE_IN_CHAT = "knowledge_in_chat"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_MEMORY_INJECT_CHAT = "memory_inject_chat"
        const val KEY_MEMORY_INJECT_AGENT = "memory_inject_agent"
        const val KEY_MEMORY_SUGGEST = "memory_suggest"
        const val KEY_MEMORY_AUTO_DIARY = "memory_auto_diary"
        const val KEY_MEMORY_AUTO_SAVE = "memory_auto_save"
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_TTS_LOCAL_MODEL = "tts_local_model"
        const val KEY_TTS_SPEED = "tts_speed"
        const val KEY_AUTO_SPEAK = "auto_speak"
        const val KEY_STT_ENGINE = "stt_engine"
        const val KEY_STT_LOCAL_MODEL = "stt_local_model"
        const val KEY_STT_LANGUAGE = "stt_language"
        const val KEY_TTS_CLOUD_PROVIDER = "tts_cloud_provider"
        const val KEY_TTS_CLOUD_ENDPOINT = "tts_cloud_endpoint"
        const val KEY_TTS_CLOUD_KEY = "tts_cloud_key"
        const val KEY_TTS_CLOUD_MODEL = "tts_cloud_model"
        const val KEY_TTS_CLOUD_VOICE = "tts_cloud_voice"
        const val KEY_TTS_CLOUD_STYLE = "tts_cloud_style"
        const val KEY_TTS_CLOUD_VOICE_SAMPLE = "tts_cloud_voice_sample"
        const val KEY_STT_CLOUD_PROVIDER = "stt_cloud_provider"
        const val KEY_STT_CLOUD_ENDPOINT = "stt_cloud_endpoint"
        const val KEY_STT_CLOUD_KEY = "stt_cloud_key"
        const val KEY_STT_CLOUD_MODEL = "stt_cloud_model"
    }
}
