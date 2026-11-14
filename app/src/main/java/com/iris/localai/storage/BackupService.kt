package com.iris.localai.storage

import android.content.Context
import android.net.Uri
import com.iris.localai.character.CardSpec
import com.iris.localai.character.CharacterCardStore
import com.iris.localai.data.ModelProfileStore
import com.iris.localai.data.SettingsRepository
import com.iris.localai.lorebook.WorldBookSettings
import com.iris.localai.lorebook.WorldBookStore
import com.iris.localai.memory.MemoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class BackupCategory(val id: String, val label: String) {
    CHARACTERS("characters", "角色卡"),
    WORLD_BOOKS("worldbooks", "世界书"),
    MEMORY("memory", "记忆"),
    SETTINGS("settings", "设置"),
}

data class BackupPreview(
    val createdAt: Long,
    val categories: Set<BackupCategory>,
    val counts: Map<BackupCategory, Int>,
)

data class BackupOutcome(
    val counts: Map<BackupCategory, Int>,
    val skipped: Int,
)

/**
 * 分类备份与恢复：一个 ZIP 内按分类放文件，manifest 记录版本与数量。
 * 备份不包含 API Key、角色模型、对话记录与插件/MCP 配置；恢复只覆盖所选分类。
 */
class BackupService(
    context: Context,
    private val settings: SettingsRepository,
    private val models: ModelProfileStore,
    private val characters: CharacterCardStore,
    private val worldBooks: WorldBookStore,
    private val memory: MemoryStore,
) {

    private val resolver = context.applicationContext.contentResolver

    fun write(uri: Uri, categories: Set<BackupCategory>): Result<BackupOutcome> = runCatching {
        require(categories.isNotEmpty()) { "至少选择一个备份内容" }
        val counts = countsFor(categories)
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("无法写入备份文件")
        ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
            writeEntry(zip, MANIFEST, manifest(categories, counts).toString().toByteArray())
            if (BackupCategory.CHARACTERS in categories) writeCharacters(zip)
            if (BackupCategory.WORLD_BOOKS in categories) writeWorldBooks(zip)
            if (BackupCategory.MEMORY in categories) writeMemory(zip)
            if (BackupCategory.SETTINGS in categories) writeSettings(zip)
        }
        BackupOutcome(counts, skipped = 0)
    }

    fun inspect(uri: Uri): Result<BackupPreview> = runCatching {
        val stream = resolver.openInputStream(uri) ?: error("无法读取备份文件")
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var root: JsonObject? = null
            var entries = 0
            while (root == null) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "备份条目过多" }
                if (!entry.isDirectory && entry.name == MANIFEST) {
                    val bytes = readLimited(zip, MAX_MANIFEST_BYTES)
                    root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
                }
                zip.closeEntry()
            }
            val manifest = root ?: error("不是 IRIS 备份文件")
            validateManifest(manifest)
            val categories = (manifest["categories"] as? JsonArray)
                ?.mapNotNull { element ->
                    (element as? JsonPrimitive)?.contentOrNull?.let { id ->
                        BackupCategory.entries.firstOrNull { it.id == id }
                    }
                }
                .orEmpty()
                .toSet()
            val counts = (manifest["counts"] as? JsonObject)
                ?.mapNotNull { (id, value) ->
                    val category = BackupCategory.entries.firstOrNull { it.id == id }
                        ?: return@mapNotNull null
                    category to ((value as? JsonPrimitive)?.intOrNull ?: 0)
                }
                .orEmpty()
                .toMap()
            BackupPreview(
                createdAt = (manifest["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                categories = categories,
                counts = counts,
            )
        }
    }

    fun restore(uri: Uri, categories: Set<BackupCategory>): Result<BackupOutcome> = runCatching {
        require(categories.isNotEmpty()) { "至少选择一个恢复内容" }
        val buffer = RestoreBuffer()
        var totalBytes = 0L
        var entryCount = 0
        var skipped = 0
        var manifestFound = false
        val stream = resolver.openInputStream(uri) ?: error("无法读取备份文件")
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "备份条目过多，已停止恢复" }
                if (entry.name == MANIFEST) {
                    val bytes = readLimited(zip, MAX_MANIFEST_BYTES)
                    validateManifest(Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject)
                    manifestFound = true
                    zip.closeEntry()
                    continue
                }
                val segments = BackupFormat.segments(entry.name)
                if (segments == null || segments.size < 2) {
                    skipped++
                    zip.closeEntry()
                    continue
                }
                val category = BackupCategory.entries.firstOrNull { it.id == segments[0] }
                if (category == null || category !in categories) {
                    zip.closeEntry()
                    continue
                }
                val bytes = readLimited(zip, MAX_ENTRY_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= MAX_TOTAL_BYTES) { "备份内容解压后过大，已停止恢复" }
                if (!buffer.collect(category, segments, bytes)) skipped++
                zip.closeEntry()
            }
        }
        require(manifestFound) { "不是 IRIS 备份文件" }
        apply(buffer, categories, skipped)
    }

    private fun countsFor(categories: Set<BackupCategory>): Map<BackupCategory, Int> = buildMap {
        if (BackupCategory.CHARACTERS in categories) {
            put(BackupCategory.CHARACTERS, characters.cards.value.size)
        }
        if (BackupCategory.WORLD_BOOKS in categories) {
            put(BackupCategory.WORLD_BOOKS, worldBooks.books.value.size)
        }
        if (BackupCategory.MEMORY in categories) {
            put(
                BackupCategory.MEMORY,
                memory.diary.value.size + memory.memories.value.size + memory.knowledge.value.size,
            )
        }
        if (BackupCategory.SETTINGS in categories) {
            put(BackupCategory.SETTINGS, settings.snapshotForBackup().size)
        }
    }

    private fun manifest(
        categories: Set<BackupCategory>,
        counts: Map<BackupCategory, Int>,
    ): JsonObject = buildJsonObject {
        put("app", APP_NAME)
        put("schema", SCHEMA)
        put("createdAt", System.currentTimeMillis())
        putJsonArray("categories") {
            categories.sortedBy { it.ordinal }.forEach { add(it.id) }
        }
        putJsonObject("counts") {
            counts.forEach { (category, count) -> put(category.id, count) }
        }
    }

    private fun validateManifest(manifest: JsonObject) {
        require((manifest["app"] as? JsonPrimitive)?.contentOrNull == APP_NAME) { "不是 IRIS 备份文件" }
        val schema = (manifest["schema"] as? JsonPrimitive)?.intOrNull ?: 0
        require(schema in 1..SCHEMA) { "不支持的备份格式版本：$schema" }
    }

    private fun writeCharacters(zip: ZipOutputStream) {
        val cards = characters.cards.value
        val index = buildJsonObject {
            putJsonArray("favorites") { cards.filter { it.favorite }.forEach { add(it.id) } }
        }
        writeEntry(zip, "$CHARACTERS_INDEX", index.toString().toByteArray())
        cards.forEach { entry ->
            writeEntry(zip, "characters/${entry.id}/card.json", entry.card.toJson(pretty = false).toByteArray())
            val avatar = entry.avatar?.takeIf { it.isFile } ?: return@forEach
            if (avatar.length() > MAX_AVATAR_BYTES) return@forEach
            val ext = avatar.extension.lowercase().takeIf { EXT_PATTERN.matches(it) } ?: "png"
            writeEntry(zip, "characters/${entry.id}/avatar.$ext", avatar.readBytes())
        }
    }

    private fun writeWorldBooks(zip: ZipOutputStream) {
        val current = worldBooks.settings.value
        val payload = buildJsonObject {
            put("books", Json.parseToJsonElement(worldBooks.snapshotJson()))
            putJsonObject("settings") {
                put("scanDepth", current.scanDepth)
                put("tokenBudget", current.tokenBudget)
                put("minActivations", current.minActivations)
                put("maxRecursion", current.maxRecursion)
                put("includeConstant", current.includeConstant)
            }
        }
        writeEntry(zip, WORLD_BOOKS_FILE, payload.toString().toByteArray())
    }

    private fun writeMemory(zip: ZipOutputStream) {
        memory.snapshotFiles().forEach { (name, bytes) ->
            writeEntry(zip, "memory/$name", bytes)
        }
    }

    private fun writeSettings(zip: ZipOutputStream) {
        writeEntry(zip, SETTINGS_FILE, settings.snapshotForBackup().toString().toByteArray())
        writeEntry(zip, MODEL_PROFILES_FILE, models.snapshotForBackup().toString().toByteArray())
    }

    private fun apply(
        buffer: RestoreBuffer,
        categories: Set<BackupCategory>,
        skipped: Int,
    ): BackupOutcome {
        val counts = LinkedHashMap<BackupCategory, Int>()
        if (BackupCategory.CHARACTERS in categories) {
            var restored = 0
            buffer.cards.forEach { (id, bytes) ->
                if (bytes.size > MAX_CARD_BYTES) return@forEach
                val card = runCatching {
                    CardSpec.parse(String(bytes, Charsets.UTF_8))
                }.getOrNull() ?: return@forEach
                val avatar = buffer.avatars[id]
                characters.save(
                    card = card,
                    id = id,
                    avatarBytes = avatar?.first,
                    avatarExt = avatar?.second ?: "png",
                )
                restored++
            }
            val favorites = buffer.favorites
            if (favorites != null) {
                characters.restoreFavorites(buffer.cards.keys, favorites)
            } else {
                characters.refresh()
            }
            counts[BackupCategory.CHARACTERS] = restored
        }

        if (BackupCategory.WORLD_BOOKS in categories) {
            buffer.worldBooks?.let { payload ->
                val books = payload["books"] as? JsonArray
                if (books != null) {
                    val current = worldBooks.settings.value
                    val stored = payload["settings"] as? JsonObject
                    worldBooks.restoreFromBackup(
                        books.toString(),
                        WorldBookSettings(
                            scanDepth = stored?.int("scanDepth") ?: current.scanDepth,
                            tokenBudget = stored?.int("tokenBudget") ?: current.tokenBudget,
                            minActivations = stored?.int("minActivations") ?: current.minActivations,
                            maxRecursion = stored?.int("maxRecursion") ?: current.maxRecursion,
                            includeConstant = stored?.bool("includeConstant") ?: current.includeConstant,
                        ),
                    )
                    counts[BackupCategory.WORLD_BOOKS] = books.size
                }
            }
        }

        if (BackupCategory.MEMORY in categories && buffer.memoryFiles.isNotEmpty()) {
            memory.restoreFiles(buffer.memoryFiles)
            counts[BackupCategory.MEMORY] =
                memory.diary.value.size + memory.memories.value.size + memory.knowledge.value.size
        }

        if (BackupCategory.SETTINGS in categories) {
            buffer.modelProfiles?.let { models.restoreFromBackup(it) }
            buffer.settings?.let { settings.restoreFromBackup(it) }
            counts[BackupCategory.SETTINGS] = buffer.settings?.size ?: 0
        }

        return BackupOutcome(counts, skipped)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "单个备份条目过大" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private class RestoreBuffer {
        val cards = LinkedHashMap<String, ByteArray>()
        val avatars = LinkedHashMap<String, Pair<ByteArray, String>>()
        var favorites: Set<String>? = null
        var worldBooks: JsonObject? = null
        val memoryFiles = LinkedHashMap<String, ByteArray>()
        var settings: JsonObject? = null
        var modelProfiles: JsonObject? = null

        fun collect(category: BackupCategory, segments: List<String>, bytes: ByteArray): Boolean =
            when (category) {
                BackupCategory.CHARACTERS -> {
                    if (segments.size == 2 && segments[1] == "index.json") {
                        val parsed = parseFavorites(bytes)
                        if (parsed != null) favorites = parsed
                        parsed != null
                    } else {
                        collectCharacter(segments, bytes)
                    }
                }
                BackupCategory.WORLD_BOOKS -> {
                    val payload = parseObject(bytes)
                    if (segments.size == 2 && segments[1] == "worldbooks.json" &&
                        payload != null && payload["books"] is JsonArray
                    ) {
                        worldBooks = payload
                        true
                    } else {
                        false
                    }
                }
                BackupCategory.MEMORY -> {
                    if (segments.size == 2 && segments[1].endsWith(".json") &&
                        runCatching {
                            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonArray
                        }.isSuccess
                    ) {
                        memoryFiles[segments[1]] = bytes
                        true
                    } else {
                        false
                    }
                }
                BackupCategory.SETTINGS -> {
                    when {
                        segments.size != 2 -> false
                        segments[1] == "settings.json" -> {
                            settings = parseObject(bytes)
                            settings != null
                        }
                        segments[1] == "model_profiles.json" -> {
                            modelProfiles = parseObject(bytes)
                            modelProfiles != null
                        }
                        else -> false
                    }
                }
            }

        private fun collectCharacter(segments: List<String>, bytes: ByteArray): Boolean {
            if (segments.size != 3) return false
            val id = segments[1]
            if (!BackupFormat.isSafeId(id)) return false
            return when {
                segments[2] == "card.json" -> {
                    if (bytes.size > MAX_CARD_BYTES ||
                        runCatching { CardSpec.parse(String(bytes, Charsets.UTF_8)) }.isFailure
                    ) {
                        false
                    } else {
                        cards[id] = bytes
                        true
                    }
                }
                segments[2].startsWith("avatar.") -> {
                    val ext = segments[2].removePrefix("avatar.").lowercase()
                    if (bytes.size > MAX_AVATAR_BYTES || !EXT_PATTERN.matches(ext)) {
                        false
                    } else {
                        avatars[id] = bytes to ext
                        true
                    }
                }
                else -> false
            }
        }

        private fun parseObject(bytes: ByteArray): JsonObject? = runCatching {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull()

        private fun parseFavorites(bytes: ByteArray): Set<String>? = runCatching {
            val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
            (root["favorites"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
        }.getOrNull()
    }

    private companion object {
        const val APP_NAME = "IRIS LocalAI"
        const val SCHEMA = 1
        const val MANIFEST = "manifest.json"
        const val CHARACTERS_INDEX = "characters/index.json"
        const val WORLD_BOOKS_FILE = "worldbooks/worldbooks.json"
        const val SETTINGS_FILE = "settings/settings.json"
        const val MODEL_PROFILES_FILE = "settings/model_profiles.json"
        const val MAX_ENTRIES = 5000
        const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    }
}

private const val MAX_CARD_BYTES = 8L * 1024 * 1024
private const val MAX_AVATAR_BYTES = 16L * 1024 * 1024
private val EXT_PATTERN = Regex("[a-z0-9]{1,5}")

/** 备份条目路径校验：拒绝绝对路径、反斜杠、控制字符与 .. 穿越。 */
internal object BackupFormat {

    fun segments(name: String): List<String>? {
        if (name.isBlank() || name.length > 512) return null
        if (name.startsWith("/") || '\\' in name) return null
        if (name.any { it.code < 0x20 || it.code == 0x7f }) return null
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts
    }

    fun isSafeId(id: String): Boolean =
        id.isNotBlank() && id.length <= 64 && '/' !in id && '\\' !in id &&
            id != "." && id != ".." && id.none { it.isISOControl() }
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
