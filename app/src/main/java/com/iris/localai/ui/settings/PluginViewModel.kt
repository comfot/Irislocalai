package com.iris.localai.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.plugin.InstalledPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PluginViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph
    private val store = graph.plugins

    val plugins = store.plugins
    val busy = store.busy
    val registrations = graph.pluginRuntime.registrations
    val registrationErrors = graph.pluginRuntime.errors

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun importUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { readPluginZip(context, uri) }
            bytes.onSuccess { import(it, uri.lastPathSegment.orEmpty()) }
                .onFailure { _error.value = it.message ?: "无法读取插件包" }
        }
    }

    fun import(bytes: ByteArray, source: String) {
        _error.value = null
        viewModelScope.launch {
            runCatching { store.installZip(bytes, source) }
                .onSuccess { _message.value = "已安装：${it.name}" }
                .onFailure { _error.value = it.message ?: "插件安装失败" }
        }
    }

    fun installUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        _error.value = null
        viewModelScope.launch {
            _message.value = "正在下载插件包…"
            runCatching { store.installUrl(trimmed) }
                .onSuccess { _message.value = "已安装：${it.name}" }
                .onFailure { _error.value = it.message ?: "插件下载失败" }
        }
    }

    fun setEnabled(plugin: InstalledPlugin, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.setEnabled(plugin, enabled) }
                .onFailure { _error.value = it.message ?: "插件状态更新失败" }
        }
    }

    fun delete(plugin: InstalledPlugin) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = runCatching { store.delete(plugin) }
                .onFailure { _error.value = it.message ?: "删除失败" }
                .getOrDefault(false)
            _message.value = if (removed) "已删除：${plugin.name}" else null
            if (!removed && _error.value == null) _error.value = "删除失败"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun reportError(message: String) {
        _error.value = message
    }

    fun loadRegistrations() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.pluginRuntime.ensureAllRegistrations() }
        }
    }
}
