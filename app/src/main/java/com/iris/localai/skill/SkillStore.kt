package com.iris.localai.skill

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

/** SKILL.md 技能库：导入、创建、删除、URL 下载、启用与提示词注入。 */
class SkillStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "skills").apply { mkdirs() }

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val _settings = MutableStateFlow(
        SkillSettings(
            autoTrigger = prefs.getBoolean(KEY_AUTO, true),
            maxChars = prefs.getInt(KEY_MAX, 6000),
            injectToChat = prefs.getBoolean(KEY_CHAT, true),
            injectToAgent = prefs.getBoolean(KEY_AGENT, true),
        ),
    )
    val settings: StateFlow<SkillSettings> = _settings.asStateFlow()

    init {
        refresh()
        ensureBundledSkills()
    }

    /**
     * 内置技能来自 assets/builtin_skills。升级时保留 enabled/alwaysOn；
     * 用户改过（hash 不符）或删除过就不覆盖。
     */
    private fun ensureBundledSkills() {
        val ids = runCatching { appContext.assets.list(BUILTIN_SKILL_DIR) }.getOrNull().orEmpty()
            .filter { it.isNotBlank() && !it.contains('.') }
        if (ids.isEmpty()) return
        ids.forEach { id -> installBundledSkill(id) }
        refresh()
    }

    private fun installBundledSkill(id: String) {
        if (prefs.getBoolean(deletedKey(id), false)) return
        val files = readAssetSkill(id) ?: return
        val markdown = files["SKILL.md"]?.let { String(it, Charsets.UTF_8) } ?: return
        val parsed = runCatching { SkillParser.parse(markdown, id) }.getOrNull() ?: return
        val assetHash = hashEntries(files)
        val installed = _skills.value.firstOrNull { it.id == id }
        val storedHash = prefs.getString(hashKey(id), null)
        val storedVersion = prefs.getString(versionKey(id), null)
        val currentHash = hashSkillDir(File(root, id))
        if (installed != null) {
            val userModified = storedHash != null && storedHash != currentHash
            if (userModified) return
            if (storedVersion == parsed.version && storedHash == currentHash) return
        }
        val target = File(root, id)
        if (target.exists() && !target.deleteRecursively()) return
        files.forEach { (path, bytes) ->
            runCatching { writeResource(target, path, bytes) }
        }
        val saved = save(
            parsed.copy(
                id = id,
                source = "builtin",
                enabled = installed?.enabled ?: parsed.enabled,
                alwaysOn = installed?.alwaysOn ?: parsed.alwaysOn,
                triggers = if (installed != null && installed.triggers.isNotEmpty()) installed.triggers else parsed.triggers,
            ),
        )
        prefs.edit()
            .putString(versionKey(id), saved.version)
            .putString(hashKey(id), hashSkillDir(target))
            .apply()
    }

    private fun readAssetSkill(id: String): Map<String, ByteArray>? {
        val rootPath = "$BUILTIN_SKILL_DIR/$id"
        val entries = linkedMapOf<String, ByteArray>()
        fun walk(path: String, prefix: String) {
            val children = runCatching { appContext.assets.list(path) }.getOrNull().orEmpty()
            if (children.isEmpty()) {
                val bytes = runCatching { appContext.assets.open(path).use { it.readBytes() } }.getOrNull() ?: return
                entries[prefix] = bytes
                return
            }
            children.forEach { child -> walk("$path/$child", if (prefix.isBlank()) child else "$prefix/$child") }
        }
        runCatching { walk(rootPath, "") }
        return entries.takeIf { it.isNotEmpty() }
    }

    private fun hashEntries(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray())
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashSkillDir(dir: File): String {
        if (!dir.isDirectory) return ""
        val files = dir.walkTopDown()
            .filter { it.isFile && it.name != META && !it.name.startsWith(".") }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to runCatching { it.readBytes() }.getOrDefault(ByteArray(0)) }
        return hashEntries(files)
    }

    private fun deletedKey(id: String) = "deleted_$id"
    private fun hashKey(id: String) = "bundle_hash_$id"
    private fun versionKey(id: String) = "bundle_version_$id"

    fun updateSettings(transform: (SkillSettings) -> SkillSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putBoolean(KEY_AUTO, next.autoTrigger)
            .putInt(KEY_MAX, next.maxChars)
            .putBoolean(KEY_CHAT, next.injectToChat)
            .putBoolean(KEY_AGENT, next.injectToAgent)
            .apply()
    }

    fun create(name: String = "新技能"): Skill {
        val skill = Skill(name = name)
        save(skill)
        return skill
    }

    fun save(skill: Skill, new: Boolean = false): Skill {
        val id = if (new) uniqueId(skill.name) else skill.id
        val saved = skill.copy(id = id, updatedAt = System.currentTimeMillis())
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "SKILL.md").writeText(SkillParser.toMarkdown(saved))
        File(dir, META).writeText(
            buildJsonObject {
                put("enabled", saved.enabled)
                put("alwaysOn", saved.alwaysOn)
                putJsonArray("triggers") { saved.triggers.forEach { add(JsonPrimitive(it)) } }
                put("version", saved.version)
                put("suite", saved.suite)
                put("source", saved.source)
                put("updatedAt", saved.updatedAt)
            }.toString(),
        )
        refresh()
        return saved
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _skills.value.firstOrNull { it.id == id }?.let { save(it.copy(enabled = enabled)) }
    }

    /** 恢复内置技能为 assets 版本；会覆盖用户对内建技能的修改。 */
    fun rebuildBundledSkills() {
        val ids = runCatching { appContext.assets.list(BUILTIN_SKILL_DIR) }.getOrNull().orEmpty()
            .filter { it.isNotBlank() && !it.contains('.') }
        prefs.edit().apply {
            ids.forEach { id ->
                remove(hashKey(id))
                remove(versionKey(id))
            }
        }.apply()
        ensureBundledSkills()
    }

    fun delete(id: String) {
        if (id.contains('/') || id.contains("..")) return
        prefs.edit().putBoolean(deletedKey(id), true).apply()
        File(root, id).deleteRecursively()
        refresh()
    }

    suspend fun importBytes(bytes: ByteArray, fileName: String): Result<Skill> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseName = fileName.substringBeforeLast('.').ifBlank { "导入技能" }
                if (isZip(bytes)) {
                    val packageFiles = readSkillZip(bytes)
                    val skill = SkillParser.parse(packageFiles.markdown, baseName)
                    val saved = save(skill.copy(id = uniqueId(skill.name), source = fileName), new = true)
                    val dir = File(root, saved.id)
                    packageFiles.resources.forEach { (path, content) ->
                        writeResource(dir, path, content)
                    }
                    refresh()
                    _skills.value.firstOrNull { it.id == saved.id }
                        ?: error("技能资源写入失败")
                } else {
                    val text = String(bytes, Charsets.UTF_8)
                    val skill = SkillParser.parse(text, baseName)
                    save(skill.copy(id = uniqueId(skill.name), source = fileName), new = true)
                }
            }
        }

    suspend fun download(url: String): Result<Skill> = withContext(Dispatchers.IO) {
        runCatching {
            if (url.isBlank()) error("URL 为空")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IRIS-LocalAI/0.1")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("HTTP $code")
                val text = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(64 * 1024)
                    val out = StringBuilder()
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        out.append(buffer, 0, count)
                        if (out.length > 1_000_000) error("技能文件超过 1 MB")
                    }
                    out.toString()
                }
                val name = url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "下载技能" }
                val skill = SkillParser.parse(text, name)
                save(skill.copy(id = uniqueId(skill.name), source = url), new = true)
            } finally {
                connection.disconnect()
            }
        }
    }

    fun activeFor(query: String, forAgent: Boolean): List<Skill> {
        val config = _settings.value
        if (forAgent && !config.injectToAgent) return emptyList()
        if (!forAgent && !config.injectToChat) return emptyList()
        return _skills.value
            .filter { it.enabled }
            .filter { skill ->
                if (!config.autoTrigger) return@filter true
                skill.alwaysOn || skill.triggers.any { trigger ->
                    trigger.isNotBlank() && query.contains(trigger, ignoreCase = true)
                }
            }
    }

    fun promptBlock(query: String, forAgent: Boolean): String {
        val config = _settings.value
        if (forAgent && !config.injectToAgent) return ""
        if (!forAgent && !config.injectToChat) return ""
        var budget = config.maxChars
        return activeFor(query, forAgent)
            .sortedByDescending { it.alwaysOn }
            .takeWhile { skill ->
                if (budget <= 0) return@takeWhile false
                budget -= skill.instructions.length + 40
                true
            }
            .joinToString("\n\n") { skill ->
                val refs = if (skill.resources.isEmpty()) {
                    ""
                } else {
                    "\n参考文件（需要时用 use_skill 读取，file 传相对路径）：" +
                        skill.resources.take(12).joinToString("、")
                }
                "【技能：${skill.name}】\n${skill.instructions.take(2000)}$refs"
            }
    }

    private fun refresh() {
        val loaded = root.listFiles { file -> file.isDirectory && File(file, "SKILL.md").isFile }
            .orEmpty()
            .mapNotNull { dir -> load(dir) }
            .sortedBy { it.name }
        _skills.value = loaded
    }

    private fun load(dir: File): Skill? {
        val markdown = runCatching { File(dir, "SKILL.md").readText() }.getOrNull() ?: return null
        val parsed = runCatching { SkillParser.parse(markdown, dir.name) }.getOrNull() ?: return null
        val meta = runCatching {
            Json.parseToJsonElement(File(dir, META).readText()).jsonObject
        }.getOrNull()
        val triggers = meta?.let { element ->
            (element["triggers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
        val resources = dir.walkTopDown()
            .filter { file ->
                file.isFile && file.name != "SKILL.md" && file.name != META &&
                    !file.name.startsWith(".") && !file.path.contains("__MACOSX")
            }
            .map { file -> file.relativeTo(dir).invariantSeparatorsPath }
            .sorted()
            .toList()
        return parsed.copy(
            id = dir.name,
            enabled = meta?.boolean("enabled") ?: true,
            alwaysOn = meta?.boolean("alwaysOn") ?: parsed.alwaysOn,
            triggers = triggers ?: parsed.triggers,
            version = meta?.string("version") ?: parsed.version,
            suite = meta?.string("suite") ?: parsed.suite,
            source = meta?.string("source") ?: "",
            updatedAt = meta?.long("updatedAt") ?: 0L,
            resources = resources,
        )
    }

    private fun uniqueId(name: String): String {
        val base = SkillParser.slug(name)
        var index = 0
        while (true) {
            val id = if (index == 0) base else "$base-$index"
            if (!File(root, id).exists()) return id
            index++
        }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private data class ZipSkill(
        val markdown: String,
        val resources: List<Pair<String, ByteArray>>,
    )

    private fun readSkillZip(bytes: ByteArray): ZipSkill {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.contains("__MACOSX/") || name.endsWith(".DS_Store")) continue
                if (name.split('/').any { it.isBlank() || it == "." || it == ".." }) continue
                val content = input.readBytes()
                total += content.size
                if (total > MAX_SKILL_ZIP_BYTES) throw IOException("技能包过大")
                entries[name] = content
            }
        }
        val skillEntry = entries.entries.firstOrNull {
            it.key.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)
        } ?: error("ZIP 中没有 SKILL.md")
        val prefix = skillEntry.key.substringBeforeLast('/', "")
        val resources = entries.entries
            .filter { (name, _) ->
                name != skillEntry.key &&
                    (prefix.isEmpty() || name.startsWith("$prefix/"))
            }
            .map { (name, data) ->
                val relative = if (prefix.isEmpty()) name else name.removePrefix("$prefix/")
                relative to data
            }
            .filter { it.first.isNotBlank() }
        return ZipSkill(String(skillEntry.value, Charsets.UTF_8), resources)
    }

    private fun writeResource(dir: File, relative: String, bytes: ByteArray) {
        val root = dir.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "技能资源路径非法：$relative" }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    fun readResource(skillId: String, relative: String): Result<String> = runCatching {
        val skill = _skills.value.firstOrNull { it.id == skillId } ?: error("技能不存在：$skillId")
        val path = relative.replace('\\', '/').trimStart('/')
        require(path.isNotBlank() && !path.split('/').any { it == ".." }) { "技能资源路径非法" }
        require(skill.resources.any { it == path }) { "技能没有该参考文件：$path" }
        val skillDir = File(root, skillId).canonicalFile
        val file = File(skillDir, path).canonicalFile
        require(file.isFile && file.path.startsWith(skillDir.path + File.separator)) { "技能资源不存在：$path" }
        require(file.length() <= MAX_RESOURCE_BYTES) { "参考文件超过 200 KB" }
        file.readText()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val PREFS = "iris_skills"
        const val META = "meta.json"
        const val BUILTIN_SKILL_DIR = "builtin_skills"
        const val KEY_AUTO = "auto_trigger"
        const val KEY_MAX = "max_chars"
        const val KEY_CHAT = "inject_chat"
        const val KEY_AGENT = "inject_agent"
        const val MAX_SKILL_ZIP_BYTES = 32L * 1024 * 1024
        const val MAX_RESOURCE_BYTES = 200L * 1024
    }
}
