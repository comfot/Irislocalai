package com.iris.localai.character

import com.iris.localai.data.DownloadSources
import com.iris.localai.data.RemoteFetch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PlazaSite(
    val id: String,
    val label: String,
    val icon: String,
    val url: String,
    val description: String,
    val searchUrl: String,
    val group: String,
)

data class PlazaCreator(
    val name: String,
    val description: String,
    val role: String,
    val sites: List<String>,
    val tier: String?,
)

data class CharacterPlazaConfig(
    val sites: List<PlazaSite>,
    val creators: List<PlazaCreator>,
    val remote: Boolean,
)

/**
 * 参照 MikuMikuAR 广场：聚合第三方模型站点并提供创作者名录，不托管模型文件。
 * 远端配置来自 MikuMikuAR 仓库的 creators.json / workshop_sites.json，失败时使用内置站点。
 */
object CharacterPlazaClient {

    private const val PLAZA_REPO = "eghrhegpe/MikuMikuAR"
    private const val PLAZA_BRANCH = "main"

    private fun creatorUrls(mirrorsEnabled: Boolean): List<String> =
        DownloadSources.githubContentCandidates(PLAZA_REPO, PLAZA_BRANCH, "creators.json", mirrorsEnabled)

    private fun siteUrls(mirrorsEnabled: Boolean): List<String> =
        DownloadSources.githubContentCandidates(PLAZA_REPO, PLAZA_BRANCH, "workshop_sites.json", mirrorsEnabled)

    private const val MAX_CONFIG_BYTES = 2L * 1024 * 1024

    suspend fun load(mirrorsEnabled: Boolean = true): CharacterPlazaConfig {
        val creators = fetchParsed(creatorUrls(mirrorsEnabled), ::parseCreators)
        val sites = fetchParsed(siteUrls(mirrorsEnabled), ::parseSites)
        return CharacterPlazaConfig(
            sites = (sites.ifEmpty { builtInSites }).distinctBy { it.id },
            creators = creators,
            remote = creators.isNotEmpty() || sites.isNotEmpty(),
        )
    }

    val builtInSites: List<PlazaSite> = listOf(
        PlazaSite(
            id = "bowlroll",
            label = "Bowlroll",
            icon = "📦",
            url = "https://bowlroll.net/",
            description = "日系老牌 MMD 模型与动作仓库。",
            searchUrl = "https://bowlroll.net/file/keyword/{{q}}",
            group = "海外",
        ),
        PlazaSite(
            id = "booth",
            label = "BOOTH",
            icon = "🛍",
            url = "https://booth.pm/",
            description = "Pixiv 旗下创作者商店，含 VRM / Live2D / 服装。",
            searchUrl = "https://booth.pm/ja/search/{{q}}",
            group = "海外",
        ),
        PlazaSite(
            id = "vroid",
            label = "VRoid Hub",
            icon = "🎨",
            url = "https://hub.vroid.com/",
            description = "VRM 角色模型分享与授权平台。",
            searchUrl = "",
            group = "海外",
        ),
        PlazaSite(
            id = "github",
            label = "GitHub",
            icon = "🐙",
            url = "https://github.com/",
            description = "开源模型、加载器与示例资产。",
            searchUrl = "https://github.com/search?q={{q}}",
            group = "通用",
        ),
        PlazaSite(
            id = "deviantart",
            label = "DeviantArt",
            icon = "🖼",
            url = "https://www.deviantart.com/",
            description = "创作者社区，可检索 MMD / VRM 相关作品。",
            searchUrl = "https://www.deviantart.com/search?q={{q}}",
            group = "海外",
        ),
    )

    private suspend fun <T> fetchParsed(urls: List<String>, parser: (String) -> List<T>): List<T> {
        for (url in urls) {
            val text = fetchOne(url) ?: continue
            val parsed = runCatching { parser(text) }.getOrNull().orEmpty()
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private suspend fun fetchOne(url: String): String? {
        val text = runCatching { RemoteFetch.text(url, MAX_CONFIG_BYTES, timeoutMs = 15_000) }.getOrNull()
            ?: return null
        if (!url.startsWith("https://api.github.com/")) return text
        return decodeGithubContent(text)
    }

    private fun decodeGithubContent(raw: String): String? = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        String(java.util.Base64.getMimeDecoder().decode(content.replace("\n", "")), Charsets.UTF_8)
    }.getOrNull()

    private fun parseCreators(raw: String): List<PlazaCreator> {
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.text("name") ?: return@mapNotNull null
            val role = when (obj.text("role")) {
                "official" -> "official"
                "vup" -> "vup"
                "oc" -> "oc"
                else -> "creator"
            }
            PlazaCreator(
                name = name,
                description = obj.text("desc").orEmpty(),
                role = role,
                sites = obj.text("type").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() },
                tier = obj.text("tier"),
            )
        }
    }

    private fun parseSites(raw: String): List<PlazaSite> {
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id") ?: return@mapNotNull null
            val label = obj.text("label") ?: obj.text("name") ?: return@mapNotNull null
            val url = obj.text("url") ?: return@mapNotNull null
            PlazaSite(
                id = id,
                label = label,
                icon = obj.text("icon").orEmpty(),
                url = url,
                description = obj.text("desc").orEmpty(),
                searchUrl = obj.text("searchUrl").orEmpty(),
                group = obj.text("group") ?: "站点",
            )
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
