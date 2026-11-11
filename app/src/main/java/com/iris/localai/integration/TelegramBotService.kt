package com.iris.localai.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface IntegrationStatus {
    data object Stopped : IntegrationStatus
    data object Connecting : IntegrationStatus
    data class Online(val detail: String) : IntegrationStatus
    data class Error(val message: String) : IntegrationStatus

    fun label(): String = when (this) {
        Stopped -> "未启动"
        Connecting -> "连接中…"
        is Online -> "在线 $detail"
        is Error -> "错误：$message"
    }
}

/** Telegram 长轮询服务：收到文本消息后交给 BotMessageRouter，再回发结果。 */
class TelegramBotService(
    private val store: IntegrationStore,
    private val router: BotMessageRouter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Stopped)
    val status: StateFlow<IntegrationStatus> = _status.asStateFlow()
    private var job: Job? = null

    fun start() {
        stop()
        val config = store.settings.value.telegram
        if (!config.enabled || config.token.isBlank()) {
            _status.value = IntegrationStatus.Stopped
            return
        }
        job = scope.launch {
            val client = TelegramBotClient(config.token)
            _status.value = IntegrationStatus.Connecting
            var offset = 0L
            try {
                val username = client.getMe()
                _status.value = IntegrationStatus.Online("@$username")
                while (isActive) {
                    try {
                        val messages = client.getUpdates(offset)
                        for (message in messages) {
                            offset = maxOf(offset, message.updateId + 1)
                            val reply = runCatching {
                                router.reply(
                                    platform = "Telegram",
                                    chatId = message.chatId.toString(),
                                    displayName = message.fromName,
                                    text = message.text,
                                )
                            }.getOrElse { "处理失败：${it.message ?: it.javaClass.simpleName}" }
                            client.sendMessage(message.chatId, reply)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        _status.value = IntegrationStatus.Error(
                            error.message ?: error.javaClass.simpleName,
                        )
                        delay(5_000)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _status.value = IntegrationStatus.Error(
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = IntegrationStatus.Stopped
    }
}
