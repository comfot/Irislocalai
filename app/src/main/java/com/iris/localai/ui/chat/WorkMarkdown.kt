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
    data object Divider : WorkBlock
}

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
        if (trimmed.isBlank()) {
            flushAll()
            i++
            continue
        }
        if (trimmed.length >= 3 && trimmed.all { it in "-*_" }) {
            flushAll()
            blocks += WorkBlock.Divider
            i++
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

@Composable
internal fun WorkMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val source = if (streaming) "$text▍" else text
    val blocks = remember(source) { parseWorkMarkdown(source) }
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
                    text = workInline(block.text),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is WorkBlock.Code -> WorkCodeBlock(block.language, block.code)

        WorkBlock.Divider -> HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
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
private fun workInline(text: String): AnnotatedString {
    val colorScheme = MaterialTheme.colorScheme
    val base = SpanStyle(color = colorScheme.onSurface)
    val code = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        background = colorScheme.surfaceVariant,
        color = colorScheme.primary,
    )
    return remember(text, colorScheme) {
        buildAnnotatedString {
            appendInline(
                text = text,
                base = base,
                code = code,
                accent = colorScheme.primary,
            )
        }
    }
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    base: SpanStyle,
    code: SpanStyle,
    accent: Color,
    allowCode: Boolean = true,
) {
    var i = 0
    while (i < text.length) {
        if (allowCode && text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(base.merge(code)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i + 3 && canOpenEmphasis(text, i, 2) && !text[end - 1].isWhitespace()) {
                val style = base.copy(fontWeight = FontWeight.SemiBold)
                withStyle(style) {
                    appendInline(text.substring(i + 2, end), style, code, accent, allowCode)
                }
                i = end + 2
                continue
            }
        }
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 3 && canOpenEmphasis(text, i, 2) && !text[end - 1].isWhitespace()) {
                val style = base.copy(textDecoration = TextDecoration.LineThrough)
                withStyle(style) {
                    appendInline(text.substring(i + 2, end), style, code, accent, allowCode)
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
                    appendInline(text.substring(i + 1, end), style, code, accent, allowCode)
                }
                i = end + 1
                continue
            }
        }
        if (text[i] == '[') {
            val close = text.indexOf("](", i + 1)
            if (close > i + 1) {
                val end = text.indexOf(')', close + 2)
                if (end > close + 2) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, end)
                    val style = base.copy(color = accent, textDecoration = TextDecoration.Underline)
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(style) { append(label) }
                    }
                    i = end + 1
                    continue
                }
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
    return before == null || before.isWhitespace() || before in "([{>\"'"
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("IRIS", text))
}
