package com.iris.localai.plugin

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
import com.iris.localai.agent.ToolRegistry
import com.iris.localai.data.ToolPermission
import com.iris.localai.data.RemoteFetch
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
    ): String = session(plugin).callTool(subpackageId, toolName, arguments)

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

    suspend fun transformSystemPrompt(chatId: Long, prompt: String): String {
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

    suspend fun notifyChatRuntime(
        chatId: Long,
        state: String,
        message: String? = null,
        toolName: String? = null,
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
                put("currentTurnToolInvocationCount", 0)
                put("activeConversationCount", if (state == "idle") 0 else 1)
                put("currentSessionToolCount", 0)
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
        compatTool(name, arguments)?.let { return it }
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

    private suspend fun compatTool(name: String, arguments: JsonObject): String? = withContext(Dispatchers.IO) {
        when (name.trim()) {
            "make_directory" -> {
                val path = arguments.text("path") ?: error("缺少 path 参数")
                if (!File(path).mkdirs() && !File(path).isDirectory) error("创建目录失败：$path")
                "{\"success\":true}"
            }
            "delete_file" -> {
                val path = arguments.text("path") ?: error("缺少 path 参数")
                File(path).delete()
                "{\"success\":true}"
            }
            "list_files" -> {
                val path = arguments.text("path") ?: "."
                val dir = File(path)
                if (!dir.isDirectory) error("目录不存在：$path")
                val names = dir.list()?.sorted().orEmpty()
                buildJsonObject { put("entries", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }) }.toString()
            }
            "read_file" -> {
                val path = arguments.text("path") ?: error("缺少 path 参数")
                val file = File(path)
                if (!file.isFile) error("文件不存在：$path")
                if (file.length() > 4L * 1024 * 1024) error("文件超过 4 MB")
                buildJsonObject {
                    put("path", file.absolutePath)
                    put("content", file.readText())
                }.toString()
            }
            "write_file" -> {
                val path = arguments.text("path") ?: error("缺少 path 参数")
                val content = arguments.text("content") ?: error("缺少 content 参数")
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeText(content)
                "{\"success\":true}"
            }
            "http_request" -> {
                val url = arguments.text("url") ?: error("缺少 url 参数")
                val method = arguments.text("method")?.uppercase() ?: "GET"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "IRIS-LocalAI-Plugin/1.0")
                    (arguments["headers"] as? JsonObject)?.forEach { (key, value) ->
                        (value as? JsonPrimitive)?.contentOrNull?.let { setRequestProperty(key, it) }
                    }
                }
                val body = arguments.text("body")
                if (body != null && method != "GET" && method != "HEAD") {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                try {
                    val code = connection.responseCode
                    val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..399) error("HTTP $code: ${text.take(300)}")
                    text
                } finally {
                    connection.disconnect()
                }
            }
            "download_file" -> {
                val url = arguments.text("url") ?: error("缺少 url 参数")
                val path = arguments.text("destination") ?: arguments.text("path") ?: error("缺少 destination 参数")
                val target = File(path)
                target.parentFile?.mkdirs()
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "IRIS-LocalAI-Plugin/1.0")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..399) error("下载失败 HTTP $code")
                    connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                    buildJsonObject { put("success", true); put("path", target.absolutePath) }.toString()
                } finally {
                    connection.disconnect()
                }
            }
            else -> null
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
    }
}

private class PluginSession(
    private val context: Context,
    private val plugin: InstalledPlugin,
    private val toolExecutor: suspend (String, JsonObject, String) -> String,
    private val terminal: PluginTerminalHost?,
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
                    scope = scope,
                    webViewProvider = { webView },
                    onUiInvalidate = onUiInvalidate,
                    resources = resources,
                    wasm = wasm,
                    pending = pending,
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

    suspend fun callTool(subpackageId: String, toolName: String, arguments: JsonObject): String {
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
            .replace("%ARGS%", Json.encodeToString(String.serializer(), Json.encodeToString(JsonObject.serializer(), arguments))), requestId)
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

    private suspend fun call(script: String, requestId: String): String = withContext(Dispatchers.Main.immediate) {
        callMutex.withLock {
            val view = webView ?: error("插件会话尚未启动")
            val result = CompletableDeferred<String>()
            pending[requestId] = result
            try {
                view.evaluateJavascript(script, null)
                withTimeout(30_000) { result.await() }
            } finally {
                pending.remove(requestId)
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
                icon = obj.text("icon").orEmpty(),
                screen = obj.text("screen").orEmpty(),
                route = obj.text("route").orEmpty(),
                surface = obj.text("surface").orEmpty(),
            )
            when (type) {
                "toolbox_ui", "ui_route", "navigation", "desktop_widget" -> ui += descriptor
                "ai_provider" -> providers += id
                else -> hooks += descriptor
            }
        }
        return PluginRegistrations(hooks = hooks, ui = ui, aiProviders = providers)
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
        private val scope: CoroutineScope,
        private val webViewProvider: () -> WebView?,
        private val onToast: (String) -> Unit,
        private val onUiInvalidate: (String) -> Unit,
        private val resources: Map<String, File>,
        private val wasm: Map<String, File>,
        private val pending: ConcurrentHashMap<String, CompletableDeferred<String>>,
        private val ready: CompletableDeferred<Unit>,
        private val registrations: CompletableDeferred<String>,
        private val scriptError: AtomicReference<String?>,
        private val moduleErrors: ConcurrentHashMap<String, String>,
    ) {
        private var dexLoader: ClassLoader? = null

        @JavascriptInterface
        fun loadDex(path: String): String {
            val file = File(path).canonicalFile
            val root = pluginDir.canonicalFile
            if (!file.path.startsWith(root.path + File.separator)) {
                throw IllegalArgumentException("dex 必须位于插件目录内")
            }
            if (!file.isFile) throw IllegalArgumentException("dex 文件不存在：$path")
            if (file.length() > 16 * 1024 * 1024) throw IllegalArgumentException("dex 超过 16 MB")
            val bytes = file.readBytes()
            dexLoader = dalvik.system.InMemoryDexClassLoader(
                java.nio.ByteBuffer.wrap(bytes),
                context.classLoader,
            )
            return "true"
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

        private fun convertJavaArgument(element: JsonElement, type: Class<*>): Any? {
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
        fun callTool(toolName: String, argsJson: String, callbackId: String) {
            scope.launch {
                val result = runCatching {
                    val arguments = runCatching {
                        Json.parseToJsonElement(argsJson).jsonObject
                    }.getOrElse { JsonObject(emptyMap()) }
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

        private fun resolve(path: String): File? {
            val normalized = path.replace('\\', '/').trim()
            if (normalized.split('/').any { it == ".." }) return null
            val absolute = normalized.startsWith("/")
            return sequenceOf(pluginDir, configDir).mapNotNull { root ->
                val base = root.canonicalFile
                val file = if (absolute) {
                    File(normalized).canonicalFile
                } else {
                    File(base, normalized).canonicalFile
                }
                if (file.path == base.path || file.path.startsWith(base.path + File.separator)) file else null
            }.firstOrNull()
        }

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
              if (list.length === 1 && list[0] && typeof list[0] === 'object' && !Array.isArray(list[0])) {
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
              var parsed = __irisToolArgs(arguments);
              if (!parsed.name) return Promise.reject(new Error('toolCall 缺少工具名'));
              var id = 'tool-' + Date.now() + '-' + Math.random().toString(16).slice(2);
              return new Promise(function (resolve, reject) {
                globalThis.__irisToolPending[id] = { resolve: resolve, reject: reject };
                try {
                  irisHost.callTool(parsed.name, JSON.stringify(parsed.params), id);
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
                globalThis.__irisCompat.callToolAsync(callbackId, toolType, toolName, paramsJson);
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
              var values = props && typeof props === 'object' ? props : {};
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
              readResource: function (key) { return Promise.resolve(irisHost.readResource(String(key))); },
              callTool: __irisCallTool,
              callToolAsync: __irisCompat.callToolAsync,
              getConfigDir: function () { return irisHost.configDir(); },
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
            globalThis.getEnv = function (key) {
              try { return irisHost.getEnv(String(key)); } catch (error) { return ''; }
            };
            globalThis.setEnv = function (key, value) {
              try { return Promise.resolve(irisHost.setEnv(String(key), String(value))); }
              catch (error) { return Promise.reject(error); }
            };
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
            function __irisJavaClassProxy(className) {
              return new Proxy({}, {
                get: function (target, method) {
                  if (method === 'then' || typeof method === 'symbol') return undefined;
                  if (method === 'class' || method === 'getName') return className;
                  return function () {
                    var args = Array.prototype.slice.call(arguments).map(function (value) {
                      if (value && value.__irisContext) return { __irisContext: true };
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
                return [
                  'java.io.File', 'java.lang.String', 'java.lang.StringBuilder',
                  'java.nio.charset.StandardCharsets', 'java.nio.file.Files',
                  'java.security.MessageDigest', 'java.util.Base64', 'java.util.ArrayList',
                  'java.util.HashMap', 'java.io.FileOutputStream', 'java.io.OutputStreamWriter',
                  'java.io.BufferedWriter', 'java.lang.System', 'java.util.Date'
                ].indexOf(String(name || '').trim()) >= 0;
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
            globalThis.Kotlin = globalThis.Java;
            globalThis.NativeInterface = {
              callTool: __irisCallTool,
              callToolAsync: __irisCompat.callToolAsync,
              logInfo: function (message) { irisHost.log(String(message)); },
              logError: function (message) { irisHost.log(String(message)); }
            };
            globalThis.toolCall = __irisCallTool;
            globalThis.complete = function (value) { globalThis.__irisComplete(value); };
            </script></head><body></body></html>
        """.trimIndent()

        private fun String.js(): String = Json.encodeToString(String.serializer(), this)
    }
}
