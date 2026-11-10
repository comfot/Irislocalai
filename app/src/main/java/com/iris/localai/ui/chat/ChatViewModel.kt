package com.iris.localai.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.agent.AgentEvent
import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.AssistantMessageParser
import com.iris.localai.agent.AttachmentKind
import com.iris.localai.agent.ChatAttachment
import com.iris.localai.agent.ChatMessage
import com.iris.localai.agent.ChatRole
import com.iris.localai.agent.Conversation
import com.iris.localai.agent.PerformanceTag
import com.iris.localai.agent.RoleplayOverlay
import com.iris.localai.agent.StepStatus
import com.iris.localai.character.CharacterEntry
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ContextStrategy
import com.iris.localai.data.ModelProfile
import com.iris.localai.data.ModelRole
import com.iris.localai.data.ProviderKind
import com.iris.localai.data.Space
import com.iris.localai.data.VoiceEngineType
import com.iris.localai.engine.EngineState
import com.iris.localai.engine.PromptMessage
import com.iris.localai.engine.SamplingConfig
import com.iris.localai.lorebook.LorebookResolver
import com.iris.localai.motion.MotionBridge
import com.iris.localai.motion.MotionCategory
import com.iris.localai.pet.PetChatBus
import com.iris.localai.pet.PetDestination
import com.iris.localai.pet.PetNavigator
import com.iris.localai.plugin.PluginHookDescriptor
import com.iris.localai.scene.SceneConfig
import com.iris.localai.workspace.Workspace
import com.iris.localai.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph
    private val store = graph.conversations
    private val attachmentsDir = File(application.filesDir, "attachments").apply { mkdirs() }

    val conversations: StateFlow<List<Conversation>> = store.conversations

    private val _currentId = MutableStateFlow<Long?>(null)
    val currentId: StateFlow<Long?> = _currentId.asStateFlow()

    val currentConversation: StateFlow<Conversation?> =
        combine(store.conversations, _currentId) { list, id ->
            list.firstOrNull { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<ChatMessage>> =
        combine(store.conversations, _currentId) { list, id ->
            list.firstOrNull { it.id == id }?.messages.orEmpty()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<ChatMessage>())

    val space: StateFlow<Space> = graph.settings.settings
        .map { it.space }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.settings.current.space)

    val settings: StateFlow<AppSettings> = graph.settings.settings

    val modelProfiles = graph.models.profiles
    val activeModelProfileId = graph.models.activeId

    val characters = graph.characters.cards
    val characterModels = graph.characterModels.models
    val characterModelBindings = graph.characterModels.bindings
    val activeCharacterModelId = graph.characterModels.activeId
    val scenes = graph.scenes.scenes
    val activeSceneId = graph.scenes.activeId

    private val _callReply = MutableStateFlow("")
    val callReply: StateFlow<String> = _callReply.asStateFlow()
    private var callMode = false
    val skills = graph.skills.skills
    val tools get() = graph.tools.all()
    val diary = graph.memory.diary
    val memories = graph.memory.memories
    val knowledge = graph.memory.knowledge
    val mcpServers = graph.mcp.servers
    val mcpSettings = graph.mcp.settings
    val workspaces = graph.workspaces.workspaces
    val activeWorkspaceId = graph.workspaces.activeId
    val activeWorkspace: StateFlow<Workspace?> =
        combine(graph.workspaces.workspaces, graph.workspaces.activeId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            graph.workspaces.active(),
        )
    val activeScene: StateFlow<SceneConfig?> =
        combine(graph.scenes.scenes, graph.scenes.activeId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            graph.scenes.active(),
        )
    val activeCharacter: StateFlow<CharacterEntry?> =
        combine(graph.characters.cards, graph.settings.settings) { cards, settings ->
            cards.firstOrNull { it.id == settings.characterId } ?: cards.firstOrNull()
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            graph.characters.get(graph.settings.current.characterId),
        )

    fun setActiveModelProfile(id: String) {
        graph.models.setActive(id)
    }

    fun modelProfileSummary(id: String): String {
        val profile = graph.models.profiles.value.firstOrNull { it.id == id } ?: return "默认配置"
        val provider = graph.models.providerFor(profile, ModelRole.LANGUAGE)
        return "${provider.name} · ${graph.models.modelNameFor(profile, ModelRole.LANGUAGE)}"
    }

    fun activeLanguageProviderKind(): ProviderKind =
        graph.models.providerFor(graph.models.active(), ModelRole.LANGUAGE).kind

    fun enableVisionForActiveProfile() {
        val profile = graph.models.active()
        val role = if (profile.role(ModelRole.VISION).enabled) ModelRole.VISION else ModelRole.LANGUAGE
        val provider = graph.models.providerFor(profile, role)
        val modelName = graph.models.modelNameFor(profile, role)
        graph.models.updateModelCapabilities(provider.id, modelName) {
            it.copy(vision = true)
        }
        _attachmentError.value = null
    }

    fun setActiveCharacter(id: String) {
        graph.settings.update { it.copy(characterId = id) }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        graph.skills.setEnabled(id, enabled)
    }

    fun setActiveWorkspace(id: String) {
        graph.workspaces.setActive(id)
        _currentId.value?.let { conversationId ->
            if (store.find(conversationId)?.workspaceId != id) {
                store.setWorkspace(conversationId, id)
            }
        }
    }

    private val _studioPlace = MutableStateFlow("studio")
    val studioPlace: StateFlow<String> = _studioPlace.asStateFlow()

    fun selectStudioPlace(id: String) {
        _studioPlace.value = id
    }

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _attachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val attachments: StateFlow<List<ChatAttachment>> = _attachments.asStateFlow()

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _performanceMood = MutableStateFlow("neutral")
    val performanceMood: StateFlow<String> = _performanceMood.asStateFlow()
    private val _performanceIntensity = MutableStateFlow(0.85f)
    val performanceIntensity: StateFlow<Float> = _performanceIntensity.asStateFlow()

    private val _submitting = MutableStateFlow(false)
    private val _pluginMenuItems = MutableStateFlow<List<PluginHookDescriptor>>(emptyList())
    val pluginMenuItems: StateFlow<List<PluginHookDescriptor>> = _pluginMenuItems.asStateFlow()
    private val _pluginSidebarEntries = MutableStateFlow<List<PluginHookDescriptor>>(emptyList())
    val pluginSidebarEntries: StateFlow<List<PluginHookDescriptor>> = _pluginSidebarEntries.asStateFlow()
    private var pluginMenuLoading = false
    private var pluginSidebarLoading = false

    val engineState: StateFlow<EngineState> = graph.engines.state
    val ttsSpeaking: StateFlow<Boolean> = graph.tts.speaking
    val ttsAmplitude: StateFlow<Float> = graph.tts.amplitude
    val apiState = graph.api.state
    val permissions = graph.permissions.state

    private var job: Job? = null
    private var loadedEngineSignature: String? = null
    private val roleplayRoles = mutableMapOf<Long, String>()

    init {
        store.bindUnboundLifeConversations(graph.settings.current.characterId)
        // 空间既可能来自侧栏，也可能来自设置页；统一在这里切换到该空间的最近会话。
        viewModelScope.launch {
            graph.settings.settings
                .map { it.space }
                .distinctUntilChanged()
                .collect { space ->
                    val current = store.find(_currentId.value)
                    if (current == null || current.space != space) openLatest(space, lifeCharacterId())
                }
        }
        // 生活空间切换角色卡时，自动切到该角色最近的会话（设置页与侧栏都会触发）。
        viewModelScope.launch {
            graph.settings.settings
                .map { it.characterId }
                .distinctUntilChanged()
                .collect { characterId ->
                    if (graph.settings.current.space != Space.LIFE) return@collect
                    val current = store.find(_currentId.value)
                    if (current == null || current.space != Space.LIFE || current.characterId != characterId) {
                        openLatest(Space.LIFE, characterId)
                    }
                }
        }
        // 当前会话始终跟随全局工作区：设置页或侧栏切换后同步写回会话绑定。
        viewModelScope.launch {
            graph.workspaces.activeId.collect { workspaceId ->
                val conversation = store.find(_currentId.value) ?: return@collect
                if (workspaceId.isNotBlank() && conversation.workspaceId != workspaceId) {
                    store.setWorkspace(conversation.id, workspaceId)
                }
            }
        }
        // 插件注册可能异步完成或随启停重建，直接跟随注册表刷新侧边栏与消息菜单。
        viewModelScope.launch {
            graph.pluginRuntime.registrations.collect { registrations ->
                _pluginSidebarEntries.value = registrations.values
                    .flatMap { it.ui }
                    .filter { it.type == "navigation" && it.surface.equals("main_sidebar_plugins", ignoreCase = true) }
                    .sortedWith(compareBy({ it.order }, { it.pluginName }, { it.id }))
                _pluginMenuItems.value = registrations.values
                    .flatMap { it.hooksOf("chat_message_menu_item") }
            }
        }
        // 桌宠输入栏与菜单跳转：桌宠在后台运行，消息经总线进入生活空间会话。
        viewModelScope.launch {
            PetChatBus.messages.collect { text -> sendPetMessage(text) }
        }
        viewModelScope.launch {
            PetNavigator.events.collect { event ->
                if (event?.destination == PetDestination.LIFE_CHAT) {
                    graph.settings.update { it.copy(space = Space.LIFE) }
                    openLatest(Space.LIFE, lifeCharacterId())
                }
            }
        }
    }

    private fun sendPetMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            graph.settings.update { it.copy(space = Space.LIFE) }
            val characterId = lifeCharacterId()
            val conversation = store.conversations.value.firstOrNull {
                it.space == Space.LIFE && it.characterId == characterId
            } ?: store.create(
                space = Space.LIFE,
                workspaceId = graph.workspaces.active()?.id.orEmpty(),
                characterId = characterId,
            )
            _currentId.value = conversation.id
            _input.value = trimmed
            send()
        }
    }

    fun beginVideoCall() {
        callMode = true
        _callReply.value = ""
        graph.tts.stop()
    }

    fun endVideoCall() {
        callMode = false
        graph.tts.stop()
    }

    fun sendCallTurn(text: String, jpegBase64: String? = null) {
        if (_running.value) return
        val conversation = store.find(_currentId.value) ?: store.create(
            space = Space.LIFE,
            workspaceId = graph.workspaces.active()?.id.orEmpty(),
            characterId = lifeCharacterId(),
        ).also { _currentId.value = it.id }
        val attachment = jpegBase64?.let { data ->
            runCatching {
                val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
                val file = File(attachmentsDir, "call-${UUID.randomUUID()}.jpg")
                file.writeBytes(bytes)
                ChatAttachment(
                    name = "视频通话画面.jpg",
                    path = file.absolutePath,
                    kind = AttachmentKind.IMAGE,
                    mimeType = "image/jpeg",
                    imagePath = file.absolutePath,
                )
            }.getOrNull()
        }
        val prompt = text.trim().ifBlank { "看看我现在的画面，自然地回应我。" }
        sendNow(conversation, prompt, listOfNotNull(attachment))
    }

    private fun speakCallReply(text: String) {
        if (text.isBlank()) return
        val settings = graph.settings.current
        when (settings.ttsEngine) {
            VoiceEngineType.CLOUD -> graph.tts.speakCloud(text, settings)
            VoiceEngineType.SYSTEM -> graph.tts.speak(text, settings.ttsSpeed)
            VoiceEngineType.LOCAL -> graph.tts.speakLocal(text, settings)
        }
    }

    fun onInputChange(text: String) {
        _input.value = text
    }

    fun selectSpace(space: Space) {
        graph.settings.update { it.copy(space = space) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        graph.settings.update(transform)
    }

    fun selectConversation(id: Long) {
        val conversation = store.find(id) ?: return
        _currentId.value = id
        if (conversation.space == Space.LIFE && conversation.characterId.isNotBlank() &&
            graph.settings.current.characterId != conversation.characterId
        ) {
            graph.settings.update { it.copy(characterId = conversation.characterId) }
        }
        if (space.value != conversation.space) {
            graph.settings.update { it.copy(space = conversation.space) }
        }
        syncWorkspace(conversation)
        resetAttachments()
    }

    fun openConversation(conversation: Conversation) {
        _currentId.value = conversation.id
        if (conversation.space == Space.LIFE && conversation.characterId.isNotBlank() &&
            graph.settings.current.characterId != conversation.characterId
        ) {
            graph.settings.update { it.copy(characterId = conversation.characterId) }
        }
        if (space.value != conversation.space) {
            graph.settings.update { it.copy(space = conversation.space) }
        }
        syncWorkspace(conversation)
        resetAttachments()
    }

    fun newConversation() {
        val currentSpace = space.value
        val created = store.create(
            space = currentSpace,
            workspaceId = graph.workspaces.active()?.id.orEmpty(),
            characterId = if (currentSpace == Space.LIFE) lifeCharacterId() else "",
        )
        _currentId.value = created.id
        resetAttachments()
    }

    fun renameConversation(id: Long, title: String) {
        val cleaned = title.trim().take(40)
        if (cleaned.isNotBlank()) store.rename(id, cleaned)
    }

    fun clearExecutionRecords() {
        _currentId.value?.let { store.clearSteps(it) }
    }

    fun deleteConversation(id: Long) {
        val currentSpace = space.value
        store.delete(id)
        if (_currentId.value == id) openLatest(currentSpace, lifeCharacterId())
    }

    fun archiveConversation(id: Long) {
        val conversation = store.find(id) ?: return
        if (!store.archive(id)) return
        if (graph.settings.current.memoryAutoSave) {
            graph.memoryAutoCapture.captureAsync(
                conversationId = id,
                force = true,
                snapshot = conversation,
            )
        }
        if (_currentId.value == id) openLatest(conversation.space, lifeCharacterId())
    }

    fun restoreConversation(id: Long) {
        store.restore(id)
    }

    fun toggleApi() = graph.api.toggle()

    fun stop() {
        job?.cancel()
        graph.engines.cancel()
        graph.tts.stop()
        _running.value = false
        store.find(_currentId.value)?.let { notifyPluginChatRuntime(it.id, "idle") }
    }

    override fun onCleared() {
        graph.tts.stop()
        super.onCleared()
    }

    fun send() {
        val text = _input.value.trim()
        val pendingAttachments = _attachments.value.toList()
        if ((text.isEmpty() && pendingAttachments.isEmpty()) || _running.value || _submitting.value) return
        _submitting.value = true
        viewModelScope.launch {
            try {
                val conversation = store.find(_currentId.value)
                    ?: store.create(
                        space = space.value,
                        workspaceId = graph.workspaces.active()?.id.orEmpty(),
                        characterId = if (space.value == Space.LIFE) lifeCharacterId() else "",
                    ).also { _currentId.value = it.id }
                val decision = try {
                    graph.pluginRuntime.chatInputDecision(conversation.id, text)
                } catch (e: Exception) {
                    Toast.makeText(getApplication(), "插件 Hook 执行失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (decision.action == "block" || decision.action == "consume") {
                    Toast.makeText(
                        getApplication(),
                        decision.message ?: "插件阻止了这条消息",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                val finalText = if (decision.action == "replace") decision.text else text
                sendNow(conversation, finalText, pendingAttachments)
            } finally {
                _submitting.value = false
            }
        }
    }

    private fun sendNow(
        conversation: com.iris.localai.agent.Conversation,
        text: String,
        pendingAttachments: List<ChatAttachment>,
    ) {
        if (_running.value) return
        // 会话绑定的工作区优先；绑定失效时沿用当前工作区。
        graph.workspaces.workspaces.value
            .firstOrNull { it.id == conversation.workspaceId }
            ?.let { graph.workspaces.setActive(it.id) }

        _input.value = ""
        _attachments.value = emptyList()
        _attachmentError.value = null
        graph.tts.stop()

        if (conversation.title == "新对话") {
            val seed = text.ifBlank { pendingAttachments.firstOrNull()?.name ?: "新对话" }
            store.rename(conversation.id, autoTitle(seed))
        }

        val userId = store.newMessageId()
        val userMessage = ChatMessage(userId, ChatRole.USER, text, attachments = pendingAttachments)
        store.appendMessage(conversation.id, userMessage)
        val assistantId = store.newMessageId()
        store.appendMessage(conversation.id, ChatMessage(assistantId, ChatRole.ASSISTANT, ""))
        val roleplay = RoleplayOverlay.decide(text, roleplayRoles[conversation.id])
        if (roleplay.changed) {
            if (roleplay.role == null) roleplayRoles.remove(conversation.id)
            else roleplayRoles[conversation.id] = roleplay.role
        }
        _running.value = true
        _performanceMood.value = "neutral"
        _performanceIntensity.value = 0.85f
        notifyPluginChatMessage(conversation.id, userMessage, "user")
        notifyPluginChatRuntime(conversation.id, "processing")

        job = viewModelScope.launch {
            try {
                val profile = graph.models.resolve(
                    space = conversation.space,
                    characterId = conversation.characterId,
                    workspaceId = conversation.workspaceId,
                )
                val settings = graph.settings.current
                val signature = profile.engineSignature() + listOf(
                    settings.backend.name,
                    settings.contextLength,
                    settings.threads,
                    settings.flashAttention,
                    settings.kvCacheType,
                    settings.batchSize,
                    settings.useMmap,
                    settings.backendFallback,
                ).joinToString("|")
                if (loadedEngineSignature != signature) {
                    graph.engines.unload()
                    loadedEngineSignature = signature
                }
                if (!graph.engines.ensureLoaded(profile)) {
                    val reason = (engineState.value as? EngineState.Failed)?.message ?: "引擎加载失败"
                    appendError(conversation.id, assistantId, reason)
                    return@launch
                }
                val language = profile.role(ModelRole.LANGUAGE)
                val languageProvider = graph.models.providerFor(profile, ModelRole.LANGUAGE)
                val cloud = languageProvider.kind == ProviderKind.CLOUD
                val useProfileParams = cloud && settings.useProfileGenerationParams
                val visionEnabled = isVisionEnabled(profile)
                val (history, userImages) = buildPromptHistory(
                    messages = conversation.messages,
                    currentAttachments = pendingAttachments,
                    visionEnabled = visionEnabled,
                    maxSide = settings.visionQualitySide.coerceIn(384, 2048),
                )
                val trimmedHistory = trimHistory(history, settings)
                val userInput = buildPromptContent(text, pendingAttachments, visionEnabled)
                val character = graph.characters.get(settings.characterId)?.card
                val userBlock = buildString {
                    if (settings.userName.isNotBlank()) {
                        appendLine("用户名：${settings.userName}")
                    }
                    if (settings.userNarrative.isNotBlank()) {
                        appendLine("用户叙事：${settings.userNarrative}")
                    }
                    if (settings.userVisualTags.isNotBlank()) {
                        appendLine("用户视觉标签：${settings.userVisualTags}")
                    }
                }
                val characterBlock = if (settings.space == Space.LIFE && character != null) {
                    buildString {
                        appendLine("【当前角色】${character.charName}")
                        if (character.description.isNotBlank()) {
                            appendLine("描述：${character.description}")
                        }
                        if (character.personality.isNotBlank()) {
                            appendLine("性格：${character.personality}")
                        }
                        if (character.scenario.isNotBlank()) {
                            appendLine("场景：${character.scenario}")
                        }
                        if (character.systemPrompt.isNotBlank()) {
                            appendLine("角色系统提示：${character.systemPrompt}")
                        }
                        if (character.postHistory.isNotBlank()) {
                            appendLine("历史后指令：${character.postHistory}")
                        }
                    }
                } else {
                    ""
                }
                val loreBlock = graph.worldBooks.let { store ->
                    LorebookResolver.promptBlock(
                        LorebookResolver.resolve(
                            books = store.books.value,
                            text = text,
                            history = history.map { it.content },
                            settings = store.settings.value,
                        ),
                    )
                }
                val skillBlock = graph.skills.promptBlock(
                    query = text,
                    forAgent = settings.space == Space.WORK || settings.space == Space.COMPOSITE,
                )
                val injectMemory = if (settings.space == Space.WORK || settings.space == Space.COMPOSITE) {
                    settings.memoryInjectAgent
                } else {
                    settings.memoryInjectChat
                }
                val memoryBlock = if (settings.memoryEnabled) {
                    buildString {
                        if (injectMemory) {
                            append(graph.memory.briefContext(
                                includeMemory = true,
                                includeKnowledge = settings.knowledgeInChat,
                                maxChars = 420,
                            ))
                        }
                        if (graph.memory.looksLikeRecall(text)) {
                            val recall = graph.memory.recall(
                                query = text,
                                limit = 6,
                                includeDiary = true,
                                includeKnowledge = settings.knowledgeInChat,
                            )
                            if (recall.hits.isNotEmpty()) {
                                if (isNotEmpty()) appendLine()
                                appendLine(graph.memory.formatRecall(recall))
                            }
                        }
                    }.trim()
                } else {
                    ""
                }
                val workspaceBlock = if (settings.space == Space.WORK) {
                    graph.workspaces.active()?.let { workspace ->
                        buildString {
                            appendLine("【工作空间】")
                            appendLine("名称：${workspace.name}")
                            if (workspace.path.isNotBlank()) {
                                appendLine("路径：${workspace.path}")
                            } else {
                                appendLine("类型：SAF 文件夹，应用 Shell 无法直接进入")
                            }
                            appendLine(
                                "这是编程工作空间：回答用 Markdown 组织，代码用带语言标注的围栏代码块；" +
                                    "读写文件调用 workspace 工具，执行命令调用 shell 工具；" +
                                    "动手前先确认事实并说明改动影响。",
                            )
                        }
                    }.orEmpty()
                } else {
                    ""
                }
                val nowBlock = "【时间与情境】\n当前时间：" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.CHINA))
                val sceneBlock = when {
                    Regex("跳舞|舞蹈|dance|来一段|跳个舞", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                        "【场景包】表演/舞蹈：需要时使用 [dance:] 与 [cam:] 标签。"
                    Regex("记住|记忆|上次|之前|回忆|还记得", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                        "【场景包】回忆/记忆：优先参考长期记忆与日记，不确定时不要编造。"
                    else -> "【场景包】日常闲聊：自然对话，不必主动表演。"
                }
                val roleplayBlock = roleplayRoles[conversation.id]?.let { RoleplayOverlay.systemBlock(it) }.orEmpty()
                val promptSuffix = buildString {
                    if (userBlock.isNotBlank()) {
                        appendLine("【用户设定】")
                        appendLine(userBlock)
                    }
                    if (characterBlock.isNotBlank()) {
                        appendLine(characterBlock)
                    }
                    if (memoryBlock.isNotBlank()) {
                        appendLine("【长期记忆】")
                        appendLine(memoryBlock)
                    }
                    appendLine(nowBlock)
                    appendLine(sceneBlock)
                    if (roleplayBlock.isNotBlank()) {
                        appendLine(roleplayBlock)
                    }
                    if (loreBlock.isNotBlank()) {
                        appendLine("【世界书】")
                        appendLine(loreBlock)
                    }
                    if (skillBlock.isNotBlank()) {
                        appendLine("【技能】")
                        appendLine(skillBlock)
                    }
                    if (workspaceBlock.isNotBlank()) {
                        appendLine(workspaceBlock)
                    }
                    if (settings.space == Space.LIFE || settings.space == Space.COMPOSITE) {
                        appendLine("【表演标签】")
                        appendLine("需要角色表演时，可在回复句首插入标签；标签不会显示也不会被朗读：")
                        appendLine("[emo:happy:0.8] 表达情绪；[act:wave] 做动作；[dance:舞蹈名] 跳舞；[cam:半身] 切景别或[cam:镜头名] 用镜头素材；[intent:greet] 打招呼。")
                        appendLine("一次只放 1 到 2 个标签，不要解释标签，也不要每次都加。")
                        val dances = graph.motionAssets.assets.value
                            .filter { it.category == MotionCategory.DANCE }
                            .take(12)
                        if (dances.isNotEmpty()) {
                            appendLine("可用舞蹈素材：" + dances.joinToString("、") { it.name })
                        }
                        val cameras = graph.motionAssets.assets.value
                            .filter { it.category == MotionCategory.CAMERA }
                            .take(8)
                        if (cameras.isNotEmpty()) {
                            appendLine("可用镜头素材：" + cameras.joinToString("、") { it.name })
                        }
                    }
                    if (settings.deepThinking) {
                        append("请先进行必要的思考再回答。")
                        if (settings.thinkingBudget > 0) {
                            append("思考预算约 ${settings.thinkingBudget} tokens。")
                        }
                    } else {
                        append("直接回答，不要展示思考过程。")
                    }
                    if (settings.systemPrompt.isNotBlank()) {
                        appendLine()
                        append(settings.systemPrompt)
                    }
                }
                val finalPromptSuffix = graph.pluginRuntime.transformSystemPrompt(conversation.id, promptSuffix)
                val startInThink = settings.deepThinking &&
                    (engineState.value as? EngineState.Ready)?.details?.thinkingPrefilled == true
                val pendingReasoning = StringBuilder()
                var reasoningStartedAt = 0L
                var reasoningLastAt = 0L
                graph.agent.run(
                    userInput = userInput,
                    userImages = userImages,
                    history = trimmedHistory,
                    params = SamplingConfig(
                        temperature = if (useProfileParams) language.temperature else settings.temperature,
                        topP = if (useProfileParams) language.topP else settings.topP,
                        topK = settings.topK,
                        maxNewTokens = if (useProfileParams && language.maxOutputTokens > 0) {
                            language.maxOutputTokens
                        } else {
                            settings.maxNewTokens
                        },
                        minP = settings.minP,
                        repeatPenalty = settings.repeatPenalty,
                        repeatLastN = settings.repeatLastN,
                        presencePenalty = settings.presencePenalty,
                        frequencyPenalty = settings.frequencyPenalty,
                        seed = if (settings.seedEnabled) settings.seedValue else null,
                        disableThinking = !settings.deepThinking,
                    ),
                    userSystemPrompt = finalPromptSuffix,
                    enabledTools = enabledToolIds(settings),
                    toolPermissions = settings.toolPermissions,
                    maxStepsOverride = when (settings.space) {
                        Space.WORK, Space.COMPOSITE -> 0
                        else -> settings.agentMaxSteps
                    },
                    planFirst = settings.planFirst,
                    startInThink = startInThink,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Delta -> store.updateMessage(conversation.id, assistantId) {
                            it.copy(text = it.text + event.text)
                        }
                        is AgentEvent.Performance -> applyPerformance(event.tags)
                        is AgentEvent.Reasoning -> {
                            val now = System.currentTimeMillis()
                            if (pendingReasoning.isEmpty()) {
                                reasoningStartedAt = now
                            }
                            reasoningLastAt = now
                            pendingReasoning.append(event.text)
                            store.updateMessage(conversation.id, assistantId) {
                                it.copy(reasoning = it.reasoning + event.text)
                            }
                        }
                        is AgentEvent.StepFinished -> {
                            val stepReasoning = pendingReasoning.toString()
                            val stepReasoningMs = if (reasoningStartedAt > 0L) {
                                (reasoningLastAt - reasoningStartedAt).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                            pendingReasoning.setLength(0)
                            reasoningStartedAt = 0L
                            store.updateMessage(conversation.id, assistantId) {
                                it.copy(
                                    reasoning = "",
                                    steps = it.steps + event.step.copy(
                                        reasoning = stepReasoning,
                                        reasoningMs = stepReasoningMs,
                                    ),
                                )
                            }
                        }
                        is AgentEvent.Finished -> {
                            val finalReasoningMs = if (pendingReasoning.isNotEmpty() && reasoningStartedAt > 0L) {
                                (reasoningLastAt - reasoningStartedAt).coerceAtLeast(0L)
                            } else {
                                null
                            }
                            store.updateMessage(conversation.id, assistantId) {
                                it.copy(
                                    text = event.text.ifBlank { it.text },
                                    reasoningMs = finalReasoningMs ?: it.reasoningMs,
                                )
                            }
                            store.find(conversation.id)?.messages
                                ?.firstOrNull { it.id == assistantId }
                                ?.let { notifyPluginChatMessage(conversation.id, it, "ai") }
                            notifyPluginChatRuntime(conversation.id, "completed")
                            val finalText = event.text.ifBlank {
                                store.find(conversation.id)?.messages?.lastOrNull()?.text.orEmpty()
                            }
                            val spokenText = AssistantMessageParser.mainText(finalText)
                                .ifBlank { "角色状态已更新" }
                            graph.notifier.notifyReply(
                                title = conversation.title.ifBlank { "对话" },
                                body = spokenText,
                            )
                            if (callMode) {
                                _callReply.value = spokenText
                                speakCallReply(spokenText)
                            } else {
                                speakIfEnabled(spokenText)
                            }
                        }
                        is AgentEvent.Failed -> {
                            store.updateMessage(conversation.id, assistantId) {
                                it.copy(
                                    steps = it.steps + AgentStep(
                                        tool = "对话",
                                        arguments = "",
                                        result = event.message,
                                        status = StepStatus.ERROR,
                                    ),
                                )
                            }
                            appendError(conversation.id, assistantId, event.message)
                            if (callMode) _callReply.value = "[通话出错] ${event.message}"
                            notifyPluginChatRuntime(conversation.id, "error", event.message)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendError(conversation.id, assistantId, e.message ?: "发送失败")
                notifyPluginChatRuntime(conversation.id, "error", e.message)
            } finally {
                _running.value = false
            }
        }
    }

    private fun openLatest(space: Space, characterId: String? = null) {
        val target = store.latest(space, characterId)
            ?: store.create(
                space = space,
                workspaceId = graph.workspaces.active()?.id.orEmpty(),
                characterId = if (space == Space.LIFE) characterId.orEmpty() else "",
            )
        _currentId.value = target.id
        syncWorkspace(target)
        resetAttachments()
    }

    private fun lifeCharacterId(): String = graph.settings.current.characterId

    private fun syncWorkspace(conversation: Conversation) {
        val workspaces = graph.workspaces.workspaces.value
        val bound = workspaces.firstOrNull { it.id == conversation.workspaceId }
        if (bound != null) {
            graph.workspaces.setActive(bound.id)
            return
        }
        val fallback = graph.workspaces.active() ?: workspaces.firstOrNull() ?: return
        graph.workspaces.setActive(fallback.id)
        if (conversation.workspaceId != fallback.id) {
            store.setWorkspace(conversation.id, fallback.id)
        }
    }

    private fun resetAttachments() {
        _attachments.value = emptyList()
        _attachmentError.value = null
    }

    fun removeAttachment(index: Int) {
        _attachments.update { list -> list.filterIndexed { i, _ -> i != index } }
        _attachmentError.value = null
    }

    fun attachWorkspaceFile(path: String, name: String) {
        val workspace = graph.workspaces.active()
        if (workspace == null) {
            _attachmentError.value = "当前没有工作区，无法读取工作区文件"
            return
        }
        if (_attachments.value.size >= MAX_ATTACHMENTS) {
            _attachmentError.value = "最多附加 $MAX_ATTACHMENTS 个文件"
            return
        }
        val displayName = name.ifBlank { path.substringAfterLast('/') }
        viewModelScope.launch {
            if (isImageName(displayName)) {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = WorkspaceFs(getApplication(), workspace)
                            .readBytes(path, MAX_IMAGE_BYTES.toLong())
                            .getOrThrow()
                        saveImageAttachment(displayName, mimeForName(displayName), bytes)
                    }
                }
                result.onSuccess { addImageAttachment(it) }
                    .onFailure { _attachmentError.value = it.message ?: "读取图片失败" }
            } else {
                val result = withContext(Dispatchers.IO) {
                    WorkspaceFs(getApplication(), workspace).readTextFile(path)
                }
                result.onSuccess { content ->
                    addAttachment(name = displayName, path = path, text = content)
                }.onFailure { error ->
                    _attachmentError.value = error.message ?: "读取文件失败"
                }
            }
        }
    }

    fun attachDeviceFile(uri: Uri) {
        if (_attachments.value.size >= MAX_ATTACHMENTS) {
            _attachmentError.value = "最多附加 $MAX_ATTACHMENTS 个文件"
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readDeviceFile(uri) }
            }
            result.onSuccess { loaded ->
                when (loaded) {
                    is LoadedAttachment.Text -> addAttachment(loaded.name, "", loaded.content)
                    is LoadedAttachment.Image -> addImageAttachment(loaded.attachment)
                }
            }.onFailure { error ->
                _attachmentError.value = error.message ?: "读取文件失败"
            }
        }
    }

    private fun addAttachment(name: String, path: String, text: String) {
        if (_attachments.value.size >= MAX_ATTACHMENTS) {
            _attachmentError.value = "最多附加 $MAX_ATTACHMENTS 个文件"
            return
        }
        if (text.isBlank()) {
            _attachmentError.value = "文件为空：$name"
            return
        }
        val used = _attachments.value.sumOf { it.content.length }
        val remaining = MAX_ATTACHMENT_TOTAL_CHARS - used
        if (remaining <= 0) {
            _attachmentError.value = "附件总内容已达上限（${MAX_ATTACHMENT_TOTAL_CHARS} 字符）"
            return
        }
        val limit = minOf(MAX_ATTACHMENT_CHARS, remaining)
        val content = if (text.length > limit) text.take(limit) else text
        _attachments.update {
            it + ChatAttachment(
                name = name,
                path = path,
                content = content,
                truncated = text.length > content.length,
            )
        }
        _attachmentError.value = null
    }

    private fun addImageAttachment(attachment: ChatAttachment) {
        if (_attachments.value.size >= MAX_ATTACHMENTS) {
            _attachmentError.value = "最多附加 $MAX_ATTACHMENTS 个文件"
            return
        }
        _attachmentError.value = if (isVisionEnabled(graph.models.active())) {
            null
        } else {
            "图片已附加，但当前模型未开启「支持识图」，发送时不会带上图片；请在模型设置开启"
        }
        _attachments.update { it + attachment }
    }

    private fun saveImageAttachment(name: String, mime: String, bytes: ByteArray): ChatAttachment {
        require(bytes.isNotEmpty()) { "图片为空：$name" }
        require(bytes.size <= MAX_IMAGE_BYTES) { "图片超过 12 MB 上限：$name" }
        val file = File(attachmentsDir, "${UUID.randomUUID()}.${imageExtension(name, mime)}")
        file.writeBytes(bytes)
        return ChatAttachment(
            name = name,
            kind = AttachmentKind.IMAGE,
            mimeType = mime.ifBlank { mimeForName(name) },
            imagePath = file.absolutePath,
        )
    }

    private fun readDeviceFile(uri: Uri): LoadedAttachment {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "设备文件"
        val mime = resolver.getType(uri).orEmpty()

        if (mime.startsWith("image/") || isImageName(name)) {
            val bytes = readUriBytes(uri, MAX_IMAGE_BYTES, name)
            return LoadedAttachment.Image(saveImageAttachment(name, mime, bytes))
        }
        val bytes = readUriBytes(uri, MAX_ATTACHMENT_BYTES, name)
        if (bytes.any { it == 0.toByte() }) error("暂不支持二进制文件：$name")
        return LoadedAttachment.Text(name, String(bytes, Charsets.UTF_8))
    }

    private fun readUriBytes(uri: Uri, limit: Int, name: String): ByteArray {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: error("无法打开文件：$name")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) error("文件超过 ${limit / (1024 * 1024)} MB 上限：$name")
                output.write(buffer, 0, read)
            }
            if (total == 0) error("文件为空：$name")
            output.toByteArray()
        }
    }

    /** 文件与命令工具只在工作/综合空间注入；Shell 还需要在「编程管理」开启。 */
    private fun enabledToolIds(settings: AppSettings): Set<String> {
        if (!settings.agentMode) return emptySet()
        var ids = settings.enabledToolIds
        val inCodeSpace = settings.space == Space.WORK || settings.space == Space.COMPOSITE
        if (!inCodeSpace) {
            ids = ids - codeToolIds
        } else if (graph.code.current.enabled) {
            ids = ids + setOf("shell", "workspace")
        }
        val inOperateSpace = settings.space == Space.OPERATE || settings.space == Space.COMPOSITE
        if (!inOperateSpace) ids = ids - com.iris.localai.agent.tools.DeviceToolNames.all
        return ids
    }

    private fun trimHistory(
        history: List<PromptMessage>,
        settings: AppSettings,
    ): List<PromptMessage> {
        if (history.isEmpty() || !settings.autoCompact) return history
        return when (settings.contextStrategy) {
            ContextStrategy.SLIDING_WINDOW,
            ContextStrategy.SUMMARY,
            ContextStrategy.KV_SHIFT -> history.takeLast((settings.contextKeepTurns * 2).coerceAtLeast(2))

            ContextStrategy.TOKEN_BUDGET -> {
                val budget = (settings.contextLength * settings.summaryTriggerPercent / 100)
                    .coerceAtLeast(512)
                val kept = ArrayDeque<PromptMessage>()
                var tokens = 0
                for (message in history.asReversed()) {
                    val cost = message.content.length / 4 + 8
                    if (kept.isNotEmpty() && tokens + cost > budget) break
                    kept.addFirst(message)
                    tokens += cost
                }
                kept.toList()
            }
        }
    }

    private fun autoTitle(text: String): String =
        text.replace("\n", " ").trim().take(18).ifBlank { "新对话" }

    private fun ChatMessage.promptText(visionEnabled: Boolean = true): String =
        buildPromptContent(text, attachments, visionEnabled)

    private fun buildPromptContent(
        text: String,
        attachments: List<ChatAttachment>,
        visionEnabled: Boolean = true,
    ): String {
        if (attachments.isEmpty()) return text
        return buildString {
            if (text.isNotBlank()) {
                append(text)
                append("\n\n")
            }
            attachments.forEachIndexed { index, attachment ->
                if (index > 0) append("\n\n")
                if (attachment.kind == AttachmentKind.IMAGE) {
                    val hasFile = attachment.imagePath.isNotBlank() && File(attachment.imagePath).isFile
                    append("【附件图片：${attachment.name}")
                    if (attachment.path.isNotBlank()) append("（${attachment.path}）")
                    append("】\n")
                    append(
                        when {
                            !visionEnabled -> "（当前模型未开启「支持识图」，这张图片未发送。）"
                            !hasFile -> "（图片文件已丢失，未发送。）"
                            else -> "（图片已随消息发送，请直接查看图片内容。）"
                        },
                    )
                } else {
                    append("【附件：${attachment.name}")
                    if (attachment.path.isNotBlank()) append("（${attachment.path}）")
                    append("】\n")
                    append(attachment.content)
                    if (attachment.truncated) append("\n（附件内容已截断）")
                }
            }
        }.trimEnd()
    }

    private suspend fun buildPromptHistory(
        messages: List<ChatMessage>,
        currentAttachments: List<ChatAttachment>,
        visionEnabled: Boolean,
        maxSide: Int,
    ): Pair<List<PromptMessage>, List<String>> = withContext(Dispatchers.IO) {
        val currentImages = if (visionEnabled) {
            currentAttachments
                .filter { it.kind == AttachmentKind.IMAGE }
                .mapNotNull { imageDataUrl(it, maxSide) }
                .take(MAX_PROMPT_IMAGES)
        } else {
            emptyList()
        }
        var remaining = (MAX_PROMPT_IMAGES - currentImages.size).coerceAtLeast(0)
        val historyImages = HashMap<Int, List<String>>()
        if (visionEnabled && remaining > 0) {
            for (index in messages.indices.reversed()) {
                if (remaining <= 0) break
                val message = messages[index]
                if (message.role != ChatRole.USER) continue
                val urls = message.attachments
                    .filter { it.kind == AttachmentKind.IMAGE }
                    .mapNotNull { imageDataUrl(it, maxSide) }
                    .take(remaining)
                if (urls.isNotEmpty()) {
                    historyImages[index] = urls
                    remaining -= urls.size
                }
            }
        }
        val history = messages.mapIndexedNotNull { index, message ->
            if (message.text.isBlank() && message.attachments.isEmpty()) {
                return@mapIndexedNotNull null
            }
            PromptMessage(
                role = if (message.role == ChatRole.USER) {
                    PromptMessage.USER
                } else {
                    PromptMessage.ASSISTANT
                },
                content = message.promptText(visionEnabled),
                images = historyImages[index].orEmpty(),
            )
        }
        history to currentImages
    }

    private fun imageDataUrl(attachment: ChatAttachment, maxSide: Int): String? {
        val file = File(attachment.imagePath)
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@runCatching null
            val largest = max(bitmap.width, bitmap.height)
            val scaled = if (largest > maxSide) {
                val ratio = maxSide.toFloat() / largest
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            try {
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
                "data:image/jpeg;base64," +
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }.getOrNull()
    }

    private fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }

    private fun imageExtension(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in IMAGE_EXTENSIONS) return ext
        return when (mime.substringAfter('/', "").substringBefore('+').lowercase()) {
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            "bmp" -> "bmp"
            "heic", "heif" -> "heic"
            else -> "jpg"
        }
    }

    private fun isVisionEnabled(profile: ModelProfile): Boolean {
        val languageCaps = graph.models.modelFor(profile, ModelRole.LANGUAGE)?.capabilities
        val visionCaps = graph.models.modelFor(profile, ModelRole.VISION)?.capabilities
        return languageCaps?.vision == true ||
            (profile.role(ModelRole.VISION).enabled && visionCaps?.vision == true)
    }

    private fun ModelProfile.engineSignature(): String {
        val languageProvider = graph.models.providerFor(this, ModelRole.LANGUAGE)
        val visionProvider = graph.models.providerFor(this, ModelRole.VISION)
        return listOf(
            id,
            languageProvider.id,
            languageProvider.kind.name,
            languageProvider.vendor.name,
            languageProvider.endpoint,
            graph.models.modelNameFor(this, ModelRole.LANGUAGE),
            languageProvider.localModelPath,
            languageProvider.apiKey.hashCode().toString(),
            visionProvider.id,
            visionProvider.endpoint,
            graph.models.modelNameFor(this, ModelRole.VISION),
            isVisionEnabled(this).toString(),
        ).joinToString("|")
    }

    private fun applyPerformance(tags: List<PerformanceTag>) {
        for (tag in tags) {
            when (tag) {
                is PerformanceTag.Emotion -> {
                    _performanceMood.value = normalizeMood(tag.name)
                    _performanceIntensity.value = tag.intensity
                }
                is PerformanceTag.Expression -> {
                    _performanceMood.value = normalizeMood(tag.name)
                }
                is PerformanceTag.Action -> playPerformanceAction(tag.name)
                is PerformanceTag.Intent -> {
                    when (tag.name.lowercase()) {
                        "greet", "wave", "打招呼", "挥手" -> playPerformanceAction("wave")
                        "nod", "点头" -> playPerformanceAction("nod")
                        "shake", "摇头" -> playPerformanceAction("shake")
                        "dance", "跳舞" -> playPerformanceDance("")
                        else -> Unit
                    }
                }
                is PerformanceTag.Dance -> playPerformanceDance(tag.name)
                is PerformanceTag.Camera -> playPerformanceCamera(tag.name)
                is PerformanceTag.Motion,
                is PerformanceTag.Stand,
                is PerformanceTag.Outfit,
                is PerformanceTag.Param,
                is PerformanceTag.Preset,
                -> Unit
            }
        }
    }

    private fun normalizeMood(name: String): String = when (name.trim().lowercase()) {
        "happy", "joy", "smile", "开心", "高兴" -> "happy"
        "angry", "anger", "生气" -> "angry"
        "sad", "sorrow", "难过", "伤心" -> "sad"
        "surprised", "surprise", "惊讶" -> "surprised"
        "relaxed", "calm", "放松" -> "relaxed"
        "thinking", "think", "思考" -> "thinking"
        else -> "neutral"
    }

    private fun playPerformanceAction(name: String) {
        val wanted = name.trim()
        if (wanted.isEmpty()) return
        val categories = setOf(MotionCategory.GREET, MotionCategory.INTERACT, MotionCategory.IDLE)
        val asset = graph.motionAssets.findByName(wanted)
            ?: graph.motionAssets.assets.value.firstOrNull {
                it.category in categories && it.name.contains(wanted, ignoreCase = true)
            }
        if (asset != null) {
            MotionBridge.playAsset(asset.id, loop = false, bgmPath = asset.bgm?.absolutePath)
        }
        if (MotionBridge.hasMotionView()) {
            val action = when (wanted.lowercase()) {
                "wave", "挥手", "摆手", "打招呼" -> "wave"
                "nod", "点头" -> "nod"
                "shake", "摇头" -> "shake"
                "dance", "跳舞" -> "dance"
                "idle", "待机" -> "idle"
                else -> wanted
            }
            MotionBridge.play(action)
        }
    }

    private fun playPerformanceDance(name: String) {
        val pool = graph.motionAssets.assets.value.filter { it.category == MotionCategory.DANCE }
        val asset = if (name.isBlank()) {
            pool.randomOrNull()
        } else {
            graph.motionAssets.findByName(name)
                ?: pool.firstOrNull { it.name.contains(name, ignoreCase = true) }
        }
        if (asset != null) {
            MotionBridge.playAsset(asset.id, loop = true, bgmPath = asset.bgm?.absolutePath)
        }
        if (MotionBridge.hasMotionView()) {
            MotionBridge.play("dance")
        }
    }

    private fun playPerformanceCamera(name: String) {
        val shot = cameraShotId(name)
        if (shot != null) {
            MotionBridge.playCamera("shot:$shot")
            return
        }
        val pool = graph.motionAssets.assets.value.filter { it.category == MotionCategory.CAMERA }
        val asset = if (name.isBlank()) {
            pool.randomOrNull()
        } else {
            graph.motionAssets.findByName(name)
                ?: pool.firstOrNull { it.name.contains(name, ignoreCase = true) }
        }
        if (asset != null) {
            MotionBridge.playCamera(asset.id)
        } else {
            MotionBridge.playCamera("shot:half")
        }
    }

    private fun cameraShotId(name: String): String? = when (name.trim().lowercase()) {
        "close", "特写", "ecu" -> "ecu"
        "bust", "胸", "半身特写" -> "bust"
        "half", "半身" -> "half"
        "threeq", "3/4", "七分", "七分身" -> "threeQ"
        "full", "全身" -> "full"
        "long", "远景" -> "long"
        else -> null
    }

    private fun speakIfEnabled(text: String) {
        val settings = graph.settings.current
        if (!settings.autoSpeak || text.isBlank()) return
        when (settings.ttsEngine) {
            VoiceEngineType.SYSTEM -> graph.tts.speak(text, settings.ttsSpeed)
            VoiceEngineType.CLOUD -> graph.tts.speakCloud(text, settings)
            VoiceEngineType.LOCAL -> graph.tts.speakLocal(text, settings)
        }
    }

    suspend fun transcribeVoice(file: File): Result<String> {
        val settings = graph.settings.current
        return when (settings.sttEngine) {
            VoiceEngineType.CLOUD -> graph.cloudVoice.transcribe(settings, file)
            VoiceEngineType.LOCAL -> runCatching { graph.localVoice.transcribe(file) }
            VoiceEngineType.SYSTEM -> Result.failure(
                IllegalStateException("系统语音识别不支持录音文件，请选择本地或云端识别"),
            )
        }
    }

    private fun appendError(conversationId: Long, messageId: Long, message: String) {
        store.updateMessage(conversationId, messageId) {
            it.copy(text = (it.text + "\n\n[错误] " + message).trim())
        }
    }

    private fun notifyPluginChatMessage(conversationId: Long, message: ChatMessage, sender: String) {
        viewModelScope.launch {
            val snapshot = buildJsonObject {
                val now = System.currentTimeMillis()
                put("timestamp", now)
                put("sender", sender)
                put("roleName", if (sender == "user") graph.settings.current.userName else "IRIS")
                put("content", message.text)
                put("completedAt", now)
                put("provider", graph.models.modelNameFor(graph.models.active(), ModelRole.LANGUAGE))
                put("modelName", graph.models.modelNameFor(graph.models.active(), ModelRole.LANGUAGE))
                put("inputTokens", 0)
                put("outputTokens", 0)
                put("cachedInputTokens", 0)
                put("sentAt", now)
                put("outputDurationMs", 0)
                put("waitDurationMs", 0)
                put("displayMode", "normal")
                put("selectedVariantIndex", 0)
                put("variantCount", 1)
                put("isFavorite", false)
            }
            runCatching { graph.pluginRuntime.notifyChatMessage(conversationId, snapshot) }
        }
    }

    private fun notifyPluginChatRuntime(conversationId: Long, state: String, message: String? = null) {
        viewModelScope.launch {
            runCatching { graph.pluginRuntime.notifyChatRuntime(conversationId, state, message) }
        }
    }

    fun refreshPermissions() = graph.permissions.refresh()

    fun requestShizukuPermission(): Boolean = graph.permissions.requestShizukuPermission()

    fun openShizuku(): Boolean = graph.permissions.openShizuku()

    fun enableAccessibilityViaShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            val granted = graph.permissions.enableAccessibilityViaShizuku()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    getApplication(),
                    if (granted) "已通过 Shizuku 开启无障碍" else "自动开启无障碍失败，请手动开启",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            graph.permissions.refresh()
        }
    }

    fun setOperateMode(background: Boolean, shizuku: Boolean) {
        graph.settings.update { it.copy(operateBackgroundMode = background, operateShizukuMode = shizuku) }
        if (shizuku || background) {
            val state = graph.permissions.state.value
            if (!state.shizukuInstalled) {
                Toast.makeText(getApplication(), "未安装 Shizuku，无法启用 Shizuku 交互", Toast.LENGTH_SHORT).show()
            } else if (!state.shizukuBinderConnected) {
                graph.permissions.openShizuku()
                Toast.makeText(getApplication(), "请先启动 Shizuku 服务", Toast.LENGTH_SHORT).show()
            } else if (!state.shizukuAuthorized) {
                graph.permissions.requestShizukuPermission()
                Toast.makeText(getApplication(), "已请求 Shizuku 授权，请在弹出的窗口中允许", Toast.LENGTH_SHORT).show()
            } else if (!state.accessibilityEnabled) {
                enableAccessibilityViaShizuku()
            }
        }
        graph.permissions.refresh()
    }

    fun authorizeOperation() {
        val state = graph.permissions.state.value
        when {
            !state.shizukuInstalled -> Toast.makeText(
                getApplication(), "未安装 Shizuku，请先安装并启动 Shizuku", Toast.LENGTH_SHORT,
            ).show()
            !state.shizukuBinderConnected -> {
                graph.permissions.openShizuku()
                Toast.makeText(getApplication(), "已打开 Shizuku，请启动服务后返回", Toast.LENGTH_SHORT).show()
            }
            !state.shizukuAuthorized -> {
                graph.permissions.requestShizukuPermission()
                Toast.makeText(getApplication(), "已请求 Shizuku 授权", Toast.LENGTH_SHORT).show()
            }
            !state.accessibilityEnabled -> enableAccessibilityViaShizuku()
            else -> graph.permissions.refresh()
        }
    }

    fun setOperationSurfaceMode(mode: com.iris.localai.data.OperationSurfaceMode) {
        graph.settings.update {
            it.copy(
                operationSurfaceMode = mode,
                operateBackgroundMode = if (mode == com.iris.localai.data.OperationSurfaceMode.NORMAL) {
                    it.operateBackgroundMode
                } else {
                    true
                },
            )
        }
        if (mode != com.iris.localai.data.OperationSurfaceMode.NORMAL) {
            authorizeOperation()
        }
    }

    fun releaseVirtualDisplay() {
        graph.deviceActions.releaseVirtualDisplay()
        Toast.makeText(getApplication(), "已释放虚拟屏", Toast.LENGTH_SHORT).show()
    }

    val virtualDisplayRunning = graph.virtualDisplay.running

    suspend fun captureVirtualFrame(): Bitmap? = withContext(Dispatchers.IO) {
        graph.virtualDisplay.capture()
    }

    fun bringVirtualDisplayToMain() {
        viewModelScope.launch(Dispatchers.IO) {
            graph.virtualDisplay.bringLaunchedAppToMain()
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "已把虚拟屏任务带回主屏", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun testVirtualDisplay() {
        graph.settings.update {
            it.copy(
                operationSurfaceMode = com.iris.localai.data.OperationSurfaceMode.ARIES_VIRTUAL_DISPLAY,
                operateBackgroundMode = true,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val metrics = getApplication<Application>().resources.displayMetrics
            val result = graph.virtualDisplay.start(
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    getApplication(),
                    result.fold(
                        { "虚拟屏已启动：display $it" },
                        { "虚拟屏启动失败：${it.message ?: "未知错误"}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun loadPluginMenuItems() {        if (pluginMenuLoading) return
        pluginMenuLoading = true
        viewModelScope.launch {
            runCatching {
                val items = graph.pluginRuntime.menuItems("ai") + graph.pluginRuntime.menuItems("user")
                _pluginMenuItems.value = items.distinctBy { it.pluginId + ":" + it.id }
            }
            pluginMenuLoading = false
        }
    }

    fun loadPluginSidebarEntries() {
        if (pluginSidebarLoading) return
        pluginSidebarLoading = true
        viewModelScope.launch {
            runCatching {
                _pluginSidebarEntries.value = graph.pluginRuntime.sidebarEntries()
            }
            pluginSidebarLoading = false
        }
    }

    fun runPluginSidebarEntry(entry: PluginHookDescriptor) {
        viewModelScope.launch {
            val result = graph.pluginRuntime.invokeNavigationEntry(entry)
            val message = when {
                !result.isNullOrBlank() -> result.take(300)
                entry.route.isNotBlank() -> "该入口使用 Compose DSL 路由，本应用无法渲染"
                else -> "插件入口没有可执行的 action"
            }
            Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
        }
    }

    fun runPluginMenuItem(item: PluginHookDescriptor, message: ChatMessage, index: Int) {        val chatId = store.find(_currentId.value)?.id ?: return
        viewModelScope.launch {
            val payload = buildJsonObject {
                put("event", "chat_message_menu_item_click")
                put("eventName", "chat_message_menu_item_click")
                putJsonObject("eventPayload") {
                    put("action", "click")
                    put("chatId", chatId)
                    put("messageIndex", index)
                    put("menuItemId", item.id)
                    putJsonObject("message") {
                        put("sender", if (message.role == ChatRole.USER) "user" else "ai")
                        put("content", message.text)
                        put("timestamp", System.currentTimeMillis())
                        put("completedAt", System.currentTimeMillis())
                        put("provider", graph.models.modelNameFor(graph.models.active(), ModelRole.LANGUAGE))
                        put("modelName", graph.models.modelNameFor(graph.models.active(), ModelRole.LANGUAGE))
                        put("isFavorite", false)
                        put("variantCount", 1)
                    }
                }
            }
            val result = runCatching {
                graph.pluginRuntime.callMenuItem(item.pluginId, item.id, payload)
            }.getOrNull()
            Toast.makeText(
                getApplication(),
                result?.take(300)?.takeIf { it.isNotBlank() } ?: "插件菜单未返回内容（Compose DSL 弹窗不支持）",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

private val codeToolIds = setOf(
    "shell",
    "workspace",
    "read_file",
    "write_file",
    "edit_file",
    "list_files",
    "search_files",
)

private const val MAX_ATTACHMENTS = 5
private const val MAX_ATTACHMENT_CHARS = 8_000
private const val MAX_ATTACHMENT_TOTAL_CHARS = 24_000
private const val MAX_ATTACHMENT_BYTES = 256 * 1024
private const val MAX_PROMPT_IMAGES = 3
private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

private sealed interface LoadedAttachment {
    data class Text(val name: String, val content: String) : LoadedAttachment
    data class Image(val attachment: ChatAttachment) : LoadedAttachment
}
