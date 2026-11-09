package com.iris.localai.character

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream

enum class CharacterModelType(val label: String) {
    LIVE2D("Live2D"),
    VRM("VRM"),
    GLB("glTF/GLB"),
    MMD("MMD"),
}

data class CharacterModelEntry(
    val id: String,
    val name: String,
    val type: CharacterModelType,
    val entryFile: String,
    val directory: String,
    val thumbnail: String?,
    val sizeBytes: Long,
    val importedAt: Long,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * 角色模型资产管理：Live2D（ZIP/model3.json）与 3D（VRM / GLB / MMD）。
 * 模型文件存放在 `filesDir/character_models/<id>/`，并尽量抽取缩略图。
 * 实际渲染参考 AIRI：WebView + Three.js / Live2D；本模板先完成导入、管理与预览图。
 */
class CharacterModelStore(
    private val context: Context,
    modelsDir: String = MODELS_DIR,
    prefsName: String = PREFS,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val root = File(context.filesDir, modelsDir).apply { mkdirs() }

    private val _models = MutableStateFlow(loadModels())
    val models: StateFlow<List<CharacterModelEntry>> = _models.asStateFlow()

    private val _activeId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, "").orEmpty(),
    )
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _bindings = MutableStateFlow(loadBindings())
    val bindings: StateFlow<Map<String, String>> = _bindings.asStateFlow()

    fun active(): CharacterModelEntry? =
        _models.value.firstOrNull { it.id == _activeId.value }

    fun boundModel(characterId: String?): CharacterModelEntry? {
        val modelId = characterId?.let { _bindings.value[it] } ?: return null
        return _models.value.firstOrNull { it.id == modelId }
    }

    fun bind(characterId: String, modelId: String) {
        if (characterId.isBlank() || _models.value.none { it.id == modelId }) return
        _bindings.update { it + (characterId to modelId) }
        persistBindings()
    }

    fun unbind(characterId: String) {
        if (characterId !in _bindings.value) return
        _bindings.update { it - characterId }
        persistBindings()
    }

    fun updateOffset(id: String, offsetX: Float, offsetY: Float) {
        if (_models.value.none { it.id == id }) return
        _models.update { models ->
            models.map { model ->
                if (model.id != id) model else model.copy(
                    offsetX = offsetX.coerceIn(-0.5f, 0.5f),
                    offsetY = offsetY.coerceIn(-0.5f, 0.5f),
                )
            }
        }
        persist()
    }

    fun setActive(id: String) {
        if (_models.value.none { it.id == id }) return
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun clearActive() {
        _activeId.value = ""
        prefs.edit().putString(KEY_ACTIVE, "").apply()
    }

    fun import(uri: Uri, displayName: String): Result<CharacterModelEntry> = runCatching {
        val temp = File(context.cacheDir, "model-import-${UUID.randomUUID()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) error("文件超过 300 MB")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法读取文件")
            if (temp.length() > MAX_IMPORT_BYTES) error("文件超过 300 MB")
            importFile(temp, displayName)
        } finally {
            temp.delete()
        }
    }

    fun importFile(file: File, displayName: String): CharacterModelEntry {
        if (!file.isFile) error("模型文件不存在")
        if (file.length() > MAX_IMPORT_BYTES) error("文件超过 300 MB")
        val id = "model-${UUID.randomUUID()}"
        val dir = File(root, id).apply { mkdirs() }
        try {
            val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            if (safeName.endsWith(".zip", true) || isZip(file)) {
                extractZip(file, dir)
            } else {
                val target = File(dir, safeName.ifBlank { file.name.ifBlank { "model.bin" } })
                if (!file.renameTo(target)) file.copyTo(target, overwrite = true)
            }

            val detected = detect(dir) ?: error("未找到 Live2D model3.json / VRM / GLB / MMD 模型")
            val thumbnail = buildThumbnail(dir, detected.second, detected.first)
            val model = CharacterModelEntry(
                id = id,
                name = displayNameFor(safeName, detected.second),
                type = detected.first,
                entryFile = detected.second.relativeTo(dir).path,
                directory = dir.name,
                thumbnail = thumbnail?.absolutePath,
                sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                importedAt = System.currentTimeMillis(),
            )
            _models.update { it + model }
            persist()
            setActive(id)
            return model
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun delete(id: String): Boolean {
        val model = _models.value.firstOrNull { it.id == id } ?: return false
        if (model.directory.contains("..") || model.directory.contains('/')) return false
        val dir = File(root, model.directory)
        val deleted = dir.deleteRecursively()
        _models.update { list -> list.filterNot { it.id == id } }
        val removedBindings = _bindings.value.filterValues { it == id }
        if (removedBindings.isNotEmpty()) {
            _bindings.update { current -> current.filterValues { it != id } }
            persistBindings()
        }
        if (_activeId.value == id) {
            _models.value.firstOrNull()?.let { setActive(it.id) } ?: clearActive()
        }
        persist()
        return deleted
    }

    private fun detect(dir: File): Pair<CharacterModelType, File>? {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        files.firstOrNull {
            it.name == "model3.json" || it.name.endsWith(".model3.json", true)
        }?.let { return CharacterModelType.LIVE2D to it }
        files.firstOrNull { it.extension.equals("vrm", true) }
            ?.let { return CharacterModelType.VRM to it }
        files.firstOrNull { it.extension.equals("glb", true) || it.extension.equals("gltf", true) }
            ?.let { return CharacterModelType.GLB to it }
        files.firstOrNull { it.extension.equals("pmx", true) || it.extension.equals("pmd", true) }
            ?.let { return CharacterModelType.MMD to it }
        return null
    }

    private fun buildThumbnail(
        dir: File,
        entry: File,
        type: CharacterModelType,
    ): File? {
        if (type == CharacterModelType.VRM || type == CharacterModelType.GLB) {
            GlbThumbnail.extract(entry)?.let { bytes ->
                return File(dir, "thumbnail.img").apply { writeBytes(bytes) }
            }
        }
        return dir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") &&
                    file.length() > 4_000
            }
            .maxByOrNull { it.length() }
    }

    private fun displayNameFor(fileName: String, entry: File): String {
        val base = fileName.substringBeforeLast('.').ifBlank {
            entry.name.substringBeforeLast('.')
        }
        return base.ifBlank { "角色模型" }
    }

    private fun extractZip(zip: File, dest: File) {
        var total = 0L
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                entries++
                if (entries > 5000) error("压缩包文件数量过多")
                val name = entry.name.replace('\\', '/').trimStart('/')
                val parts = name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                if (parts.isEmpty()) continue
                val target = File(dest, parts.joinToString("/"))
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) error("压缩包解压后超过 300 MB")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun isZip(file: File): Boolean =
        file.length() > 4 && runCatching {
            file.inputStream().use { stream ->
                val head = ByteArray(4)
                stream.read(head)
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            }
        }.getOrDefault(false)

    private fun loadModels(): List<CharacterModelEntry> {
        val raw = prefs.getString(KEY_MODELS, null) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.string("id") ?: return@mapNotNull null
                val directory = obj.string("directory") ?: return@mapNotNull null
                if (!File(root, directory).isDirectory) return@mapNotNull null
                // FBX 支持已移除；旧数据直接跳过，避免出现无法渲染的条目。
                if ((obj.string("type") ?: "").equals("FBX", ignoreCase = true)) return@mapNotNull null
                CharacterModelEntry(
                    id = id,
                    name = obj.string("name") ?: "角色模型",
                    type = runCatching {
                        CharacterModelType.valueOf(obj.string("type") ?: "")
                    }.getOrDefault(CharacterModelType.GLB),
                    entryFile = obj.string("entryFile").orEmpty(),
                    directory = directory,
                    thumbnail = obj.string("thumbnail"),
                    sizeBytes = obj.long("sizeBytes") ?: 0L,
                    importedAt = obj.long("importedAt") ?: 0L,
                    offsetX = obj.float("offsetX") ?: 0f,
                    offsetY = obj.float("offsetY") ?: 0f,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun loadBindings(): Map<String, String> {
        val raw = prefs.getString(KEY_BINDINGS, null) ?: return emptyMap()
        return runCatching {
            val validIds = _models.value.map { it.id }.toSet()
            Json.parseToJsonElement(raw).jsonObject.mapNotNull { (characterId, value) ->
                val modelId = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (characterId.isBlank() || modelId !in validIds) return@mapNotNull null
                characterId to modelId
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun persistBindings() {
        val json = buildJsonObject {
            _bindings.value.forEach { (characterId, modelId) -> put(characterId, modelId) }
        }.toString()
        prefs.edit().putString(KEY_BINDINGS, json).apply()
    }

    private fun persist() {
        val json = buildJsonArray {
            _models.value.forEach { model ->
                addJsonObject {
                    put("id", model.id)
                    put("name", model.name)
                    put("type", model.type.name)
                    put("entryFile", model.entryFile)
                    put("directory", model.directory)
                    put("thumbnail", model.thumbnail)
                    put("sizeBytes", model.sizeBytes)
                    put("importedAt", model.importedAt)
                    put("offsetX", model.offsetX)
                    put("offsetY", model.offsetY)
                }
            }
        }.toString()
        prefs.edit().putString(KEY_MODELS, json).apply()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.floatOrNull

    private companion object {
        const val PREFS = "iris_character_models"
        const val KEY_MODELS = "models"
        const val KEY_ACTIVE = "active"
        const val KEY_BINDINGS = "bindings"
        const val MODELS_DIR = "character_models"
        const val MAX_IMPORT_BYTES = 300L * 1024 * 1024
    }
}

/** 从 VRM 0.x / VRM 1.0 的 GLB 中读出 meta 缩略图。 */
object GlbThumbnail {

    fun extract(file: File): ByteArray? = runCatching {
        if (!file.isFile || file.length() > 300L * 1024 * 1024) return null
        RandomAccessFile(file, "r").use { raf ->
            if (raf.readIntLe() != 0x46546C67) return null
            raf.readIntLe() // version
            raf.readIntLe() // total length
            var json: JsonObject? = null
            var binOffset = -1L
            while (raf.filePointer + 8 <= raf.length()) {
                val length = raf.readIntLe()
                val type = raf.readIntLe()
                val dataOffset = raf.filePointer
                if (length < 0 || dataOffset + length > raf.length()) break
                when (type) {
                    0x4E4F534A -> {
                        val bytes = ByteArray(length)
                        raf.readFully(bytes)
                        json = Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
                            .jsonObject
                    }
                    0x004E4942 -> binOffset = dataOffset
                }
                raf.seek(dataOffset + length)
            }
            val root = json ?: return null
            val extensions = root["extensions"] as? JsonObject ?: return null
            val vrm0 = (extensions["VRM"] as? JsonObject)
                ?.get("meta") as? JsonObject
            val vrm1 = (extensions["VRMC_vrm"] as? JsonObject)
                ?.get("meta") as? JsonObject
            val index = (vrm0?.get("texture") ?: vrm1?.get("thumbnailImage"))
                ?.jsonPrimitive?.intOrNull ?: return null
            val image = (root["images"] as? JsonArray)
                ?.getOrNull(index) as? JsonObject ?: return null

            val dataUri = image.stringValue("uri")
            if (!dataUri.isNullOrBlank() && dataUri.startsWith("data:")) {
                val comma = dataUri.indexOf(',')
                if (comma < 0 || !dataUri.substring(0, comma).endsWith(";base64")) return null
                return Base64.getMimeDecoder().decode(dataUri.substring(comma + 1))
            }

            val viewIndex = image.intValue("bufferView") ?: return null
            val view = (root["bufferViews"] as? JsonArray)
                ?.getOrNull(viewIndex) as? JsonObject ?: return null
            val byteOffset = view.intValue("byteOffset") ?: 0
            val length = view.intValue("byteLength") ?: return null
            if (length <= 0 || length > 20 * 1024 * 1024) return null
            if (binOffset < 0) return null
            val absolute = binOffset + byteOffset
            if (absolute + length > raf.length()) return null
            raf.seek(absolute)
            ByteArray(length).also { raf.readFully(it) }
        }
    }.getOrNull()

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intValue(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun RandomAccessFile.readIntLe(): Int {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return -1
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
