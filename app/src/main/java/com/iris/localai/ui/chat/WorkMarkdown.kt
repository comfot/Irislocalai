package com.iris.localai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 工作空间回答的块级结构；只覆盖模型实际会输出的常用 Markdown 语法。 */
internal sealed interface WorkBlock {
    data class Heading(val level: Int, val text: String) : WorkBlock
    data class Paragraph(val text: String) : WorkBlock
    data class Bullets(val items: List<String>) : WorkBlock
    data class Numbers(val start: Int, val items: List<String>) : WorkBlock
    data class Quote(val text: String) : WorkBlock
    data class Code(val language: String, val code: String) : WorkBlock

    /** LaTeX 公式：内容不再参与 Markdown 内联解析，按公式样式展示。 */
    data class Math(val text: String) : WorkBlock
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<WorkAlign>,
    ) : WorkBlock
    data object Divider : WorkBlock
}

internal enum class WorkAlign { START, CENTER, END }

/** 流式期间块级重解析的最小间隔；结束（streaming=false）后立即全量解析。 */
private const val STREAM_PARSE_DELAY_MS = 100L

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^[-*+]\\s+(.*)$")
private val numberRegex = Regex("^(\\d+)[.)]\\s+(.*)$")
private val taskRegex = Regex("^\\[( |x|X)]\\s*(.*)$")

internal fun parseWorkMarkdown(source: String): List<WorkBlock> {
    val blocks = mutableListOf<WorkBlock>()
    val paragraph = StringBuilder()
    val bullets = mutableListOf<String>()
    val numbers = mutableListOf<String>()
    val quote = StringBuilder()
    var numberStart = 1

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += WorkBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) blocks += WorkBlock.Bullets(bullets.toList())
        bullets.clear()
    }

    fun flushNumbers() {
        if (numbers.isNotEmpty()) blocks += WorkBlock.Numbers(numberStart, numbers.toList())
        numbers.clear()
    }

    fun flushQuote() {
        if (quote.isNotBlank()) blocks += WorkBlock.Quote(quote.toString().trim())
        quote.clear()
    }

    fun flushAll() {
        flushParagraph()
        flushBullets()
        flushNumbers()
        flushQuote()
    }

    val lines = source.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushAll()
            val fence = trimmed.take(3)
            val language = trimmed.removePrefix(fence).trim()
            val code = StringBuilder()
            i++
            while (i < lines.size) {
                if (lines[i].trimStart().startsWith(fence)) {
                    i++
                    break
                }
                code.appendLine(lines[i])
                i++
            }
            blocks += WorkBlock.Code(language, code.toString().trimEnd('\n'))
            continue
        }
        if (trimmed.startsWith("$$")) {
            flushAll()
            val rest = trimmed.substring(2)
            val close = rest.indexOf("$$")
            if (close >= 0) {
                val inner = rest.substring(0, close).trim()
                if (inner.isNotEmpty()) blocks += WorkBlock.Math(inner)
                val tail = rest.substring(close + 2).trim()
                if (tail.isNotEmpty()) blocks += WorkBlock.Paragraph(tail)
                i++
                continue
            }
            val content = StringBuilder()
            if (rest.isNotBlank()) content.appendLine(rest)
            i++
            while (i < lines.size) {
                val closeIdx = lines[i].indexOf("$$")
                if (closeIdx >= 0) {
                    if (closeIdx > 0) content.append(lines[i].substring(0, closeIdx))
                    i++
                    break
                }
                content.appendLine(lines[i])
                i++
            }
            if (content.isNotBlank()) blocks += WorkBlock.Math(content.toString().trim())
            continue
        }
        if (trimmed.startsWith("\\[")) {
            flushAll()
            val rest = trimmed.substring(2)
            val close = rest.indexOf("\\]")
            if (close >= 0) {
                val inner = rest.substring(0, close).trim()
                if (inner.isNotEmpty()) blocks += WorkBlock.Math(inner)
                val tail = rest.substring(close + 2).trim()
                if (tail.isNotEmpty()) blocks += WorkBlock.Paragraph(tail)
                i++
                continue
            }
            val content = StringBuilder()
            if (rest.isNotBlank()) content.appendLine(rest)
            i++
            while (i < lines.size) {
                val closeIdx = lines[i].indexOf("\\]")
                if (closeIdx >= 0) {
                    if (closeIdx > 0) content.append(lines[i].substring(0, closeIdx))
                    i++
                    break
                }
                content.appendLine(lines[i])
                i++
            }
            if (content.isNotBlank()) blocks += WorkBlock.Math(content.toString().trim())
            continue
        }
        if (trimmed.isBlank()) {
            flushAll()
            i++
            continue
        }
        val dividerBody = trimmed.filter { it != ' ' }
        if (dividerBody.length >= 3 && dividerBody.toSet().size == 1 && dividerBody[0] in "-*_") {
            flushAll()
            blocks += WorkBlock.Divider
            i++
            continue
        }
        parseWorkTable(lines, i)?.let { (table, next) ->
            flushAll()
            blocks += table
            i = next
            continue
        }
        val heading = headingRegex.find(trimmed)
        if (heading != null) {
            flushAll()
            blocks += WorkBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }
        val bullet = bulletRegex.find(trimmed)
        if (bullet != null) {
            flushParagraph()
            flushNumbers()
            flushQuote()
            bullets += bullet.groupValues[1].trim()
            i++
            continue
        }
        val number = numberRegex.find(trimmed)
        if (number != null) {
            flushParagraph()
            flushBullets()
            flushQuote()
            if (numbers.isEmpty()) numberStart = number.groupValues[1].toIntOrNull() ?: 1
            numbers += number.groupValues[2].trim()
            i++
            continue
        }
        if (trimmed.startsWith(">")) {
            flushParagraph()
            flushBullets()
            flushNumbers()
            quote.appendLine(trimmed.removePrefix(">").trimStart())
            i++
            continue
        }
        when {
            bullets.isNotEmpty() -> bullets[bullets.lastIndex] += " $trimmed"
            numbers.isNotEmpty() -> numbers[numbers.lastIndex] += " $trimmed"
            quote.isNotEmpty() -> quote.appendLine(trimmed)
            else -> paragraph.appendLine(line)
        }
        i++
    }
    flushAll()
    return blocks
}

private fun parseWorkTable(lines: List<String>, start: Int): Pair<WorkBlock.Table, Int>? {
    if (start + 1 >= lines.size) return null
    val headerLine = lines[start]
    if (!headerLine.contains('|')) return null
    val headers = splitTableRow(headerLine)
    if (headers.size < 2 || headers.all { it.isBlank() }) return null
    val separator = splitTableRow(lines[start + 1])
    if (separator.size != headers.size) return null
    if (!separator.all { TABLE_SEPARATOR.matches(it.replace(" ", "")) }) return null
    val alignments = separator.map { cell ->
        val trimmed = cell.trim()
        when {
            trimmed.startsWith(":") && trimmed.endsWith(":") -> WorkAlign.CENTER
            trimmed.endsWith(":") -> WorkAlign.END
            else -> WorkAlign.START
        }
    }
    val rows = mutableListOf<List<String>>()
    var index = start + 2
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank() || !line.contains('|')) break
        val cells = splitTableRow(line)
        rows += headers.indices.map { cellIndex -> cells.getOrElse(cellIndex) { "" } }
        index++
    }
    return WorkBlock.Table(headers, rows, alignments) to index
}

private val TABLE_SEPARATOR = Regex("^:?-+:?$")

/** 表格行拆分：支持 `\|` 转义，去掉首尾管道后按列切分。 */
private fun splitTableRow(line: String): List<String> {
    var value = line.trim()
    if (value.startsWith("|")) value = value.substring(1)
    if (value.endsWith("|") && !value.endsWith("\\|")) value = value.dropLast(1)
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '\\' && value.getOrNull(index + 1) == '|') {
            current.append('|')
            index += 2
            continue
        }
        if (char == '|') {
            cells += current.toString().trim()
            current.clear()
            index++
            continue
        }
        current.append(char)
        index++
    }
    cells += current.toString().trim()
    return cells
}

@Composable
internal fun WorkMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val source = if (streaming) "$text▍" else text
    // 流式期间每个 delta 都变化，限频重解析，避免长回答下每 token 全量 parse；
    // LaunchedEffect 会取消上一轮，实际按最后一次变化执行。
    var blocks by remember { mutableStateOf(parseWorkMarkdown(source)) }
    LaunchedEffect(source) {
        if (!streaming) {
            blocks = parseWorkMarkdown(source)
        } else {
            delay(STREAM_PARSE_DELAY_MS)
            blocks = parseWorkMarkdown(source)
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> WorkBlockView(block) }
    }
}

@Composable
private fun WorkBlockView(block: WorkBlock) {
    when (block) {
        is WorkBlock.Heading -> Text(
            text = workInline(block.text),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
        )

        is WorkBlock.Paragraph -> SelectionContainer {
            Text(
                text = workInline(block.text),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            )
        }

        is WorkBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            block.items.forEach { item -> ListRow(item, dot = "•") }
        }

        is WorkBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            block.items.forEachIndexed { index, item ->
                ListRow(item, dot = "${block.start + index}.")
            }
        }

        is WorkBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.width(10.dp))
            SelectionContainer {
                Text(
                    text = workInline(block.text, MaterialTheme.colorScheme.onSurfaceVariant),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is WorkBlock.Code -> WorkCodeBlock(block.language, block.code)

        is WorkBlock.Math -> WorkMathBlock(block.text)

        is WorkBlock.Table -> WorkTable(block)

        WorkBlock.Divider -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun WorkMathBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WorkTable(table: WorkBlock.Table) {
    val widths = table.headers.indices.map { index ->
        val longest = (listOf(table.headers[index]) + table.rows.map { it.getOrElse(index) { "" } })
            .maxOfOrNull { it.sumOf { char -> if (char.isCjk()) 2 else 1 } } ?: 8
        (longest.coerceIn(4, 24) * 9).coerceIn(64, 220).dp
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
                table.headers.forEachIndexed { index, header ->
                    TableCell(header, widths[index], table.alignments.getOrElse(index) { WorkAlign.START }, true)
                }
            }
            table.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Row {
                    row.forEachIndexed { index, cell ->
                        TableCell(cell, widths[index], table.alignments.getOrElse(index) { WorkAlign.START }, false)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, align: WorkAlign, header: Boolean) {
    Text(
        text = workInline(text),
        modifier = Modifier
            .width(width)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = when (align) {
            WorkAlign.START -> TextAlign.Start
            WorkAlign.CENTER -> TextAlign.Center
            WorkAlign.END -> TextAlign.End
        },
    )
}

@Composable
private fun ListRow(item: String, dot: String) {
    val task = taskRegex.find(item)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (task != null) {
            val checked = task.groupValues[1].equals("x", ignoreCase = true)
            Icon(
                imageVector = if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = workInline(task.groupValues[2]),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            )
        } else {
            Text(
                text = dot,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = workInline(item),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            )
        }
    }
}

@Composable
internal fun WorkCodeBlock(
    language: String,
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        copyToClipboard(context, code)
                        copied = true
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制代码",
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun workInline(text: String, baseColor: Color? = null): AnnotatedString {
    val colorScheme = MaterialTheme.colorScheme
    val base = SpanStyle(color = baseColor ?: colorScheme.onSurface)
    val code = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        background = colorScheme.surfaceVariant,
        color = colorScheme.primary,
    )
    val math = SpanStyle(
        fontFamily = FontFamily.Monospace,
        color = colorScheme.primary,
        fontStyle = FontStyle.Italic,
    )
    return remember(text, base, math) {
        buildAnnotatedString {
            appendInline(
                text = text,
                base = base,
                code = code,
                accent = colorScheme.primary,
                math = math,
            )
        }
    }
}

internal fun AnnotatedString.Builder.appendInline(
    text: String,
    base: SpanStyle,
    code: SpanStyle,
    accent: Color,
    math: SpanStyle,
    allowCode: Boolean = true,
) {
    var i = 0
    while (i < text.length) {
        // \(...\) 是 KaTeX 的行内公式定界符，必须在转义处理之前识别；
        // 找不到配对的 \\) 时回退到转义/普通文本。
        if (text.startsWith("\\(", i)) {
            val end = text.indexOf("\\)", i + 2)
            if (end > i + 2) {
                withStyle(base.merge(math)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
            append(text[i + 1])
            i += 2
            continue
        }
        if (allowCode && text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(base.merge(code)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        // $$...$$ 段中公式（行首的 $$ 块由块级解析处理）；先于单个 $ 判定。
        if (text.startsWith("$$", i)) {
            val end = findMathEndDouble(text, i + 2)
            if (end > i + 2) {
                withStyle(base.merge(math)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        // $...$ 行内公式：开头/结尾紧贴空白的不算（避免把 “总价 $5 与 $6” 当公式）。
        if (text[i] == '$' && (i + 1 >= text.length || !text[i + 1].isWhitespace())) {
            val end = findMathEnd(text, i + 1, '$')
            if (end > i + 1 && !text[end - 1].isWhitespace()) {
                withStyle(base.merge(math)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (text.startsWith("***", i)) {
            val end = text.indexOf("***", i + 3)
            if (end > i + 3 && canOpenEmphasis(text, i, 3) && !text[end - 1].isWhitespace()) {
                val style = base.copy(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
                withStyle(style) {
                    appendInline(text.substring(i + 3, end), style, code, accent, math, allowCode)
                }
                i = end + 3
                continue
            }
        }
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i + 2 && canOpenEmphasis(text, i, 2) && !text[end - 1].isWhitespace()) {
                val style = base.copy(fontWeight = FontWeight.SemiBold)
                withStyle(style) {
                    appendInline(text.substring(i + 2, end), style, code, accent, math, allowCode)
                }
                i = end + 2
                continue
            }
        }
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2 && canOpenEmphasis(text, i, 2) && !text[end - 1].isWhitespace()) {
                val style = base.copy(textDecoration = TextDecoration.LineThrough)
                withStyle(style) {
                    appendInline(text.substring(i + 2, end), style, code, accent, math, allowCode)
                }
                i = end + 2
                continue
            }
        }
        if (text[i] == '*' || text[i] == '_') {
            val end = text.indexOf(text[i], i + 1)
            if (end > i + 1 && canOpenEmphasis(text, i, 1) && !text[end - 1].isWhitespace()) {
                val style = base.copy(fontStyle = FontStyle.Italic)
                withStyle(style) {
                    appendInline(text.substring(i + 1, end), style, code, accent, math, allowCode)
                }
                i = end + 1
                continue
            }
        }
        if (text[i] == '!' && text.startsWith("![", i)) {
            val close = text.indexOf("](", i + 2)
            val parsed = if (close > i + 2) parseMarkdownLinkUrl(text, close + 2) else null
            if (parsed != null && isRenderableUrl(parsed.first)) {
                val label = text.substring(i + 2, close)
                withLink(LinkAnnotation.Url(parsed.first)) {
                    withStyle(linkStyle(base, accent)) {
                        append(if (label.isBlank()) "图片" else "图片：$label")
                    }
                }
                i = parsed.second + 1
                continue
            }
        }
        if (text[i] == '[') {
            val close = text.indexOf("](", i + 1)
            val parsed = if (close > i + 1) parseMarkdownLinkUrl(text, close + 2) else null
            if (parsed != null && isRenderableUrl(parsed.first)) {
                val label = text.substring(i + 1, close)
                withLink(LinkAnnotation.Url(parsed.first)) {
                    withStyle(linkStyle(base, accent)) { append(label) }
                }
                i = parsed.second + 1
                continue
            }
        }
        if (text[i] == '<') {
            val close = text.indexOf('>', i + 1)
            if (close > i + 1) {
                val inner = text.substring(i + 1, close)
                if (inner.startsWith("http://") || inner.startsWith("https://")) {
                    withLink(LinkAnnotation.Url(inner)) {
                        withStyle(linkStyle(base, accent)) { append(inner) }
                    }
                    i = close + 1
                    continue
                }
            }
        }
        if (isBareUrlStart(text, i)) {
            val end = bareUrlEnd(text, i)
            if (end > i) {
                val raw = text.substring(i, end)
                val url = if (raw.startsWith("www.")) "https://$raw" else raw
                withLink(LinkAnnotation.Url(url)) {
                    withStyle(linkStyle(base, accent)) { append(raw) }
                }
                i = end
                continue
            }
        }
        if (text[i] == '▍') {
            withStyle(base.copy(color = accent)) { append('▍') }
            i++
            continue
        }
        append(text[i])
        i++
    }
}

/** `_inline_` 在标识符里很常见，只在词边界上当成强调。 */
private fun canOpenEmphasis(text: String, index: Int, markerLength: Int): Boolean {
    val before = text.getOrNull(index - 1)
    val after = text.getOrNull(index + markerLength) ?: return false
    if (after.isWhitespace()) return false
    // 双字符强调可以在中文/标点后直接开始，例如“这是**重点**”；
    // 单字符强调仍要求词边界，避免把 snake_case 里的下划线当成斜体。
    if (markerLength >= 2) return true
    return before == null || before.isWhitespace() || before in "([{>\"'" || before.isCjk()
}

private fun Char.isCjk(): Boolean =
    this in '\u4e00'..'\u9fff' || this in '\u3040'..'\u30ff' || this in '\uac00'..'\ud7af'

/** 只放行 http/https，避免 intent:// 等任意 scheme 被 ACTION_VIEW 拉起。 */
private fun isRenderableUrl(url: String): Boolean =
    url.startsWith("http://") || url.startsWith("https://")

private fun linkStyle(base: SpanStyle, accent: Color): SpanStyle =
    base.copy(color = accent, textDecoration = TextDecoration.Underline)

/** 内联反斜杠转义可处理的字符；公式内容内部的转义对由 findMathEnd 原样保留。 */
private val ESCAPABLE = "*_[]()~`#+-.!|<>\"'$".toSet()

/** 从 start 起找行内公式的结束字符，跳过 \\X 转义对；未找到返回 -1。 */
private fun findMathEnd(text: String, start: Int, close: Char): Int {
    var i = start
    while (i < text.length) {
        if (text[i] == '\\') {
            i += 2
            continue
        }
        if (text[i] == close) return i
        i++
    }
    return -1
}

/** $$ 双定界符版本的结束位置查找。 */
private fun findMathEndDouble(text: String, start: Int): Int {
    var i = start
    while (i < text.length - 1) {
        if (text[i] == '\\') {
            i += 2
            continue
        }
        if (text[i] == '$' && text[i + 1] == '$') return i
        i++
    }
    return -1
}

/** 判断 i 是否为裸 URL 起点：前面须是行首、空白或开括号类字符。 */
private fun isBareUrlStart(text: String, i: Int): Boolean {
    val prev = text.getOrNull(i - 1)
    if (prev != null && !prev.isWhitespace() && prev !in ">(（“\"'") return false
    return text.startsWith("http://", i) || text.startsWith("https://", i) || text.startsWith("www.", i)
}

/** 裸 URL 结束位置：空白或常见收尾标点截断，括号配平后再剥掉尾部标点。 */
private fun bareUrlEnd(text: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < text.length) {
        val char = text[i]
        if (char.isWhitespace()) break
        when (char) {
            '(' -> depth++
            ')' -> if (depth == 0) break else depth--
            ',', ';', '!', '?', '"', '\'', '。', '，', '；', '：', '！', '？', '）', '】', '」', '』' -> break
            else -> Unit
        }
        i++
    }
    while (i > start && text[i - 1] in ".,;:!?。！？") i--
    return i
}

/**
 * 解析 `[label](destination)`：destination 支持配对括号与可选 title；
 * 返回 URL 与 ')' 的下标，格式不完整时返回 null。
 */
private fun parseMarkdownLinkUrl(text: String, start: Int): Pair<String, Int>? {
    var i = start
    while (i < text.length && text[i].isWhitespace()) i++
    if (i >= text.length) return null
    val urlStart = i
    var depth = 0
    while (i < text.length) {
        when (val char = text[i]) {
            '\\' -> i += 2
            '(' -> {
                depth++
                i++
            }
            ')' -> {
                if (depth == 0) return text.substring(urlStart, i) to i
                depth--
                i++
            }
            ' ', '\t', '\n' -> {
                // 括号未配平时空格属于 destination（如维基百科链接）；
                // 配平后的第一个空白后面只能是与 title 一起收尾的 ')'。
                if (depth > 0) {
                    i++
                    continue
                }
                val close = text.indexOf(')', i)
                if (close < 0) return null
                return text.substring(urlStart, i).trimEnd() to close
            }
            else -> i++
        }
    }
    return null
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("IRIS", text))
}
