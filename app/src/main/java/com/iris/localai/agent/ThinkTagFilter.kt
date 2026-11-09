package com.iris.localai.agent

data class ThinkFeed(
    val text: String,
    val reasoning: String,
    val sawThink: Boolean,
)

/**
 * 流式剥离 `<think>...</think>` 等思考块。模型把思考写进 content 时，
 * 思考内容转入 reasoning 事件，正文只保留真正回答；未闭合的思考块在结束时不展示。
 */
class ThinkTagFilter {

    private val pending = StringBuilder()
    private var inThink = false
    private var sawThink = false

    fun feed(chunk: String): ThinkFeed {
        if (chunk.isEmpty() && pending.isEmpty()) return ThinkFeed("", "", sawThink)
        pending.append(chunk)
        val text = StringBuilder()
        val reasoning = StringBuilder()
        while (pending.isNotEmpty()) {
            if (!inThink) {
                val opening = findOpeningTag(pending)
                if (opening == null) {
                    val hold = partialTagSuffix(pending, OPEN_TAGS)
                    if (hold > 0) {
                        text.append(pending, 0, pending.length - hold)
                        pending.delete(0, pending.length - hold)
                    } else {
                        text.append(pending)
                        pending.clear()
                    }
                    break
                }
                text.append(pending, 0, opening.first)
                pending.delete(0, opening.first + opening.second)
                inThink = true
                sawThink = true
            } else {
                val closing = findClosingTag(pending)
                if (closing == null) {
                    val hold = partialTagSuffix(pending, CLOSE_TAGS)
                    if (hold > 0) {
                        reasoning.append(pending, 0, pending.length - hold)
                        pending.delete(0, pending.length - hold)
                    } else {
                        reasoning.append(pending)
                        pending.clear()
                    }
                    break
                }
                reasoning.append(pending, 0, closing.first)
                pending.delete(0, closing.first + closing.second)
                inThink = false
            }
        }
        return ThinkFeed(text.toString(), reasoning.toString(), sawThink)
    }

    fun flush(): ThinkFeed {
        if (pending.isEmpty()) return ThinkFeed("", "", sawThink)
        val body = pending.toString()
        pending.clear()
        return if (inThink) {
            inThink = false
            ThinkFeed("", body, sawThink)
        } else {
            ThinkFeed(body, "", sawThink)
        }
    }

    fun hasThought(): Boolean = sawThink

    private fun findOpeningTag(buffer: CharSequence): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (tag in OPEN_TAGS) {
            val index = indexOfIgnoreCase(buffer, tag)
            if (index >= 0 && (best == null || index < best.first)) best = index to tag.length
        }
        return best
    }

    private fun findClosingTag(buffer: CharSequence): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (tag in CLOSE_TAGS) {
            val index = indexOfIgnoreCase(buffer, tag)
            if (index >= 0 && (best == null || index < best.first)) best = index to tag.length
        }
        return best
    }

    private fun partialTagSuffix(buffer: CharSequence, tags: List<String>): Int {
        for (length in minOf(buffer.length, MAX_TAG_LENGTH) downTo 1) {
            val suffix = buffer.substring(buffer.length - length)
            if (tags.any { it.startsWith(suffix, ignoreCase = true) }) return length
        }
        return 0
    }

    private fun indexOfIgnoreCase(text: CharSequence, needle: String): Int {
        if (needle.length > text.length) return -1
        outer@ for (index in 0..text.length - needle.length) {
            for (offset in needle.indices) {
                if (text[index + offset].lowercaseChar() != needle[offset].lowercaseChar()) continue@outer
            }
            return index
        }
        return -1
    }

    private companion object {
        val OPEN_TAGS = listOf("<think>", "<thinking>", "<|think|>", "<|begin_of_thought|>")
        val CLOSE_TAGS = listOf("</think>", "</thinking>", "<|end_of_thought|>", "<|/think|>")
        val MAX_TAG_LENGTH = maxOf(OPEN_TAGS.maxOf { it.length }, CLOSE_TAGS.maxOf { it.length })
    }
}
