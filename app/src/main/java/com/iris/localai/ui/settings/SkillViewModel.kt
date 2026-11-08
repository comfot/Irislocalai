package com.iris.localai.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.localai.IrisApp
import com.iris.localai.skill.Skill
import com.iris.localai.skill.SkillSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SkillViewModel(application: Application) : AndroidViewModel(application) {

    private val store = (application as IrisApp).graph.skills

    val skills = store.skills
    val settings = store.settings

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun save(skill: Skill, new: Boolean = false) {
        store.save(skill, new)
        _message.value = "已保存技能：${skill.name}"
        _error.value = null
    }

    fun delete(id: String) {
        store.delete(id)
        _message.value = "已删除技能"
    }

    fun setEnabled(id: String, enabled: Boolean) = store.setEnabled(id, enabled)

    fun updateSettings(transform: (SkillSettings) -> SkillSettings) {
        store.updateSettings(transform)
    }

    fun import(bytes: ByteArray, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.importBytes(bytes, fileName)
                .onSuccess {
                    _message.value = "已导入技能：${it.name}"
                    _error.value = null
                }
                .onFailure { _error.value = "导入失败：${it.message}" }
        }
    }

    fun download(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.download(url)
                .onSuccess {
                    _message.value = "已下载技能：${it.name}"
                    _error.value = null
                }
                .onFailure { _error.value = "下载失败：${it.message}" }
        }
    }

    fun clearMessage() {
        _message.value = null
        _error.value = null
    }
}
