package com.iris.localai.skill

import android.content.Context
import com.iris.localai.data.ProfileCatalog
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
        if (root.listFiles { file -> file.isDirectory }?.isEmpty() != false) seed()
        refresh()
    }

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

    fun delete(id: String) {
        if (id.contains('/') || id.contains("..")) return
        File(root, id).deleteRecursively()
        refresh()
    }

    suspend fun importBytes(bytes: ByteArray, fileName: String): Result<Skill> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = if (isZip(bytes)) {
                    firstMarkdownInZip(bytes) ?: error("ZIP 中没有 .md 技能文件")
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                val baseName = fileName.substringBeforeLast('.').ifBlank { "导入技能" }
                val skill = SkillParser.parse(text, baseName)
                save(skill.copy(id = uniqueId(skill.name), source = fileName), new = true)
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

    fun activeFor(query: String): List<Skill> {
        val config = _settings.value
        if (!config.injectToChat && !config.injectToAgent) return emptyList()
        return _skills.value
            .filter { it.enabled }
            .filter { skill ->
                if (!config.autoTrigger) return@filter true
                skill.alwaysOn || skill.triggers.any { trigger ->
                    trigger.isNotBlank() && query.contains(trigger, ignoreCase = true)
                }
            }
    }

    fun promptBlock(query: String): String {
        val config = _settings.value
        if (!config.injectToChat && !config.injectToAgent) return ""
        var budget = config.maxChars
        return activeFor(query)
            .sortedByDescending { it.alwaysOn }
            .takeWhile { skill ->
                if (budget <= 0) return@takeWhile false
                budget -= skill.instructions.length + 40
                true
            }
            .joinToString("\n\n") { skill ->
                "【技能：${skill.name}】\n${skill.instructions.take(2000)}"
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
        val triggers = meta?.get("triggers")?.let { element ->
            (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
        return parsed.copy(
            id = dir.name,
            enabled = meta?.boolean("enabled") ?: true,
            alwaysOn = meta?.boolean("alwaysOn") ?: parsed.alwaysOn,
            triggers = triggers ?: parsed.triggers,
            version = meta?.string("version") ?: parsed.version,
            suite = meta?.string("suite") ?: parsed.suite,
            source = meta?.string("source") ?: "",
            updatedAt = meta?.long("updatedAt") ?: 0L,
        )
    }

    private fun seed() {
        ProfileCatalog.skills.forEach { profile ->
            val skill = Skill(
                id = profile.id,
                name = profile.label,
                description = profile.description,
                instructions = "## ${profile.label}\n\n${profile.description}。\n\n" +
                    "在任务需要该能力时使用它，先确认输入与目标，再执行并汇报结果。",
                triggers = listOf(profile.label),
                enabled = true,
            )
            save(skill)
        }
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

    private fun firstMarkdownInZip(bytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            var total = 0L
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".md", ignoreCase = true)) continue
                val content = input.readBytes()
                total += content.size
                if (total > 2_000_000) throw IOException("技能包过大")
                return String(content, Charsets.UTF_8)
            }
        }
        return null
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
        const val KEY_AUTO = "auto_trigger"
        const val KEY_MAX = "max_chars"
        const val KEY_CHAT = "inject_chat"
        const val KEY_AGENT = "inject_agent"
    }
}
