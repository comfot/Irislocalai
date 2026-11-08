package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.iris.localai.IrisApp
import com.iris.localai.lorebook.Lorebook
import com.iris.localai.lorebook.WorldBookSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WorldBookViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as IrisApp).graph

    val books = graph.worldBooks.books
    val settings = graph.worldBooks.settings

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun create(name: String): Lorebook {
        val book = graph.worldBooks.create(name)
        _message.value = "已创建世界书：${book.name}"
        return book
    }

    fun update(book: Lorebook) {
        graph.worldBooks.update(book)
    }

    fun delete(id: String) {
        graph.worldBooks.delete(id)
        _message.value = "已删除世界书"
    }

    fun importJson(text: String, fileName: String) {
        graph.worldBooks.importJson(text, fileName)
            .onSuccess { _message.value = "已导入：${it.name}（${it.entries.size} 条）"; _error.value = null }
            .onFailure { _error.value = "导入失败：${it.message ?: "格式不支持"}" }
    }

    fun exportJson(id: String): ByteArray? =
        graph.worldBooks.exportJson(id)?.toByteArray(Charsets.UTF_8)

    fun updateSettings(transform: (WorldBookSettings) -> WorldBookSettings) {
        graph.worldBooks.updateSettings(transform)
    }

    fun clearMessage() {
        _message.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
