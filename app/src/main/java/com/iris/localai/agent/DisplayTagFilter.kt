package com.iris.localai.agent

/**
 * 流式清理模型偶尔输出的展示包裹：
 * - `<maintext>...</maintext>` 只去掉标签，保留正文；
 * - `<Status_block>...</Status_block>` 整块丢弃，避免状态卡进入聊天/朗读。
 */
class DisplayTagFilter {

    private val pending = StringBuilder()
    private var inStatusBlock = false

    fun feed(chunk: String): String {
        if (chunk.isEmpty() && pending.isEmpty()) return ""
        pending.append(chunk)
        val output = StringBuilder()
        while (pending.isNotEmpty()) {
            if (inStatusBlock) {
                val start = indexOfIgnoreCase(pending, STATUS_CLOSE)
                if (start < 0) {
                    val hold = partialSuffix(pending, listOf(STATUS_CLOSE to TagType.STATUS_CLOSE))
                    if (hold > 0) {
                        pending.delete(0, pending.length - hold)
                    } else {
                        pending.clear()
                    }
                    break
                }
                val end = pending.indexOf('>', start)
                if (end < 0) {
                    if (start > 0) pending.delete(0, start)
                    break
                }
                pending.delete(0, end + 1)
                inStatusBlock = false
                continue
            }

            val hit = findTag(pending)
            if (hit == null) {
                val hold = partialSuffix(pending, TAG_PREFIXES)
                if (hold > 0) {
                    output.append(pending, 0, pending.length - hold)
                    pending.delete(0, pending.length - hold)
                } else {
                    output.append(pending)
                    pending.clear()
                }
                break
            }
            if (hit.index > 0) {
                output.append(pending, 0, hit.index)
                pending.delete(0, hit.index)
                continue
            }
            if (hit.end < 0) break
            pending.delete(0, hit.end)
            if (hit.type == TagType.STATUS_OPEN) inStatusBlock = true
        }
        return output.toString()
    }

    fun flush(): String {
        if (inStatusBlock) {
            pending.clear()
            inStatusBlock = false
            return ""
        }
        if (pending.isEmpty()) return ""
        val hit = findTag(pending)
        val output = if (hit != null) {
            pending.substring(0, hit.index)
        } else {
            val hold = partialSuffix(pending, TAG_PREFIXES)
            pending.substring(0, pending.length - hold)
        }
        pending.clear()
        return output
    }

    private fun findTag(buffer: CharSequence): TagHit? {
        var best: TagHit? = null
        for ((prefix, type) in TAG_PREFIXES) {
            val index = indexOfIgnoreCase(buffer, prefix)
            if (index < 0) continue
            val close = buffer.indexOf('>', index)
            val hit = TagHit(index, if (close >= 0) close + 1 else -1, type)
            if (best == null || hit.index < best.index) best = hit
        }
        return best
    }

    private fun partialSuffix(buffer: CharSequence, prefixes: List<Pair<String, TagType>>): Int {
        for (length in minOf(buffer.length, MAX_PREFIX_LENGTH) downTo 1) {
            val suffix = buffer.substring(buffer.length - length)
            if (prefixes.any { it.first.startsWith(suffix, ignoreCase = true) }) return length
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

    private data class TagHit(val index: Int, val end: Int, val type: TagType)

    private enum class TagType { MAINTEXT_OPEN, MAINTEXT_CLOSE, STATUS_OPEN, STATUS_CLOSE }

    companion object {
        private const val STATUS_CLOSE = "</status_block"
        private val TAG_PREFIXES = listOf(
            "<maintext" to TagType.MAINTEXT_OPEN,
            "</maintext" to TagType.MAINTEXT_CLOSE,
            "<status_block" to TagType.STATUS_OPEN,
            STATUS_CLOSE to TagType.STATUS_CLOSE,
        )
        private val MAX_PREFIX_LENGTH = TAG_PREFIXES.maxOf { it.first.length }

        fun strip(text: String): String {
            val filter = DisplayTagFilter()
            return filter.feed(text) + filter.flush()
        }
    }
}
