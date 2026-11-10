package com.iris.localai.memory

import com.iris.localai.data.ModelProfile
import com.iris.localai.data.ModelProvider
import com.iris.localai.data.ModelRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

data class MemoryVector(
    val key: String,
    val values: FloatArray,
)

/**
 * 本地特征哈希向量：中文按字/二元/三元组切分，英文按词切分，
 * 带符号哈希到固定维度并做 L2 归一化。无需模型即可做近似的向量召回。
 */
object HashEmbedding {

    const val KEY = "local-hash-v1"
    private const val DIMENSIONS = 384

    fun embed(text: String): FloatArray {
        val normalized = text.lowercase()
        val tokens = buildList {
            Regex("[a-z0-9_]+").findAll(normalized).forEach { match ->
                add(match.value)
            }
            val han = normalized.filter { it.code in 0x4E00..0x9FFF }
            for (index in han.indices) {
                add(han[index].toString())
                if (index + 1 < han.length) add(han.substring(index, index + 2))
                if (index + 2 < han.length) add(han.substring(index, index + 3))
            }
        }
        val vector = FloatArray(DIMENSIONS)
        tokens.forEach { token ->
            val hash = token.hashCode()
            val index = (hash and Int.MAX_VALUE) % DIMENSIONS
            val sign = if (hash and 1 == 0) 1f else -1f
            vector[index] += sign
        }
        return normalize(vector)
    }

    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        vector.forEach { sum += it * it }
        val length = sqrt(sum)
        if (length <= 0.0) return vector
        return FloatArray(vector.size) { index -> (vector[index] / length).toFloat() }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) return 0f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index].toDouble() * right[index]
            leftNorm += left[index].toDouble() * left[index]
            rightNorm += right[index].toDouble() * right[index]
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat().coerceIn(-1f, 1f)
    }
}

/**
 * 可选云端向量：读取模型档案的 Embedding 角色，调用 OpenAI 兼容 /embeddings。
 * 批量请求按 32 条分块；未配置、非兼容端点或任一请求失败时返回 null，调用方回退本地哈希向量。
 */
class MemoryEmbeddingClient(
    private val profileProvider: () -> ModelProfile?,
    private val providerResolver: (ModelProfile) -> ModelProvider?,
) {

    suspend fun embed(text: String): MemoryVector? =
        embedBatch(listOf(text))?.firstOrNull()

    suspend fun embedBatch(texts: List<String>): List<MemoryVector?>? {
        if (texts.isEmpty()) return emptyList()
        val result = ArrayList<MemoryVector?>(texts.size)
        texts.chunked(MAX_BATCH).forEach { chunk ->
            val vectors = request(chunk) ?: return null
            result += vectors
        }
        return result
    }

    private suspend fun request(texts: List<String>): List<MemoryVector?>? =
        withContext(Dispatchers.IO) {
            val profile = profileProvider() ?: return@withContext null
            val role = profile.role(ModelRole.EMBEDDING)
            val provider = providerResolver(profile) ?: return@withContext null
            val model = role.modelName.trim().ifBlank {
                provider.models.firstOrNull { it.enabled }?.name.orEmpty()
            }
            if (!role.enabled || model.isBlank()) return@withContext null
            if (provider.kind != com.iris.localai.data.ProviderKind.CLOUD) return@withContext null
            if (provider.vendor !in COMPATIBLE_VENDORS) return@withContext null
            val apiKey = provider.apiKey.trim()
            if (apiKey.isBlank()) return@withContext null
            val endpoint = provider.endpoint.ifBlank { provider.vendor.defaultEndpoint }
                .trimEnd('/') + "/embeddings"
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("input") {
                    texts.forEach { text -> add(text.take(MAX_INPUT_CHARS)) }
                }
            }.toString()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                role.customHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) return@withContext null
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val data = runCatching {
                    Json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray
                }.getOrNull() ?: return@withContext null
                val vectors = arrayOfNulls<FloatArray>(texts.size)
                data.forEach { element ->
                    val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                    val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    if (index !in vectors.indices) return@forEach
                    val vector = obj["embedding"]?.jsonArray
                        ?.mapNotNull { item -> (item as? JsonPrimitive)?.floatOrNull }
                        ?.toFloatArray()
                    if (vector != null && vector.isNotEmpty()) {
                        vectors[index] = HashEmbedding.normalize(vector)
                    }
                }
                val key = "cloud:${provider.vendor.name}:$model"
                vectors.map { values -> values?.let { MemoryVector(key, it) } }
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val MAX_INPUT_CHARS = 8_000
        const val MAX_BATCH = 32
        val COMPATIBLE_VENDORS = setOf(
            com.iris.localai.data.CloudVendor.OPENAI,
            com.iris.localai.data.CloudVendor.DEEPSEEK,
            com.iris.localai.data.CloudVendor.OPENROUTER,
            com.iris.localai.data.CloudVendor.CUSTOM,
        )
    }
}
