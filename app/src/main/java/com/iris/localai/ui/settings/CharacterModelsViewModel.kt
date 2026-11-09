package com.iris.localai.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.character.CharacterModelType
import com.iris.localai.data.CharacterModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CharacterModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph
    private val store = graph.characterModels
    private val settings = graph.settings

    val models = store.models
    val activeId = store.activeId
    val bindings = store.bindings
    val characters = (application as IrisApp).graph.characters.cards
    val activeCharacterId = (application as IrisApp).graph.settings.settings
        .map { it.characterId }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            (application as IrisApp).graph.settings.current.characterId,
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun import(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.import(uri, displayName)
                .onSuccess { model ->
                    _message.value = "已导入模型：${model.name}"
                    _error.value = null
                    ensureDisplayMode(model.type)
                }
                .onFailure { _error.value = "导入失败：${it.message ?: "无法识别模型"}" }
        }
    }

    private fun ensureDisplayMode(type: CharacterModelType) {
        settings.update {
            it.copy(
                characterModel = if (
                    type == CharacterModelType.VRM ||
                    type == CharacterModelType.GLB ||
                    type == CharacterModelType.MMD
                ) {
                    CharacterModel.THREE_D
                } else {
                    CharacterModel.TWO_D
                },
            )
        }
    }

    fun setActive(id: String) = store.setActive(id)

    fun clearActive() = store.clearActive()

    fun bind(characterId: String, modelId: String) {
        store.bind(characterId, modelId)
        store.models.value.firstOrNull { it.id == modelId }?.let { ensureDisplayMode(it.type) }
        _message.value = "已绑定角色模型"
    }

    fun unbind(characterId: String) {
        store.unbind(characterId)
        _message.value = "已解除角色模型绑定"
    }

    fun delete(id: String) {
        store.delete(id)
        _message.value = "已删除模型"
    }

    fun clearMessage() {
        _message.value = null
    }
}
