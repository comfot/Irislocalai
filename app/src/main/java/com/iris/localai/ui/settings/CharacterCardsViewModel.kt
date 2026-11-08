package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.character.CharacterCardData
import com.iris.localai.data.CharacterLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CharacterCardsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    val cards = graph.characters.cards
    val settings = graph.settings.settings

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setActive(id: String) {
        graph.settings.update { it.copy(characterId = id) }
    }

    fun setLayout(layout: CharacterLayout) {
        graph.settings.update { it.copy(characterLayout = layout) }
    }

    fun importCard(bytes: ByteArray, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.characters.importCard(bytes, fileName)
                .onSuccess { id ->
                    val entry = graph.characters.get(id)
                    entry?.card?.let { card ->
                        card.characterBook?.let { book ->
                            graph.worldBooks.importJson(
                                book.toString(),
                                "${card.charName}·世界书",
                            )
                        }
                    }
                    setActive(id)
                    _message.value = "已导入角色卡：${graph.characters.get(id)?.card?.charName ?: fileName}"
                    _error.value = null
                }
                .onFailure {
                    _error.value = "导入失败：${it.message ?: "无法解析角色卡"}"
                }
        }
    }

    fun saveCard(card: CharacterCardData, id: String?, avatarBytes: ByteArray?) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = graph.characters.save(card, id, avatarBytes)
            setActive(newId)
            _message.value = "已保存：${card.charName}"
            _error.value = null
        }
    }

    fun deleteCard(id: String) {
        val wasActive = graph.settings.current.characterId == id
        graph.characters.delete(id)
        if (wasActive) {
            graph.characters.cards.value.firstOrNull()?.let { setActive(it.id) }
        }
        _message.value = "已删除角色卡"
    }

    fun toggleFavorite(id: String) {
        graph.characters.toggleFavorite(id)
    }

    fun exportJson(id: String): ByteArray? =
        graph.characters.exportJson(id)?.toByteArray(Charsets.UTF_8)

    fun exportPng(id: String): ByteArray? = graph.characters.exportPng(id)

    fun clearMessage() {
        _message.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun reportError(message: String) {
        _error.value = message
    }
}
