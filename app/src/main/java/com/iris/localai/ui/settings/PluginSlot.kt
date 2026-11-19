package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.plugin.InstalledPlugin
import com.iris.localai.plugin.PluginHookDescriptor
import com.iris.localai.plugin.resolveUiModuleId
import com.iris.localai.plugin.uiRouteModuleId
import kotlinx.coroutines.launch

/**
 * 在宿主页面里内联渲染插件注册的 slot。slot 只在插件启用时出现，
 * 事件与 UI 失效都走与插件面板相同的运行时。
 */
@Composable
internal fun PluginSlot(
    surface: String,
    modifier: Modifier = Modifier,
    viewModel: PluginViewModel = viewModel(),
) {
    val registrations by viewModel.registrations.collectAsStateWithLifecycle()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    LaunchedEffect(surface) { viewModel.loadRegistrations() }
    val slots = remember(registrations, plugins, surface) {
        val enabled = plugins.filter { it.enabled }.associateBy { it.id }
        registrations.values
            .flatMap { it.ui }
            .filter { it.type == "plugin_slot" && it.surface.equals(surface, ignoreCase = true) }
            .filter { enabled.containsKey(it.pluginId) }
            .sortedWith(compareBy({ it.order }, { it.pluginName }, { it.id }))
    }
    if (slots.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slots.forEach { entry ->
            val plugin = plugins.firstOrNull { it.id == entry.pluginId } ?: return@forEach
            if (!plugin.enabled) return@forEach
            key(entry.pluginId, entry.id) {
                PluginSlotBlock(
                    plugin = plugin,
                    entry = entry,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PluginSlotBlock(
    plugin: InstalledPlugin,
    entry: PluginHookDescriptor,
    viewModel: PluginViewModel,
    modifier: Modifier,
) {
    val registrations by viewModel.registrations.collectAsStateWithLifecycle()
    val moduleId = remember(registrations, plugin.id, entry.id) {
        registrations[plugin.id]?.resolveUiModuleId(entry) ?: entry.uiRouteModuleId()
    }
    if (moduleId.isBlank()) return
    val scope = rememberCoroutineScope()
    val contents = remember(plugin.id, moduleId) { mutableStateMapOf<String, String>() }
    val key = "${plugin.id}:$moduleId"

    suspend fun reload() {
        viewModel.renderPluginUi(plugin, moduleId)
            .onSuccess { contents[key] = it }
    }

    LaunchedEffect(plugin.id, moduleId) {
        reload()
        viewModel.uiInvalidations.collect { invalidated ->
            if (invalidated == moduleId) reload()
        }
    }

    contents[key]?.let { raw ->
        PluginInlineUi(raw, modifier) { handler, value ->
            if (!handler.isNullOrBlank()) {
                scope.launch {
                    viewModel.dispatchPluginUiEvent(plugin, moduleId, handler, value)
                        .onSuccess { contents[key] = it }
                }
            }
        }
    }
}
