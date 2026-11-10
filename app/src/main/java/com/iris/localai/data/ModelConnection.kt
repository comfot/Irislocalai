package com.iris.localai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ConnectionTestResult {
    data class Success(val message: String) : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Done(val success: Boolean, val message: String) : ConnectionTestState
}

object ModelConnectionTester {

    suspend fun listModels(provider: ModelProvider): Result<List<String>> = withContext(Dispatchers.IO) {
        if (provider.kind != ProviderKind.CLOUD) {
            return@withContext Result.success(provider.models.map { it.name })
        }
        val apiKey = provider.apiKey.trim()
        if (apiKey.isBlank()) return@withContext Result.failure(IOException("API Key 为空"))
        val endpoint = provider.endpoint.ifBlank { provider.vendor.defaultEndpoint }
            .trimEnd('/') + "/models"
        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    val body = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.take(160)
                        .orEmpty()
                    error("HTTP ${connection.responseCode} ${body.trim()}".trim())
                }
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val data = kotlinx.serialization.json.Json
                    .parseToJsonElement(raw)
                    .jsonObject["data"]
                    ?.jsonArray
                    ?: error("响应缺少 data 数组")
                data.mapNotNull { element ->
                    (element as? JsonObject)
                        ?.get("id")
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?.takeIf { it.isNotBlank() }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun test(provider: ModelProvider): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (provider.kind == ProviderKind.LOCAL) {
            if (provider.localModelPath.isBlank()) {
                return@withContext ConnectionTestResult.Failure("还没有选择本地模型")
            }
            val file = File(provider.localModelPath)
            return@withContext if (file.exists()) {
                ConnectionTestResult.Success("本地模型存在：${file.name}")
            } else {
                ConnectionTestResult.Failure("文件不存在：${provider.localModelPath}")
            }
        }

        if (provider.apiKey.isBlank()) {
            return@withContext ConnectionTestResult.Failure("API Key 为空")
        }
        if (provider.endpoint.isBlank()) {
            return@withContext ConnectionTestResult.Failure("API Endpoint 为空")
        }

        runCatching {
            val url = URL(provider.endpoint.trimEnd('/') + "/models")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
                setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
            }
            try {
                val code = connection.responseCode
                if (code in 200..299) {
                    ConnectionTestResult.Success("连接成功（HTTP $code）")
                } else {
                    val body = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.take(160)
                        .orEmpty()
                    ConnectionTestResult.Failure("HTTP $code ${body.trim()}".trim())
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            ConnectionTestResult.Failure(error.message ?: "连接失败")
        }
    }
}

internal fun httpConnection(url: String, method: String = "GET"): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 8_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
    }

internal fun HttpURLConnection.readTextOrThrow(): String {
    if (responseCode !in 200..299) {
        val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        throw IOException("HTTP $responseCode ${body.take(120)}".trim())
    }
    return inputStream.bufferedReader().use { it.readText() }
}
