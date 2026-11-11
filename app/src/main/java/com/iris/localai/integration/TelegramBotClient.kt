package com.iris.localai.integration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

data class TelegramMessage(
    val updateId: Long,
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val fromName: String,
)

/** Telegram Bot API 客户端：长轮询收消息 + sendMessage 回复。 */
class TelegramBotClient(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getMe(): String = withContext(Dispatchers.IO) {
        val root = execute("getMe")
        val result = root["result"]?.jsonObject ?: error("getMe 返回格式异常")
        result["username"]?.jsonPrimitive?.contentOrNull ?: "telegram-bot"
    }

    suspend fun getUpdates(offset: Long): List<TelegramMessage> = withContext(Dispatchers.IO) {
        val root = execute("getUpdates?timeout=30&offset=$offset&allowed_updates=%5B%22message%22%5D")
        val array = root["result"]?.jsonArray ?: return@withContext emptyList()
        array.mapNotNull { element ->
            val update = element as? JsonObject ?: return@mapNotNull null
            val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val message = update["message"]?.jsonObject ?: return@mapNotNull null
            val text = message["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val chat = message["chat"]?.jsonObject ?: return@mapNotNull null
            val chatId = chat["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val from = message["from"]?.jsonObject
            val name = from?.get("first_name")?.jsonPrimitive?.contentOrNull
                ?: from?.get("username")?.jsonPrimitive?.contentOrNull
                ?: ""
            TelegramMessage(
                updateId = updateId,
                chatId = chatId,
                messageId = message["message_id"]?.jsonPrimitive?.longOrNull ?: 0L,
                text = text,
                fromName = name,
            )
        }
    }

    suspend fun sendMessage(chatId: Long, text: String) = withContext(Dispatchers.IO) {
        for (chunk in text.chunked(3900)) {
            val form = FormBody.Builder()
                .add("chat_id", chatId.toString())
                .add("text", chunk)
                .add("disable_web_page_preview", "true")
                .build()
            execute("sendMessage", form)
        }
    }

    private fun execute(method: String, body: RequestBody? = null): JsonObject {
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .apply { if (body != null) post(body) else get() }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Telegram $method 失败：${response.code} $text")
            val root = json.parseToJsonElement(text).jsonObject
            if (root["ok"]?.jsonPrimitive?.contentOrNull == "false") {
                error(root["description"]?.jsonPrimitive?.contentOrNull ?: "Telegram 请求失败")
            }
            return root
        }
    }
}
