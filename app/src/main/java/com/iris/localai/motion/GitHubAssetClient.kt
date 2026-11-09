package com.iris.localai.motion

import com.iris.localai.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class RemoteAsset(
    val name: String,
    val path: String,
    val size: Long,
    val directory: Boolean,
)

/**
 * GitHub 素材仓库客户端。素材仓库默认是私有库，因此下载走 Contents API 的 raw 媒体类型，
 * 由用户在设置里填写只读 Token；Token 只保存在本机 SharedPreferences。
 */
class GitHubAssetClient(private val settings: SettingsRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(path: String): List<RemoteAsset> = withContext(Dispatchers.IO) {
        val current = settings.current
        val url = URL(apiUrl(current.assetRepo, path, current.assetBranch))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 40_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "IRIS-LocalAI")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (current.assetToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${current.assetToken}")
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(300)?.trim()
                error(errorMessage(code, current.assetRepo, message))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = json.parseToJsonElement(body) as? JsonArray ?: error("素材仓库返回格式异常")
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val itemPath = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                RemoteAsset(
                    name = name,
                    path = itemPath,
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    directory = obj["type"]?.jsonPrimitive?.contentOrNull == "dir",
                )
            }.sortedWith(compareBy({ it.directory.not() }, { it.name.lowercase() }))
        } finally {
            connection.disconnect()
        }
    }

    fun request(path: String): Pair<String, Map<String, String>> {
        val current = settings.current
        val headers = buildMap {
            put("Accept", "application/vnd.github.raw")
            put("X-GitHub-Api-Version", "2022-11-28")
            if (current.assetToken.isNotBlank()) put("Authorization", "Bearer ${current.assetToken}")
        }
        return apiUrl(current.assetRepo, path, current.assetBranch) to headers
    }

    fun fileName(path: String): String = path.substringAfterLast('/')

    private fun apiUrl(repo: String, path: String, branch: String): String {
        val encoded = path.split('/').joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        val ref = URLEncoder.encode(branch, Charsets.UTF_8.name()).replace("+", "%20")
        return "https://api.github.com/repos/$repo/contents/$encoded?ref=$ref"
    }

    private fun errorMessage(code: Int, repo: String, body: String?): String = when (code) {
        401, 403 -> "素材仓库鉴权失败，请检查 Token 是否有效且拥有该仓库读取权限"
        404 -> "素材仓库不可访问：$repo 可能是私有库，请填写 Token 或确认路径"
        429 -> "GitHub 请求过于频繁，请稍后再试"
        else -> "素材仓库请求失败（HTTP $code）${body?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
    }
}
