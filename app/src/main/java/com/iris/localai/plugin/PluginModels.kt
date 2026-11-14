package com.iris.localai.plugin

enum class PluginOrigin(val label: String) {
    OPERIT("Operit ToolPkg"),
    IRIS("IRIS 插件"),
}

data class PluginToolParameter(
    val name: String,
    val description: String,
    val type: String,
    val required: Boolean,
)

data class PluginToolSpec(
    val name: String,
    val description: String,
    val parameters: List<PluginToolParameter> = emptyList(),
    val advice: Boolean = false,
)

data class PluginSubpackage(
    val id: String,
    val entry: String,
)

data class PluginRequirement(
    val id: String,
    val description: String,
    val minVersion: String? = null,
    val maxVersion: String? = null,
)

data class PluginResource(
    val key: String,
    val path: String,
    val mime: String = "",
)

data class PluginWasmModule(
    val id: String,
    val path: String,
    val exports: List<String> = emptyList(),
    val abi: String = "assemblyscript",
)

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: String,
    val description: String,
    val author: List<String>,
    val main: String,
    val enabledByDefault: Boolean,
    val subpackages: List<PluginSubpackage>,
    val requirements: List<PluginRequirement>,
    val resources: List<PluginResource> = emptyList(),
    val wasmModules: List<PluginWasmModule> = emptyList(),
    val toolSpecs: List<PluginToolSpec>,
    val logo: String?,
    val origin: PluginOrigin,
)

data class PluginSubpackageRuntime(
    val id: String,
    val entry: String,
    val tools: List<PluginToolSpec>,
)

data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: List<String>,
    val origin: PluginOrigin,
    val enabled: Boolean,
    val installedAt: Long,
    val source: String,
    val apiVersion: String,
    val main: String,
    val requirements: List<PluginRequirement>,
    val subpackages: List<PluginSubpackageRuntime>,
    val resources: List<PluginResource> = emptyList(),
    val wasmModules: List<PluginWasmModule> = emptyList(),
    val logoPath: String?,
    val directory: String,
) {
    val tools: List<PluginToolSpec>
        get() = subpackages.flatMap { it.tools }

    val toolCount: Int get() = tools.size
}

data class PluginToolBinding(
    val plugin: InstalledPlugin,
    val subpackage: PluginSubpackageRuntime,
    val tool: PluginToolSpec,
)

fun sanitizePluginId(raw: String): String {
    val cleaned = raw.trim().map { ch ->
        if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
    }.joinToString("")
    return cleaned.trim('.', '_', '-').ifBlank { "plugin" }
}

data class PluginHookDescriptor(
    val pluginId: String,
    val pluginName: String,
    val type: String,
    val id: String,
    val title: String = "",
    val senders: List<String> = emptyList(),
    val order: Int = 0,
    val icon: String = "",
    val screen: String = "",
    val route: String = "",
    val surface: String = "",
)

fun PluginHookDescriptor.uiRouteModuleId(): String {
    val routeId = route.trim()
    val marker = ":ui:"
    val routeCandidate = if (routeId.contains(marker)) {
        routeId.substringAfterLast(marker).trim()
    } else {
        routeId
    }
    val candidate = routeCandidate.ifBlank { screen.trim().ifBlank { id.trim() } }
    return candidate.removeSuffix("_entry").removeSuffix("_nav").ifBlank { id.trim() }
}

fun PluginRegistrations.resolveUiModuleId(entry: PluginHookDescriptor): String {
    val direct = entry.uiRouteModuleId()
    val screens = ui.filter { it.type == "ui_route" || it.type == "toolbox_ui" }
    if (screens.any { it.id == direct }) return direct
    screens.firstOrNull { it.uiRouteModuleId() == direct }?.let { return it.id }
    if (entry.route.isNotBlank()) {
        screens.firstOrNull { it.route == entry.route }?.let { return it.id }
    }
    return screens.singleOrNull()?.id ?: direct
}

data class PluginRegistrations(
    val hooks: List<PluginHookDescriptor> = emptyList(),
    val ui: List<PluginHookDescriptor> = emptyList(),
    val aiProviders: List<String> = emptyList(),
) {
    fun hooksOf(type: String): List<PluginHookDescriptor> =
        hooks.filter { it.type == type }.sortedWith(compareBy({ it.pluginId }, { it.order }, { it.id }))

    val isEmpty: Boolean get() = hooks.isEmpty() && ui.isEmpty() && aiProviders.isEmpty()
}

data class ChatInputDecision(
    val action: String,
    val text: String,
    val message: String? = null,
)

data class ToolInterceptDecision(
    val blocked: Boolean,
    val reason: String = "",
)
