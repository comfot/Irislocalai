package com.iris.localai.character

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

data class CharacterEntry(
    val id: String,
    val card: CharacterCardData,
    val avatar: File?,
    val favorite: Boolean,
)

/**
 * 角色卡存储：`filesDir/characters/<id>/card.json` + `avatar.png`。
 * 首次启动会种入三张内置卡（小满/岚/阿澈），收藏状态存 SharedPreferences。
 */
class CharacterCardStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val root = File(context.filesDir, "characters").apply { mkdirs() }

    private val _cards = MutableStateFlow<List<CharacterEntry>>(emptyList())
    val cards: StateFlow<List<CharacterEntry>> = _cards.asStateFlow()

    init {
        seedBuiltIns()
        refresh()
    }

    fun get(id: String?): CharacterEntry? =
        _cards.value.firstOrNull { it.id == id }

    fun refresh() {
        val favorites = favoriteIds()
        _cards.value = root.listFiles { file -> file.isDirectory && File(file, CARD).isFile }
            .orEmpty()
            .mapNotNull { dir ->
                val card = runCatching { CardSpec.parse(File(dir, CARD).readText()) }.getOrNull()
                    ?: return@mapNotNull null
                CharacterEntry(
                    id = dir.name,
                    card = card,
                    avatar = dir.listFiles { file -> file.isFile && file.name.startsWith("avatar.") }
                        ?.maxByOrNull { it.lastModified() },
                    favorite = dir.name in favorites,
                )
            }
            .sortedBy { it.card.name }
    }

    fun save(
        card: CharacterCardData,
        id: String? = null,
        avatarBytes: ByteArray? = null,
        avatarExt: String = "png",
    ): String {
        val useId = id?.takeIf { isSafeId(it) } ?: newId(card.name)
        val dir = File(root, useId).apply { mkdirs() }
        File(dir, CARD).writeText(card.toJson(pretty = true))
        if (avatarBytes != null) {
            dir.listFiles { file -> file.name.startsWith("avatar.") }?.forEach { it.delete() }
            val ext = avatarExt.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "png" }
            File(dir, "avatar.$ext").writeBytes(avatarBytes)
        }
        refresh()
        return useId
    }

    /** 导入 PNG / JSON 角色卡，返回新卡片 id。 */
    fun importCard(bytes: ByteArray, fileName: String = ""): Result<String> = runCatching {
        val imported = CardCodec.decode(bytes, fileName)
        val avatar = imported.avatar ?: CardCodec.solidPng(256, colorFor(imported.card.name))
        save(
            card = imported.card,
            avatarBytes = avatar,
            avatarExt = imageExt(avatar) ?: "png",
        )
    }

    fun toggleFavorite(id: String) {
        val favorites = favoriteIds()
        if (id in favorites) favorites.remove(id) else favorites.add(id)
        prefs.edit()
            .putStringSet(KEY_FAVORITES, favorites.map { it }.toSet())
            .apply()
        refresh()
    }

    fun delete(id: String): Boolean {
        if (!isSafeId(id)) return false
        val dir = File(root, id)
        if (!dir.isDirectory) return false
        val ok = dir.deleteRecursively()
        val favorites = favoriteIds().also { it.remove(id) }
        prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
        refresh()
        return ok
    }

    fun exportJson(id: String): String? = get(id)?.card?.toJson(pretty = true)

    fun exportPng(id: String): ByteArray? {
        val entry = get(id) ?: return null
        val avatar = entry.avatar?.takeIf { it.isFile }?.readBytes()
        val png = avatar?.takeIf { CardCodec.isPng(it) }
            ?: CardCodec.solidPng(256, colorFor(entry.card.name))
        return CardCodec.encodePng(entry.card, png)
    }

    private fun seedBuiltIns() {
        if (root.listFiles { file -> file.isDirectory }?.isNotEmpty() == true) return
        data class Seed(
            val id: String,
            val name: String,
            val tagline: String,
            val greeting: String,
            val accent: Long,
        )
        val seeds = listOf(
            Seed("xiaoman", "小满", "温柔的生活搭子", "今天也辛苦啦，想聊点什么？", 0xFFFF9AA2L),
            Seed("lan", "岚", "冷静的事务助手", "我在。列个清单，我们一件件来。", 0xFF7DB3FFL),
            Seed("che", "阿澈", "元气的行动派", "出发！今天想完成什么？", 0xFF7FE0C3L),
        )
        seeds.forEach { seed ->
            val card = CardSpec.normalize(
                buildJsonObject {
                    put("name", seed.name)
                    put("description", seed.tagline)
                    put("first_mes", seed.greeting)
                    putJsonObject("extensions") {
                        putJsonObject("iris") { put("accent", seed.accent) }
                    }
                },
            )
            val png = CardCodec.solidPng(256, (seed.accent and 0xFFFFFFL).toInt())
            save(card, seed.id, png, "png")
        }
    }

    private fun newId(name: String): String {
        val base = slug(name).ifEmpty { "card" }
        var index = 0
        while (true) {
            val id = if (index == 0) base else "$base-$index"
            if (!File(root, id).exists()) return id
            index++
        }
    }

    private fun favoriteIds(): MutableSet<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toMutableSet()

    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && '/' !in id && '\\' !in id && id != "." && id != ".."

    private fun colorFor(name: String): Int {
        val palette = intArrayOf(
            0xFF8E7CC3.toInt(), 0xFF5B8DEF.toInt(), 0xFF34C759.toInt(),
            0xFFFF9F0A.toInt(), 0xFFAF52DE.toInt(), 0xFF00C7BE.toInt(),
        )
        val index = (name.hashCode() and Int.MAX_VALUE) % palette.size
        return palette[index]
    }

    private fun imageExt(bytes: ByteArray): String? = when {
        CardCodec.isPng(bytes) -> "png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 12 &&
            String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        bytes.size > 6 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif"
        else -> null
    }

    private companion object {
        const val PREFS = "iris_characters"
        const val KEY_FAVORITES = "favorites"
        const val CARD = "card.json"

        fun slug(name: String): String = name.trim()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(40)
    }
}
