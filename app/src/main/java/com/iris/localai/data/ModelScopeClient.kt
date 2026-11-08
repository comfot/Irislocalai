package com.iris.localai.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ModelScope（魔搭）检索与 GGUF 下载，作为 Hugging Face 搜索不可用时的备用路径。
 * 接口参照 ZorvAI 离线模型下载中心：搜索 PUT /api/v1/models，文件树 GET /repo/files。
 */
class ModelScopeClient {

    suspend fun search(query: String, limit: Int = 20): Result<List<HfModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("Search", query.trim())
                put("PageSize", limit)
                put("PageNumber", 1)
            }.toString()
            val connection = (URL(SEARCH_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                parseSearch(raw)
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun files(repoId: String): Result<List<HfFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = repoId.split('/').joinToString("/") { encode(it) }
            val connection = (URL("$FILES_URL_PREFIX$encoded/repo/files?Recursive=true").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val files = Json.parseToJsonElement(raw).jsonObject["Data"]
                    ?.jsonObject?.get("Files") as? JsonArray
                    ?: return@runCatching emptyList<HfFile>()
                files.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val path = obj["Path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
                    HfFile(
                        name = path,
                        size = obj["Size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        source = ModelSource.MODELSCOPE,
                    )
                }.sortedBy { it.name }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun download(
        repoId: String,
        fileName: String,
        destDir: File,
        preservePath: Boolean = false,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedRepo = repoId.split('/').joinToString("/") { encode(it) }
            val encodedFile = fileName.split('/').joinToString("/") { encode(it) }
            val dir = destDir.apply { mkdirs() }
            val relative = fileName.split('/')
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
            val target = if (preservePath) File(dir, relative) else File(dir, fileName.substringAfterLast('/'))
            target.parentFile?.mkdirs()
            val part = File(target.parentFile ?: dir, target.name + "." + Integer.toHexString(repoId.hashCode()) + ".part")
            val existing = if (part.exists()) part.length() else 0L

            val connection = (URL("$RESOLVE_PREFIX$encodedRepo/resolve/master/$encodedFile").openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val append = existing > 0 && code == 206
                if (!append) part.delete()
                var downloaded = if (append) existing else 0L
                val reported = connection.contentLengthLong
                val total = if (reported > 0) reported + downloaded else 0L
                FileOutputStream(part, append).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (!currentCoroutineContext().isActive) throw IOException("下载已取消")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                validateGguf(target, fileName)
                target
            } finally {
                connection.disconnect()
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    private fun validateGguf(file: File, fileName: String) {
        if (!fileName.endsWith(".gguf", ignoreCase = true)) return
        val head = ByteArray(4)
        val read = file.inputStream().use { it.read(head) }
        if (read != 4 || String(head, Charsets.US_ASCII) != "GGUF") {
            file.delete()
            throw IOException("下载内容不是有效的 GGUF 文件")
        }
    }

    private fun parseSearch(raw: String): List<HfModel> {
        val models = Json.parseToJsonElement(raw).jsonObject["Data"]
            ?.jsonObject?.get("Models") as? JsonArray
            ?: return emptyList()
        return models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path = obj["Path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["Name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            HfModel(
                id = "$path/$name",
                downloads = obj["Downloads"]?.jsonPrimitive?.longOrNull ?: 0L,
                likes = obj["Stars"]?.jsonPrimitive?.longOrNull ?: 0L,
                tags = listOf(ModelSource.MODELSCOPE.label),
                source = ModelSource.MODELSCOPE,
            )
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val SEARCH_URL = "https://modelscope.cn/api/v1/models"
        const val FILES_URL_PREFIX = "https://modelscope.cn/api/v1/models/"
        const val RESOLVE_PREFIX = "https://modelscope.cn/models/"
        const val USER_AGENT = "IRIS-LocalAI/0.1"
    }
}
