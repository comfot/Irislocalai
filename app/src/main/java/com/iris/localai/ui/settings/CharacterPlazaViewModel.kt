package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.character.CharacterPlazaClient
import com.iris.localai.character.PlazaCreator
import com.iris.localai.character.PlazaSite
import com.iris.localai.data.RemoteFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

class CharacterPlazaViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    private val _sites = MutableStateFlow<List<PlazaSite>>(CharacterPlazaClient.builtInSites)
    val sites: StateFlow<List<PlazaSite>> = _sites.asStateFlow()

    private val _creators = MutableStateFlow<List<PlazaCreator>>(emptyList())
    val creators: StateFlow<List<PlazaCreator>> = _creators.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _role = MutableStateFlow("all")
    val role: StateFlow<String> = _role.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val models = graph.characterModels.models
    val activeId = graph.characterModels.activeId

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                withTimeout(30_000) {
                    withContext(Dispatchers.IO) { CharacterPlazaClient.load() }
                }
            }
                .onSuccess { config ->
                    _sites.value = config.sites
                    _creators.value = config.creators
                    _message.value = if (config.remote) {
                        "已更新广场配置：${config.sites.size} 个站点 · ${config.creators.size} 位创作者"
                    } else {
                        "远端配置不可用，已使用内置站点"
                    }
                }
                .onFailure { _error.value = it.message ?: "广场配置加载失败" }
            _loading.value = false
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onRoleChange(value: String) {
        _role.value = value
    }

    fun importUrl(url: String, name: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            _error.value = null
            _message.value = "正在下载角色模型…"
            val temp = File(getApplication<Application>().cacheDir, "plaza-${UUID.randomUUID()}.download")
            try {
                RemoteFetch.downloadToFile(trimmed, temp, MAX_MODEL_BYTES, timeoutMs = 120_000)
                val displayName = displayName(name, trimmed, temp)
                val model = withContext(Dispatchers.IO) {
                    graph.characterModels.importFile(temp, displayName)
                }
                _message.value = "已导入：${model.name}（${model.type.label}）"
            } catch (error: Exception) {
                _error.value = error.message ?: "角色模型导入失败"
            } finally {
                temp.delete()
                _importing.value = false
            }
        }
    }

    fun setActive(id: String) {
        graph.characterModels.setActive(id)
        _message.value = "已设为当前角色模型"
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearError() {
        _error.value = null
    }

    private fun displayName(name: String, url: String, file: File): String {
        val raw = name.trim().ifBlank {
            url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        }
        if (raw.substringAfterLast('.', "").lowercase() in MODEL_EXTENSIONS) return raw
        val fromUrl = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val extension = fromUrl.substringAfterLast('.', "").lowercase()
            .takeIf { it in MODEL_EXTENSIONS }
            ?: magicExtension(file)
        return if (extension != null) "$raw.$extension" else raw
    }

    private fun magicExtension(file: File): String? {
        val head = ByteArray(8)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (read < 4) return null
        return when {
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> "zip"
            head[0] == 'g'.code.toByte() && head[1] == 'l'.code.toByte() &&
                head[2] == 'T'.code.toByte() && head[3] == 'F'.code.toByte() -> "glb"
            head[0] == 'P'.code.toByte() && head[1] == 'M'.code.toByte() &&
                head[2] == 'X'.code.toByte() -> "pmx"
            else -> null
        }
    }

    private companion object {
        const val MAX_MODEL_BYTES = 300L * 1024 * 1024
        val MODEL_EXTENSIONS = setOf("zip", "vrm", "glb", "gltf", "pmx", "pmd")
    }
}
