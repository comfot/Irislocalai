package com.iris.localai.integration

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class WeixinQrCode(
    val qrcode: String,
    val content: String,
)

data class WeixinQrStatus(
    val status: String,
    val botToken: String?,
    val baseUrl: String?,
    val botId: String?,
)

/** 微信 iLink 扫码登录：获取二维码 + 长轮询扫码状态。 */
class WeixinLoginClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(38, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestQr(baseUrl: String): WeixinQrCode = withContext(Dispatchers.IO) {
        val root = get(baseUrl, "ilink/bot/get_bot_qrcode?bot_type=3")
        WeixinQrCode(
            qrcode = root["qrcode"]?.jsonPrimitive?.contentOrNull
                ?: error("二维码接口缺少 qrcode"),
            content = root["qrcode_img_content"]?.jsonPrimitive?.contentOrNull
                ?: error("二维码接口缺少 qrcode_img_content"),
        )
    }

    suspend fun pollStatus(qrcode: String, baseUrl: String): WeixinQrStatus =
        withContext(Dispatchers.IO) {
            val root = try {
                get(baseUrl, "ilink/bot/get_qrcode_status?qrcode=" + URLEncoder.encode(qrcode, "UTF-8"))
            } catch (timeout: SocketTimeoutException) {
                return@withContext WeixinQrStatus("wait", null, null, null)
            }
            WeixinQrStatus(
                status = root["status"]?.jsonPrimitive?.contentOrNull ?: "wait",
                botToken = root["bot_token"]?.jsonPrimitive?.contentOrNull,
                baseUrl = root["baseurl"]?.jsonPrimitive?.contentOrNull,
                botId = root["ilink_bot_id"]?.jsonPrimitive?.contentOrNull,
            )
        }

    private fun get(baseUrl: String, path: String) = runCatching {
        val base = baseUrl.trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val uin = Base64.encodeToString(
            (Random.nextInt().toLong() and 0xFFFFFFFFL).toString().toByteArray(),
            Base64.NO_WRAP,
        )
        val request = Request.Builder()
            .url("$base/$path")
            .header("Content-Type", "application/json")
            .header("AuthorizationType", "ilink_bot_token")
            .header("X-WECHAT-UIN", uin)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("微信登录接口失败：${response.code} $text")
            json.parseToJsonElement(text).jsonObject
        }
    }.getOrThrow()

    private companion object {
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
    }
}
