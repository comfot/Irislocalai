package com.iris.localai.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class TelegramBotConfig(
    val enabled: Boolean = false,
    val token: String = "",
)

data class QqBotConfig(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
)

data class WeixinBotConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://ilinkai.weixin.qq.com",
    val botToken: String = "",
    val botId: String = "",
)

data class BrowserConfig(
    val enabled: Boolean = true,
    val homeUrl: String = "https://www.bing.com",
    val allowDomains: String = "",
    val blockDomains: String = "",
    val allowScreenshot: Boolean = true,
)

data class IntegrationSettings(
    val telegram: TelegramBotConfig = TelegramBotConfig(),
    val qq: QqBotConfig = QqBotConfig(),
    val weixin: WeixinBotConfig = WeixinBotConfig(),
    val browser: BrowserConfig = BrowserConfig(),
)

/** 接入配置独立于 AppSettings：token 等敏感字段不进入 settings.json 镜像。 */
class IntegrationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<IntegrationSettings> = _settings.asStateFlow()

    fun update(transform: (IntegrationSettings) -> IntegrationSettings) {
        _settings.value = transform(_settings.value)
        persist()
    }

    fun readCursor(key: String): String = prefs.getString("cursor_$key", "") ?: ""

    fun saveCursor(key: String, value: String) {
        prefs.edit().putString("cursor_$key", value).apply()
    }

    private fun load(): IntegrationSettings {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return IntegrationSettings()
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            IntegrationSettings(
                telegram = root["telegram"]?.jsonObject?.let { obj ->
                    TelegramBotConfig(
                        enabled = obj.boolean("enabled", false),
                        token = obj.string("token"),
                    )
                } ?: TelegramBotConfig(),
                qq = root["qq"]?.jsonObject?.let { obj ->
                    QqBotConfig(
                        enabled = obj.boolean("enabled", false),
                        appId = obj.string("appId"),
                        appSecret = obj.string("appSecret"),
                    )
                } ?: QqBotConfig(),
                weixin = root["weixin"]?.jsonObject?.let { obj ->
                    WeixinBotConfig(
                        enabled = obj.boolean("enabled", false),
                        baseUrl = obj.string("baseUrl", "https://ilinkai.weixin.qq.com"),
                        botToken = obj.string("botToken"),
                        botId = obj.string("botId"),
                    )
                } ?: WeixinBotConfig(),
                browser = root["browser"]?.jsonObject?.let { obj ->
                    BrowserConfig(
                        enabled = obj.boolean("enabled", true),
                        homeUrl = obj.string("homeUrl", "https://www.bing.com"),
                        allowDomains = obj.string("allowDomains"),
                        blockDomains = obj.string("blockDomains"),
                        allowScreenshot = obj.boolean("allowScreenshot", true),
                    )
                } ?: BrowserConfig(),
            )
        }.getOrDefault(IntegrationSettings())
    }

    private fun persist() {
        val current = _settings.value
        val json = buildJsonObject {
            putJsonObject("telegram") {
                put("enabled", current.telegram.enabled)
                put("token", current.telegram.token)
            }
            putJsonObject("qq") {
                put("enabled", current.qq.enabled)
                put("appId", current.qq.appId)
                put("appSecret", current.qq.appSecret)
            }
            putJsonObject("weixin") {
                put("enabled", current.weixin.enabled)
                put("baseUrl", current.weixin.baseUrl)
                put("botToken", current.weixin.botToken)
                put("botId", current.weixin.botId)
            }
            putJsonObject("browser") {
                put("enabled", current.browser.enabled)
                put("homeUrl", current.browser.homeUrl)
                put("allowDomains", current.browser.allowDomains)
                put("blockDomains", current.browser.blockDomains)
                put("allowScreenshot", current.browser.allowScreenshot)
            }
        }.toString()
        prefs.edit().putString(KEY_CONFIG, json).apply()
    }

    private fun JsonObject.string(key: String, fallback: String = ""): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: fallback

    private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: fallback

    private companion object {
        const val PREFS = "iris_integrations"
        const val KEY_CONFIG = "config"
    }
}
