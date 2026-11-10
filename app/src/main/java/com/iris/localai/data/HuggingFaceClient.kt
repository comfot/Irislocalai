package com.iris.localai.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder

data class HfModel(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val tags: List<String>,
    val source: ModelSource = ModelSource.HUGGING_FACE,
)

data class HfFile(
    val name: String,
    val size: Long,
    val source: ModelSource = ModelSource.HUGGING_FACE,
)

/**
 * Hugging Face 搜索与 GGUF 下载。优先官方源，超时/失败自动回退 hf-mirror（国内可用）。
 * 下载支持 .part 断点续传，目录为应用私有 models/。
 */
class HuggingFaceClient {

    suspend fun search(
        query: String,
        source: DownloadSource = DownloadSource.AUTO,
    ): Result<List<HfModel>> = withContext(Dispatchers.IO) {
        tryBases(source) { base ->
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val url = "$base/api/models?search=$encoded&limit=20&sort=downloads&direction=-1"
            val connection = httpConnection(url)
            try {
                val text = connection.readTextOrThrow()
                Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    HfModel(
                        id = id,
                        downloads = obj["downloads"]?.jsonPrimitive?.longOrNull ?: 0L,
                        likes = obj["likes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        tags = (obj["tags"] as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: emptyList(),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun files(
        repoId: String,
        source: DownloadSource = DownloadSource.AUTO,
    ): Result<List<HfFile>> = siblings(repoId, source, ggufOnly = true)

    /** 语音/模型包需要完整文件列表（不只 GGUF）。 */
    suspend fun allFiles(
        repoId: String,
        source: DownloadSource = DownloadSource.AUTO,
    ): Result<List<HfFile>> = siblings(repoId, source, ggufOnly = false)

    private suspend fun siblings(
        repoId: String,
        source: DownloadSource,
        ggufOnly: Boolean,
    ): Result<List<HfFile>> = withContext(Dispatchers.IO) {
        tryBases(source) { base ->
            val repo = repoId.trim().trim('/')
            val connection = httpConnection("$base/api/models/$repo?blobs=true")
            try {
                val text = connection.readTextOrThrow()
                val obj = Json.parseToJsonElement(text).jsonObject
                (obj["siblings"] as? JsonArray)
                    ?.mapNotNull { element ->
                        val sibling = element as? JsonObject ?: return@mapNotNull null
                        val name = sibling["rfilename"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        if (ggufOnly && !name.endsWith(".gguf", ignoreCase = true)) {
                            return@mapNotNull null
                        }
                        HfFile(
                            name = name,
                            size = sibling["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        )
                    }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun download(
        repoId: String,
        fileName: String,
        destDir: File,
        source: DownloadSource = DownloadSource.AUTO,
        preservePath: Boolean = false,
        revision: String = "main",
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        tryBases(source) { base ->
            downloadOnce(base, repoId, fileName, destDir, preservePath, revision, onProgress)
        }
    }

    private suspend fun downloadOnce(
        base: String,
        repoId: String,
        fileName: String,
        destDir: File,
        preservePath: Boolean,
        revision: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val repo = repoId.trim().trim('/')
        val encodedFile = fileName.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val dir = destDir.apply { mkdirs() }
        val relative = fileName.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
        val target = if (preservePath) {
            File(dir, relative)
        } else {
            File(dir, fileName.substringAfterLast('/'))
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile ?: dir, target.name + "." + Integer.toHexString(repo.hashCode()) + ".part")
        val existing = if (part.exists()) part.length() else 0L

        val connection = httpConnection(
            "$base/$repo/resolve/$revision/$encodedFile?download=true",
        )
        if (existing > 0) {
            connection.setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
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
            return target
        } finally {
            connection.disconnect()
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

    private inline fun <T> tryBases(source: DownloadSource, block: (String) -> T): Result<T> {
        var lastError: Throwable? = null
        for (base in bases(source)) {
            try {
                return Result.success(block(base))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError ?: IOException("没有可用的 Hugging Face 源"))
    }

    private fun bases(source: DownloadSource): List<String> = when (source) {
        DownloadSource.AUTO -> BASES
        DownloadSource.HUGGING_FACE -> listOf(HUGGING_FACE)
        DownloadSource.MIRROR -> listOf(MIRROR)
    }

    private companion object {
        const val HUGGING_FACE = "https://huggingface.co"
        const val MIRROR = "https://hf-mirror.com"
        val BASES = listOf(HUGGING_FACE, MIRROR)
    }
}
