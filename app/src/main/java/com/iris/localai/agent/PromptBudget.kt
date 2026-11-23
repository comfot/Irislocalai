package com.iris.localai.agent

import com.iris.localai.engine.PromptMessage

/**
 * 粗估一段文本占用的 token 数：CJK 字符约一字一 token，其余按 4 字符一 token。
 * 只用于裁剪 history 时留出余量，不要求与具体分词器一致。
 */
fun estimatePromptTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (char in text) {
        if (isCjk(char)) cjk++ else other++
    }
    return cjk + other / 4
}

fun PromptMessage.tokenCost(): Int = estimatePromptTokens(content) + 8

/**
 * 从最新消息往前保留到预算上限；最老一条如果落在回答上，补回它对应的用户消息，
 * 避免上下文从半轮对话开始。
 */
fun trimPromptHistory(history: List<PromptMessage>, budgetTokens: Int): List<PromptMessage> {
    if (history.isEmpty() || budgetTokens <= 0) return emptyList()
    val kept = ArrayDeque<PromptMessage>()
    var tokens = 0
    var index = history.lastIndex
    while (index >= 0) {
        val cost = history[index].tokenCost()
        if (kept.isNotEmpty() && tokens + cost > budgetTokens) break
        kept.addFirst(history[index])
        tokens += cost
        index--
    }
    if (kept.isNotEmpty() && kept.first().role == PromptMessage.ASSISTANT &&
        index >= 0 && history[index].role == PromptMessage.USER
    ) {
        kept.addFirst(history[index])
    }
    return kept.toList()
}

private fun isCjk(char: Char): Boolean {
    val code = char.code
    return code in 0x2E80..0x9FFF ||
        code in 0x3000..0x303F ||
        code in 0xAC00..0xD7AF ||
        code in 0xF900..0xFAFF ||
        code in 0xFF00..0xFFEF
}
