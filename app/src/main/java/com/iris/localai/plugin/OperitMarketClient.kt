package com.iris.localai.plugin

import com.iris.localai.data.RemoteFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class OperitMarketPlugin(
    val id: String,
    val title: String,
    val description: String,
    val detail: String,
    val author: String,
    val version: String,
    val downloads: Long,
    val likes: Long,
    val featured: Boolean,
    val assetUrl: String,
    val sha256: String,
    val assetName: String,
    val formatVersion: String,
)

/** Operit 官方市场 v2 的公开静态索引，仅列出 ToolPkg 资产。 */
class OperitMarketClient {

    suspend fun list(sort: String, page: Int): Result<List<OperitMarketPlugin>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE/lists/type/package/$sort/page-$page.json"
            val raw = RemoteFetch.text(url, 4L * 1024 * 1024, timeoutMs = 25_000)
            Json.parseToJsonElement(raw).jsonObject["items"]?.jsonArray
                ?.mapNotNull(::parseItem)
                .orEmpty()
        }
    }

    private fun parseItem(element: kotlinx.serialization.json.JsonElement): OperitMarketPlugin? {
        val obj = element as? JsonObject ?: return null
        val id = obj.text("id") ?: return null
        val title = obj.text("title") ?: return null
        val latest = obj["latestVersion"] as? JsonObject ?: return null
        val format = latest.text("formatVer").orEmpty()
        if (!format.startsWith("toolpkg", ignoreCase = true)) return null
        val latestId = latest.text("id")
        val publisher = latest["publisher"] as? JsonObject
        val assets = obj["assets"] as? JsonArray ?: return null
        val asset = assets
            .mapNotNull { it as? JsonObject }
            .firstOrNull { latestId != null && it.text("versionId") == latestId }
            ?: assets.mapNotNull { it as? JsonObject }.lastOrNull()
            ?: return null
        val url = asset.text("url")?.takeIf { it.startsWith("https://") } ?: return null
        val sha = asset.text("sha256")?.takeIf { it.length == 64 } ?: return null
        val stats = obj["stats"] as? JsonObject
        return OperitMarketPlugin(
            id = id,
            title = title,
            description = obj.text("description").orEmpty(),
            detail = obj.text("detail").orEmpty(),
            author = publisher?.text("login") ?: obj.text("authorId").orEmpty(),
            version = latest.text("version").orEmpty(),
            downloads = stats?.long("downloads") ?: 0L,
            likes = stats?.long("likes") ?: 0L,
            featured = (obj["featured"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            assetUrl = url,
            sha256 = sha,
            assetName = asset.text("assetName").orEmpty(),
            formatVersion = format,
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

    private companion object {
        const val BASE = "https://static.operit.app/market/v2"
    }
}
