package com.iris.localai.scene

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.iris.localai.storage.AppStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class StudioModelEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val importedAt: Long,
    /** 模型入口：未加密模型为 ysm.json，加密模型为 .ysm 文件。 */
    val entry: String = MANIFEST,
) {
    companion object {
        const val MANIFEST = "ysm.json"
    }
}

/**
 * 综合空间小人的 YSM 模型。
 *
 * 只接受未加密的文件夹 / ZIP（内部含 ysm.json、基岩版 geometry 与 animation）。
 * `.ysm` 是加密格式，无法在客户端解析，导入时会直接给出提示。
 */
class StudioModelStore(
    context: Context,
    private val storage: AppStorage,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _models = MutableStateFlow(load())
    val models: StateFlow<List<StudioModelEntry>> = _models.asStateFlow()

    /** 返回模型根目录（内含 ysm.json）；拦截器与打包逻辑共用。 */
    fun directory(id: String): File? {
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        val entry = _models.value.firstOrNull { it.id == id } ?: return null
        return storage.readDirs(DIR)
            .asSequence()
            .map { File(it, entry.id) }
            .firstOrNull { candidate -> File(candidate, entry.entry).isFile }
    }

    fun importZip(uri: Uri, displayName: String): Result<StudioModelEntry> =
        importFromStaging(displayName) { staging ->
            val temp = File(appContext.cacheDir, "studio-${UUID.randomUUID()}.zip")
            try {
                copyUri(uri, temp)
                if (!isZip(temp)) {
                    error("请选择包含 ysm.json 或 .ysm 的 ZIP")
                }
                extractZip(temp, staging)
            } finally {
                temp.delete()
            }
        }

    /** 单文件导入：ZIP（含 ysm.json/.ysm）或直接的 .ysm。 */
    fun importFile(uri: Uri, displayName: String): Result<StudioModelEntry> =
        importFromStaging(displayName) { staging ->
            val safe = safeName(displayName).ifBlank { "model.ysm" }
            val temp = File(staging, safe)
            copyUri(uri, temp)
            if (isZip(temp)) {
                val extractDir = File(staging, "extracted").apply { mkdirs() }
                extractZip(temp, extractDir)
                temp.delete()
            }
        }

    fun importTree(uri: Uri, displayName: String): Result<StudioModelEntry> =
        importFromStaging(displayName) { staging ->
            copyTree(uri, staging)
        }

    fun delete(id: String): Boolean {
        val entry = _models.value.firstOrNull { it.id == id } ?: return false
        val deleted = storage.readDirs(DIR).fold(false) { acc, root ->
            val candidate = File(root, entry.id)
            acc || (candidate.isDirectory && candidate.deleteRecursively())
        }
        _models.update { list -> list.filterNot { it.id == id } }
        persist()
        return deleted
    }

    private fun importFromStaging(
        displayName: String,
        fill: (File) -> Unit,
    ): Result<StudioModelEntry> = runCatching {
        val staging = File(appContext.cacheDir, "studio-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            fill(staging)
            val source = findSource(staging)
                ?: error("没有找到 ysm.json 或 .ysm；请确认模型未被破坏")
            val id = "ysm-${UUID.randomUUID()}"
            val dest = File(storage.writeDir(DIR), id)
            copyDirectory(source.root, dest)
            if (!File(dest, source.entry).isFile) error("模型缺少入口文件 ${source.entry}")
            val fallback = displayName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
                .ifBlank { "YSM 模型" }
            val entry = StudioModelEntry(
                id = id,
                name = (manifestName(File(dest, source.entry)) ?: fallback).take(60),
                sizeBytes = dest.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                importedAt = System.currentTimeMillis(),
                entry = source.entry,
            )
            _models.update { it + entry }
            persist()
            entry
        } finally {
            staging.deleteRecursively()
        }
    }

    private data class ModelSource(val root: File, val entry: String)

    private fun findSource(root: File): ModelSource? {
        val manifest = root.walkTopDown()
            .filter { it.isFile && it.name.equals(MANIFEST, ignoreCase = true) }
            .minByOrNull { depthOf(root, it) }
        if (manifest != null) {
            val parent = manifest.parentFile ?: root
            return ModelSource(parent, manifest.relativeTo(parent).invariantSeparatorsPath)
        }
        val archive = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("ysm", ignoreCase = true) }
            .minByOrNull { depthOf(root, it) }
        if (archive != null) {
            val parent = archive.parentFile ?: root
            return ModelSource(parent, archive.relativeTo(parent).invariantSeparatorsPath)
        }
        return null
    }

    private fun depthOf(root: File, file: File): Int =
        file.relativeTo(root).invariantSeparatorsPath.count { it == '/' }

    private fun manifestName(manifest: File): String? = runCatching {
        val obj = Json.parseToJsonElement(manifest.readText()).jsonObject
        val metadata = obj["metadata"] as? JsonObject
        listOf(
            obj["name"]?.jsonPrimitive?.contentOrNull,
            metadata?.get("name")?.jsonPrimitive?.contentOrNull,
        ).firstOrNull { !it.isNullOrBlank() }
    }.getOrNull()

    private fun copyUri(uri: Uri, target: File) {
        val counter = longArrayOf(0)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> copyLimited(input, output, counter) }
        } ?: error("无法读取文件")
    }

    private fun copyTree(treeUri: Uri, dest: File) {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: error("无法读取文件夹")
        val counter = longArrayOf(0)
        val count = intArrayOf(0)
        copyChildren(
            treeUri = treeUri,
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId),
            dest = dest,
            counter = counter,
            count = count,
        )
    }

    private fun copyChildren(
        treeUri: Uri,
        childrenUri: Uri,
        dest: File,
        counter: LongArray,
        count: IntArray,
    ) {
        val resolver = appContext.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = safeName(cursor.getString(1) ?: "file")
                val mime = cursor.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childDir = File(dest, name).apply { mkdirs() }
                    copyChildren(
                        treeUri = treeUri,
                        childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                            treeUri,
                            documentId,
                        ),
                        dest = childDir,
                        counter = counter,
                        count = count,
                    )
                } else {
                    count[0]++
                    if (count[0] > MAX_FILES) error("模型文件数量过多")
                    resolver.openInputStream(childUri)?.use { input ->
                        File(dest, name).outputStream().use { output ->
                            copyLimited(input, output, counter)
                        }
                    }
                }
            }
        }
    }

    private fun extractZip(zip: File, dest: File) {
        val counter = longArrayOf(0)
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                entries++
                if (entries > MAX_FILES) error("压缩包文件数量过多")
                val parts = entry.name
                    .replace('\\', '/')
                    .split('/')
                    .filter { it.isNotEmpty() && it != "." && it != ".." }
                if (parts.isEmpty()) continue
                val target = File(dest, parts.joinToString("/"))
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> copyLimited(input, output, counter) }
            }
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream, counter: LongArray) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            counter[0] += read
            if (counter[0] > MAX_BYTES) error("模型体积超过 200 MB")
            output.write(buffer, 0, read)
        }
    }

    private fun copyDirectory(source: File, dest: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            if (relative.isBlank()) return@forEach
            val target = File(dest, relative)
            if (file.isDirectory) {
                target.mkdirs()
            } else if (file.isFile) {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "file" }.take(120)

    private fun isZip(file: File): Boolean =
        file.length() > 4 && runCatching {
            file.inputStream().use { stream ->
                val head = ByteArray(4)
                stream.read(head)
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            }
        }.getOrDefault(false)

    private fun load(): List<StudioModelEntry> = runCatching {
        val raw = prefs.getString(KEY_MODELS, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            StudioModelEntry(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "YSM 模型",
                sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                importedAt = obj["importedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                entry = obj["entry"]?.jsonPrimitive?.contentOrNull ?: MANIFEST,
            )
        }.filter { entry ->
            storage.readDirs(DIR).any { root -> File(root, entry.id).isDirectory }
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val json = buildJsonArray {
            _models.value.forEach { entry ->
                add(
                    buildJsonObject {
                        put("id", entry.id)
                        put("name", entry.name)
                        put("size", entry.sizeBytes)
                        put("importedAt", entry.importedAt)
                        put("entry", entry.entry)
                    },
                )
            }
        }.toString()
        prefs.edit().putString(KEY_MODELS, json).apply()
    }

    private companion object {
        const val PREFS = "iris_studio_models"
        const val KEY_MODELS = "models"
        const val DIR = "studio_models"
        const val MANIFEST = "ysm.json"
        const val MAX_BYTES = 200L * 1024 * 1024
        const val MAX_FILES = 3000
    }
}
