package com.iris.localai.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.plugin.InstalledPlugin

@Composable
internal fun PluginSettingsPage(
    plugin: InstalledPlugin,
    moduleId: String,
    title: String,
    onClose: () -> Unit,
) {
    val viewModel: PluginViewModel = viewModel()
    PluginUiDialogHost(
        plugin = plugin,
        moduleId = moduleId,
        title = title,
        renderUi = { viewModel.renderPluginUi(plugin, moduleId) },
        dispatchUiEvent = { eventId, value ->
            viewModel.dispatchPluginUiEvent(plugin, moduleId, eventId, value)
        },
        callUiController = { controllerId, interfaceName, methodName, args ->
            viewModel.callPluginUiController(plugin, moduleId, controllerId, interfaceName, methodName, args)
        },
        callUiHandler = { eventId, payload ->
            viewModel.callPluginUiHandler(plugin, moduleId, eventId, payload)
        },
        uiInvalidations = viewModel.uiInvalidations,
        onDismiss = onClose,
    )
}
