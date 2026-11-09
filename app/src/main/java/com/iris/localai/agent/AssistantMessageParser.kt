package com.iris.localai.agent

data class StatusItem(val label: String, val value: String)

data class StatusSection(val title: String, val items: List<StatusItem>)

data class StatusCardData(
    val date: String? = null,
    val time: String? = null,
    val location: String? = null,
    val sections: List<StatusSection> = emptyList(),
)

data class AssistantDisplay(
    val mainText: String,
    val status: StatusCardData?,
)

/**
 * 解析模型常用的展示格式：
 * - `<maintext>正文</maintext>`：只保留正文；
 * - `<Status_block>`：解析成日期/时间/地点和“键：值”状态项。
 * 同时兼容流式输出时标签尚未闭合的半成品。
 */
object AssistantMessageParser {

    private val closedStatus = Regex("(?is)<status_block[^>]*>(.*?)</status_block\\s*>")
    private val openStatus = Regex("(?is)<status_block[^>]*>")
    private val mainOpen = Regex("(?is)<maintext[^>]*>")
    private val mainClose = Regex("(?is)</maintext\\s*>")
    private val htmlTag = Regex("(?is)<[^>]+>")
    private val detailsTag = Regex("(?is)</?details[^>]*>")
    private val danglingTag = Regex("(?is)<[^>]*$")

    fun parse(raw: String): AssistantDisplay {
        if (raw.isBlank()) return AssistantDisplay("", null)

        val closed = closedStatus.findAll(raw).toList()
        val statusRaw: String?
        val mainRaw: String
        if (closed.isNotEmpty()) {
            statusRaw = closed.first().groupValues.getOrNull(1).orEmpty()
            mainRaw = buildString {
                var cursor = 0
                for (match in closed) {
                    append(raw, cursor, match.range.first)
                    cursor = match.range.last + 1
                }
                append(raw, cursor, raw.length)
            }
        } else {
            val open = openStatus.find(raw)
            if (open != null) {
                statusRaw = raw.substring(open.range.last + 1)
                mainRaw = raw.substring(0, open.range.first)
            } else {
                statusRaw = null
                mainRaw = raw
            }
        }

        val main = mainRaw
            .replace(mainOpen, "")
            .replace(mainClose, "")
            .replace(detailsTag, "")
            .replace(danglingTag, "")
            .trim()
        return AssistantDisplay(main, statusRaw?.takeIf { it.isNotBlank() }?.let(::parseStatus))
    }

    fun mainText(raw: String): String = parse(raw).mainText

    private fun parseStatus(raw: String): StatusCardData {
        val clean = raw
            .replace(detailsTag, "")
            .replace(htmlTag, " ")
            .replace(Regex("(?is)</?summary[^>]*>"), "")
        val lines = clean.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val headerLine = lines.firstOrNull { it.contains("『") && it.contains("』") }
            ?: lines.firstOrNull { it.contains("日期") && it.contains("时间") }
            ?: ""
        val headerText = if (headerLine.contains("『")) {
            headerLine.substringAfter("『").substringBefore("』").trim()
        } else {
            headerLine
        }
        val segments = headerText.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val date = segmentValue(segments, "日期")
        val time = segmentValue(segments, "时间")
        val location = segmentValue(segments, "位置")

        val sections = mutableListOf<StatusSection>()
        var currentTitle = "角色状态"
        var items = mutableListOf<StatusItem>()
        val bodyLines = buildList {
            if (headerLine.isNotBlank()) {
                val rest = headerLine.substringAfter("』", "").trim()
                if (rest.isNotBlank()) add(rest)
            }
            lines.filterNot { it == headerLine }.forEach { add(it) }
        }

        fun flush() {
            if (items.isNotEmpty()) {
                sections += StatusSection(currentTitle, items)
            }
            items = mutableListOf()
        }

        for (line in bodyLines) {
            if (line == headerLine) continue
            val text = line.trim()
            if (text.startsWith("[") && text.endsWith("]") && text.length <= 30) {
                flush()
                currentTitle = text.trim('[', ']').trim().ifBlank { currentTitle }
                continue
            }
            if (text.startsWith("-")) {
                val itemText = text.removePrefix("-").trim()
                val separator = itemText.indexOfFirst { it == '：' || it == ':' }
                if (separator > 0) {
                    val label = itemText.substring(0, separator).trim().trim('*')
                    val value = cleanValue(itemText.substring(separator + 1))
                    if (label.isNotBlank() && value.isNotBlank()) {
                        items += StatusItem(label, value)
                    }
                } else if (itemText.isNotBlank()) {
                    flush()
                    currentTitle = itemText.trim('*')
                }
            } else if (text.isNotBlank()) {
                if (items.isNotEmpty()) {
                    val last = items.removeAt(items.lastIndex)
                    items += last.copy(value = (last.value + " " + text).trim())
                } else if (currentTitle == "角色状态") {
                    currentTitle = text.trim('*')
                }
            }
        }
        flush()
        if (sections.isEmpty() && lines.isNotEmpty()) {
            sections += StatusSection("角色状态", listOf(StatusItem("内容", cleanValue(lines.joinToString("\n")))))
        }
        return StatusCardData(date, time, location, sections)
    }

    private fun segmentValue(segments: List<String>, label: String): String? {
        val segment = segments.firstOrNull { it.contains(label) } ?: return null
        val afterFullWidth = segment.substringAfter('：', segment)
        val value = if (afterFullWidth != segment) {
            afterFullWidth
        } else {
            segment.substringAfter(':', segment)
        }
        return cleanValue(value).ifBlank { null }
    }

    private fun cleanValue(value: String): String =
        value.replace("*", "").replace("_", "").trim().trim(',', '，', '。').trim()
}
