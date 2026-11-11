package com.iris.localai.integration

/** 按配置启停各消息通道；设置页保存后调用 apply() 即可。 */
class IntegrationManager(
    val store: IntegrationStore,
    router: BotMessageRouter,
) {
    val telegram = TelegramBotService(store, router)
    val qq = QqBotService(store, router)
    val weixin = WeixinBotService(store, router)

    fun apply() {
        val config = store.settings.value
        if (config.telegram.enabled && config.telegram.token.isNotBlank()) telegram.start() else telegram.stop()
        if (config.qq.enabled && config.qq.appId.isNotBlank() && config.qq.appSecret.isNotBlank()) qq.start() else qq.stop()
        if (config.weixin.enabled && config.weixin.botToken.isNotBlank()) weixin.start() else weixin.stop()
    }

    fun startAll() = apply()

    fun stopAll() {
        telegram.stop()
        qq.stop()
        weixin.stop()
    }
}
