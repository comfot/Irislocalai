package com.iris.moew.character

import kotlin.math.abs
import kotlin.random.Random

/**
 * 角色卡宏：{{char}}/{{user}}/{{original}}/{{newline}}/{{trim}}/{{//注释}}、
 * {{random:}}/{{pick:}}/{{roll:N}} 与旧式 <USER>/<BOT>。
 * random/pick 用 seed 保证同一会话内 {{pick}} 稳定，random 只排除重复相邻项。
 */
object CardMacros {

    fun apply(
        text: String,
        charName: String,
        userName: String,
        originalSystemPrompt: String = "",
        seed: Int = Random.nextInt(),
    ): String {
        if (text.isBlank()) return text
        var out = text
        out = out.replace(Regex("\\{\\{//[^}]*}}\\s*"), "")
        out = out.replace("{{original}}", originalSystemPrompt)
        out = out.replace("{{char}}", charName)
        out = out.replace("{{user}}", userName)
        out = Regex("<\\s*BOT\\s*>", RegexOption.IGNORE_CASE).replace(out, charName)
        out = Regex("<\\s*USER\\s*>", RegexOption.IGNORE_CASE).replace(out, userName)
        out = out.replace("{{newline}}", "\n")
        out = Regex("\\{\\{trim}}[ \\t]*\\r?\\n?").replace(out, "")
        out = Regex("\\{\\{random:([^}]*)}}").replace(out) { match ->
            pick(match.groupValues[1], seed + match.range.first, fallback = match.value)
        }
        out = Regex("\\{\\{pick:([^}]*)}}").replace(out) { match ->
            pick(match.groupValues[1], seed, fallback = match.value)
        }
        out = Regex("\\{\\{roll:\\s*(\\d+)\\s*}}").replace(out) { match ->
            val sides = (match.groupValues[1].toIntOrNull() ?: 6).coerceAtLeast(1)
            (1 + abs(Random(seed + match.range.first).nextInt()).rem(sides)).toString()
        }
        return out
    }

    private fun pick(optionsLiteral: String, seed: Int, fallback: String): String {
        val options = optionsLiteral.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (options.isEmpty()) return fallback
        return options[abs(seed).rem(options.size)]
    }
}
