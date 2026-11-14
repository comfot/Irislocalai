package com.iris.localai.data

import com.iris.localai.engine.Backend

/** 应用级配置。字段与 IRIDIS 的模型/采样设置保持同名，方便后续对齐。 */
data class AppSettings(
    val modelPath: String = "",
    val backend: Backend = Backend.CPU,
    val contextLength: Int = 4096,
    val threads: Int = 4,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxNewTokens: Int = 512,
    val apiPort: Int = 8686,
    val apiEnabled: Boolean = false,
    val apiAllowLan: Boolean = false,
    val apiKey: String = "",
    val systemPrompt: String = "",
    val space: Space = Space.COMPOSITE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeStyle: ThemeStyle = ThemeStyle.STANDARD,
    val themeAccent: ThemeAccent = ThemeAccent.TEAL,
    val bubbleStyle: BubbleStyle = BubbleStyle.ROUNDED,
    val roleChatCardEnabled: Boolean = true,
    val userName: String = "你",
    val userAvatarPath: String = "",
    val userNarrative: String = "",
    val userVisualTags: String = "",
    val permissionMode: PermissionMode = PermissionMode.ASK_EVERY_TIME,
    val operateBackgroundMode: Boolean = false,
    val operateShizukuMode: Boolean = false,
    val operationSurfaceMode: OperationSurfaceMode = OperationSurfaceMode.NORMAL,
    val enabledMcpIds: Set<String> = setOf("local-files"),
    val characterId: String = "xiaoman",
    val characterModel: CharacterModel = CharacterModel.TWO_D,
    val characterLayout: CharacterLayout = CharacterLayout.GRID,

    // 桌宠穿透
    val petOverlayEnabled: Boolean = false,
    val petCuteAvatar: Boolean = true,
    val petModelId: String = "",
    val petTapAction: PetTapAction = PetTapAction.BUBBLE,
    val petLongPressAction: PetLongPressAction = PetLongPressAction.MENU,
    val petDraggable: Boolean = true,
    val petIdleAnimation: Boolean = true,
    val petScale: Float = 1f,
    val petFeedEnabled: Boolean = true,
    val petSingEnabled: Boolean = true,
    val petWakeEnabled: Boolean = true,

    // 通知
    val notifyReplies: Boolean = true,
    val notifyLockScreen: Boolean = true,
    val notifyHeadsUp: Boolean = false,
    val notifyIsland: Boolean = false,

    // 模型控制
    val l2dExpressionsEnabled: Boolean = true,
    val l2dMotionsEnabled: Boolean = true,
    val l2dIdleMotionEnabled: Boolean = true,
    val l2dTapMotionEnabled: Boolean = true,
    val l2dMouthSyncEnabled: Boolean = true,
    val videoCallStage: VideoCallStage = VideoCallStage.ROOM,
    /** AR 网格模式下人物高度倍率，原版默认 1.6。 */
    val videoCallArHeight: Float = 1.6f,
    /** AR 网格模式下人物水平/垂直偏移，按屏幕尺寸比例。 */
    val videoCallArOffsetX: Float = 0f,
    val videoCallArOffsetY: Float = 0f,
    /** AR 网格是否显示地面网格与地面环（原版 `grid` 键）。 */
    val videoCallArGrid: Boolean = true,
    /** AR 锚定时假设的相机高度（米），用于把屏幕点反投影到地面。 */
    val videoCallArCameraHeight: Float = 1.35f,
    /** AR 模型脚底微调：在按模型类型估算的脚底比例上再向上/下调整。 */
    val videoCallArFootOffset: Float = 0f,
    /** 实验性：用 OpenCV 稀疏光流估计相机在地面上的位移。 */
    val videoCallArVisualTracking: Boolean = false,
    val motionSource: MotionSource = MotionSource.NONE,
    val vrmMotionEngine: VrmMotionEngine = VrmMotionEngine.HAST_SEC,
    val motionEndpoint: String = "",
    val motionApiKey: String = "",
    val motionDurationSeconds: Int = 5,
    val motionAutoIdle: Boolean = false,
    val motionInCall: Boolean = true,
    /** 0=不修正，1=前后翻转（z 取反），2=左右翻转（x 取反），3=前后+左右。 */
    val motionAxisFix: Int = 1,
    /** 启动应用后自动启动 PRoot 内置动作服务。 */
    val motionServiceAutoStart: Boolean = true,

    // 素材库
    val assetRepo: String = "comfot/self-resources",
    val assetBranch: String = "main",
    val assetToken: String = "",
    val idleMotionAsset: String = "",
    val danceBgmEnabled: Boolean = true,
    /** MMD 渲染精度：后备缓冲按屏幕 DPR × 该倍率渲染。 */
    val mmdRenderScale: Float = 1f,

    // 模型与加载
    val autoLoadLastModel: Boolean = true,
    val visionQualitySide: Int = 1024,
    /** 官方源失败时是否回退 hf-mirror、jsDelivr 等备用源。 */
    val mirrorEnabled: Boolean = true,
    val downloadSource: DownloadSource = DownloadSource.AUTO,
    val flashAttention: Boolean = true,
    val kvCacheType: Int = 0,
    val batchSize: Int = 512,
    val useMmap: Boolean = true,
    val warmup: Boolean = true,

    // 后端
    val backendAuto: Boolean = true,
    val backendFallback: Boolean = true,

    // 生成
    val deepThinking: Boolean = true,
    val thinkingBudget: Int = 1024,
    val topK: Int = 40,
    val minP: Float = 0f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0f,
    val seedEnabled: Boolean = false,
    val seedValue: Int = 42,
    val useProfileGenerationParams: Boolean = true,

    // 性能与资源
    val resourceTier: Int = 1,
    val idleUnloadMinutes: Int = 15,
    val backgroundGeneration: Boolean = false,
    val thermalLimitC: Int = 0,
    val thermalCutoffC: Int = 0,
    val keepScreenOn: Boolean = true,
    val perfOverlay: Boolean = false,

    // 智能体
    val agentMode: Boolean = true,
    val agentMaxSteps: Int = 4,
    val planFirst: Boolean = false,
    val lazyTools: Boolean = false,
    val enabledToolIds: Set<String> = defaultEnabledTools,
    val toolPermissions: Map<String, ToolPermission> = emptyMap(),
    val workspaceLocation: WorkspaceLocation = WorkspaceLocation.APP_PRIVATE,
    val customWorkspacePath: String = "",

    // 上下文推理
    val contextStrategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW,
    val contextKeepTurns: Int = 8,
    val summaryTriggerPercent: Int = 80,
    val autoCompact: Boolean = true,
    val knowledgeInChat: Boolean = true,
    val memoryEnabled: Boolean = true,
    val memoryInjectChat: Boolean = true,
    val memoryInjectAgent: Boolean = true,
    val memorySuggest: Boolean = true,
    val memoryAutoDiary: Boolean = true,
    val memoryAutoSave: Boolean = true,

    // 语音
    val ttsEngine: VoiceEngineType = VoiceEngineType.SYSTEM,
    val ttsLocalModelId: String = "",
    val ttsSpeed: Float = 1.0f,
    val autoSpeak: Boolean = false,
    val sttEngine: VoiceEngineType = VoiceEngineType.SYSTEM,
    val sttLocalModelId: String = "",
    val sttLanguage: String = "zh-CN",
    val ttsCloudProvider: CloudVoiceProvider = CloudVoiceProvider.OPENAI,
    val ttsCloudEndpoint: String = "",
    val ttsCloudKey: String = "",
    val ttsCloudModel: String = "",
    val ttsCloudVoice: String = "",
    val ttsCloudStyle: String = "",
    val ttsCloudVoiceSample: String = "",
    val sttCloudProvider: CloudVoiceProvider = CloudVoiceProvider.OPENAI,
    val sttCloudEndpoint: String = "",
    val sttCloudKey: String = "",
    val sttCloudModel: String = "",
) {
    companion object {
        /** 默认开启的工具；Shell 仍需在「编程管理」单独开启。 */
        val defaultEnabledTools: Set<String> = setOf(
            "current_time",
            "device_info",
            "random_pick",
            "use_skill",
            "workspace",
            "read_file",
            "write_file",
            "edit_file",
            "list_files",
            "search_files",
            "fetch_url",
            "memory",
            "motion_control",
            "expression_control",
            "browser_open",
            "browser_read",
            "browser_snapshot",
            "browser_click",
            "browser_input",
            "browser_scroll",
            "browser_wait",
            "browser_navigate",
            "browser_back",
            "browser_screenshot",
        )

        /** 首次升级时补默认开启的工具目录，避免旧安装一直停留在三五个工具。 */
        val addedToolCatalog: Set<String> = setOf(
            "use_skill",
            "workspace",
            "read_file",
            "write_file",
            "edit_file",
            "list_files",
            "search_files",
            "fetch_url",
            "memory",
            "motion_control",
            "expression_control",
            "browser_open",
            "browser_read",
            "browser_snapshot",
            "browser_click",
            "browser_input",
            "browser_scroll",
            "browser_wait",
            "browser_navigate",
            "browser_back",
            "browser_screenshot",
        )
    }
}
