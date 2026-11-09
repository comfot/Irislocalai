package com.iris.localai.pet

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** 桌宠输入栏发往聊天界面的消息总线；应用未启动时先缓存，打开后再消费。 */
object PetChatBus {
    private val channel = Channel<String>(Channel.UNLIMITED)
    val messages: Flow<String> = channel.receiveAsFlow()

    fun send(text: String) {
        channel.trySend(text)
    }
}

enum class PetDestination {
    SETTINGS,
    PET_SETTINGS,
    LIFE_CHAT,
}

data class PetNavEvent(val seq: Long, val destination: PetDestination)

/** 桌宠菜单里的跨页面跳转请求；由 MainScreen 与 ChatViewModel 消费。 */
object PetNavigator {
    private val _events = MutableStateFlow<PetNavEvent?>(null)
    val events: StateFlow<PetNavEvent?> = _events.asStateFlow()
    private var seq = 0L

    fun go(destination: PetDestination) {
        seq += 1
        _events.value = PetNavEvent(seq, destination)
    }
}
