package com.iris.localai.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iris.localai.data.RemoteFetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
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
) {

    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, PluginSession>()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _registrations = MutableStateFlow<Map<String, PluginRegistrations>>(emptyMap())
    val registrations: StateFlow<Map<String, PluginRegistrations>> = _registrations.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

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
                onRegistrations = { id, registrations ->
                    _registrations.update { it + (id to registrations) }
                    _errors.update { it - id }
                },
                onError = { id, message ->
                    _errors.update { it + (id to message) }
                },
            )
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
    private val onRegistrations: (String, PluginRegistrations) -> Unit,
    private val onError: (String, String) -> Unit,
) {

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
                    pluginDir = pluginDir,
                    configDir = configDir,
                    resources = resources,
                    wasm = wasm,
                    pending = pending,
                    ready = ready,
                    registrations = registrationsJson,
                    scriptError = scriptError,
                    moduleErrors = moduleErrors,
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
                    buildLoadScript(mainSource, subSources, subEntries),
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
        private val pluginDir: File,
        private val configDir: File,
        private val resources: Map<String, File>,
        private val wasm: Map<String, File>,
        private val pending: ConcurrentHashMap<String, CompletableDeferred<String>>,
        private val ready: CompletableDeferred<Unit>,
        private val registrations: CompletableDeferred<String>,
        private val scriptError: AtomicReference<String?>,
        private val moduleErrors: ConcurrentHashMap<String, String>,
    ) {

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
            return runCatching {
                if (!RemoteFetch.isPublicHttpUrl(url)) throw IOException("仅允许公网 HTTP/HTTPS")
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
                    if (!RemoteFetch.isPublicHttpUrl(connection.url.toString())) throw IOException("重定向到非公网地址")
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
                    """{"ok":${code in 200..299},"status":$code,"url":${connection.url.toString().json()},"body":${responseBody.json()}}"""
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                """{"ok":false,"status":0,"error":${error.message.orEmpty().json()}}"""
            }
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
            globalThis.__irisHooks = {};
            globalThis.__irisProviders = {};
            globalThis.__irisIpc = {};
            globalThis.__irisComplete = function () {};
            globalThis.console = {
              log: function () { irisHost.log(Array.prototype.join.call(arguments, ' ')); },
              warn: function () { irisHost.log(Array.prototype.join.call(arguments, ' ')); },
              error: function () { irisHost.log(Array.prototype.join.call(arguments, ' ')); }
            };
            function __irisRegister(type, def) {
              if (!def || typeof def !== 'object') return;
              var id = String(def.id || '');
              if (!id) return;
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
            globalThis.Tools = {
              Files: {
                read: function (path) { return irisHost.readText(String(path)); },
                write: function (path, content) { return irisHost.writeText(String(path), String(content)); },
                exists: function (path) { return irisHost.exists(String(path)); },
                info: function (path) { return JSON.parse(irisHost.fileInfo(String(path))); },
                list: function (path) { return JSON.parse(irisHost.listFiles(String(path))); },
                deleteFile: function (path, recursive) { return irisHost.deletePath(String(path), !!recursive); },
                mkdir: function (path) { return irisHost.mkdirs(String(path)); },
                readBinary: function (path) { return irisHost.readBase64(String(path)); },
                writeBinary: function (path, base64) { return irisHost.writeBase64(String(path), String(base64)); }
              },
              System: {
                sleep: function (milliseconds) { irisHost.sleep(Number(milliseconds || 0)); }
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
            function __irisJavaType(name) {
              var className = String(name || '').trim();
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
            globalThis.Kotlin = globalThis.Java;
            globalThis.NativeInterface = {
              callTool: function () { throw new Error('NativeInterface.callTool 不支持；请直接实现工具逻辑'); },
              callToolAsync: function () { throw new Error('NativeInterface.callToolAsync 不支持'); },
              logInfo: function (message) { irisHost.log(String(message)); },
              logError: function (message) { irisHost.log(String(message)); }
            };
            globalThis.toolCall = function () { throw new Error('toolCall 跨工具调用在当前宿主未实现'); };
            globalThis.complete = function (value) { globalThis.__irisComplete(value); };
            </script></head><body></body></html>
        """.trimIndent()

        private fun String.js(): String = Json.encodeToString(String.serializer(), this)
    }
}
