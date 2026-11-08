package com.iris.localai.plugin

import android.content.Context
import com.iris.localai.data.RemoteFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** 插件仓库：导入 Operit ToolPkg / IRIS 插件包，管理启用状态并解析工具声明。 */
class PluginStore(private val context: Context) {

    private val root = File(context.filesDir, PLUGINS_DIR).apply { mkdirs() }
    private val cache = File(context.cacheDir, "plugin-import").apply { mkdirs() }

    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    var onToolsChanged: (() -> Unit)? = null

    init {
        refresh()
    }

    fun refresh() {
        _plugins.value = root.listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir -> load(dir) }
            ?.sortedByDescending { it.installedAt }
            .orEmpty()
    }

    suspend fun installZip(
        bytes: ByteArray,
        source: String,
        expectedSha256: String? = null,
    ): InstalledPlugin = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_PACKAGE_BYTES) error("插件包超过 64 MB")
        if (expectedSha256 != null && !sha256(bytes).equals(expectedSha256, ignoreCase = true)) {
            error("SHA-256 校验失败，已拒绝安装")
        }
        _busy.value = true
        val tempZip = File(cache, "plugin-${UUID.randomUUID()}.zip")
        val tempDir = File(cache, "plugin-${UUID.randomUUID()}")
        try {
            tempZip.writeBytes(bytes)
            extractZip(tempZip, tempDir)
            val manifestFile = findManifest(tempDir)
                ?: error("压缩包中没有 manifest.json / manifest.hjson")
            if (manifestFile.length() > MAX_MANIFEST_BYTES) error("插件清单超过 2 MB")
            val manifest = PluginManifestParser.parse(manifestFile.readText())
            val baseDir = manifestFile.parentFile ?: tempDir
            val id = sanitizePluginId(manifest.id)
            val target = File(root, id)
            val previous = readMeta(target)
            val staging = File(root, ".staging-${UUID.randomUUID()}")
            val backup = File(root, ".backup-${UUID.randomUUID()}")
            try {
                if (!staging.mkdirs()) error("无法创建插件安装目录")
                copyTree(baseDir, staging)
                writeMeta(
                    staging,
                    PluginMeta(
                        enabled = previous?.enabled ?: manifest.enabledByDefault,
                        installedAt = System.currentTimeMillis(),
                        source = source,
                    ),
                )
                if (target.exists() && !target.renameTo(backup)) error("无法替换已安装插件")
                if (!staging.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target)
                    error("无法写入插件目录")
                }
                backup.deleteRecursively()
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
            refresh()
            onToolsChanged?.invoke()
            _plugins.value.firstOrNull { it.id == id } ?: error("安装后无法读取插件")
        } finally {
            tempZip.delete()
            tempDir.deleteRecursively()
            _busy.value = false
        }
    }

    suspend fun installUrl(url: String, expectedSha256: String? = null): InstalledPlugin {
        _busy.value = true
        return try {
            val bytes = RemoteFetch.bytes(url, MAX_PACKAGE_BYTES, timeoutMs = 60_000)
            installZip(bytes, url, expectedSha256)
        } finally {
            _busy.value = false
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val dir = resolve(root, id) ?: return
        setEnabledDir(dir, enabled)
    }

    fun setEnabled(plugin: InstalledPlugin, enabled: Boolean) {
        val dir = resolve(root, plugin.directory) ?: return
        setEnabledDir(dir, enabled)
    }

    private fun setEnabledDir(dir: File, enabled: Boolean) {
        if (!dir.isDirectory) return
        val meta = readMeta(dir) ?: PluginMeta(true, System.currentTimeMillis(), "")
        writeMeta(dir, meta.copy(enabled = enabled))
        refresh()
        onToolsChanged?.invoke()
    }

    fun delete(id: String): Boolean {
        val dir = resolve(root, id) ?: return false
        return deleteDir(dir)
    }

    fun delete(plugin: InstalledPlugin): Boolean {
        val dir = resolve(root, plugin.directory) ?: return false
        return deleteDir(dir)
    }

    private fun deleteDir(dir: File): Boolean {
        val removed = dir.deleteRecursively()
        if (removed) {
            refresh()
            onToolsChanged?.invoke()
        }
        return removed
    }

    fun enabledBindings(): List<PluginToolBinding> = _plugins.value
        .asSequence()
        .filter { it.enabled }
        .flatMap { plugin ->
            plugin.subpackages.asSequence().flatMap { sub ->
                sub.tools.asSequence().map { tool -> PluginToolBinding(plugin, sub, tool) }
            }
        }
        .toList()

    fun scriptOf(plugin: InstalledPlugin, subpackage: PluginSubpackageRuntime): File? =
        resolve(File(root, plugin.directory), subpackage.entry)

    fun pluginDir(plugin: InstalledPlugin): File = File(root, plugin.directory)

    fun resourceOf(plugin: InstalledPlugin, resource: PluginResource): File? =
        resolve(pluginDir(plugin), resource.path)

    fun runtimeName(binding: PluginToolBinding): String =
        "plugin_" + binding.plugin.id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") +
            "_" + binding.tool.name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

    private fun load(dir: File): InstalledPlugin? = runCatching {
        val manifestFile = findManifest(dir) ?: return null
        if (manifestFile.length() > MAX_MANIFEST_BYTES) return null
        val manifest = PluginManifestParser.parse(manifestFile.readText())
        val meta = readMeta(dir)
        val subpackages = manifest.subpackages.map { sub ->
            val script = resolve(dir, sub.entry)
            val tools = when {
                script == null || !script.isFile -> emptyList()
                script.length() > MAX_SCRIPT_BYTES -> emptyList()
                else -> PluginManifestParser.parseMetadata(script.readText())
            }.ifEmpty {
                if (manifest.subpackages.size == 1) manifest.toolSpecs else emptyList()
            }
            PluginSubpackageRuntime(sub.id, sub.entry, tools)
        }
        val logo = manifest.logo?.let { logo ->
            val path = manifest.resources.firstOrNull { it.key == logo }?.path ?: logo
            resolve(dir, path)
        }?.takeIf { it.isFile }?.absolutePath
        InstalledPlugin(
            id = sanitizePluginId(manifest.id),
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            author = manifest.author,
            origin = manifest.origin,
            enabled = meta?.enabled ?: manifest.enabledByDefault,
            installedAt = meta?.installedAt ?: (dir.lastModified()),
            source = meta?.source.orEmpty(),
            apiVersion = manifest.apiVersion,
            main = manifest.main,
            requirements = manifest.requirements,
            subpackages = subpackages,
            resources = manifest.resources,
            wasmModules = manifest.wasmModules,
            logoPath = logo,
            directory = dir.name,
        )
    }.getOrNull()

    private fun findManifest(base: File): File? = base.walkTopDown()
        .maxDepth(2)
        .filter { it.isFile && (it.name == "manifest.json" || it.name == "manifest.hjson") }
        .minByOrNull { it.absolutePath.length }

    private fun extractZip(zip: File, dest: File) {
        var total = 0L
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                entries++
                if (entries > MAX_ENTRIES) error("压缩包文件数量过多")
                val parts = entry.name.replace('\\', '/')
                    .split('/')
                    .filter { it.isNotEmpty() && it != "." && it != ".." }
                if (parts.isEmpty() || parts.any { it.contains(':') }) continue
                val target = File(dest, parts.joinToString("/"))
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_EXTRACTED_BYTES) error("插件解压后超过 200 MB")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun copyTree(source: File, target: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).path
            if (relative.isEmpty()) return@forEach
            val destination = resolve(target, relative) ?: return@forEach
            if (file.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resolve(base: File, relative: String): File? {
        val normalized = relative.replace('\\', '/').trimStart('/')
        if (normalized.split('/').any { it == ".." }) return null
        val baseCanonical = base.canonicalFile
        val file = File(baseCanonical, normalized).canonicalFile
        return if (file.path == baseCanonical.path || file.path.startsWith(baseCanonical.path + File.separator)) {
            file
        } else {
            null
        }
    }

    private fun readMeta(dir: File): PluginMeta? {
        val file = File(dir, META)
        if (!file.isFile || file.length() > MAX_META_BYTES) return null
        return runCatching {
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            PluginMeta(
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull,
                installedAt = obj["installedAt"]?.jsonPrimitive?.longOrNull ?: dir.lastModified(),
                source = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.getOrNull()
    }

    private fun writeMeta(dir: File, meta: PluginMeta) {
        File(dir, META).writeText(
            buildJsonObject {
                put("enabled", meta.enabled ?: true)
                put("installedAt", meta.installedAt)
                put("source", meta.source)
            }.toString(),
        )
    }

    private data class PluginMeta(
        val enabled: Boolean?,
        val installedAt: Long,
        val source: String,
    )

    private companion object {
        const val PLUGINS_DIR = "plugins"
        const val META = "iris-plugin.json"
        const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 200L * 1024 * 1024
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        const val MAX_META_BYTES = 64L * 1024
        const val MAX_ENTRIES = 2000
    }
}
