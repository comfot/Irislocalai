package com.iris.localai.skill

import java.util.UUID

data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val triggers: List<String> = emptyList(),
    val enabled: Boolean = true,
    val alwaysOn: Boolean = false,
    val version: String = "1.0",
    val suite: String = "",
    val source: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SkillSettings(
    val autoTrigger: Boolean = true,
    val maxChars: Int = 6000,
    val injectToChat: Boolean = true,
    val injectToAgent: Boolean = true,
)

/** SKILL.md：YAML frontmatter（name/description/triggers/alwaysOn/version/suite）+ 指令正文。 */
object SkillParser {

    fun parse(text: String, fallbackName: String = "新技能"): Skill {
        val normalized = text.trimStart('\uFEFF')
        var name = fallbackName
        var description = ""
        var triggers = emptyList<String>()
        var alwaysOn = false
        var version = "1.0"
        var suite = ""
        val body: String

        if (normalized.startsWith("---")) {
            val end = normalized.indexOf("\n---", 3)
            if (end > 0) {
                val front = normalized.substring(3, end)
                body = normalized.substring(end + 4).trimStart('\n')
                front.lineSequence().forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEach
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim().trim('"', '\'')
                    when (key) {
                        "name" -> if (value.isNotBlank()) name = value
                        "description" -> description = value
                        "triggers", "trigger", "keywords" ->
                            triggers = value.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                        "alwayson", "always_on" -> alwaysOn = value.equals("true", true)
                        "version" -> version = value
                        "suite" -> suite = value
                    }
                }
            } else {
                body = normalized
            }
        } else {
            body = normalized
        }

        if (description.isBlank()) {
            description = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                .orEmpty()
                .take(120)
        }
        return Skill(
            id = slug(name),
            name = name.trim().ifBlank { fallbackName },
            description = description,
            instructions = body.trim(),
            triggers = triggers,
            alwaysOn = alwaysOn,
            version = version,
            suite = suite,
        )
    }

    fun toMarkdown(skill: Skill): String = buildString {
        appendLine("---")
        appendLine("name: ${skill.name}")
        if (skill.description.isNotBlank()) appendLine("description: ${skill.description}")
        if (skill.triggers.isNotEmpty()) appendLine("triggers: ${skill.triggers.joinToString(",")}")
        if (skill.alwaysOn) appendLine("alwaysOn: true")
        appendLine("version: ${skill.version}")
        if (skill.suite.isNotBlank()) appendLine("suite: ${skill.suite}")
        appendLine("---")
        appendLine()
        appendLine(skill.instructions.trim())
    }

    fun slug(name: String): String = name.trim()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(40)
        .ifBlank { "skill-${UUID.randomUUID().toString().take(8)}" }
}
