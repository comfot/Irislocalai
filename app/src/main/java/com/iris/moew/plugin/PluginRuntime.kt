package com.iris.moew.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iris.moew.agent.ToolRegistry
import com.iris.moew.agent.Tool
import com.iris.moew.engine.PromptMessage
import com.iris.moew.data.ToolPermission
import com.iris.moew.data.RemoteFetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Operit ToolPkg 兼容运行时。每个启用的插件保留一个 WebView 会话，
 * main 与子包共享上下文，从而支持 main 注册、同包 IPC 与有状态工具调用。
 */
class PluginRuntime(
    context: Context,
    private val pluginsProvider: () -> List<InstalledPlugin> = { emptyList() },
    private val tools: ToolRegistry? = null,
    private val terminal: PluginTerminalHost? = null,
    private val permissionOf: (String) -> ToolPermission = { ToolPermission.ALLOW },
    private val hostActions: HostActionRegistry? = null,
) {

    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, PluginSession>()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _registrations = MutableStateFlow<Map<String, PluginRegistrations>>(emptyMap())
    val registrations: StateFlow<Map<String, PluginRegistrations>> = _registrations.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _uiInvalidations = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val uiInvalidations: SharedFlow<String> = _uiInvalidations.asSharedFlow()

    @Volatile
    private var warmed = false

    fun onPluginsChanged(active: List<InstalledPlugin>) {
        mainScope.launch {
            closeAll()
            warmed = false
            ensureAllRegistrations()
        }
    }

    suspend fun ensureAllRegistrations() {
        if (warmed) return
        warmed = true
        val plugins = pluginsProvider().filter { it.enabled }
        plugins.forEach { plugin ->
            val mainFile = File(pluginDirectory(plugin), plugin.main)
            if (plugin.main.isBlank() || !mainFile.isFile) {
                _errors.update {
                    it + (plugin.id to "找不到 main 脚本：${plugin.main.ifBlank { "(manifest.main 为空)" }}")
                }
            }
        }
        plugins
            .filter { plugin -> plugin.main.isNotBlank() && File(pluginDirectory(plugin), plugin.main).isFile }
            .forEach { plugin ->
                runCatching { session(plugin).start() }
                    .onFailure { error ->
                        _errors.update { it + (plugin.id to (error.message ?: "插件 main 启动失败")) }
                    }
            }
    }

    suspend fun callTool(
        plugin: InstalledPlugin,
        subpackageId: String,
        toolName: String,
        arguments: JsonObject,
        onProgress: ((String) -> Unit)? = null,
    ): String = session(plugin).callTool(subpackageId, toolName, arguments, onProgress)

    suspend fun callAiProvider(
        pluginId: String,
        providerId: String,
        method: String,
        payload: JsonObject,
    ): String {
        val plugin = pluginsProvider().firstOrNull { it.id == pluginId }
            ?: error("插件未启用：$pluginId")
        return session(plugin).callProvider(providerId, method, payload)
    }

    suspend fun listAiProviderModels(pluginId: String, providerId: String): List<String> {
        val payload = buildJsonObject {
            put("event", "toolpkg_ai_provider_list_models")
            put("eventName", "toolpkg_ai_provider_list_models")
            putJsonObject("eventPayload") {
                put("providerId", providerId)
                putJsonObject("config") {
                    put("id", providerId)
                    put("apiProviderType", "plugin")
                }
            }
        }
        val raw = callAiProvider(pluginId, providerId, "listModels", payload)
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["models"] as? JsonArray)
                ?: (element["data"] as? JsonObject)?.let { it["models"] as? JsonArray }
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }
                is JsonObject -> item.text("name") ?: item.text("id")
                else -> null
            }
        }
    }

    suspend fun renderUi(plugin: InstalledPlugin, moduleId: String): Result<String> =
        runCatching { session(plugin).renderUi(moduleId) }

    suspend fun dispatchUiEvent(
        plugin: InstalledPlugin,
        moduleId: String,
        eventId: String,
        valueJson: String? = null,
    ): Result<String> = runCatching { session(plugin).dispatchUiEvent(moduleId, eventId, valueJson) }

    suspend fun callUiController(
        plugin: InstalledPlugin,
        moduleId: String,
        controllerId: String,
        interfaceName: String,
        methodName: String,
        argsJson: String,
    ): String = session(plugin).callUiController(moduleId, controllerId, interfaceName, methodName, argsJson)

    suspend fun callUiHandler(
        plugin: InstalledPlugin,
        moduleId: String,
        eventId: String,
        payloadJson: String,
    ): String = session(plugin).callUiHandler(moduleId, eventId, payloadJson)

    suspend fun renderXml(tagName: String, xmlContent: String): String? {
        ensureAllRegistrations()
        for (hook in allHooks("xml_render")) {
            val plugin = pluginsProvider().firstOrNull { it.id == hook.pluginId } ?: continue
            val raw = runCatching {
                session(plugin).renderXmlHook(hook.id, tagName, xmlContent)
            }.getOrNull() ?: continue
            val handled = runCatching {
                Json.parseToJsonElement(raw).jsonObject["handled"]?.jsonPrimitive?.booleanOrNull == true
            }.getOrDefault(false)
            if (handled) return raw
        }
        return null
    }

    suspend fun chatInputDecision(chatId: Long, text: String): ChatInputDecision {
        ensureAllRegistrations()
        var current = text
        val hooks = allHooks("chat_input")
        val result = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "submit_requested")
                    put("eventName", "submit_requested")
                    putJsonObject("eventPayload") {
                        put("chatId", chatId)
                        put("text", current)
                        put("hasAttachments", false)
                        put("attachmentCount", 0)
                        put("isProcessing", false)
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                val decision = parseChatInputResult(raw, current)
                if (decision != null) {
                    when (decision.action) {
                        "block", "consume" -> return@withTimeoutOrNull decision
                        "replace" -> current = decision.text
                    }
                }
            }
            ChatInputDecision("allow", current)
        }
        return result ?: ChatInputDecision("allow", current)
    }

    suspend fun interceptTool(
        toolName: String,
        description: String,
        parameters: Map<String, String>,
    ): ToolInterceptDecision {
        ensureAllRegistrations()
        val hooks = allHooks("tool_lifecycle")
        if (hooks.isEmpty()) return ToolInterceptDecision(false)
        val payload = buildJsonObject {
            put("event", "tool_call_intercept")
            put("eventName", "tool_call_intercept")
            putJsonObject("eventPayload") {
                put("toolName", toolName)
                put("description", description)
                putJsonObject("parameters") {
                    parameters.forEach { (key, value) -> put(key, value) }
                }
            }
        }
        for (hook in hooks) {
            val raw = withTimeoutOrNull(3_000) {
                runCatching { callHook(hook, payload) }.getOrNull()
            } ?: continue
            val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            if (obj.text("action") == "block") {
                val reason = obj.text("reason").orEmpty()
                return ToolInterceptDecision(true, reason.ifBlank { "插件阻止了工具调用" })
            }
        }
        return ToolInterceptDecision(false)
    }

    suspend fun notifyChatMessage(chatId: Long, message: JsonObject) {
        ensureAllRegistrations()
        val payload = buildJsonObject {
            put("event", "message_persisted")
            put("eventName", "message_persisted")
            putJsonObject("eventPayload") {
                put("chatId", chatId)
                put("message", message)
                message.forEach { (key, value) -> if (key != "chatId") put(key, value) }
            }
        }
        allHooks("chat_message").forEach { hook ->
            withTimeoutOrNull(5_000) { runCatching { callHook(hook, payload) } }
        }
    }

    suspend fun transformSystemPrompt(chatId: Long, prompt: String, space: String? = null): String {
        ensureAllRegistrations()
        var current = prompt
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in allHooks("system_prompt_compose")) {
                val payload = buildJsonObject {
                    put("event", "after_compose_system_prompt")
                    put("eventName", "after_compose_system_prompt")
                    putJsonObject("eventPayload") {
                        put("stage", "after_compose_system_prompt")
                        put("chatId", chatId)
                        put("systemPrompt", current)
                        space?.let { put("space", it) }
                    }
                }
                val raw = withTimeoutOrNull(3_000) {
                    runCatching { callHook(hook, payload) }.getOrNull()
                } ?: continue
                val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: continue
                val next = when (element) {
                    is JsonPrimitive -> element.contentOrNull.takeIf { !it.isNullOrBlank() }
                    is JsonObject -> element.text("systemPrompt")
                    else -> null
                }
                if (!next.isNullOrBlank()) current = next
            }
        }
        return current
    }

    suspend fun promptInput(chatId: Long, text: String, source: String): ChatInputDecision? {
        ensureAllRegistrations()
        val hooks = allHooks("prompt_input")
        if (hooks.isEmpty()) return null
        var current = text
        var blocked: ChatInputDecision? = null
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "before_process")
                    put("eventName", "before_process")
                    putJsonObject("eventPayload") {
                        put("stage", "before_process")
                        put("chatId", chatId.toString())
                        put("functionType", "chat")
                        put("promptFunctionType", "chat")
                        put("useEnglish", false)
                        put("rawInput", current)
                        put("processedInput", current)
                        put("source", source)
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: continue
                val result = parsePromptTextResult(element)
                if (result != null) {
                    when (result.first) {
                        "block", "consume" -> {
                            blocked = ChatInputDecision(result.first, current, result.second)
                            return@withTimeoutOrNull
                        }
                        "replace" -> current = result.second ?: current
                    }
                }
            }
        }
        return blocked ?: if (current != text) ChatInputDecision("replace", current) else null
    }

    suspend fun transformPromptHistory(
        chatId: Long,
        history: List<PromptMessage>,
        strategy: String,
    ): List<PromptMessage> {
        ensureAllRegistrations()
        val hooks = allHooks("prompt_history")
        if (hooks.isEmpty()) return history
        var current = history
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "before_prepare_history")
                    put("eventName", "before_prepare_history")
                    putJsonObject("eventPayload") {
                        put("stage", "before_prepare_history")
                        put("chatId", chatId.toString())
                        put("strategy", strategy)
                        put("chatHistory", promptTurns(current))
                        put("preparedHistory", promptTurns(current))
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                val next = parsePromptHistoryResult(raw, current) ?: continue
                current = next
            }
        }
        return current
    }

    suspend fun composeToolPrompt(chatId: Long?, tools: List<Tool>, planFirst: Boolean): String? {
        ensureAllRegistrations()
        val hooks = allHooks("tool_prompt_compose")
        if (hooks.isEmpty()) return null
        var current: String? = null
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "before_compose_tool_prompt")
                    put("eventName", "before_compose_tool_prompt")
                    putJsonObject("eventPayload") {
                        put("stage", "before_compose_tool_prompt")
                        chatId?.let { put("chatId", it.toString()) }
                        put("planFirst", planFirst)
                        if (current != null) put("toolPrompt", current) else put("toolPrompt", "")
                        putJsonArray("availableTools") {
                            tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                })
                            }
                        }
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                parsePromptTextResult(runCatching { Json.parseToJsonElement(raw) }.getOrNull())
                    ?.let { if (it.first == "replace") current = it.second }
            }
        }
        return current
    }

    suspend fun finalizePrompt(chatId: Long?, prompt: String): String {
        ensureAllRegistrations()
        val hooks = allHooks("prompt_finalize")
        if (hooks.isEmpty()) return prompt
        var current = prompt
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "before_finalize_prompt")
                    put("eventName", "before_finalize_prompt")
                    putJsonObject("eventPayload") {
                        put("stage", "before_finalize_prompt")
                        chatId?.let { put("chatId", it.toString()) }
                        put("systemPrompt", current)
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                parsePromptTextResult(runCatching { Json.parseToJsonElement(raw) }.getOrNull())
                    ?.let { if (it.first == "replace" && it.second != null) current = it.second!! }
            }
        }
        return current
    }

    suspend fun notifyChatInput(chatId: Long, text: String, event: String) {
        ensureAllRegistrations()
        val hooks = allHooks("chat_input")
        if (hooks.isEmpty()) return
        val payload = buildJsonObject {
            put("event", event)
            put("eventName", event)
            putJsonObject("eventPayload") {
                put("chatId", chatId)
                put("text", text)
                put("hasAttachments", false)
                put("attachmentCount", 0)
                put("isProcessing", true)
            }
        }
        hooks.forEach { hook ->
            withTimeoutOrNull(5_000) { runCatching { callHook(hook, payload) } }
        }
    }

    suspend fun estimatePromptHistory(
        chatId: Long,
        history: List<PromptMessage>,
    ): List<PromptMessage> {
        ensureAllRegistrations()
        val hooks = allHooks("prompt_estimate_history")
        if (hooks.isEmpty()) return history
        var current = history
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "before_prepare_history")
                    put("eventName", "before_prepare_history")
                    putJsonObject("eventPayload") {
                        put("stage", "before_prepare_history")
                        put("chatId", chatId.toString())
                        put("chatHistory", promptTurns(current))
                        put("preparedHistory", promptTurns(current))
                        put("estimatedTokens", estimateTokens(current))
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                current = parsePromptHistoryResult(raw, current) ?: current
            }
        }
        return current
    }

    suspend fun estimateFinalPrompt(chatId: Long?, prompt: String): String {
        ensureAllRegistrations()
        val hooks = allHooks("prompt_estimate_finalize")
        if (hooks.isEmpty()) return prompt
        var current = prompt
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "before_send_to_model")
                    put("eventName", "before_send_to_model")
                    putJsonObject("eventPayload") {
                        put("stage", "before_send_to_model")
                        chatId?.let { put("chatId", it.toString()) }
                        put("systemPrompt", current)
                        put("estimatedTokens", estimateTokens(current))
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                parsePromptTextResult(runCatching { Json.parseToJsonElement(raw) }.getOrNull())
                    ?.let { if (it.first == "replace" && it.second != null) current = it.second!! }
            }
        }
        return current
    }

    suspend fun generateSummary(
        chatId: Long,
        messages: List<PromptMessage>,
        previousSummary: String,
    ): String? {
        ensureAllRegistrations()
        val hooks = allHooks("summary_generate")
        if (hooks.isEmpty()) return null
        var summary: String? = null
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "before_prepare_summary_prompt")
                    put("eventName", "before_prepare_summary_prompt")
                    putJsonObject("eventPayload") {
                        put("stage", "before_prepare_summary_prompt")
                        put("chatId", chatId.toString())
                        put("functionType", "summary")
                        put("previousSummary", previousSummary)
                        put("chatHistory", promptTurns(messages))
                        put("preparedHistory", promptTurns(messages))
                        put("summaryPrompt", "")
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: continue
                val parsed = when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    is JsonObject -> element.text("summaryResult")
                        ?: element.text("summary")
                        ?: element.text("content")
                        ?: element.text("text")
                    else -> null
                }
                if (!parsed.isNullOrBlank()) summary = parsed
                val after = buildJsonObject {
                    put("event", "after_generate_summary")
                    put("eventName", "after_generate_summary")
                    putJsonObject("eventPayload") {
                        put("stage", "after_generate_summary")
                        put("chatId", chatId.toString())
                        put("previousSummary", previousSummary)
                        put("summaryResult", summary.orEmpty())
                    }
                }
                runCatching { callHook(hook, after) }
            }
        }
        return summary
    }

    private fun estimateTokens(text: String): Int = text.length / 4 + 8

    private fun estimateTokens(messages: List<PromptMessage>): Int =
        messages.sumOf { estimateTokens(it.content) + it.images.size * 256 }

    suspend fun inputMenuToggles(chatId: Long): List<PluginInputToggle> {
        ensureAllRegistrations()
        val result = mutableListOf<PluginInputToggle>()
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in allHooks("input_menu_toggle")) {
                val payload = buildJsonObject {
                    put("event", "create")
                    put("eventName", "create")
                    putJsonObject("eventPayload") {
                        put("action", "create")
                        put("chatId", chatId.toString())
                        put("runtime", "chat")
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                result += parseInputToggles(raw, hook.pluginId)
            }
        }
        return result
    }

    suspend fun toggleInputMenu(
        chatId: Long,
        toggle: PluginInputToggle,
        checked: Boolean,
    ): List<PluginInputToggle> {
        ensureAllRegistrations()
        val hook = allHooks("input_menu_toggle").firstOrNull { it.pluginId == toggle.pluginId } ?: return emptyList()
        val payload = buildJsonObject {
            put("event", "toggle")
            put("eventName", "toggle")
            putJsonObject("eventPayload") {
                put("action", "toggle")
                put("toggleId", toggle.id)
                put("chatId", chatId.toString())
                put("runtime", "chat")
                put("isChecked", checked)
                put("checked", checked)
            }
        }
        val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: return emptyList()
        return parseInputToggles(raw, hook.pluginId)
    }

    private fun parseInputToggles(raw: String, pluginId: String): List<PluginInputToggle> {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["toggles"] as? JsonArray
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id") ?: return@mapNotNull null
            PluginInputToggle(
                id = id,
                pluginId = pluginId,
                title = obj.text("title") ?: id,
                description = obj.text("description").orEmpty(),
                icon = obj.text("icon").orEmpty(),
                checked = (obj["isChecked"] as? JsonPrimitive)?.booleanOrNull == true ||
                    (obj["checked"] as? JsonPrimitive)?.booleanOrNull == true,
                slot = obj.text("slot").orEmpty(),
            )
        }
    }

    suspend fun processMessage(
        chatId: Long,
        text: String,
        stage: String,
        sender: String,
    ): ChatInputDecision? {
        ensureAllRegistrations()
        val hooks = allHooks("message_processing")
        if (hooks.isEmpty()) return null
        var current = text
        var blocked: ChatInputDecision? = null
        withTimeoutOrNull(HOOK_TIMEOUT_MS) {
            for (hook in hooks) {
                val payload = buildJsonObject {
                    put("event", "message_processing")
                    put("eventName", "message_processing")
                    putJsonObject("eventPayload") {
                        put("chatId", chatId.toString())
                        put("stage", stage)
                        put("sender", sender)
                        put("messageContent", current)
                    }
                }
                val raw = runCatching { callHook(hook, payload) }.getOrNull() ?: continue
                val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: continue
                when (element) {
                    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { current = it }
                    is JsonObject -> {
                        val action = element.text("action")
                        val next = element.text("content") ?: element.text("text")
                        if (action == "block" || action == "consume") {
                            blocked = ChatInputDecision(action, current, element.text("message"))
                            return@withTimeoutOrNull
                        }
                        if (next != null) current = next
                    }
                    else -> Unit
                }
            }
        }
        return blocked ?: if (current != text) ChatInputDecision("replace", current) else null
    }

    suspend fun dispatchAppLifecycle(event: String, extras: JsonObject = JsonObject(emptyMap())) {
        ensureAllRegistrations()
        val hooks = allHooks("app_lifecycle")
            .filter { it.event.isBlank() || it.event == event }
        if (hooks.isEmpty()) return
        val payload = buildJsonObject {
            put("event", event)
            put("eventName", event)
            putJsonObject("eventPayload") {
                put("extras", extras)
            }
        }
        hooks.forEach { hook ->
            withTimeoutOrNull(3_000) { runCatching { callHook(hook, payload) } }
        }
    }

    fun notifyAppLifecycle(event: String) {
        mainScope.launch { runCatching { dispatchAppLifecycle(event) } }
    }

    suspend fun notifyChatView(event: String, chatId: Long, title: String) {
        ensureAllRegistrations()
        val hooks = allHooks("chat_view")
        if (hooks.isEmpty()) return
        val payload = buildJsonObject {
            put("event", event)
            put("eventName", event)
            putJsonObject("eventPayload") {
                put("viewId", chatId.toString())
                put("chatId", chatId.toString())
                put("title", title)
            }
        }
        hooks.forEach { hook ->
            withTimeoutOrNull(5_000) { runCatching { callHook(hook, payload) } }
        }
    }

    suspend fun notifyToolLifecycle(
        event: String,
        toolName: String,
        parameters: Map<String, String> = emptyMap(),
        description: String = "",
        success: Boolean? = null,
        resultText: String? = null,
        errorMessage: String? = null,
    ) {
        ensureAllRegistrations()
        val hooks = allHooks("tool_lifecycle")
        if (hooks.isEmpty()) return
        val payload = buildJsonObject {
            put("event", event)
            put("eventName", event)
            putJsonObject("eventPayload") {
                put("toolName", toolName)
                if (description.isNotBlank()) put("description", description)
                putJsonObject("parameters") { parameters.forEach { (key, value) -> put(key, value) } }
                success?.let { put("success", it) }
                resultText?.let { put("resultText", it.take(4000)) }
                errorMessage?.let { put("errorMessage", it.take(4000)) }
            }
        }
        hooks.forEach { hook ->
            withTimeoutOrNull(3_000) { runCatching { callHook(hook, payload) } }
        }
    }

    private fun parsePromptTextResult(element: JsonElement?): Pair<String, String?>? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { "replace" to it }
        is JsonObject -> {
            val action = element.text("action")
            if (action == "block" || action == "consume") {
                action to (element.text("message") ?: element.text("text"))
            } else {
                val next = element.text("processedInput")
                    ?: element.text("systemPrompt")
                    ?: element.text("toolPrompt")
                    ?: element.text("rawInput")
                    ?: element.text("text")
                next?.let { "replace" to it }
            }
        }
        else -> null
    }

    private fun parsePromptHistoryResult(raw: String, current: List<PromptMessage>): List<PromptMessage>? {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["preparedHistory"] as? JsonArray
                ?: element["chatHistory"] as? JsonArray
            else -> null
        } ?: return null
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val role = obj.text("role") ?: obj.text("kind")?.lowercase() ?: return@mapNotNull null
            val content = obj.text("content") ?: return@mapNotNull null
            PromptMessage(role, content)
        }.ifEmpty { current }
    }

    private fun promptTurns(messages: List<PromptMessage>): JsonArray = buildJsonArray {
        messages.forEach { message ->
            add(buildJsonObject {
                put("kind", message.role.uppercase())
                put("role", message.role)
                put("content", message.content)
            })
        }
    }

    suspend fun notifyChatRuntime(
        chatId: Long,
        state: String,
        message: String? = null,
        toolName: String? = null,
        turnToolCalls: Int = 0,
        sessionToolCalls: Int = 0,
        activeConversations: Int = 1,
    ) {
        ensureAllRegistrations()
        val payload = buildJsonObject {
            put("event", "state_changed")
            put("eventName", "state_changed")
            putJsonObject("eventPayload") {
                put("chatId", chatId)
                put("slot", "main")
                put("state", state)
                put("isActive", state !in setOf("idle", "completed", "error"))
                putJsonArray("activeChatIds") { add(JsonPrimitive(chatId)) }
                put("currentTurnToolInvocationCount", turnToolCalls)
                put("activeConversationCount", if (state == "idle") 0 else activeConversations)
                put("currentSessionToolCount", sessionToolCalls)
                put("timestamp", System.currentTimeMillis())
                if (!message.isNullOrBlank()) put("message", message)
                if (!toolName.isNullOrBlank()) put("toolName", toolName)
            }
        }
        allHooks("chat_runtime").forEach { hook ->
            withTimeoutOrNull(5_000) { runCatching { callHook(hook, payload) } }
        }
    }

    suspend fun menuItems(sender: String): List<PluginHookDescriptor> {
        ensureAllRegistrations()
        return allHooks("chat_message_menu_item").filter { item ->
            item.senders.isEmpty() || item.senders.any { it.equals(sender, ignoreCase = true) }
        }
    }

    suspend fun sidebarEntries(): List<PluginHookDescriptor> {
        ensureAllRegistrations()
        return _registrations.value.values
            .flatMap { it.ui }
            .filter { it.type == "navigation" && it.surface.equals("main_sidebar_plugins", ignoreCase = true) }
            .distinctBy { it.pluginId + ":" + it.id }
            .sortedWith(compareBy({ it.order }, { it.pluginName }, { it.id }))
    }

    suspend fun settingsEntries(): List<PluginHookDescriptor> {
        ensureAllRegistrations()
        return _registrations.value.values
            .flatMap { it.ui }
            .filter { it.type == "settings_entry" }
            .sortedWith(compareBy({ it.order }, { it.pluginName }, { it.id }))
    }

    suspend fun slotEntries(surface: String): List<PluginHookDescriptor> {
        ensureAllRegistrations()
        return _registrations.value.values
            .flatMap { it.ui }
            .filter { it.type == "plugin_slot" && it.surface.equals(surface, ignoreCase = true) }
            .sortedWith(compareBy({ it.order }, { it.pluginName }, { it.id }))
    }

    suspend fun invokeNavigationEntry(entry: PluginHookDescriptor): String? {
        ensureAllRegistrations()
        val payload = buildJsonObject {
            put("event", "navigation_entry_action")
            put("eventName", "navigation_entry_action")
            putJsonObject("eventPayload") {
                put("entryId", entry.id)
                put("routeId", entry.route)
                put("surface", entry.surface)
                put("title", entry.title)
                put("description", "")
            }
        }
        return runCatching { callHook(entry, payload) }.getOrNull()
    }

    suspend fun callMenuItem(
        pluginId: String,
        hookId: String,
        payload: JsonObject,
    ): String? {
        ensureAllRegistrations()
        val hook = allHooks("chat_message_menu_item")
            .firstOrNull { it.pluginId == pluginId && it.id == hookId }
            ?: return null
        return runCatching { callHook(hook, payload) }.getOrNull()
    }

    fun registrationFor(pluginId: String): PluginRegistrations? = _registrations.value[pluginId]

    suspend fun readPluginEnv(pluginId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, "plugin_config/$pluginId/env.json")
        val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return@withContext emptyMap()
        root.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }

    suspend fun savePluginEnv(pluginId: String, values: Map<String, String>) = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, "plugin_config/$pluginId/env.json")
        file.parentFile?.mkdirs()
        val root = JsonObject(values.mapValues { (_, value) -> JsonPrimitive(value) })
        file.writeText(root.toString())
    }

    suspend fun pluginEnvKeys(pluginId: String): List<String> = withContext(Dispatchers.IO) {
        val plugin = pluginsProvider().firstOrNull { it.id == pluginId } ?: return@withContext emptyList()
        val dir = File(appContext.filesDir, "plugins/${plugin.directory}")
        if (!dir.isDirectory) return@withContext emptyList()
        val literals = linkedSetOf<String>()
        val callKeys = linkedSetOf<String>()
        val callRegex = Regex(
            """(?:getEnv|getEnvironmentVariable|readEnvironmentVariable|writeEnvironmentVariable|setEnv)\(\s*["']([A-Z][A-Z0-9_]{2,})["']""",
        )
        val literalRegex = Regex("""["']([A-Z][A-Z0-9_]{2,})["']""")
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "js" && it.length() <= 2L * 1024 * 1024 }
            .take(200)
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                callRegex.findAll(text).forEach { match -> callKeys += match.groupValues[1] }
                literalRegex.findAll(text).forEach { match -> literals += match.groupValues[1] }
            }
        val prefix = pluginId.substringAfterLast('.').uppercase().replace('-', '_') + "_"
        val ignored = listOf("_FAILED", "_ERROR", "_UNCAUGHT", "_BAD_JSON", "_TOO_LONG", "_REQUIRED", "_EMPTY", "_NO_RESULT")
        val prefixed = literals.filter { key ->
            key.contains('_') && key.startsWith(prefix) && ignored.none { key.endsWith(it) }
        }
        (prefixed + callKeys).distinct().sorted()
    }

    suspend fun close(pluginId: String) {
        sessions.remove(pluginId)?.close()
        _registrations.update { it - pluginId }
        _errors.update { it - pluginId }
    }

    suspend fun closeAll() {
        val ids = sessions.keys.toList()
        ids.forEach { close(it) }
    }

    private fun session(plugin: InstalledPlugin): PluginSession =
        sessions.computeIfAbsent(plugin.id) {
            PluginSession(
                context = appContext,
                plugin = plugin,
                toolExecutor = ::executeTool,
                terminal = terminal,
                hostActions = hostActions,
                onUiInvalidate = { moduleId -> _uiInvalidations.tryEmit(moduleId) },
                onRegistrations = { id, registrations ->
                    _registrations.update { it + (id to registrations) }
                    _errors.update { it - id }
                },
                onError = { id, message ->
                    _errors.update { it + (id to message) }
                },
            )
        }

    private suspend fun executeTool(name: String, arguments: JsonObject, callerId: String): String {
        val compatName = compatToolName(name)
        if (compatName != null) {
            when (permissionOf("compat:$compatName")) {
                ToolPermission.DENY -> error("工具 $compatName 的权限为拒绝")
                ToolPermission.ASK -> error("工具 $compatName 需要用户确认，插件不能代为调用")
                ToolPermission.ALLOW -> Unit
            }
            return compatTool(compatName, arguments, callerId)
        }
        val registry = tools ?: error("宿主工具表未接入")
        val normalized = name.trim().substringAfter(':').substringBefore('@').trim()
        val candidateNames = listOf(name.trim(), normalized, name.trim().replace(':', '_'))
            .filter { it.isNotBlank() }
            .distinct()
        val direct = candidateNames.firstNotNullOfOrNull { registry.find(it) }
        val ownPrefix = "plugin_" + callerId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + "_"
        val resolved = direct ?: registry.all()
            .filter { it.name.startsWith("plugin_") }
            .firstOrNull { tool ->
                candidateNames.any { candidate -> tool.name.endsWith("_$candidate") }
            }
            ?: error("工具不存在或未启用：$name")
        if (resolved.name.startsWith(ownPrefix)) {
            error("插件内嵌套调用自身工具暂不支持：$name")
        }
        when (permissionOf(resolved.name)) {
            ToolPermission.DENY -> error("工具 ${resolved.name} 的权限为拒绝")
            ToolPermission.ASK -> error("工具 ${resolved.name} 需要用户确认，插件不能代为调用")
            ToolPermission.ALLOW -> Unit
        }
        return resolved.execute(arguments)
    }

    private fun compatToolName(name: String): String? =
        name.trim().takeIf { it in COMPAT_TOOL_NAMES }

    private fun compatRoots(callerId: String): List<File> {
        val plugin = pluginsProvider().firstOrNull { it.id == callerId }
            ?: error("插件未启用或不存在：$callerId")
        return listOf(
            pluginDirectory(plugin),
            File(appContext.filesDir, "plugin_config/$callerId"),
        )
    }

    private fun compatPath(roots: List<File>, raw: String?, allowRoot: Boolean = false): File {
        val path = raw?.trim().orEmpty()
        if (path.isEmpty()) error("路径不能为空")
        val file = PluginPathGuard.resolve(roots, path)
            ?: error("路径不在插件目录或配置目录：$path")
        if (!allowRoot && roots.any { it.canonicalFile == file }) {
            error("不能操作插件根目录：$path")
        }
        return file
    }

    private suspend fun compatTool(name: String, arguments: JsonObject, callerId: String): String =
        withContext(Dispatchers.IO) {
            val roots = compatRoots(callerId)
            when (name) {
                "make_directory" -> {
                    val target = compatPath(roots, arguments.text("path"), allowRoot = true)
                    if (!target.mkdirs() && !target.isDirectory) error("创建目录失败：${target.absolutePath}")
                    "{\"success\":true}"
                }
                "delete_file" -> {
                    val target = compatPath(roots, arguments.text("path"))
                    if (target.isDirectory) {
                        if (!target.deleteRecursively()) error("删除目录失败：${target.absolutePath}")
                    } else if (!target.delete() && target.exists()) {
                        error("删除文件失败：${target.absolutePath}")
                    }
                    "{\"success\":true}"
                }
                "list_files" -> {
                    val target = compatPath(roots, arguments.text("path"), allowRoot = true)
                    if (!target.isDirectory) error("目录不存在：${target.absolutePath}")
                    val children = target.listFiles()?.sortedBy { it.name.lowercase() }
                        ?.take(COMPAT_LIST_LIMIT).orEmpty()
                    buildJsonObject {
                        putJsonArray("entries") {
                            children.forEach { child ->
                                add(buildJsonObject {
                                    put("name", child.name)
                                    put("path", child.absolutePath)
                                    put("isDirectory", child.isDirectory)
                                    put("length", if (child.isFile) child.length() else 0L)
                                })
                            }
                        }
                    }.toString()
                }
                "read_file" -> {
                    val file = compatPath(roots, arguments.text("path"))
                    if (!file.isFile) error("文件不存在：${file.absolutePath}")
                    if (file.length() > COMPAT_READ_BYTES) error("文件超过 4 MB")
                    buildJsonObject {
                        put("path", file.absolutePath)
                        put("content", file.readText())
                    }.toString()
                }
                "write_file" -> {
                    val file = compatPath(roots, arguments.text("path"))
                    val content = arguments.text("content") ?: error("缺少 content 参数")
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    if (bytes.size > COMPAT_WRITE_BYTES) error("写入内容超过 2 MB")
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    "{\"success\":true}"
                }
                "http_request" -> compatHttp(arguments)
                "download_file" -> compatDownload(arguments, roots)
                else -> error("不支持的兼容工具：$name")
            }
        }

    private fun compatHttp(arguments: JsonObject): String {
        val url = arguments.text("url") ?: error("缺少 url 参数")
        if (!RemoteFetch.isPublicHttpUrl(url)) error("仅允许公网 HTTP/HTTPS：$url")
        val method = (arguments.text("method")?.uppercase() ?: "GET")
        if (method !in COMPAT_HTTP_METHODS) error("不支持的 HTTP 方法：$method")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "IRIS-LocalAI-Plugin/1.0")
            (arguments["headers"] as? JsonObject)?.forEach { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let {
                    if (key.isNotBlank()) setRequestProperty(key, it)
                }
            }
        }
        try {
            val body = arguments.text("body")
            if (body != null && method !in setOf("GET", "HEAD")) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (!RemoteFetch.isPublicHttpUrl(connection.url.toString())) error("重定向到非公网地址")
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    output.append(buffer, 0, read)
                    if (output.length > COMPAT_HTTP_BYTES) error("响应超过 1 MB")
                }
                output.toString()
            }.orEmpty()
            if (code !in 200..399) error("HTTP $code: ${text.take(300)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun compatDownload(arguments: JsonObject, roots: List<File>): String {
        val url = arguments.text("url") ?: error("缺少 url 参数")
        if (!RemoteFetch.isPublicHttpUrl(url)) error("仅允许公网 HTTP/HTTPS：$url")
        val target = compatPath(roots, arguments.text("destination") ?: arguments.text("path"))
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        try {
            RemoteFetch.downloadToFile(url, part, COMPAT_DOWNLOAD_BYTES, 120_000)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) error("无法写入文件：${target.absolutePath}")
            }
            return buildJsonObject { put("success", true); put("path", target.absolutePath) }.toString()
        } finally {
            part.delete()
        }
    }

    private suspend fun callHook(hook: PluginHookDescriptor, payload: JsonObject): String? {
        val plugin = pluginsProvider().firstOrNull { it.id == hook.pluginId } ?: return null
        val session = session(plugin)
        session.start()
        return session.callHook(hook.type, hook.id, payload)
    }

    private fun allHooks(type: String): List<PluginHookDescriptor> =
        _registrations.value.values
            .flatMap { it.hooksOf(type) }
            .sortedWith(compareBy({ it.pluginId }, { it.order }, { it.id }))

    private fun parseChatInputResult(raw: String, current: String): ChatInputDecision? {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (element is JsonPrimitive && element.isString) {
            val text = element.contentOrNull.orEmpty()
            return if (text.isBlank()) null else ChatInputDecision("replace", text)
        }
        val obj = element as? JsonObject ?: return null
        val action = obj.text("action") ?: if (obj.containsKey("text")) "replace" else return null
        val text = obj.text("text") ?: current
        return when (action) {
            "block", "consume", "replace", "allow" -> ChatInputDecision(action, text, obj.text("message"))
            else -> null
        }
    }

    private fun pluginDirectory(plugin: InstalledPlugin): File =
        File(appContext.filesDir, "plugins/${plugin.directory}")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val HOOK_TIMEOUT_MS = 10_000L
        const val COMPAT_READ_BYTES = 4L * 1024 * 1024
        const val COMPAT_WRITE_BYTES = 2L * 1024 * 1024
        const val COMPAT_HTTP_BYTES = 1_048_576
        const val COMPAT_DOWNLOAD_BYTES = 128L * 1024 * 1024
        const val COMPAT_LIST_LIMIT = 2_000
        val COMPAT_TOOL_NAMES = setOf(
            "make_directory",
            "delete_file",
            "list_files",
            "read_file",
            "write_file",
            "http_request",
            "download_file",
        )
        val COMPAT_HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")
    }
}

/**
 * 插件路径边界：绝对路径与相对路径都先 canonical 化，再校验是否落在插件目录或配置目录内，
 * 防止 `..`、绝对路径与符号链接逃逸。
 */
internal object PluginPathGuard {
    fun resolve(roots: List<File>, raw: String): File? {
        val normalized = raw.replace('\\', '/').trim()
        if (normalized.isEmpty()) return null
        if (normalized.split('/').any { it == ".." }) return null
        val absolute = normalized.startsWith("/")
        return roots.mapNotNull { root ->
            val base = root.canonicalFile
            val file = if (absolute) {
                File(normalized).canonicalFile
            } else {
                File(base, normalized).canonicalFile
            }
            if (file.path == base.path || file.path.startsWith(base.path + File.separator)) file else null
        }.firstOrNull()
    }
}

private class PluginSession(
    private val context: Context,
    private val plugin: InstalledPlugin,
    private val toolExecutor: suspend (String, JsonObject, String) -> String,
    private val terminal: PluginTerminalHost?,
    private val hostActions: HostActionRegistry?,
    private val onUiInvalidate: (String) -> Unit,
    private val onRegistrations: (String, PluginRegistrations) -> Unit,
    private val onError: (String, String) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pluginDir = File(context.filesDir, "plugins/${plugin.directory}")
    private val configDir = File(context.filesDir, "plugin_config/${plugin.id}").apply { mkdirs() }
    private val startMutex = Mutex()
    private val callMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val progressCallbacks = ConcurrentHashMap<String, (String) -> Unit>()
    private val scriptError = AtomicReference<String?>(null)
    private val moduleErrors = ConcurrentHashMap<String, String>()
    private val ready = CompletableDeferred<Unit>()
    private val registrationsJson = CompletableDeferred<String>()
    private var webView: WebView? = null
    private var started = false
    private var currentRegistrations = PluginRegistrations()

    suspend fun start() {
        if (started) return
        startMutex.withLock {
            if (started) return
            val mainSource = readScript(plugin.main)
            val subSources = plugin.subpackages.associate { it.id to readScript(it.entry) }
            val mainTranspiled = OperitTranspiler.transpile(mainSource)
            val subTranspiled = subSources.mapValues { (_, source) -> OperitTranspiler.transpile(source) }
            val warnings = mainTranspiled.warnings + subTranspiled.values.flatMap { it.warnings }
            val rewrites = mainTranspiled.rewrites + subTranspiled.values.sumOf { it.rewrites }
            if (rewrites > 0 || warnings.isNotEmpty()) {
                Log.d(
                    "PluginRuntime",
                    "${plugin.id} 转译：$rewrites 处调用；提示：${warnings.distinct().joinToString("；")}",
                )
            }
            val subEntries = plugin.subpackages.associate { it.id to it.entry }
            val resources = plugin.resources.mapNotNull { resource ->
                val file = resolve(pluginDir, resource.path)
                if (file != null && (file.isFile || file.isDirectory)) resource.key to file else null
            }.toMap()
            val wasm = plugin.wasmModules.mapNotNull { module ->
                val file = resolve(pluginDir, module.path)
                if (file != null && file.isFile) module.id to file else null
            }.toMap()
            try {
            withContext(Dispatchers.Main.immediate) {
                val view = WebView(context)
                configure(view)
                val bridge = Bridge(
                    context = context,
                    pluginDir = pluginDir,
                    configDir = configDir,
                    pluginId = plugin.id,
                    toolExecutor = toolExecutor,
                    terminal = terminal,
                    hostActions = hostActions,
                    scope = scope,
                    webViewProvider = { webView },
                    onUiInvalidate = onUiInvalidate,
                    resources = resources,
                    wasm = wasm,
                    pending = pending,
                    onIntermediate = { id, value -> progressCallbacks[id]?.invoke(value) },
                    ready = ready,
                    registrations = registrationsJson,
                    scriptError = scriptError,
                    moduleErrors = moduleErrors,
                    onToast = { message ->
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                webView = view
                view.addJavascriptInterface(bridge, "irisHost")
                val pageLoaded = CompletableDeferred<Unit>()
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!pageLoaded.isCompleted) pageLoaded.complete(Unit)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                }
                view.loadDataWithBaseURL(BASE_URL, BOOTSTRAP_HTML, "text/html", "utf-8", null)
                withTimeout(15_000) { pageLoaded.await() }
                view.evaluateJavascript(
                    buildLoadScript(
                        mainTranspiled.source,
                        subTranspiled.mapValues { (_, value) -> value.source },
                        subEntries,
                    ),
                    null,
                )
                withTimeout(15_000) { ready.await() }
                scriptError.get()?.let { message -> Log.w("PluginRuntime", "main 注册失败: $message") }
                val json = withTimeoutOrNull(5_000) { registrationsJson.await() }
                currentRegistrations = if (json != null) {
                    parseRegistrations(json)
                } else {
                    PluginRegistrations()
                }
                started = true
                onRegistrations(plugin.id, currentRegistrations)
                scriptError.get()?.let { message -> onError(plugin.id, "main 注册失败：$message") }
                moduleErrors.forEach { (moduleId, message) ->
                    onError(plugin.id, "子包 $moduleId 加载失败：$message")
                }
                Log.d(
                    "PluginRuntime",
                    "${plugin.id} registrations: ${currentRegistrations.hooks.size} hooks, " +
                        "${currentRegistrations.ui.size} ui, ${currentRegistrations.aiProviders.size} providers",
                )
            }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    webView?.removeJavascriptInterface("irisHost")
                    webView?.destroy()
                    webView = null
                }
                throw error
            }
        }
    }

    suspend fun callTool(
        subpackageId: String,
        toolName: String,
        arguments: JsonObject,
        onProgress: ((String) -> Unit)? = null,
    ): String {
        start()
        val requestId = UUID.randomUUID().toString()
        return call("""
            (function () {
              var id = %REQUEST%;
              var done = false;
              function send(value) {
                if (done) return;
                done = true;
                var output = value === undefined ? "null" : (typeof value === "string" ? value : JSON.stringify(value));
                if (typeof output !== "string") output = "null";
                if (output.length > 64000) output = output.slice(0, 64000) + "\n…（结果过长已截断）";
                irisHost.onResult(id, output);
              }
              globalThis.__irisComplete = send;
              globalThis.__irisToolSessionId = id;
              globalThis.__irisSendIntermediate = function (value) {
                try {
                  irisHost.sendIntermediate(id, typeof value === 'string' ? value : JSON.stringify(value));
                } catch (error) { }
              };
              try {
                var module = (globalThis.__irisModules || {})[%SUB%];
                if (!module || !Object.prototype.hasOwnProperty.call(module, %TOOL%)) {
                  throw new Error("插件未导出工具 " + %TOOL%);
                }
                Promise.resolve(module[%TOOL%](JSON.parse(%ARGS%))).then(send).catch(function (error) {
                  if (!done) { done = true; irisHost.onError(id, String(error && error.stack || error)); }
                });
              } catch (error) {
                if (!done) { done = true; irisHost.onError(id, String(error && error.stack || error)); }
              }
            })();
        """.trimIndent()
            .replace("%REQUEST%", requestId.js())
            .replace("%SUB%", subpackageId.js())
            .replace("%TOOL%", toolName.js())
            .replace("%ARGS%", Json.encodeToString(String.serializer(), Json.encodeToString(JsonObject.serializer(), arguments))), requestId, onProgress)
    }

    suspend fun callProvider(providerId: String, method: String, payload: JsonObject): String {
        start()
        val requestId = UUID.randomUUID().toString()
        return call("""
            (function () {
              var id = %REQUEST%;
              function send(value) {
                var output = value === undefined ? 'null' : (typeof value === 'string' ? value : JSON.stringify(value));
                irisHost.onResult(id, output === undefined ? 'null' : String(output));
              }
              try {
                var provider = (globalThis.__irisProviders || {})[%PROVIDER%];
                if (!provider || typeof provider[%METHOD%] !== 'function') {
                  throw new Error('插件未注册 AI Provider：' + %PROVIDER%);
                }
                Promise.resolve(provider[%METHOD%](JSON.parse(%PAYLOAD%))).then(send).catch(function (error) {
                  irisHost.onError(id, String(error && error.stack || error));
                });
              } catch (error) {
                irisHost.onError(id, String(error && error.stack || error));
              }
            })();
        """.trimIndent()
            .replace("%REQUEST%", requestId.js())
            .replace("%PROVIDER%", providerId.js())
            .replace("%METHOD%", method.js())
            .replace("%PAYLOAD%", Json.encodeToString(String.serializer(), Json.encodeToString(JsonObject.serializer(), payload))), requestId)
    }

    suspend fun callHook(type: String, hookId: String, payload: JsonObject): String {
        start()
        val requestId = UUID.randomUUID().toString()
        return call("""
            (function () {
              var id = %REQUEST%;
              var done = false;
              function send(value) {
                if (done) return;
                done = true;
                var output = value === undefined ? "null" : (typeof value === "string" ? value : JSON.stringify(value));
                if (typeof output !== "string") output = "null";
                irisHost.onResult(id, output);
              }
              globalThis.__irisComplete = send;
              globalThis.__irisToolSessionId = id;
              globalThis.__irisSendIntermediate = function () {};
              try {
                var hooks = (globalThis.__irisHooks || {})[%TYPE%] || {};
                var hook = hooks[%ID%];
                if (typeof hook !== "function") throw new Error("插件未注册 Hook " + %ID%);
                Promise.resolve(hook(JSON.parse(%PAYLOAD%))).then(send).catch(function (error) {
                  if (!done) { done = true; irisHost.onError(id, String(error && error.stack || error)); }
                });
              } catch (error) {
                if (!done) { done = true; irisHost.onError(id, String(error && error.stack || error)); }
              }
            })();
        """.trimIndent()
            .replace("%REQUEST%", requestId.js())
            .replace("%TYPE%", type.js())
            .replace("%ID%", hookId.js())
            .replace("%PAYLOAD%", Json.encodeToString(String.serializer(), Json.encodeToString(JsonObject.serializer(), payload))), requestId)
    }

    private suspend fun call(
        script: String,
        requestId: String,
        onProgress: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.Main.immediate) {
        callMutex.withLock {
            val view = webView ?: error("插件会话尚未启动")
            val result = CompletableDeferred<String>()
            pending[requestId] = result
            if (onProgress != null) progressCallbacks[requestId] = onProgress
            try {
                view.evaluateJavascript(script, null)
                withTimeout(30_000) { result.await() }
            } finally {
                pending.remove(requestId)
                progressCallbacks.remove(requestId)
            }
        }
    }

    suspend fun renderUi(moduleId: String): String {
        start()
        val requestId = UUID.randomUUID().toString()
        return call(
            """
                (function () {
                  var id = %REQUEST%;
                  function send(value) {
                    var output = typeof value === 'string' ? value : JSON.stringify(value);
                    irisHost.onResult(id, output === undefined ? 'null' : String(output));
                  }
                  try {
                    Promise.resolve(__irisRenderUi(%MODULE%)).then(send).catch(function (error) {
                      irisHost.onError(id, String(error && error.stack || error));
                    });
                  } catch (error) {
                    irisHost.onError(id, String(error && error.stack || error));
                  }
                })();
            """.trimIndent()
                .replace("%REQUEST%", requestId.js())
                .replace("%MODULE%", moduleId.js()),
            requestId,
        )
    }

    suspend fun renderXmlHook(hookId: String, tagName: String, xmlContent: String): String {
        start()
        val requestId = UUID.randomUUID().toString()
        return call(
            """
                (function () {
                  var id = %REQUEST%;
                  function send(value) {
                    var output = typeof value === 'string' ? value : JSON.stringify(value);
                    irisHost.onResult(id, output === undefined ? 'null' : String(output));
                  }
                  try {
                    Promise.resolve(__irisRenderXmlHook(%HOOK%, %TAG%, %XML%))
                      .then(send)
                      .catch(function (error) {
                        irisHost.onError(id, String(error && error.stack || error));
                      });
                  } catch (error) {
                    irisHost.onError(id, String(error && error.stack || error));
                  }
                })();
            """.trimIndent()
                .replace("%REQUEST%", requestId.js())
                .replace("%HOOK%", hookId.js())
                .replace("%TAG%", tagName.js())
                .replace("%XML%", xmlContent.js()),
            requestId,
        )
    }

    suspend fun callUiHandler(moduleId: String, eventId: String, payloadJson: String): String {
        start()
        val requestId = UUID.randomUUID().toString()
        return call(
            """
                (function () {
                  var id = %REQUEST%;
                  function send(value) {
                    var output = typeof value === 'string' ? value : JSON.stringify(value);
                    irisHost.onResult(id, output === undefined ? 'null' : String(output));
                  }
                  try {
                    Promise.resolve(__irisCallUiHandler(%EVENT%, %PAYLOAD%))
                      .then(send)
                      .catch(function (error) {
                        irisHost.onError(id, String(error && error.stack || error));
                      });
                  } catch (error) {
                    irisHost.onError(id, String(error && error.stack || error));
                  }
                })();
            """.trimIndent()
                .replace("%REQUEST%", requestId.js())
                .replace("%EVENT%", eventId.js())
                .replace("%PAYLOAD%", payloadJson),
            requestId,
        )
    }

    suspend fun dispatchUiEvent(moduleId: String, eventId: String, valueJson: String?): String {
        start()
        val requestId = UUID.randomUUID().toString()
        return call(
            """
                (function () {
                  var id = %REQUEST%;
                  function send(value) {
                    var output = typeof value === 'string' ? value : JSON.stringify(value);
                    irisHost.onResult(id, output === undefined ? 'null' : String(output));
                  }
                  try {
                    Promise.resolve(__irisDispatchUiEvent(%MODULE%, %EVENT%, %VALUE%))
                      .then(send)
                      .catch(function (error) {
                        irisHost.onError(id, String(error && error.stack || error));
                      });
                  } catch (error) {
                    irisHost.onError(id, String(error && error.stack || error));
                  }
                })();
            """.trimIndent()
                .replace("%REQUEST%", requestId.js())
                .replace("%MODULE%", moduleId.js())
                .replace("%EVENT%", eventId.js())
                .replace("%VALUE%", valueJson ?: "null"),
            requestId,
        )
    }

    suspend fun callUiController(
        moduleId: String,
        controllerId: String,
        interfaceName: String,
        methodName: String,
        argsJson: String,
    ): String {
        start()
        val requestId = UUID.randomUUID().toString()
        val normalizedArgs = runCatching {
            Json.parseToJsonElement(argsJson).toString()
        }.getOrDefault("[]")
        return call(
            """
                (function () {
                  var id = %REQUEST%;
                  function send(value) {
                    var output = typeof value === 'string' ? value : JSON.stringify(value);
                    irisHost.onResult(id, output === undefined ? '' : String(output));
                  }
                  try {
                    Promise.resolve(__irisUiCall(%CONTROLLER%, %INTERFACE%, %METHOD%, %ARGS%))
                      .then(send)
                      .catch(function (error) {
                        irisHost.onError(id, String(error && error.stack || error));
                      });
                  } catch (error) {
                    irisHost.onError(id, String(error && error.stack || error));
                  }
                })();
            """.trimIndent()
                .replace("%REQUEST%", requestId.js())
                .replace("%CONTROLLER%", controllerId.js())
                .replace("%INTERFACE%", interfaceName.js())
                .replace("%METHOD%", methodName.js())
                .replace("%ARGS%", normalizedArgs),
            requestId,
        )
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        started = false
        webView?.let { view ->
            view.removeJavascriptInterface("irisHost")
            view.stopLoading()
            view.destroy()
        }
        webView = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private fun readScript(path: String): String {
        if (path.isBlank()) return ""
        val file = resolve(pluginDir, path) ?: return ""
        if (!file.isFile || file.length() > MAX_SCRIPT_BYTES) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    private fun buildLoadScript(
        mainSource: String,
        subSources: Map<String, String>,
        subEntries: Map<String, String>,
    ): String {
        val subJson = buildJsonObject {
            subSources.forEach { (id, source) -> put(id, source) }
        }.toString()
        val entryJson = buildJsonObject {
            subEntries.forEach { (id, entry) -> put(id, entry) }
        }.toString()
        return """
            (function () {
              var subSources = $subJson;
              var subEntries = $entryJson;
              var mainSource = ${mainSource.js()};
              var mainPath = ${plugin.main.js()};
              var cache = {};
              function dirname(path) {
                var index = String(path).lastIndexOf('/');
                return index < 0 ? '' : String(path).slice(0, index);
              }
              function normalize(base, request) {
                var value = String(request || '');
                if (value.charAt(0) !== '/') value = (base ? base + '/' : '') + value;
                else value = value.slice(1);
                var parts = value.split('/');
                var output = [];
                for (var i = 0; i < parts.length; i++) {
                  if (!parts[i] || parts[i] === '.') continue;
                  if (parts[i] === '..') {
                    if (!output.length) throw new Error('require 路径越出插件目录：' + request);
                    output.pop();
                  } else {
                    output.push(parts[i]);
                  }
                }
                var result = output.join('/');
                if (irisHost.exists(result)) return result;
                if (irisHost.exists(result + '.js')) return result + '.js';
                if (irisHost.exists(result + '/index.js')) return result + '/index.js';
                return result + '.js';
              }
              function loadModule(path) {
                if (cache[path]) return cache[path].exports;
                var source = irisHost.readText(path);
                var module = { exports: {} };
                cache[path] = module;
                try {
                  var fn = new Function('exports', 'module', 'require', '__filename', '__dirname', source);
                  fn(module.exports, module, function (request) { return loadModule(normalize(dirname(path), request)); }, path, dirname(path));
                } catch (error) {
                  delete cache[path];
                  throw error;
                }
                return module.exports;
              }
              globalThis.__irisLoadModule = loadModule;
              globalThis.__irisNormalize = normalize;
              globalThis.__irisMainPath = mainPath;
              globalThis.__irisModules = {};
              Object.keys(subSources).forEach(function (id) {
                try {
                  var module = { exports: {} };
                  var entry = subEntries[id] || '';
                  var fn = new Function('exports', 'module', 'require', '__filename', '__dirname', subSources[id]);
                  fn(module.exports, module, function (request) { return loadModule(normalize(dirname(entry), request)); }, entry, dirname(entry));
                  globalThis.__irisModules[id] = module.exports;
                } catch (error) {
                  irisHost.moduleError(id, String(error && error.stack || error));
                }
              });
              try {
                if (mainSource) {
                  var module = { exports: {} };
                  var fn = new Function('exports', 'module', 'require', '__filename', '__dirname', mainSource);
                  fn(module.exports, module, function (request) { return loadModule(normalize(dirname(mainPath), request)); }, mainPath, dirname(mainPath));
                  globalThis.__irisMain = module.exports;
                  if (typeof module.exports.registerToolPkg === 'function') module.exports.registerToolPkg();
                }
              } catch (error) {
                irisHost.scriptError(String(error && error.stack || error));
              }
              try { irisHost.onRegistrations(JSON.stringify(globalThis.__irisRegistrations || [])); }
              catch (error) { irisHost.scriptError(String(error && error.stack || error)); }
              irisHost.scriptReady();
            })();
        """.trimIndent()
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun parseRegistrations(json: String): PluginRegistrations {        val array = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return PluginRegistrations()
        val hooks = mutableListOf<PluginHookDescriptor>()
        val ui = mutableListOf<PluginHookDescriptor>()
        val providers = mutableListOf<String>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val type = obj.text("type") ?: return@forEach
            val id = obj.text("id") ?: return@forEach
            val descriptor = PluginHookDescriptor(
                pluginId = plugin.id,
                pluginName = plugin.name,
                type = type,
                id = id,
                title = localized(obj["title"]),
                senders = (obj["senders"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                order = (obj["order"] as? JsonPrimitive)?.intOrNull ?: 0,
                event = obj.text("event").orEmpty(),
                screen = obj.text("screen").orEmpty(),
                route = obj.text("route").orEmpty(),
                surface = obj.text("surface").orEmpty(),
            )
            when (type) {
                "toolbox_ui", "ui_route", "navigation", "desktop_widget", "settings_entry", "plugin_slot" -> ui += descriptor
                "ai_provider" -> providers += id
                else -> hooks += descriptor
            }
        }
        return PluginRegistrations(
            hooks = hooks,
            ui = ui,
            aiProviders = providers,
            pluginId = plugin.id,
            pluginName = plugin.name,
        )
    }

    private fun localized(element: JsonElement?): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonObject -> element.text("zh") ?: element.text("en") ?: element.text("default")
            ?: element.values.firstOrNull { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull.orEmpty()
        else -> ""
    }

    private fun resolve(base: File, relative: String): File? {
        val normalized = relative.replace('\\', '/').trimStart('/')
        if (normalized.split('/').any { it == ".." }) return null
        val baseCanonical = base.canonicalFile
        val file = File(baseCanonical, normalized).canonicalFile
        return if (file.path == baseCanonical.path || file.path.startsWith(baseCanonical.path + File.separator)) file else null
    }

    private fun configure(webView: WebView) {
        @SuppressLint("SetJavaScriptEnabled")
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        webView.setBackgroundColor(0)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
    }

    private class Bridge(
        private val context: Context,
        private val pluginDir: File,
        private val configDir: File,
        private val pluginId: String,
        private val toolExecutor: suspend (String, JsonObject, String) -> String,
        private val terminal: PluginTerminalHost?,
        private val hostActions: HostActionRegistry?,
        private val scope: CoroutineScope,
        private val webViewProvider: () -> WebView?,
        private val onToast: (String) -> Unit,
        private val onUiInvalidate: (String) -> Unit,
        private val resources: Map<String, File>,
        private val wasm: Map<String, File>,
        private val pending: ConcurrentHashMap<String, CompletableDeferred<String>>,
        private val onIntermediate: (String, String) -> Unit,
        private val ready: CompletableDeferred<Unit>,
        private val registrations: CompletableDeferred<String>,
        private val scriptError: AtomicReference<String?>,
        private val moduleErrors: ConcurrentHashMap<String, String>,
    ) {
        private var dexLoader: ClassLoader? = null
        private val loadedCodePaths = mutableListOf<String>()
        private val javaHandles = ConcurrentHashMap<String, Any>()

        @JavascriptInterface
        fun loadDex(path: String): String {
            val file = requirePluginDirFile(path, "dex")
            if (file.length() > 16 * 1024 * 1024) throw IllegalArgumentException("dex 超过 16 MB")
            installDex(file.readBytes())
            loadedCodePaths += "dex:${file.absolutePath}"
            return "true"
        }

        @JavascriptInterface
        fun loadJar(path: String): String {
            val file = requirePluginDirFile(path, "jar")
            if (file.length() > 32 * 1024 * 1024) throw IllegalArgumentException("jar 超过 32 MB")
            val bytes = ZipFile(file).use { zip ->
                val entry = zip.getEntry("classes.dex")
                    ?: throw IllegalArgumentException("jar 内缺少 classes.dex")
                if (entry.size > 16 * 1024 * 1024) throw IllegalArgumentException("classes.dex 超过 16 MB")
                zip.getInputStream(entry).use { it.readBytes() }
            }
            installDex(bytes)
            loadedCodePaths += "jar:${file.absolutePath}"
            return "true"
        }

        @JavascriptInterface
        fun javaClassExists(className: String): Boolean {
            val name = className.trim()
            if (name.isEmpty()) return false
            val loader = dexLoader ?: context.classLoader
            return runCatching { Class.forName(name, false, loader) }.isSuccess
        }

        @JavascriptInterface
        fun listLoadedCodePaths(): String = buildJsonArray {
            loadedCodePaths.forEach { add(JsonPrimitive(it)) }
        }.toString()

        private fun requirePluginDirFile(path: String, kind: String): File {
            val file = File(path).canonicalFile
            val root = pluginDir.canonicalFile
            if (!file.path.startsWith(root.path + File.separator)) {
                throw IllegalArgumentException("$kind 必须位于插件目录内")
            }
            if (!file.isFile) throw IllegalArgumentException("$kind 文件不存在：$path")
            return file
        }

        private fun installDex(bytes: ByteArray) {
            dexLoader = dalvik.system.InMemoryDexClassLoader(
                java.nio.ByteBuffer.wrap(bytes),
                dexLoader ?: context.classLoader,
            )
        }

        @JavascriptInterface
        fun invokeJavaStatic(className: String, methodName: String, argsJson: String): String {
            val loader = dexLoader ?: throw IllegalStateException("dex 尚未加载")
            val args = runCatching {
                Json.parseToJsonElement(argsJson).jsonArray
            }.getOrElse { JsonArray(emptyList()) }
            val type = loader.loadClass(className)
            val methods = type.methods.filter { method ->
                method.name == methodName &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == args.size
            }
            var lastError: Throwable? = null
            for (method in methods) {
                val converted = args.mapIndexed { index, element ->
                    convertJavaArgument(element, method.parameterTypes[index])
                }
                if (converted.any { it === Unconvertible }) continue
                try {
                    val result = runBlocking(Dispatchers.Main) {
                        method.invoke(null, *converted.toTypedArray())
                    }
                    return when (result) {
                        null, Unit -> "null"
                        is String -> result
                        else -> result.toString()
                    }
                } catch (error: Throwable) {
                    lastError = error.cause ?: error
                }
            }
            throw IllegalStateException(
                "调用 $className.$methodName 失败：${lastError?.message ?: "没有匹配的静态方法"}",
                lastError,
            )
        }

        @JavascriptInterface
        fun javaNewInstance(className: String, argsJson: String): String {
            val loader = dexLoader ?: context.classLoader
            val type = loader.loadClass(className)
            val args = runCatching {
                Json.parseToJsonElement(argsJson).jsonArray
            }.getOrElse { JsonArray(emptyList()) }
            var lastError: Throwable? = null
            for (constructor in type.constructors.filter { it.parameterCount == args.size }) {
                val converted = args.mapIndexed { index, element ->
                    convertJavaArgument(element, constructor.parameterTypes[index])
                }
                if (converted.any { it === Unconvertible }) continue
                try {
                    val value = runBlocking(Dispatchers.Main) {
                        constructor.newInstance(*converted.toTypedArray())
                    }
                    return javaResultJson(value)
                } catch (error: Throwable) {
                    lastError = error.cause ?: error
                }
            }
            throw IllegalStateException(
                "构造 $className 失败：${lastError?.message ?: "没有匹配的构造方法"}",
                lastError,
            )
        }

        @JavascriptInterface
        fun javaCallInstance(handle: String, methodName: String, argsJson: String): String {
            val target = javaHandles[handle] ?: throw IllegalArgumentException("Java 实例句柄不存在")
            val args = runCatching {
                Json.parseToJsonElement(argsJson).jsonArray
            }.getOrElse { JsonArray(emptyList()) }
            var lastError: Throwable? = null
            for (method in target.javaClass.methods.filter { it.name == methodName && it.parameterCount == args.size }) {
                val converted = args.mapIndexed { index, element ->
                    convertJavaArgument(element, method.parameterTypes[index])
                }
                if (converted.any { it === Unconvertible }) continue
                try {
                    val value = runBlocking(Dispatchers.Main) {
                        method.invoke(target, *converted.toTypedArray())
                    }
                    return javaResultJson(value)
                } catch (error: Throwable) {
                    lastError = error.cause ?: error
                }
            }
            throw IllegalStateException(
                "调用 ${target.javaClass.name}.$methodName 失败：${lastError?.message ?: "没有匹配的实例方法"}",
                lastError,
            )
        }

        @JavascriptInterface
        fun javaHasInstanceMethod(handle: String, methodName: String): Boolean {
            val target = javaHandles[handle] ?: return false
            return target.javaClass.methods.any { it.name == methodName }
        }

        @JavascriptInterface
        fun javaGetInstanceField(handle: String, fieldName: String): String {
            val target = javaHandles[handle] ?: throw IllegalArgumentException("Java 实例句柄不存在")
            val field = findJavaField(target.javaClass, fieldName)
                ?: throw IllegalArgumentException("字段不存在：$fieldName")
            return javaResultJson(field.get(target))
        }

        @JavascriptInterface
        fun javaSetInstanceField(handle: String, fieldName: String, valueJson: String): String {
            val target = javaHandles[handle] ?: throw IllegalArgumentException("Java 实例句柄不存在")
            val field = findJavaField(target.javaClass, fieldName)
                ?: throw IllegalArgumentException("字段不存在：$fieldName")
            val element = runCatching { Json.parseToJsonElement(valueJson) }.getOrNull() ?: JsonNull
            val converted = convertJavaArgument(element, field.type)
            if (converted === Unconvertible) throw IllegalArgumentException("字段值类型不匹配：$fieldName")
            field.set(target, converted)
            return """{"success":true}"""
        }

        private fun findJavaField(type: Class<*>, name: String): java.lang.reflect.Field? {
            var current: Class<*>? = type
            while (current != null) {
                val field = runCatching { current.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return field
                }
                current = current.superclass
            }
            return null
        }

        private fun javaResultJson(value: Any?): String = buildJsonObject {
            put("success", true)
            when (value) {
                null, Unit -> put("data", JsonNull)
                is String -> put("data", value)
                is Number -> put("data", JsonPrimitive(value))
                is Boolean -> put("data", value)
                is Char -> put("data", value.toString())
                else -> {
                    val handle = UUID.randomUUID().toString()
                    javaHandles[handle] = value
                    putJsonObject("data") {
                        put("__irisHandle", handle)
                        put("__irisClass", value.javaClass.name)
                    }
                }
            }
        }.toString()

        private fun convertJavaArgument(element: JsonElement, type: Class<*>): Any? {
            if (element is JsonObject) {
                element["__irisHandle"]?.jsonPrimitive?.contentOrNull?.let { handle ->
                    val value = javaHandles[handle] ?: return Unconvertible
                    return if (type == Any::class.java || type.isInstance(value)) value else Unconvertible
                }
            }
            if (element is JsonObject && element["__irisContext"]?.jsonPrimitive?.booleanOrNull == true) {
                return if (type.isAssignableFrom(context.javaClass)) context else Unconvertible
            }
            if (element is JsonPrimitive && element.contentOrNull == null) {
                return if (type.isPrimitive) Unconvertible else null
            }
            val primitive = element as? JsonPrimitive ?: return Unconvertible
            return when {
                type == String::class.java -> primitive.contentOrNull
                type == Int::class.javaPrimitiveType || type == Integer::class.java -> primitive.intOrNull
                type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> primitive.longOrNull
                type == Float::class.javaPrimitiveType || type == java.lang.Float::class.java -> primitive.floatOrNull
                type == Double::class.javaPrimitiveType || type == java.lang.Double::class.java -> primitive.doubleOrNull
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> primitive.booleanOrNull
                else -> primitive.contentOrNull
            } ?: Unconvertible
        }

        private object Unconvertible

        @JavascriptInterface
        fun sendIntermediate(id: String, value: String) {
            onIntermediate(id, value.take(4000))
        }

        @JavascriptInterface
        fun callTool(toolName: String, argsJson: String, callbackId: String) {
            startToolCall(toolName, argsJson, callbackId, null)
        }

        @JavascriptInterface
        fun callToolStreaming(
            toolName: String,
            argsJson: String,
            callbackId: String,
            intermediateId: String,
        ) {
            startToolCall(toolName, argsJson, callbackId, intermediateId)
        }

        private fun startToolCall(
            toolName: String,
            argsJson: String,
            callbackId: String,
            intermediateId: String?,
        ) {
            scope.launch {
                if (intermediateId != null) {
                    emitIntermediate(intermediateId, "{\"status\":\"started\",\"tool\":${toolName.json()}}")
                }
                val result = runCatching {
                    val element = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
                    val arguments = when {
                        element is JsonObject -> element
                        element is JsonPrimitive && element.isString && toolName == "plugin_dev" ->
                            buildJsonObject { put("op", element.content) }
                        else -> JsonObject(emptyMap())
                    }
                    toolExecutor(toolName, arguments, pluginId)
                }
                val text = result.fold(
                    onSuccess = { it },
                    onFailure = { it.message ?: "工具执行失败" },
                ).take(100_000)
                val isError = result.isFailure
                withContext(Dispatchers.Main) {
                    webViewProvider()?.evaluateJavascript(
                        "window.__irisToolResult(${callbackId.json()}, ${text.json()}, $isError)",
                        null,
                    )
                }
            }
        }

        @JavascriptInterface
        fun toolIntermediate(intermediateId: String, value: String) {
            Handler(Looper.getMainLooper()).post {
                webViewProvider()?.evaluateJavascript(
                    "window[${intermediateId.json()}](${value.json()})",
                    null,
                )
            }
        }

        private suspend fun emitIntermediate(intermediateId: String, value: String) {
            withContext(Dispatchers.Main) {
                webViewProvider()?.evaluateJavascript(
                    "window[${intermediateId.json()}](${value.json()})",
                    null,
                )
            }
        }

        @JavascriptInterface
        fun getEnv(key: String): String {
            val file = File(configDir, "env.json")
            val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                ?: return ""
            return (root[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }

        @JavascriptInterface
        fun setEnv(key: String, value: String): Boolean {
            val file = File(configDir, "env.json")
            val values = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                ?.toMutableMap() ?: LinkedHashMap()
            values[key] = JsonPrimitive(value)
            file.parentFile?.mkdirs()
            file.writeText(JsonObject(values).toString())
            return true
        }

        @JavascriptInterface
        fun getPref(name: String, key: String, defaultJson: String): String {
            val root = readPrefs(name)
            return root[key]?.toString() ?: defaultJson
        }

        @JavascriptInterface
        fun setPref(name: String, key: String, valueJson: String): Boolean {
            val values = readPrefs(name).toMutableMap()
            values[key] = runCatching { Json.parseToJsonElement(valueJson) }.getOrNull() ?: JsonPrimitive("")
            writePrefs(name, JsonObject(values))
            return true
        }

        private fun readPrefs(name: String): JsonObject {
            val file = prefsFile(name)
            return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
        }

        private fun writePrefs(name: String, values: JsonObject) {
            val file = prefsFile(name)
            file.parentFile?.mkdirs()
            file.writeText(values.toString())
        }

        private fun prefsFile(name: String): File {
            val safe = name.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")
            return File(configDir, "prefs_${safe.ifBlank { "default" }}.json")
        }

        @JavascriptInterface
        fun toast(message: String) {
            onToast(message.take(500))
        }

        @JavascriptInterface
        fun uiInvalidate(moduleId: String) {
            if (moduleId.isNotBlank()) onUiInvalidate(moduleId)
        }

        @JavascriptInterface
        fun terminalCreate(name: String): String {
            val host = terminal ?: error("Linux 终端未接入")
            return host.create("${pluginId}:$name")
        }

        @JavascriptInterface
        fun terminalExec(sessionId: String, command: String, timeoutMs: Int): String {
            val host = terminal ?: error("Linux 终端未接入")
            return host.exec(sessionId, command, timeoutMs)
        }

        @JavascriptInterface
        fun unzipLinux(archivePath: String, destinationPath: String): String {
            val host = terminal ?: error("Linux 终端未接入")
            return host.unzip(archivePath, destinationPath)
        }

        @JavascriptInterface
        fun terminalExecStreaming(sessionId: String, command: String, timeoutMs: Int): String {
            val host = terminal ?: error("Linux 终端未接入")
            return host.execStreaming(sessionId, command, timeoutMs)
        }

        @JavascriptInterface
        fun onResult(id: String, result: String) {
            pending.remove(id)?.complete(result)
        }

        @JavascriptInterface
        fun onError(id: String, message: String) {
            pending.remove(id)?.completeExceptionally(IOException(message.take(4000)))
        }

        @JavascriptInterface
        fun scriptError(message: String) {
            scriptError.compareAndSet(null, message.take(4000))
        }

        @JavascriptInterface
        fun moduleError(id: String, message: String) {
            moduleErrors[id] = message.take(2000)
        }

        @JavascriptInterface
        fun onRegistrations(json: String) {
            if (!registrations.isCompleted) registrations.complete(json)
        }

        @JavascriptInterface
        fun scriptReady() {
            if (!ready.isCompleted) ready.complete(Unit)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d("PluginRuntime", message.take(2000))
        }

        @JavascriptInterface
        fun readText(path: String): String {
            val file = resolve(path) ?: throw SecurityException("路径不在插件目录")
            if (file.length() > MAX_FILE_BYTES) throw IOException("文件超过 2 MB")
            return file.readText()
        }

        @JavascriptInterface
        fun writeText(path: String, content: String): String {
            val file = resolve(path) ?: throw SecurityException("路径不在插件目录")
            if (file.name == "iris-plugin.json" && file.parentFile == pluginDir) {
                throw SecurityException("插件不能改写自身元数据")
            }
            if (content.length > MAX_FILE_BYTES) throw IOException("写入内容超过 2 MB")
            file.parentFile?.mkdirs()
            file.writeText(content)
            return file.absolutePath
        }

        @JavascriptInterface
        fun exists(path: String): Boolean = resolve(path)?.exists() == true

        @JavascriptInterface
        fun fileInfo(path: String): String {
            val file = resolve(path) ?: return """{"error":"路径不在插件目录"}"""
            return buildJsonObject {
                put("exists", file.exists())
                put("isFile", file.isFile)
                put("isDirectory", file.isDirectory)
                put("name", file.name)
                put("absolutePath", file.absolutePath)
                put("length", if (file.isFile) file.length() else 0L)
                put("lastModified", file.lastModified())
                put("parent", file.parent)
            }.toString()
        }

        @JavascriptInterface
        fun listFiles(path: String): String {
            val file = resolve(path) ?: return "[]"
            val children = file.listFiles().orEmpty()
            return buildJsonArray {
                children.forEach { child ->
                    add(buildJsonObject {
                        put("name", child.name)
                        put("absolutePath", child.absolutePath)
                        put("isDirectory", child.isDirectory)
                        put("length", if (child.isFile) child.length() else 0L)
                    })
                }
            }.toString()
        }

        @JavascriptInterface
        fun deletePath(path: String, recursive: Boolean): Boolean {
            val file = resolve(path) ?: return false
            if (file == pluginDir || file == configDir) return false
            return if (recursive) file.deleteRecursively() else file.delete()
        }

        @JavascriptInterface
        fun mkdirs(path: String): Boolean = resolve(path)?.mkdirs() == true

        @JavascriptInterface
        fun readBase64(path: String): String {
            val file = resolve(path) ?: throw SecurityException("路径不在插件目录")
            if (file.length() > MAX_BINARY_BYTES) throw IOException("文件超过 16 MB")
            return Base64.getEncoder().encodeToString(file.readBytes())
        }

        @JavascriptInterface
        fun writeBase64(path: String, base64: String): String {
            val file = resolve(path) ?: throw SecurityException("路径不在插件目录")
            val bytes = Base64.getDecoder().decode(base64)
            if (bytes.size > MAX_BINARY_BYTES) throw IOException("文件超过 16 MB")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return file.absolutePath
        }

        @JavascriptInterface
        fun digestHex(algorithm: String, text: String): String =
            MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        @JavascriptInterface
        fun readWasmBase64(moduleId: String): String {
            val file = wasm[moduleId] ?: throw IllegalArgumentException("WASM 模块不存在：$moduleId")
            if (file.length() > MAX_WASM_BYTES) throw IOException("WASM 模块超过 32 MB")
            return Base64.getEncoder().encodeToString(file.readBytes())
        }

        @JavascriptInterface
        fun downloadFile(url: String, path: String): String {
            val target = File(path).canonicalFile
            val protocol = runCatching { java.net.URL(url).protocol.lowercase() }.getOrDefault("")
            if (protocol != "http" && protocol != "https") throw IllegalArgumentException("只支持 HTTP/HTTPS 下载")
            target.parentFile?.mkdirs()
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "IRIS-LocalAI-Plugin/1.0")
            }
            try {
                val code = connection.responseCode
                if (code < 200 || code >= 400) throw IllegalStateException("下载失败 HTTP $code")
                val limit = 128L * 1024 * 1024
                val temp = File(target.parentFile, target.name + ".part")
                var total = 0L
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > limit) throw IllegalStateException("下载文件超过 128 MB")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (total == 0L) throw IllegalStateException("下载内容为空")
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return target.absolutePath
            } finally {
                connection.disconnect()
            }
        }

        @JavascriptInterface
        fun readResource(key: String): String {
            val file = resources[key] ?: throw IllegalArgumentException("资源不存在：$key")
            return file.absolutePath
        }

        @JavascriptInterface
        fun configDir(): String = configDir.absolutePath

        @JavascriptInterface
        fun hostAction(id: String, argsJson: String): String {
            val registry = hostActions ?: return """{"ok":false,"error":"宿主动作未接入"}"""
            val args = runCatching {
                Json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
            return runBlocking { registry.invoke(id.trim(), args) }.toString()
        }

        @JavascriptInterface
        fun sleep(milliseconds: Int) {
            Thread.sleep(milliseconds.coerceIn(0, 5000).toLong())
        }

        @JavascriptInterface
        fun httpRequest(url: String, method: String, headersJson: String, body: String): String {
            if (!RemoteFetch.isPublicHttpUrl(url) && !isLoopbackHttp(url)) {
                return """{"ok":false,"status":0,"error":${IOResultText("仅允许公网 HTTP/HTTPS 或本机回环地址")}}"""
            }
            val loopbackPort = if (isLoopbackHttp(url)) {
                runCatching { java.net.URI(url).port }.getOrNull()?.takeIf { it > 0 }
            } else null
            fun perform(): String {
                val normalizedMethod = method.trim().uppercase().takeIf { it in ALLOWED_METHODS } ?: "GET"
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = normalizedMethod
                    connectTimeout = 20_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
                }
                runCatching {
                    Json.parseToJsonElement(headersJson).jsonObject.forEach { (key, value) ->
                        (value as? JsonPrimitive)?.contentOrNull?.let {
                            if (key.isNotBlank()) connection.setRequestProperty(key, it)
                        }
                    }
                }
                try {
                    if (body.isNotEmpty() && normalizedMethod !in setOf("GET", "HEAD")) {
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = connection.responseCode
                    if (!RemoteFetch.isPublicHttpUrl(connection.url.toString()) &&
                        !isLoopbackHttp(connection.url.toString())
                    ) {
                        throw IOException("重定向到非公网或非本机地址")
                    }
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val responseBody = stream?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(16 * 1024)
                        val output = StringBuilder()
                        while (output.length <= MAX_HTTP_BYTES) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            output.append(buffer, 0, read)
                        }
                        output.toString().take(MAX_HTTP_BYTES.toInt())
                    }.orEmpty()
                    return """{"ok":${code in 200..299},"status":$code,"url":${connection.url.toString().json()},"body":${responseBody.json()}}"""
                } finally {
                    connection.disconnect()
                }
            }
            return try {
                perform()
            } catch (error: Throwable) {
                android.util.Log.d("PluginLoopback", "direct failed $url: ${error.message}")
                val bridged = loopbackPort != null && terminal?.ensureLoopbackBridge(loopbackPort) == true
                if (bridged) {
                    runCatching { perform() }.getOrElse { retryError ->
                        android.util.Log.e("PluginLoopback", "bridge retry failed $url: ${retryError.message}")
                        """{"ok":false,"status":0,"error":${IOResultText(retryError.message.orEmpty())}}"""
                    }
                } else {
                    """{"ok":false,"status":0,"error":${IOResultText(error.message.orEmpty())}}"""
                }
            }
        }

        private fun IOResultText(value: String): String = value.json()

        private fun isLoopbackHttp(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
            return uri.host?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]")
        }

        private fun resolve(path: String): File? =
            PluginPathGuard.resolve(listOf(pluginDir, configDir), path)

        private fun String.json(): String = Json.encodeToString(String.serializer(), this)
    }

    companion object {
        private const val BASE_URL = "https://iris.local/plugin/"
        private const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val MAX_BINARY_BYTES = 16L * 1024 * 1024
        private const val MAX_WASM_BYTES = 32L * 1024 * 1024
        private const val MAX_HTTP_BYTES = 1L * 1024 * 1024
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")

        val BOOTSTRAP_HTML = """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <script>
            globalThis.__irisRegistrations = [];
            globalThis.__irisUiScreens = {};
            globalThis.__irisUiStates = {};
            globalThis.__irisUiHandlers = {};
            globalThis.__irisUiControllers = {};
            globalThis.__irisUiHandlerSeq = 0;
            globalThis.__irisHooks = {};
            globalThis.__irisProviders = {};
            globalThis.__irisIpc = {};
            globalThis.__irisComplete = function () {};
            globalThis.__irisToolPending = {};
            globalThis.__irisToolResult = function (id, result, isError) {
              var entry = globalThis.__irisToolPending[id];
              if (!entry) return;
              delete globalThis.__irisToolPending[id];
              if (isError) {
                entry.reject(new Error(String(result || '工具执行失败')));
                return;
              }
              var data = result;
              try { data = JSON.parse(result); } catch (error) { }
              entry.resolve(data);
            };
            function __irisToolArgs(args) {
              var list = Array.prototype.slice.call(args);
              var name = '';
              var params = {};
              if (list.length >= 2 && typeof list[0] === 'string' &&
                  list[1] && typeof list[1] === 'object' && !Array.isArray(list[1])) {
                name = list[0];
                params = list[1];
              } else if (list.length === 1 && list[0] && typeof list[0] === 'object' && !Array.isArray(list[0])) {
                name = list[0].name || '';
                params = list[0].params || {};
              } else if (list.length >= 3) {
                name = list[1];
                params = list[2] || {};
              } else {
                name = list[0];
                params = list[1] || {};
              }
              return { name: String(name || ''), params: params || {} };
            }
            function __irisCallTool() {
              var list = Array.prototype.slice.call(arguments);
              var options = null;
              if (list.length && list[list.length - 1] && typeof list[list.length - 1] === 'object' &&
                  typeof list[list.length - 1].onIntermediateResult === 'function') {
                options = list.pop();
              }
              var parsed = __irisToolArgs(list);
              if (!parsed.name) return Promise.reject(new Error('toolCall 缺少工具名'));
              var id = 'tool-' + Date.now() + '-' + Math.random().toString(16).slice(2);
              var intermediateId = null;
              if (options) {
                intermediateId = 'tool-intermediate-' + Date.now() + '-' + Math.random().toString(16).slice(2);
                window[intermediateId] = function (value) {
                  try {
                    options.onIntermediateResult(typeof value === 'string' ? JSON.parse(value) : value);
                  } catch (error) { }
                };
                globalThis.__irisSendIntermediate = function (value) {
                  try {
                    irisHost.toolIntermediate(intermediateId, typeof value === 'string' ? value : JSON.stringify(value));
                  } catch (error) { }
                };
              }
              return new Promise(function (resolve, reject) {
                globalThis.__irisToolPending[id] = { resolve: resolve, reject: reject };
                try {
                  if (intermediateId) {
                    irisHost.callToolStreaming(parsed.name, JSON.stringify(parsed.params), id, intermediateId);
                  } else {
                    irisHost.callTool(parsed.name, JSON.stringify(parsed.params), id);
                  }
                } catch (error) {
                  delete globalThis.__irisToolPending[id];
                  reject(error);
                }
              });
            }
            globalThis.__irisCompat = {
              callTool: __irisCallTool,
              callToolAsync: function (callbackId, toolType, toolName, paramsJson) {
                var params = {};
                try { params = paramsJson ? JSON.parse(paramsJson) : {}; } catch (error) { }
                __irisCallTool(toolName || toolType, params).then(function (value) {
                  try { window[callbackId](JSON.stringify(value), false); } catch (error) { }
                }).catch(function (error) {
                  try { window[callbackId](String(error && error.message || error), true); } catch (e) { }
                });
              },
              callToolAsyncStreaming: function (callbackId, intermediateId, toolType, toolName, paramsJson) {
                var params = {};
                try { params = paramsJson ? JSON.parse(paramsJson) : {}; } catch (error) { }
                var id = 'tool-' + Date.now() + '-' + Math.random().toString(16).slice(2);
                globalThis.__irisSendIntermediate = function (value) {
                  try {
                    irisHost.toolIntermediate(String(intermediateId), typeof value === 'string' ? value : JSON.stringify(value));
                  } catch (error) { }
                };
                globalThis.__irisToolPending[id] = {
                  resolve: function (value) {
                    try { window[callbackId](JSON.stringify(value), false); } catch (error) { }
                  },
                  reject: function (error) {
                    try { window[callbackId](String(error && error.message || error), true); } catch (e) { }
                  }
                };
                try {
                  irisHost.callToolStreaming(String(toolName || toolType), JSON.stringify(params), id, String(intermediateId));
                } catch (error) {
                  delete globalThis.__irisToolPending[id];
                  try { window[callbackId](String(error && error.message || error), true); } catch (e) { }
                }
              }
            };
            globalThis.console = {
              log: function () { irisHost.log(Array.prototype.join.call(arguments, ' ')); },
              warn: function () { irisHost.log(Array.prototype.join.call(arguments, ' ')); },
              error: function () { irisHost.log(Array.prototype.join.call(arguments, ' ')); }
            };
            function __irisUiHandler(fn, kind) {
              if (typeof fn !== 'function') return null;
              var id = 'ui-' + (++globalThis.__irisUiHandlerSeq);
              globalThis.__irisUiHandlers[id] = { fn: fn, kind: kind || 'value' };
              return id;
            }
            function __irisUiPush(out, value) {
              if (value === undefined || value === null || value === false) return;
              if (Array.isArray(value)) {
                for (var i = 0; i < value.length; i++) __irisUiPush(out, value[i]);
                return;
              }
              if (typeof value === 'object' && value.type) out.push(value);
            }
            function __irisUiColorToken(name) {
              return {
                __irisColor: String(name),
                copy: function (options) {
                  var next = { __irisColor: String(name) };
                  if (options && typeof options === 'object') {
                    Object.keys(options).forEach(function (key) { next[key] = options[key]; });
                  }
                  return next;
                },
                toString: function () { return String(name); }
              };
            }
            function __irisUiModifier(data) {
              var modifier = { __irisModifier: true };
              Object.keys(data || {}).forEach(function (key) { modifier[key] = data[key]; });
              modifier.clickable = function (fn) {
                var next = Object.assign({}, modifier);
                next.__clickableId = __irisUiHandler(fn, 'value');
                return __irisUiModifier(next);
              };
              modifier.padding = function (value) {
                var next = Object.assign({}, modifier);
                next.__padding = value;
                return __irisUiModifier(next);
              };
              modifier.weight = function (value) {
                var next = Object.assign({}, modifier);
                next.__weight = value;
                return __irisUiModifier(next);
              };
              modifier.height = function (value) {
                var next = Object.assign({}, modifier);
                next.__height = value;
                return __irisUiModifier(next);
              };
              modifier.size = function (value) {
                var next = Object.assign({}, modifier);
                next.__size = value;
                return __irisUiModifier(next);
              };
              modifier.clip = function (shape) {
                var next = Object.assign({}, modifier);
                next.__clip = shape;
                return __irisUiModifier(next);
              };
              modifier.fillMaxWidth = function () {
                var next = Object.assign({}, modifier);
                next.__fillMaxWidth = true;
                return __irisUiModifier(next);
              };
              return modifier;
            }
            function __irisUiNode(type, props, children) {
              var node = { type: String(type), props: {}, children: [] };
              var values = typeof props === 'string'
                ? { text: props }
                : (props && typeof props === 'object' ? props : {});
              Object.keys(values).forEach(function (key) {
                var value = values[key];
                if (key === 'content') {
                  if (value && typeof value === 'object' && value.type) {
                    node.children.push(value);
                    return;
                  }
                  if (Array.isArray(value)) {
                    __irisUiPush(node.children, value);
                    return;
                  }
                  if (typeof value === 'string' && value) {
                    node.children.push(__irisUiNode('Text', { text: value }, []));
                    return;
                  }
                }
                if (typeof value === 'function') {
                  if (key.indexOf('on') === 0) {
                    var kind = 'value';
                    if (type === 'TextField' || type === 'OutlinedTextField') kind = 'string';
                    if (type === 'Switch' || type === 'Checkbox') kind = 'boolean';
                    node.props[key] = __irisUiHandler(value, kind);
                  }
                  return;
                }
                if (key === 'controller' && value && typeof value === 'object' && value.__irisControllerId) {
                  node.props.controllerId = String(value.__irisControllerId);
                  node.props.controllerInterfaces = Object.keys(value.interfaces || {});
                  if (value.__html) node.props.html = String(value.__html);
                  if (value.__url) node.props.url = String(value.__url);
                  if (value.__baseUrl) node.props.baseUrl = String(value.__baseUrl);
                  return;
                }
                if (key === 'modifier' && value && typeof value === 'object' && value.__irisModifier) {
                  if (value.__clickableId) node.props.clickable = value.__clickableId;
                  if (value.__padding !== undefined) node.props.padding = value.__padding;
                  if (value.__weight !== undefined) node.props.weight = value.__weight;
                  if (value.__height !== undefined) node.props.height = value.__height;
                  if (value.__size !== undefined) node.props.size = value.__size;
                  if (value.__clip !== undefined) node.props.clip = value.__clip;
                  if (value.__fillMaxWidth) node.props.fillMaxWidth = true;
                  return;
                }
                if (value === undefined || value === null) return;
                if (typeof value === 'object') {
                  try { node.props[key] = JSON.parse(JSON.stringify(value)); } catch (error) { }
                } else {
                  node.props[key] = value;
                }
              });
              __irisUiPush(node.children, children);
              return node;
            }
            var __irisUi = {};
            ['Column','Row','Box','LazyColumn','LazyRow','Card','Surface','FlowRow','FlowColumn'].forEach(function (type) {
              __irisUi[type] = function (props, children) { return __irisUiNode(type, props, children); };
            });
            __irisUi.Text = function (props) { return __irisUiNode('Text', props, []); };
            __irisUi.Spacer = function (props) { return __irisUiNode('Spacer', props, []); };
            __irisUi.Button = function (props, children) { return __irisUiNode('Button', props, children); };
            __irisUi.TextField = function (props) { return __irisUiNode('TextField', props, []); };
            __irisUi.OutlinedTextField = __irisUi.TextField;
            __irisUi.IconButton = function (props, children) { return __irisUiNode('IconButton', props, children); };
            __irisUi.Switch = function (props) { return __irisUiNode('Switch', props, []); };
            __irisUi.Checkbox = function (props) { return __irisUiNode('Checkbox', props, []); };
            __irisUi.Icon = function (props) { return __irisUiNode('Icon', props, []); };
            __irisUi.Image = function (props) { return __irisUiNode('Image', props, []); };
            __irisUi.Divider = function (props) { return __irisUiNode('Divider', props, []); };
            __irisUi.HorizontalDivider = __irisUi.Divider;
            __irisUi.VerticalDivider = function (props) { return __irisUiNode('VerticalDivider', props, []); };
            __irisUi.LinearProgressIndicator = function (props) { return __irisUiNode('LinearProgressIndicator', props, []); };
            __irisUi.CircularProgressIndicator = function (props) { return __irisUiNode('CircularProgressIndicator', props, []); };
            __irisUi.AlertDialog = function (props, children) { return __irisUiNode('AlertDialog', props, children); };
            __irisUi.Dialog = function (props, children) { return __irisUiNode('Dialog', props, children); };
            __irisUi.AiChat = function (props) { return __irisUiNode('AiChat', props, []); };
            __irisUi.AdaptiveSidePanel = function (props, main) {
              var values = Object.assign({}, props || {});
              var side = values.side;
              delete values.side;
              return __irisUiNode('AdaptiveSidePanel', values, [side, main]);
            };
            ['TextButton','OutlinedButton','ElevatedButton','FilledTonalButton'].forEach(function (type) {
              __irisUi[type] = function (props, children) { return __irisUiNode('Button', props, children); };
            });
            ['OutlinedCard','ElevatedCard'].forEach(function (type) {
              __irisUi[type] = function (props, children) { return __irisUiNode('Card', props, children); };
            });
            __irisUi = new Proxy(__irisUi, {
              get: function (target, prop) {
                if (prop in target) return target[prop];
                if (typeof prop !== 'string') return undefined;
                return function (props, children) { return __irisUiNode(prop, props, children); };
              }
            });
            __irisUi.Modifier = __irisUiModifier({});
            function __irisUiContext(moduleId, state) {
              function setState(key, value) {
                state[key] = typeof value === 'function' ? value(state[key]) : value;
                try { irisHost.uiInvalidate(moduleId); } catch (error) { }
              }
              var ctx = {
                UI: __irisUi,
                Modifier: __irisUiModifier({}),
                MaterialTheme: {
                  colorScheme: new Proxy({}, { get: function (target, prop) { return __irisUiColorToken(prop); } }),
                  typography: new Proxy({}, { get: function () { return {}; } })
                },
                useState: function (key, initial) {
                  var name = String(key);
                  if (!(name in state)) state[name] = initial;
                  return [state[name], function (value) { setState(name, value); }];
                },
                useRef: function (key, initial) {
                  var name = '__ref_' + String(key);
                  if (!state[name]) state[name] = { current: initial };
                  return state[name];
                },
                createWebViewController: function (id) {
                  var controllerId = String(id || ('ui-controller-' + Date.now()));
                  if (globalThis.__irisUiControllers[controllerId]) {
                    return globalThis.__irisUiControllers[controllerId];
                  }
                  var controller = {
                    __irisControllerId: controllerId,
                    __html: '',
                    __url: '',
                    __baseUrl: '',
                    interfaces: {},
                    loadHtml: function (html, options) {
                      controller.__html = String(html || '');
                      controller.__url = '';
                      controller.__baseUrl = String(options && (options.baseUrl || options.baseURL) || '');
                      try { irisHost.uiInvalidate(moduleId); } catch (error) { }
                      return Promise.resolve(true);
                    },
                    loadUrl: function (url) {
                      controller.__html = '';
                      controller.__url = String(url || '');
                      try { irisHost.uiInvalidate(moduleId); } catch (error) { }
                      return Promise.resolve(true);
                    },
                    reload: function () {
                      try { irisHost.uiInvalidate(moduleId); } catch (error) { }
                      return Promise.resolve(true);
                    },
                    addJavascriptInterface: function (name, value) {
                      if (name && value && typeof value === 'object') {
                        controller.interfaces[String(name)] = value;
                      }
                      return controller;
                    }
                  };
                  globalThis.__irisUiControllers[controllerId] = controller;
                  return controller;
                },
                useMemo: function (key, factory, deps) {
                  var name = String(key);
                  var stamp = JSON.stringify(deps || []);
                  var cached = state['__memo_' + name];
                  if (!cached || cached.stamp !== stamp) {
                    cached = { stamp: stamp, value: factory() };
                    state['__memo_' + name] = cached;
                  }
                  return cached.value;
                },
                callTool: function () { return __irisCallTool.apply(null, arguments); },
                host: {
                  invoke: function (id, args) {
                    try {
                      return Promise.resolve(JSON.parse(irisHost.hostAction(String(id), JSON.stringify(args || {}))));
                    } catch (error) { return Promise.reject(error); }
                  }
                },
                getEnv: function (key) { try { return irisHost.getEnv(String(key)); } catch (error) { return ''; } },
                setEnv: function (key, value) {
                  try { return Promise.resolve(irisHost.setEnv(String(key), String(value))); }
                  catch (error) { return Promise.reject(error); }
                },
                setEnvs: function (values) {
                  var keys = Object.keys(values || {});
                  for (var i = 0; i < keys.length; i++) ctx.setEnv(keys[i], values[keys[i]]);
                  return Promise.resolve(true);
                },
                showToast: function (message) { try { irisHost.toast(String(message)); } catch (error) { } return Promise.resolve(); },
                reportError: function (error) { try { irisHost.log(String(error)); } catch (e) { } },
                navigate: function () { return Promise.resolve(false); },
                listRoutes: function () { return []; },
                getHostRoutes: function () { return []; },
                openFilePicker: function () { return Promise.reject(new Error('插件 UI 文件选择暂不支持')); },
                formatTemplate: function (template, values) {
                  return String(template || '').replace(/\{([^{}]+)\}/g, function (all, key) {
                    return Object.prototype.hasOwnProperty.call(values || {}, key) ? String(values[key]) : all;
                  });
                },
                getCurrentPackageName: function () { return moduleId; },
                getCurrentToolPkgId: function () { return moduleId; },
                getCurrentUiModuleId: function () { return moduleId; },
                getModuleSpec: function () { return {}; },
                isPackageImported: function () { return Promise.resolve(false); },
                importPackage: function () { return Promise.resolve(false); },
                removePackage: function () { return Promise.resolve(false); },
                usePackage: function () { return Promise.resolve(false); },
                listImportedPackages: function () { return []; },
                resolveToolName: function (spec) {
                  return Promise.resolve(spec && (spec.toolName || spec.name) || '');
                }
              };
              return ctx;
            }
            globalThis.__irisRenderUi = function (moduleId) {
              var screen = globalThis.__irisUiScreens[moduleId];
              if (typeof screen !== 'function') throw new Error('UI 模块未注册可执行的 screen：' + moduleId);
              var state = globalThis.__irisUiStates[moduleId];
              if (!state) { state = {}; globalThis.__irisUiStates[moduleId] = state; }
              var tree = screen(__irisUiContext(moduleId, state));
              if (tree && typeof tree.then === 'function') {
                return tree.then(function (value) { return JSON.stringify({ ok: true, tree: value }); });
              }
              return JSON.stringify({ ok: true, tree: tree });
            };
            globalThis.__irisXmlSeq = 0;
            globalThis.__irisXmlScreens = {};
            globalThis.__irisRenderXmlHook = function (hookId, tagName, xmlContent) {
              var hook = globalThis.__irisHooks['xml_render'] && globalThis.__irisHooks['xml_render'][hookId];
              if (typeof hook !== 'function') return Promise.resolve(JSON.stringify({ handled: false }));
              return Promise.resolve(hook({
                eventName: 'xml_render',
                eventPayload: { tagName: String(tagName), xmlContent: String(xmlContent) }
              })).then(function (result) {
                if (!result) return JSON.stringify({ handled: false });
                if (result.composeDsl && typeof result.composeDsl.screen === 'function') {
                  var moduleId = 'xml_' + hookId + '_' + (++globalThis.__irisXmlSeq);
                  var state = Object.assign({}, result.composeDsl.state || {});
                  globalThis.__irisUiStates[moduleId] = state;
                  globalThis.__irisXmlScreens[moduleId] = result.composeDsl.screen;
                  var tree = result.composeDsl.screen(__irisUiContext(moduleId, state));
                  return Promise.resolve(tree).then(function (value) {
                    return JSON.stringify({ handled: true, tree: value, moduleId: moduleId, text: result.text || '' });
                  });
                }
                return JSON.stringify({ handled: !!result.handled, text: result.text || '' });
              });
            };
            globalThis.__irisUiCall = function (controllerId, interfaceName, methodName, argsJson) {
              var controller = globalThis.__irisUiControllers[controllerId];
              var api = controller && controller.interfaces[interfaceName];
              var fn = api && api[methodName];
              if (typeof fn !== 'function') throw new Error('WebView 接口不存在：' + interfaceName + '.' + methodName);
              var args = [];
              try { args = JSON.parse(argsJson || '[]'); } catch (error) { args = []; }
              return Promise.resolve(fn.apply(api, args)).then(function (value) {
                if (value === undefined) return '';
                return typeof value === 'string' ? value : JSON.stringify(value);
              });
            };
            globalThis.__irisDispatchUiEvent = function (moduleId, eventId, valueJson) {
              var entry = globalThis.__irisUiHandlers[eventId];
              var handler = entry && entry.fn;
              if (typeof handler !== 'function') return Promise.reject(new Error('UI 事件不存在：' + eventId));
              var value = undefined;
              if (valueJson !== null && valueJson !== undefined) {
                if (entry.kind === 'string') value = String(valueJson);
                else if (entry.kind === 'boolean') value = valueJson === true || valueJson === 'true';
                else {
                  try { value = JSON.parse(valueJson); } catch (error) { value = valueJson; }
                }
              }
              try {
                var result = handler(value);
                if (result && typeof result.catch === 'function') {
                  result.catch(function (error) { try { irisHost.log(String(error && error.stack || error)); } catch (e) { } });
                }
              } catch (error) {
                try { irisHost.log(String(error && error.stack || error)); } catch (e) { }
              }
              return __irisRenderUi(moduleId);
            };
            globalThis.__irisCallUiHandler = function (eventId, payloadJson) {
              var entry = globalThis.__irisUiHandlers[eventId];
              var handler = entry && entry.fn;
              if (typeof handler !== 'function') return Promise.reject(new Error('UI 处理器不存在：' + eventId));
              var value = undefined;
              try { value = JSON.parse(payloadJson || 'null'); } catch (error) { value = payloadJson; }
              return Promise.resolve(handler(value)).then(function (result) {
                if (result === undefined) return 'null';
                return typeof result === 'string' ? result : JSON.stringify(result);
              });
            };
            function __irisRegister(type, def) {
              if (!def || typeof def !== 'object') return;
              var id = String(def.id || '');
              if (!id) return;
              var screen = def.screen;
              for (var depth = 0; depth < 4 && screen && typeof screen === 'object'; depth++) {
                if (typeof screen.function === 'function') {
                  screen = screen.function;
                } else if (typeof screen.default === 'function') {
                  screen = screen.default;
                } else {
                  break;
                }
              }
              if (typeof screen === 'function') {
                globalThis.__irisUiScreens[id] = screen;
              } else if (typeof screen === 'string' && screen) {
                var screenFn = null;
                try {
                  var mainPath = String(globalThis.__irisMainPath || '');
                  var mainDir = mainPath.lastIndexOf('/') >= 0 ? mainPath.slice(0, mainPath.lastIndexOf('/')) : '';
                  var candidates = ['', mainDir];
                  for (var ci = 0; ci < candidates.length && !screenFn; ci++) {
                    try {
                      var loadedModule = globalThis.__irisLoadModule(globalThis.__irisNormalize(candidates[ci], screen));
                      var loadedValue = loadedModule && (loadedModule.default || loadedModule);
                      if (loadedValue && typeof loadedValue.default === 'function') loadedValue = loadedValue.default;
                      if (loadedValue && typeof loadedValue.screen === 'function') loadedValue = loadedValue.screen;
                      if (typeof loadedValue === 'function') screenFn = loadedValue;
                    } catch (loadError) { }
                  }
                } catch (loadError) { }
                globalThis.__irisUiScreens[id] = screenFn || function () { throw new Error('UI screen 文件加载失败：' + screen); };
              }
              if (typeof def.function === 'function') {
                __irisHooks[type] = __irisHooks[type] || {};
                __irisHooks[type][id] = def.function;
              }
              if (type === 'navigation' && def.action) {
                var actionFn = typeof def.action === 'function' ? def.action : def.action.function;
                if (typeof actionFn === 'function') {
                  __irisHooks['navigation'] = __irisHooks['navigation'] || {};
                  __irisHooks['navigation'][id] = actionFn;
                }
              }
              if (type === 'ai_provider') {
                __irisProviders[id] = {
                  listModels: def.listModels && def.listModels.function,
                  sendMessage: def.sendMessage && def.sendMessage.function,
                  testConnection: def.testConnection && def.testConnection.function,
                  calculateInputTokens: def.calculateInputTokens && def.calculateInputTokens.function
                };
              }
              __irisRegistrations.push({
                type: type, id: id,
                title: def.title || def.displayName || null,
                senders: def.senders || null,
                order: def.order || 0,
                event: def.event || null,
                icon: def.icon || null,
                screen: def.screen || null,
                route: def.route || def.routeId || null,
                surface: def.surface || null
              });
            }
            function __irisIconGroup() {
              return new Proxy({}, { get: function (target, prop) { return typeof prop === 'string' ? prop : undefined; } });
            }
            globalThis.Icons = new Proxy({}, {
              get: function (target, prop) {
                if (typeof prop !== 'string') return undefined;
                if (['Default', 'Outlined', 'Filled', 'Rounded', 'Sharp', 'TwoTone'].indexOf(prop) >= 0) {
                  return __irisIconGroup();
                }
                return prop;
              }
            });
            globalThis.ToolPkg = {
              // OperitForge 市场包会在 main 顶层写入编码后的市场元数据；不参与运行，直接忽略。
              _m: function () {},
              registerToolboxUiModule: function (def) { __irisRegister('toolbox_ui', def); },
              registerUiRoute: function (def) { __irisRegister('ui_route', def); },
              registerSettingsEntry: function (def) { __irisRegister('settings_entry', def); },
              registerPluginSlot: function (def) { __irisRegister('plugin_slot', def); },
              registerNavigationEntry: function (def) { __irisRegister('navigation', def); },
              registerDesktopWidget: function (def) { __irisRegister('desktop_widget', def); },
              registerAppLifecycleHook: function (def) { __irisRegister('app_lifecycle', def); },
              registerMessageProcessingPlugin: function (def) { __irisRegister('message_processing', def); },
              registerXmlRenderPlugin: function (def) { __irisRegister('xml_render', def); },
              registerInputMenuTogglePlugin: function (def) { __irisRegister('input_menu_toggle', def); },
              registerChatInputHook: function (def) { __irisRegister('chat_input', def); },
              registerChatViewHook: function (def) { __irisRegister('chat_view', def); },
              registerChatMessageHook: function (def) { __irisRegister('chat_message', def); },
              registerChatMessageMenuItem: function (def) { __irisRegister('chat_message_menu_item', def); },
              registerChatRuntimeHook: function (def) { __irisRegister('chat_runtime', def); },
              registerToolLifecycleHook: function (def) { __irisRegister('tool_lifecycle', def); },
              registerPromptInputHook: function (def) { __irisRegister('prompt_input', def); },
              registerPromptHistoryHook: function (def) { __irisRegister('prompt_history', def); },
              registerPromptEstimateHistoryHook: function (def) { __irisRegister('prompt_estimate_history', def); },
              registerSystemPromptComposeHook: function (def) { __irisRegister('system_prompt_compose', def); },
              registerToolPromptComposeHook: function (def) { __irisRegister('tool_prompt_compose', def); },
              registerPromptFinalizeHook: function (def) { __irisRegister('prompt_finalize', def); },
              registerPromptEstimateFinalizeHook: function (def) { __irisRegister('prompt_estimate_finalize', def); },
              registerSummaryGenerateHook: function (def) { __irisRegister('summary_generate', def); },
              registerAiProvider: function (def) { __irisRegister('ai_provider', def); },
              readResource: function (key, outputFileName, internal) {
                return Promise.resolve(irisHost.readResource(String(key)));
              },
              callTool: __irisCallTool,
              callToolAsync: __irisCompat.callToolAsync,
              callToolAsyncStreaming: __irisCompat.callToolAsyncStreaming,
              getConfigDir: function (pluginId) { return irisHost.configDir(); },
              ui: {
                floatingWindows: {
                  capabilities: function () {
                    return Promise.resolve({ scope: 'unsupported', systemOverlay: false, applicationOverlay: false, supported: false, permissionGranted: false, positioning: false, alwaysOnTop: false, clickThrough: false, focusable: false });
                  },
                  requestPermission: function () { return Promise.resolve(false); },
                  open: function () { return Promise.reject(new Error('IRIS 未支持悬浮窗口')); },
                  update: function () { return Promise.reject(new Error('IRIS 未支持悬浮窗口')); },
                  show: function () { return Promise.reject(new Error('IRIS 未支持悬浮窗口')); },
                  hide: function () { return Promise.reject(new Error('IRIS 未支持悬浮窗口')); },
                  close: function () { return Promise.reject(new Error('IRIS 未支持悬浮窗口')); },
                  list: function () { return Promise.resolve([]); }
                }
              },
              ipc: {
                on: function (channel, handler) {
                  channel = String(channel || '').trim();
                  if (!channel || typeof handler !== 'function') throw new Error('IPC channel/handler 无效');
                  __irisIpc[channel] = handler;
                  return function () { if (__irisIpc[channel] === handler) delete __irisIpc[channel]; };
                },
                off: function (channel, handler) {
                  channel = String(channel || '').trim();
                  if (!channel) return false;
                  if (handler && __irisIpc[channel] !== handler) return false;
                  delete __irisIpc[channel];
                  return true;
                },
                call: function (channel, payload) {
                  channel = String(channel || '').trim();
                  var handler = __irisIpc[channel];
                  if (!handler) return Promise.reject(new Error('IPC handler 不存在：' + channel));
                  return Promise.resolve(handler(payload, { channel: channel }));
                }
              },
              wasm: {
                call: function (moduleId, exportName, args) {
                  var cache = globalThis.__irisWasmCache = globalThis.__irisWasmCache || {};
                  var key = String(moduleId);
                  var loaded = cache[key] || (cache[key] = Promise.resolve().then(function () {
                    var base64 = irisHost.readWasmBase64(key);
                    var binary = atob(base64);
                    var bytes = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                    var imports = { env: {
                      abort: function (msg) { throw new Error('WASM abort: ' + msg); },
                      trace: function () { irisHost.log(Array.prototype.join.call(arguments, ' ')); },
                      seed: function () { return Date.now(); }
                    } };
                    return WebAssembly.instantiate(bytes, imports).then(function (result) { return result.instance; });
                  }));
                  return loaded.then(function (instance) {
                    var fn = instance.exports[String(exportName)];
                    if (typeof fn !== 'function') throw new Error('WASM 导出不存在：' + exportName);
                    var list = Array.isArray(args) ? args : [];
                    var converted = list.map(function (item) {
                      var type = String(item && item.type || 'i32').toLowerCase();
                      var value = item && item.value;
                      if (type === 'i64') return BigInt(value);
                      if (type === 'f32' || type === 'f64') return Number(value);
                      return Number(value);
                    });
                    var result = fn.apply(null, converted);
                    if (typeof result === 'bigint') return String(result);
                    return result === undefined ? null : result;
                  });
                }
              }
            };
            function __irisRegisterFromJson(register) {
              return function (spec) {
                try {
                  var value = typeof spec === 'string' ? JSON.parse(spec) : spec;
                  register(value);
                } catch (error) {
                  try { irisHost.log('注册失败：' + String(error && error.message || error)); } catch (ignored) { }
                }
              };
            }
            globalThis.registerToolPkgToolboxUiModule = __irisRegisterFromJson(globalThis.ToolPkg.registerToolboxUiModule);
            globalThis.registerToolPkgUiRoute = __irisRegisterFromJson(globalThis.ToolPkg.registerUiRoute);
            globalThis.registerToolPkgSettingsEntry = __irisRegisterFromJson(globalThis.ToolPkg.registerSettingsEntry);
            globalThis.registerToolPkgPluginSlot = __irisRegisterFromJson(globalThis.ToolPkg.registerPluginSlot);
            globalThis.registerToolPkgNavigationEntry = __irisRegisterFromJson(globalThis.ToolPkg.registerNavigationEntry);
            globalThis.registerToolPkgDesktopWidget = __irisRegisterFromJson(globalThis.ToolPkg.registerDesktopWidget);
            globalThis.registerToolPkgAppLifecycleHook = __irisRegisterFromJson(globalThis.ToolPkg.registerAppLifecycleHook);
            globalThis.registerToolPkgMessageProcessingPlugin = __irisRegisterFromJson(globalThis.ToolPkg.registerMessageProcessingPlugin);
            globalThis.registerToolPkgXmlRenderPlugin = __irisRegisterFromJson(globalThis.ToolPkg.registerXmlRenderPlugin);
            globalThis.registerToolPkgInputMenuTogglePlugin = __irisRegisterFromJson(globalThis.ToolPkg.registerInputMenuTogglePlugin);
            globalThis.registerToolPkgChatInputHook = __irisRegisterFromJson(globalThis.ToolPkg.registerChatInputHook);
            globalThis.registerToolPkgChatViewHook = __irisRegisterFromJson(globalThis.ToolPkg.registerChatViewHook);
            globalThis.registerToolPkgChatMessageHook = __irisRegisterFromJson(globalThis.ToolPkg.registerChatMessageHook);
            globalThis.registerToolPkgChatMessageMenuItem = __irisRegisterFromJson(globalThis.ToolPkg.registerChatMessageMenuItem);
            globalThis.registerToolPkgChatRuntimeHook = __irisRegisterFromJson(globalThis.ToolPkg.registerChatRuntimeHook);
            globalThis.registerToolPkgToolLifecycleHook = __irisRegisterFromJson(globalThis.ToolPkg.registerToolLifecycleHook);
            globalThis.registerToolPkgPromptInputHook = __irisRegisterFromJson(globalThis.ToolPkg.registerPromptInputHook);
            globalThis.registerToolPkgPromptHistoryHook = __irisRegisterFromJson(globalThis.ToolPkg.registerPromptHistoryHook);
            globalThis.registerToolPkgPromptEstimateHistoryHook = __irisRegisterFromJson(globalThis.ToolPkg.registerPromptEstimateHistoryHook);
            globalThis.registerToolPkgSystemPromptComposeHook = __irisRegisterFromJson(globalThis.ToolPkg.registerSystemPromptComposeHook);
            globalThis.registerToolPkgToolPromptComposeHook = __irisRegisterFromJson(globalThis.ToolPkg.registerToolPromptComposeHook);
            globalThis.registerToolPkgPromptFinalizeHook = __irisRegisterFromJson(globalThis.ToolPkg.registerPromptFinalizeHook);
            globalThis.registerToolPkgPromptEstimateFinalizeHook = __irisRegisterFromJson(globalThis.ToolPkg.registerPromptEstimateFinalizeHook);
            globalThis.registerToolPkgSummaryGenerateHook = __irisRegisterFromJson(globalThis.ToolPkg.registerSummaryGenerateHook);
            globalThis.registerToolPkgAiProvider = __irisRegisterFromJson(globalThis.ToolPkg.registerAiProvider);
            function __irisShellQuote(value) {
              var quote = String.fromCharCode(39);
              return quote + String(value).split(quote).join(quote + '\\' + quote + quote) + quote;
            }
            var __irisLinuxSessionId = null;
            function __irisLinuxExec(command, timeoutMs) {
              try {
                if (!__irisLinuxSessionId) {
                  var created = JSON.parse(irisHost.terminalCreate('plugin-linux-files'));
                  __irisLinuxSessionId = created.sessionId;
                }
                return JSON.parse(irisHost.terminalExec(__irisLinuxSessionId, String(command), Number(timeoutMs) || 30000));
              } catch (error) {
                return { exitCode: 1, output: String(error && error.message || error) };
              }
            }
            globalThis.Tools = {
              Files: {
                read: function (pathOrOptions, environment) {
                  var path = pathOrOptions;
                  if (pathOrOptions && typeof pathOrOptions === 'object') {
                    path = pathOrOptions.path;
                    environment = pathOrOptions.environment;
                  }
                  if (environment === 'linux') {
                    var result = __irisLinuxExec('cat ' + __irisShellQuote(path), 30000);
                    return { content: result.output, exitCode: result.exitCode };
                  }
                  return irisHost.readText(String(path));
                },
                write: function (path, content, environment) {
                  if (environment === 'linux') {
                    return __irisLinuxExec('printf %s ' + __irisShellQuote(String(content)) + ' > ' + __irisShellQuote(path), 30000);
                  }
                  return irisHost.writeText(String(path), String(content));
                },
                exists: function (path, environment) {
                  if (environment === 'linux') {
                    var result = __irisLinuxExec('test -e ' + __irisShellQuote(path) + ' && echo true || echo false', 15000);
                    return { exists: String(result.output || '').trim() === 'true' };
                  }
                  return irisHost.exists(String(path));
                },
                info: function (path, environment) {
                  if (environment === 'linux') {
                    var result = __irisLinuxExec('stat -c %s ' + __irisShellQuote(path), 15000);
                    return { exists: result.exitCode === 0, length: Number(String(result.output || '').trim() || 0) };
                  }
                  return JSON.parse(irisHost.fileInfo(String(path)));
                },
                list: function (path, environment) {
                  if (environment === 'linux') {
                    var result = __irisLinuxExec('ls -1 ' + __irisShellQuote(path), 15000);
                    return String(result.output || '').split('\n').filter(Boolean).map(function (name) {
                      return { name: name, path: path + '/' + name };
                    });
                  }
                  return JSON.parse(irisHost.listFiles(String(path)));
                },
                deleteFile: function (path, recursive, environment) {
                  if (environment === 'linux') {
                    return __irisLinuxExec('rm -rf ' + __irisShellQuote(path), 30000);
                  }
                  return irisHost.deletePath(String(path), !!recursive);
                },
                mkdir: function (path, recursive, environment) {
                  if (environment === 'linux') {
                    return __irisLinuxExec('mkdir -p ' + __irisShellQuote(path), 15000);
                  }
                  return irisHost.mkdirs(String(path));
                },
                move: function (source, destination, environment) {
                  if (environment === 'linux') {
                    return __irisLinuxExec('mv -f ' + __irisShellQuote(source) + ' ' + __irisShellQuote(destination), 30000);
                  }
                  return irisHost.movePath(String(source), String(destination));
                },
                unzip: function (archive, destination, environment) {
                  if (environment === 'linux') {
                    var raw = irisHost.unzipLinux(String(archive), String(destination));
                    var result = JSON.parse(raw);
                    if (result.error) throw new Error(result.error);
                    return result;
                  }
                  return { exitCode: 1, output: 'unzip 仅支持 linux 环境' };
                },
                readBinary: function (path) { return irisHost.readBase64(String(path)); },
                writeBinary: function (path, base64) { return irisHost.writeBase64(String(path), String(base64)); },
                download: function (url, path) {
                  try { return Promise.resolve(irisHost.downloadFile(String(url), String(path))); }
                  catch (error) { return Promise.reject(error); }
                }
              },
              SoftwareSettings: {
                readEnvironmentVariable: function (key) {
                  try { return irisHost.getEnv(String(key)); } catch (error) { return ''; }
                },
                writeEnvironmentVariable: function (key, value) {
                  try { return Promise.resolve(irisHost.setEnv(String(key), String(value))); }
                  catch (error) { return Promise.reject(error); }
                },
                deleteEnvironmentVariable: function (key) {
                  try { return Promise.resolve(irisHost.setEnv(String(key), '')); }
                  catch (error) { return Promise.reject(error); }
                }
              },
              System: {
                sleep: function (milliseconds) { irisHost.sleep(Number(milliseconds || 0)); },
                terminal: {
                  create: function (name) {
                    try { return Promise.resolve(JSON.parse(irisHost.terminalCreate(String(name)))); }
                    catch (error) { return Promise.reject(error); }
                  },
                  exec: function (sessionId, command, timeoutMs) {
                    try { return Promise.resolve(JSON.parse(irisHost.terminalExec(String(sessionId), String(command), Number(timeoutMs) || 30000))); }
                    catch (error) { return Promise.reject(error); }
                  },
                  execStreaming: function (sessionId, command, options) {
                    var timeoutMs = Number(options && options.timeoutMs) || 240000;
                    try { return Promise.resolve(JSON.parse(irisHost.terminalExecStreaming(String(sessionId), String(command), timeoutMs))); }
                    catch (error) { return Promise.reject(error); }
                  }
                }
              },
              Network: {
                httpGet: function (url) {
                  var response = JSON.parse(irisHost.httpRequest(String(url), 'GET', '{}', ''));
                  if (!response.ok) throw new Error(response.error || 'HTTP 请求失败');
                  return response.body;
                }
              },
              Net: {
                http: function (options) {
                  options = options || {};
                  return globalThis.fetch(options.url, options).then(function (response) {
                    return response.text().then(function (content) {
                      return { statusCode: response.status, content: content, url: response.url };
                    });
                  });
                },
                httpGet: function (url) { return globalThis.Tools.Net.http({ url: url, method: 'GET' }); },
                httpPost: function (url, body, headers) {
                  return globalThis.Tools.Net.http({ url: url, method: 'POST', body: body, headers: headers });
                }
              }
            };
            function __irisUnsupported(name) {
              return function () { throw new Error('IRIS 未支持：' + name); };
            }
            function __irisUnsupportedObject(prefix) {
              return new Proxy({}, {
                get: function (target, prop) {
                  if (typeof prop !== 'string') return undefined;
                  if (prop === 'then') return undefined;
                  return __irisUnsupported(prefix + '.' + prop);
                }
              });
            }
            globalThis.Tools.calc = function (expression) {
              try { return Promise.resolve(eval('(' + String(expression || '') + ')')); }
              catch (error) { return Promise.reject(error); }
            };
            globalThis.Tools.UI = __irisUnsupportedObject('Tools.UI');
            globalThis.Tools.FFmpeg = __irisUnsupportedObject('Tools.FFmpeg');
            globalThis.Tools.Tasker = __irisUnsupportedObject('Tools.Tasker');
            globalThis.Tools.Workflow = __irisUnsupportedObject('Tools.Workflow');
            globalThis.Tools.Chat = __irisUnsupportedObject('Tools.Chat');
            globalThis.Tools.Memory = __irisUnsupportedObject('Tools.Memory');
            globalThis.Tools.Files.readPart = function (path, startLine, endLine, environment) {
              var text = globalThis.Tools.Files.read(path, environment);
              var content = typeof text === 'string' ? text : String(text && text.content || '');
              var lines = content.split('\n');
              var start = Math.max(1, Number(startLine) || 1);
              var count = endLine == null ? 100 : Math.max(1, Number(endLine) - start + 1);
              return lines.slice(start - 1, start - 1 + count).join('\n');
            };
            globalThis.Tools.Files.copy = function (source, destination) {
              irisHost.writeBase64(String(destination), irisHost.readBase64(String(source)));
              return { exitCode: 0, output: 'copied' };
            };
            globalThis.Tools.Files.create = function (path, content, environment) {
              return globalThis.Tools.Files.write(path, content, environment);
            };
            globalThis.Tools.Files.edit = function (path, oldContent, newContent, environment) {
              var text = globalThis.Tools.Files.read(path, environment);
              var content = typeof text === 'string' ? text : String(text && text.content || '');
              if (content.indexOf(String(oldContent)) < 0) throw new Error('未找到要替换的内容');
              return globalThis.Tools.Files.write(path, content.replace(String(oldContent), String(newContent)), environment);
            };
            globalThis.Tools.Files.apply = function (path, type, oldContent, newContent, environment) {
              if (type === 'delete') return globalThis.Tools.Files.deleteFile(path, false, environment);
              if (type === 'create') return globalThis.Tools.Files.create(path, newContent, environment);
              return globalThis.Tools.Files.edit(path, oldContent, newContent, environment);
            };
            globalThis.Tools.Files.grep = function (path, pattern, options, environment) {
              var text = globalThis.Tools.Files.read(path, environment);
              var content = typeof text === 'string' ? text : String(text && text.content || '');
              var flags = options && options.case_insensitive ? 'i' : '';
              var matcher = new RegExp(String(pattern), flags);
              return content.split('\n').filter(function (line) { return matcher.test(line); });
            };
            globalThis.Tools.Files.zip = __irisUnsupported('Tools.Files.zip');
            globalThis.Tools.Files.open = __irisUnsupported('Tools.Files.open');
            globalThis.Tools.Files.share = __irisUnsupported('Tools.Files.share');
            globalThis.Tools.Net.visit = function (url) {
              var target = typeof url === 'object' && url ? url.url : url;
              return globalThis.fetch(String(target), { method: 'GET' }).then(function (response) { return response.text(); });
            };
            globalThis.Tools.Net.uploadFile = __irisUnsupported('Tools.Net.uploadFile');
            ['startBrowser','stopBrowser','browserNavigate','browserNavigateBack','browserClick','browserHover','browserType','browserPressKey','browserSelectOption','browserFillForm','browserDrag','browserResize','browserWaitFor','browserSnapshot','browserTakeScreenshot','browserEvaluate','browserRunCode','browserHandleDialog','browserFileUpload','browserTabs','browserClose','browserCloseAll','browserConsoleMessages','browserNetworkRequests'].forEach(function (name) {
              globalThis.Tools.Net[name] = __irisUnsupported('Tools.Net.' + name);
            });
            globalThis.Tools.Net.cookies = __irisUnsupportedObject('Tools.Net.cookies');
            globalThis.Tools.System.toast = function (message) { try { irisHost.toast(String(message)); } catch (error) { } return { success: true }; };
            globalThis.Tools.System.getDeviceInfo = function () { return { platform: 'android', model: '', sdk: 0 }; };
            globalThis.Tools.System.getSetting = __irisUnsupported('Tools.System.getSetting');
            globalThis.Tools.System.setSetting = __irisUnsupported('Tools.System.setSetting');
            globalThis.Tools.System.sendNotification = __irisUnsupported('Tools.System.sendNotification');
            globalThis.Tools.System.listApps = __irisUnsupported('Tools.System.listApps');
            globalThis.Tools.System.startApp = __irisUnsupported('Tools.System.startApp');
            globalThis.Tools.System.stopApp = __irisUnsupported('Tools.System.stopApp');
            globalThis.Tools.System.installApp = __irisUnsupported('Tools.System.installApp');
            globalThis.Tools.System.uninstallApp = __irisUnsupported('Tools.System.uninstallApp');
            globalThis.Tools.System.usePackage = __irisUnsupported('Tools.System.usePackage');
            globalThis.Tools.System.shell = __irisUnsupported('Tools.System.shell');
            globalThis.Tools.System.intent = __irisUnsupported('Tools.System.intent');
            globalThis.Tools.System.sendBroadcast = __irisUnsupported('Tools.System.sendBroadcast');
            globalThis.Tools.System.getNotifications = __irisUnsupported('Tools.System.getNotifications');
            globalThis.Tools.System.getLocation = __irisUnsupported('Tools.System.getLocation');
            globalThis.Tools.System.getAppUsageTime = __irisUnsupported('Tools.System.getAppUsageTime');
            globalThis.Tools.System.bluetooth = __irisUnsupportedObject('Tools.System.bluetooth');
            globalThis.Tools.System.music = __irisUnsupportedObject('Tools.System.music');
            ['listSandboxPackages','setSandboxPackageEnabled','executeSandboxScriptDirect','restartMcpWithLogs','getSpeechServicesConfig','setSpeechServicesConfig','testTtsPlayback','listModelConfigs','createModelConfig','updateModelConfig','deleteModelConfig','listFunctionModelConfigs','getFunctionModelConfig','setFunctionModelConfig','testModelConfigConnection','listCharacterCards','getCharacterCard','createCharacterCard','updateCharacterCard','deleteCharacterCard','setActiveCharacterCard','clearActiveCharacterCard','importCharacterCardFromTavernJson','exportCharacterCardToTavernJson'].forEach(function (name) {
              globalThis.Tools.SoftwareSettings[name] = __irisUnsupported('Tools.SoftwareSettings.' + name);
            });
            globalThis.getEnv = function (key) {
              try { return irisHost.getEnv(String(key)); } catch (error) { return ''; }
            };
            globalThis.setEnv = function (key, value) {
              try { return Promise.resolve(irisHost.setEnv(String(key), String(value))); }
              catch (error) { return Promise.reject(error); }
            };
            function __irisUnsupportedCtor(name) {
              var fn = function () { throw new Error('IRIS 未支持：' + name); };
              return new Proxy(fn, {
                get: function (target, prop) {
                  if (prop === 'then') return undefined;
                  return __irisUnsupported(name + '.' + String(prop));
                }
              });
            }
            globalThis.sendIntermediateResult = function (value) {
              if (typeof globalThis.__irisSendIntermediate === 'function') {
                globalThis.__irisSendIntermediate(value);
              }
            };
            globalThis.getPluginConfigDir = function () { return irisHost.configDir(); };
            globalThis.getState = function () { return undefined; };
            globalThis.getLang = function () { return 'zh'; };
            globalThis.getCallerName = function () { return undefined; };
            globalThis.getChatId = function () { return undefined; };
            globalThis.getToolSessionId = function () { return globalThis.__irisToolSessionId || undefined; };
            globalThis.getCallerCardId = function () { return undefined; };
            globalThis.getToolExecutionContext = function () {
              return {
                callId: globalThis.__irisToolSessionId || '',
                java: globalThis.Java,
                chatId: undefined,
                callerCardId: undefined,
                language: 'zh',
                emitIntermediate: function (xml) { globalThis.sendIntermediateResult(xml); }
              };
            };
            globalThis.OPERIT_DOWNLOAD_DIR = irisHost.configDir() + '/downloads';
            globalThis.OPERIT_CLEAN_ON_EXIT_DIR = irisHost.configDir() + '/clean_on_exit';
            globalThis._ = {
              isEmpty: function (value) {
                if (value == null) return true;
                if (typeof value === 'string' || Array.isArray(value)) return value.length === 0;
                return typeof value === 'object' && Object.keys(value).length === 0;
              },
              isString: function (value) { return typeof value === 'string'; },
              isNumber: function (value) { return typeof value === 'number' && isFinite(value); },
              isBoolean: function (value) { return typeof value === 'boolean'; },
              isObject: function (value) { return value != null && typeof value === 'object'; },
              isArray: function (value) { return Array.isArray(value); },
              forEach: function (collection, iteratee) {
                if (collection == null) return collection;
                if (Array.isArray(collection)) collection.forEach(iteratee);
                else Object.keys(collection).forEach(function (key) { iteratee(collection[key], key, collection); });
                return collection;
              },
              map: function (collection, iteratee) {
                if (collection == null) return [];
                if (Array.isArray(collection)) return collection.map(iteratee);
                return Object.keys(collection).map(function (key) { return iteratee(collection[key], key, collection); });
              }
            };
            globalThis.dataUtils = {
              parseJson: function (text) { return JSON.parse(String(text)); },
              stringifyJson: function (value) { return JSON.stringify(value); },
              formatDate: function (value) {
                var date = value == null ? new Date() : new Date(value);
                return isNaN(date.getTime()) ? '' : date.toISOString();
              }
            };
            globalThis.exports = {};
            globalThis.Intent = function (action) { return { action: action, extras: {} }; };
            globalThis.IntentFlag = {};
            globalThis.IntentAction = {};
            globalThis.IntentCategory = {};
            globalThis.UINode = __irisUnsupportedCtor('UINode');
            globalThis.Android = __irisUnsupportedCtor('Android');
            globalThis.Tools.System.getAppContext = function () {
              return globalThis.Java.getApplicationContext();
            };
            globalThis.fetch = function (url, options) {
              options = options || {};
              return Promise.resolve().then(function () {
                var headers = options.headers ? JSON.stringify(options.headers) : '{}';
                var body = options.body == null ? '' : String(options.body);
                var response = JSON.parse(irisHost.httpRequest(String(url), String(options.method || 'GET'), headers, body));
                if (response.error) throw new Error(response.error);
                return {
                  ok: !!response.ok, status: response.status || 0, url: response.url || String(url),
                  text: function () { return Promise.resolve(response.body || ''); },
                  json: function () { return Promise.resolve(JSON.parse(response.body || 'null')); }
                };
              });
            };
            function __irisB64ToBytes(base64) {
              var binary = atob(String(base64 || ''));
              var bytes = new Uint8Array(binary.length);
              for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
              return bytes;
            }
            function __irisBytesToB64(value) {
              if (typeof value === 'string') return btoa(unescape(encodeURIComponent(value)));
              if (value instanceof Uint8Array || Array.isArray(value)) {
                var bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
                var binary = '';
                for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                return btoa(binary);
              }
              throw new Error('不支持的二进制值类型');
            }
            function __irisFileInfo(path) { return JSON.parse(irisHost.fileInfo(String(path))); }
            function __irisFile(path) {
              var p = String(path);
              var file = {
                __path: p,
                exists: function () { return !!__irisFileInfo(p).exists; },
                isFile: function () { return !!__irisFileInfo(p).isFile; },
                isDirectory: function () { return !!__irisFileInfo(p).isDirectory; },
                length: function () { return Number(__irisFileInfo(p).length || 0); },
                getName: function () { return String(__irisFileInfo(p).name || ''); },
                getAbsolutePath: function () { return String(__irisFileInfo(p).absolutePath || p); },
                getParent: function () { return __irisFileInfo(p).parent || null; },
                getParentFile: function () { var parent = __irisFileInfo(p).parent; return parent ? __irisFile(parent) : null; },
                lastModified: function () { return Number(__irisFileInfo(p).lastModified || 0); },
                listFiles: function () {
                  return JSON.parse(irisHost.listFiles(p)).map(function (item) { return __irisFile(item.absolutePath); });
                },
                mkdirs: function () { return !!irisHost.mkdirs(p); },
                mkdir: function () { return !!irisHost.mkdirs(p); },
                delete: function () { return !!irisHost.deletePath(p, false); },
                createNewFile: function () {
                  if (__irisFileInfo(p).exists) return false;
                  irisHost.writeText(p, '');
                  return true;
                },
                toPath: function () { return p; },
                toString: function () { return p; }
              };
              return file;
            }
            var __irisJavaDexPrefixes = [];
            function __irisJavaHandle(data) {
              if (data && typeof data === 'object' && data.__irisHandle) {
                return __irisJavaInstanceProxy(String(data.__irisHandle), String(data.__irisClass || ''));
              }
              return data;
            }
            function __irisJavaJson(raw) {
              var parsed = JSON.parse(String(raw || 'null'));
              if (parsed && parsed.success === false) throw new Error(parsed.error || 'Java 调用失败');
              return __irisJavaHandle(parsed ? parsed.data : null);
            }
            function __irisJavaNewInstance(className, args) {
              return __irisJavaJson(irisHost.javaNewInstance(String(className), JSON.stringify(args || [])));
            }
            function __irisJavaCallInstanceRaw(handle, method, args) {
              return __irisJavaJson(irisHost.javaCallInstance(String(handle), String(method), JSON.stringify(args || [])));
            }
            function __irisJavaInstanceProxy(handle, className) {
              var target = {
                __irisHandle: String(handle),
                __irisClass: String(className),
                toString: function () { return String(className) + '@' + String(handle); }
              };
              return new Proxy(target, {
                get: function (t, prop) {
                  if (prop in t) return t[prop];
                  if (prop === 'then') return undefined;
                  if (prop === 'call') {
                    return function (method) {
                      return __irisJavaCallInstanceRaw(handle, method, Array.prototype.slice.call(arguments, 1));
                    };
                  }
                  if (prop === 'get') {
                    return function (field) {
                      return __irisJavaJson(irisHost.javaGetInstanceField(String(handle), String(field)));
                    };
                  }
                  if (prop === 'set') {
                    return function (field, value) {
                      irisHost.javaSetInstanceField(String(handle), String(field), JSON.stringify(value));
                      return true;
                    };
                  }
                  if (prop === 'hasMethod') {
                    return function (method) {
                      return !!irisHost.javaHasInstanceMethod(String(handle), String(method));
                    };
                  }
                  return function () {
                    return __irisJavaCallInstanceRaw(handle, prop, Array.prototype.slice.call(arguments));
                  };
                },
                set: function (t, prop, value) {
                  if (prop === '__irisHandle' || prop === '__irisClass') {
                    t[prop] = value;
                    return true;
                  }
                  irisHost.javaSetInstanceField(String(handle), String(prop), JSON.stringify(value));
                  return true;
                }
              });
            }
            function __irisJavaClassProxy(className) {
              var target = function () {
                return __irisJavaNewInstance(className, Array.prototype.slice.call(arguments));
              };
              return new Proxy(target, {
                get: function (target, method) {
                  if (method === 'then' || typeof method === 'symbol') return undefined;
                  if (method === 'class' || method === 'getName') return className;
                  if (method === 'newInstance') {
                    return function () {
                      return __irisJavaNewInstance(className, Array.prototype.slice.call(arguments));
                    };
                  }
                  if (method === 'exists') return function () { return irisHost.javaClassExists(className); };
                  return function () {
                    var args = Array.prototype.slice.call(arguments).map(function (value) {
                      if (value && value.__irisContext) return { __irisContext: true };
                      if (value && value.__irisHandle) {
                        return { __irisHandle: String(value.__irisHandle), __irisClass: String(value.__irisClass || '') };
                      }
                      return value === undefined ? null : value;
                    });
                    var raw = irisHost.invokeJavaStatic(className, String(method), JSON.stringify(args));
                    return raw === 'null' ? null : raw;
                  };
                }
              });
            }
            function __irisJavaType(name) {
              var className = String(name || '').trim();
              if (__irisJavaDexPrefixes.some(function (prefix) { return className.indexOf(prefix) === 0; })) {
                return __irisJavaClassProxy(className);
              }
              if (className === 'java.io.File') return __irisFile;
              if (className === 'java.lang.String') {
                var JString = function (value) {
                  var text = String(value == null ? '' : value);
                  return {
                    toString: function () { return text; },
                    length: function () { return text.length; },
                    getBytes: function () { return new TextEncoder().encode(text); }
                  };
                };
                JString.valueOf = function (value) { return String(value); };
                JString.format = function (format) {
                  var args = Array.prototype.slice.call(arguments, 1);
                  var index = 0;
                  return String(format).replace(/%[sd]/g, function () { return String(args[index++]); });
                };
                return JString;
              }
              if (className === 'java.lang.StringBuilder') {
                return function (initial) {
                  var text = initial == null ? '' : String(initial);
                  return {
                    append: function (value) { text += String(value); return this; },
                    toString: function () { return text; },
                    length: function () { return text.length; }
                  };
                };
              }
              if (className === 'java.nio.charset.StandardCharsets') {
                return { UTF_8: 'UTF-8', US_ASCII: 'US-ASCII', ISO_8859_1: 'ISO-8859-1' };
              }
              if (className === 'java.nio.file.Files') {
                return {
                  exists: function (path) { return !!irisHost.exists(String(path && path.__path || path)); },
                  readAllBytes: function (path) { return __irisB64ToBytes(irisHost.readBase64(String(path && path.__path || path))); },
                  write: function (path, bytes) { return irisHost.writeBase64(String(path && path.__path || path), __irisBytesToB64(bytes)); },
                  delete: function (path) { return irisHost.deletePath(String(path && path.__path || path), false); },
                  deleteIfExists: function (path) { return irisHost.deletePath(String(path && path.__path || path), false); },
                  createDirectories: function (path) { return irisHost.mkdirs(String(path && path.__path || path)); },
                  createDirectory: function (path) { return irisHost.mkdirs(String(path && path.__path || path)); },
                  copy: function (source, target) {
                    var from = String(source && source.__path || source);
                    var to = String(target && target.__path || target);
                    irisHost.writeBase64(to, irisHost.readBase64(from));
                    return to;
                  },
                  move: function (source, target) {
                    var from = String(source && source.__path || source);
                    var to = String(target && target.__path || target);
                    irisHost.writeBase64(to, irisHost.readBase64(from));
                    irisHost.deletePath(from, false);
                    return to;
                  }
                };
              }
              if (className === 'java.security.MessageDigest') {
                return {
                  getInstance: function (algorithm) {
                    var content = '';
                    return {
                      update: function (value) { content += String(value); },
                      digest: function () {
                        var hex = irisHost.digestHex(String(algorithm), content);
                        var bytes = new Uint8Array(hex.length / 2);
                        for (var i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
                        return bytes;
                      }
                    };
                  }
                };
              }
              if (className === 'java.util.Base64') {
                return {
                  getEncoder: function () {
                    return {
                      encodeToString: function (bytes) { return __irisBytesToB64(bytes); },
                      encode: function (bytes) { return __irisB64ToBytes(__irisBytesToB64(bytes)); }
                    };
                  },
                  getDecoder: function () {
                    return { decode: function (text) { return __irisB64ToBytes(String(text)); } };
                  }
                };
              }
              if (className === 'java.util.ArrayList') {
                return function () {
                  var list = [];
                  list.add = function (value) { list.push(value); return true; };
                  list.get = function (index) { return list[index]; };
                  list.size = function () { return list.length; };
                  list.toArray = function () { return list.slice(); };
                  return list;
                };
              }
              if (className === 'java.util.HashMap') {
                return function () {
                  var map = {};
                  var keys = [];
                  map.put = function (key, value) {
                    var name = String(key);
                    if (!Object.prototype.hasOwnProperty.call(map, name)) keys.push(name);
                    map[name] = value;
                    return value;
                  };
                  map.get = function (key) { return map[String(key)]; };
                  map.containsKey = function (key) { return Object.prototype.hasOwnProperty.call(map, String(key)); };
                  map.remove = function (key) {
                    var name = String(key);
                    var index = keys.indexOf(name);
                    if (index >= 0) keys.splice(index, 1);
                    delete map[name];
                  };
                  map.size = function () { return keys.length; };
                  map.keySet = function () { return keys.slice(); };
                  return map;
                };
              }
              if (className === 'java.io.FileOutputStream') {
                return function (path) {
                  var target = String(path && path.__path || path);
                  var chunks = [];
                  function flush() {
                    if (!chunks.length) return;
                    var bytes = [];
                    chunks.forEach(function (value) {
                      var part = typeof value === 'string' ? new TextEncoder().encode(value) : new Uint8Array(value);
                      for (var i = 0; i < part.length; i++) bytes.push(part[i]);
                    });
                    irisHost.writeBase64(target, __irisBytesToB64(new Uint8Array(bytes)));
                    chunks = [];
                  }
                  return {
                    write: function (value) { chunks.push(value); },
                    flush: flush,
                    close: flush
                  };
                };
              }
              if (className === 'java.io.OutputStreamWriter') {
                return function (stream) {
                  return {
                    write: function (value) { stream.write(String(value)); },
                    flush: function () { if (stream.flush) stream.flush(); },
                    close: function () { if (stream.close) stream.close(); }
                  };
                };
              }
              if (className === 'java.io.BufferedWriter') {
                return function (writer) {
                  return {
                    write: function (value) { writer.write(String(value)); },
                    newLine: function () { writer.write('\n'); },
                    flush: function () { if (writer.flush) writer.flush(); },
                    close: function () { if (writer.close) writer.close(); }
                  };
                };
              }
              if (className === 'java.lang.System') {
                return {
                  currentTimeMillis: function () { return Date.now(); },
                  nanoTime: function () { return Date.now() * 1000000; }
                };
              }
              if (className === 'java.util.Date') {
                return function (value) {
                  return value == null ? new Date() : new Date(Number(value));
                };
              }
              throw new Error('Java 类不在受支持白名单：' + className);
            }
            globalThis.Java = {
              type: __irisJavaType,
              use: __irisJavaType,
              importClass: __irisJavaType,
              loadDex: function (path, options) {
                irisHost.loadDex(String(path));
                var prefixes = options && options.childFirstPrefixes;
                if (prefixes && prefixes.length) {
                  prefixes.forEach(function (prefix) {
                    if (__irisJavaDexPrefixes.indexOf(prefix) < 0) __irisJavaDexPrefixes.push(String(prefix));
                  });
                }
                return true;
              },
              newInstance: function (name) {
                var constructor = __irisJavaType(name);
                return constructor.apply(null, Array.prototype.slice.call(arguments, 1));
              },
              callStatic: function (name, method) {
                var target = __irisJavaType(name);
                return target[method].apply(target, Array.prototype.slice.call(arguments, 2));
              },
              implement: function () { throw new Error('Java 接口代理不在 WebView 沙箱支持范围'); },
              proxy: function () { throw new Error('Java 接口代理不在 WebView 沙箱支持范围'); },
              classExists: function (name) {
                try { return irisHost.javaClassExists(String(name || '').trim()); }
                catch (error) { return false; }
              }
            };
            globalThis.Java.getApplicationContext = function () {
              return {
                __irisContext: true,
                getSharedPreferences: function (name) {
                  function readPref(key, fallback) {
                    try {
                      return JSON.parse(irisHost.getPref(String(name), String(key), JSON.stringify(fallback)));
                    } catch (error) {
                      return fallback;
                    }
                  }
                  function editor() {
                    var changes = {};
                    var self = {
                      putString: function (key, value) { changes[key] = String(value); return self; },
                      putBoolean: function (key, value) { changes[key] = !!value; return self; },
                      putInt: function (key, value) { changes[key] = Number(value) | 0; return self; },
                      putLong: function (key, value) { changes[key] = Number(value); return self; },
                      putFloat: function (key, value) { changes[key] = Number(value); return self; },
                      putStringSet: function (key, value) { changes[key] = Array.prototype.slice.call(value || []); return self; },
                      remove: function (key) { changes[key] = null; return self; },
                      clear: function () { changes = {}; return self; },
                      apply: function () {
                        Object.keys(changes).forEach(function (key) {
                          var value = changes[key];
                          irisHost.setPref(String(name), key, value === null ? 'null' : JSON.stringify(value));
                        });
                      },
                      commit: function () { self.apply(); return true; }
                    };
                    return self;
                  }
                  return {
                    getString: function (key, fallback) {
                      var value = readPref(key, fallback == null ? '' : fallback);
                      return value == null ? (fallback == null ? '' : fallback) : String(value);
                    },
                    getBoolean: function (key, fallback) {
                      var value = readPref(key, fallback == null ? false : fallback);
                      return typeof value === 'boolean' ? value : !!(fallback == null ? false : fallback);
                    },
                    getInt: function (key, fallback) {
                      var value = readPref(key, fallback == null ? 0 : fallback);
                      return typeof value === 'number' ? Math.trunc(value) : (fallback == null ? 0 : fallback);
                    },
                    getLong: function (key, fallback) {
                      var value = readPref(key, fallback == null ? 0 : fallback);
                      return typeof value === 'number' ? value : (fallback == null ? 0 : fallback);
                    },
                    getFloat: function (key, fallback) {
                      var value = readPref(key, fallback == null ? 0 : fallback);
                      return typeof value === 'number' ? value : (fallback == null ? 0 : fallback);
                    },
                    getStringSet: function (key, fallback) {
                      var value = readPref(key, fallback || []);
                      return Array.isArray(value) ? value : (fallback || []);
                    },
                    edit: editor
                  };
                }
              };
            };
            function __irisJavaPackage(prefix) {
              return new Proxy({}, {
                get: function (target, prop) {
                  if (typeof prop !== 'string' || prop === 'then') return undefined;
                  var name = prefix ? prefix + '.' + prop : prop;
                  if (/^[A-Z]/.test(prop)) return __irisJavaType(name);
                  return __irisJavaPackage(name);
                }
              });
            }
            var __irisJavaBase = globalThis.Java;
            globalThis.Java = new Proxy(__irisJavaBase, {
              get: function (target, prop) {
                if (prop in target) return target[prop];
                if (typeof prop !== 'string') return undefined;
                if (prop === 'java' || prop === 'android' || prop === 'kotlin') {
                  return __irisJavaPackage(prop);
                }
                return undefined;
              }
            });
            globalThis.Java.package = __irisJavaPackage;
            globalThis.Kotlin = globalThis.Java;
            globalThis.NativeInterface = {
              callTool: __irisCallTool,
              callToolAsync: __irisCompat.callToolAsync,
              callToolAsyncStreaming: __irisCompat.callToolAsyncStreaming,
              logInfo: function (message) { irisHost.log(String(message)); },
              logError: function (message) { irisHost.log(String(message)); },
              logDebug: function (message, data) { irisHost.log(String(message) + (data ? ' ' + String(data) : '')); },
              reportError: function (type, message, line, stack) {
                try { irisHost.log('[reportError] ' + String(type) + ': ' + String(message) + ' @' + String(line) + '\n' + String(stack || '')); } catch (error) { }
              },
              getPluginConfigDir: function () { return irisHost.configDir(); },
              registerImageFromBase64: function (base64, mimeType) {
                var dir = irisHost.configDir() + '/images';
                irisHost.mkdirs(dir);
                var path = dir + '/' + Date.now() + '-' + Math.random().toString(16).slice(2) + '.bin';
                irisHost.writeBase64(path, String(base64));
                return '<link type="image" path="' + path + '" mime="' + String(mimeType || '') + '"/>';
              },
              registerImageFromPath: function (path) {
                return '<link type="image" path="' + String(path) + '"/>';
              },
              javaClassExists: function (name) { return irisHost.javaClassExists(String(name)); },
              javaLoadDex: function (path, optionsJson) {
                try { irisHost.loadDex(String(path)); return JSON.stringify({ success: true, data: { type: 'dex', path: String(path) } }); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaLoadJar: function (path) {
                try { irisHost.loadJar(String(path)); return JSON.stringify({ success: true, data: { type: 'jar', path: String(path) } }); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaListLoadedCodePaths: function () {
                try { return JSON.stringify({ success: true, data: JSON.parse(irisHost.listLoadedCodePaths()) }); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaGetApplicationContext: function () { return JSON.stringify({ success: true, data: { __irisContext: true } }); },
              javaGetCurrentActivity: function () { return JSON.stringify({ success: false, error: 'IRIS 未提供 Activity 句柄' }); },
              javaNewInstance: function (className, argsJson) {
                try { return irisHost.javaNewInstance(String(className), String(argsJson || '[]')); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaCallStatic: function (className, methodName, argsJson) {
                try { return JSON.stringify({ success: true, data: irisHost.invokeJavaStatic(String(className), String(methodName), String(argsJson || '[]')) }); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaCallInstance: function (handle, methodName, argsJson) {
                try { return irisHost.javaCallInstance(String(handle), String(methodName), String(argsJson || '[]')); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaHasInstanceMethod: function (handle, methodName) {
                try { return JSON.stringify({ success: true, data: !!irisHost.javaHasInstanceMethod(String(handle), String(methodName)) }); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaGetStaticField: function () { return JSON.stringify({ success: false, error: 'IRIS 未支持静态字段读取' }); },
              javaSetStaticField: function () { return JSON.stringify({ success: false, error: 'IRIS 未支持静态字段写入' }); },
              javaGetInstanceField: function (handle, fieldName) {
                try { return irisHost.javaGetInstanceField(String(handle), String(fieldName)); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              },
              javaSetInstanceField: function (handle, fieldName, valueJson) {
                try { return irisHost.javaSetInstanceField(String(handle), String(fieldName), String(valueJson || 'null')); }
                catch (error) { return JSON.stringify({ success: false, error: String(error && error.message || error) }); }
              }
            };
            [
              'registerToolPkgToolboxUiModule', 'registerToolPkgUiRoute', 'registerToolPkgNavigationEntry',
              'registerToolPkgDesktopWidget', 'registerToolPkgAppLifecycleHook', 'registerToolPkgMessageProcessingPlugin',
              'registerToolPkgXmlRenderPlugin', 'registerToolPkgInputMenuTogglePlugin', 'registerToolPkgChatInputHook',
              'registerToolPkgChatViewHook', 'registerToolPkgChatMessageHook', 'registerToolPkgChatMessageMenuItem',
              'registerToolPkgChatRuntimeHook', 'registerToolPkgToolLifecycleHook', 'registerToolPkgPromptInputHook',
              'registerToolPkgPromptHistoryHook', 'registerToolPkgPromptEstimateHistoryHook',
              'registerToolPkgSystemPromptComposeHook', 'registerToolPkgToolPromptComposeHook',
              'registerToolPkgPromptFinalizeHook', 'registerToolPkgPromptEstimateFinalizeHook',
              'registerToolPkgSummaryGenerateHook', 'registerToolPkgAiProvider'
            ].forEach(function (name) {
              globalThis.NativeInterface[name] = globalThis[name];
            });
            globalThis.toolCall = __irisCallTool;
            globalThis.__irisSendIntermediate = function () {};
            globalThis.complete = function (value) { globalThis.__irisComplete(value); };
            </script></head><body></body></html>
        """.trimIndent()

        private fun String.js(): String = Json.encodeToString(String.serializer(), this)
    }
}
