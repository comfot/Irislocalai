package com.iris.localai.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PluginAiChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
)

data class PluginAiChatState(
    val title: String,
    val messages: List<PluginAiChatMessage>,
    val onSend: (String) -> Unit,
)

/** 把当前主聊天暴露给插件面板里的 AiChat 组件；没有活跃 ChatViewModel 时为空。 */
object PluginAiChatBridge {

    private val _state = MutableStateFlow<PluginAiChatState?>(null)
    val state: StateFlow<PluginAiChatState?> = _state.asStateFlow()

    fun attach(title: String, messages: List<PluginAiChatMessage>, onSend: (String) -> Unit) {
        _state.value = PluginAiChatState(title, messages, onSend)
    }

    fun detach() {
        _state.value = null
    }
}
