package com.iris.localai.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.localai.ui.settings.PluginInlineUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface PluginMarkupSegment {
    data class Text(val text: String) : PluginMarkupSegment
    data class PluginTag(val tag: String, val xml: String) : PluginMarkupSegment
}

private val pluginTagRegex = Regex(
    """(?is)<([a-zA-Z][\w:-]*)\b[^>]*>.*?</\1\s*>|<([a-zA-Z][\w:-]*)\b[^>]*/>""",
)

internal fun splitPluginMarkup(text: String): List<PluginMarkupSegment> {
    val matches = pluginTagRegex.findAll(text).toList()
    if (matches.isEmpty()) return listOf(PluginMarkupSegment.Text(text))
    val segments = mutableListOf<PluginMarkupSegment>()
    var cursor = 0
    matches.forEach { match ->
        if (match.range.first > cursor) {
            segments += PluginMarkupSegment.Text(text.substring(cursor, match.range.first))
        }
        val tag = match.groupValues[1].ifBlank { match.groupValues[2] }
        segments += PluginMarkupSegment.PluginTag(tag, match.value)
        cursor = match.range.last + 1
    }
    if (cursor < text.length) segments += PluginMarkupSegment.Text(text.substring(cursor))
    return segments
}

@Composable
internal fun PluginMarkupContent(
    text: String,
    modifier: Modifier = Modifier,
    renderPluginXml: suspend (String, String) -> String?,
    textContent: @Composable (String) -> Unit,
) {
    val segments = remember(text) { splitPluginMarkup(text) }
    if (segments.size == 1 && segments.first() is PluginMarkupSegment.Text) {
        textContent(text)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is PluginMarkupSegment.Text -> {
                    val value = segment.text.trim('\n')
                    if (value.isNotBlank()) textContent(value)
                }

                is PluginMarkupSegment.PluginTag -> PluginMarkupTag(
                    segment = segment,
                    renderPluginXml = renderPluginXml,
                    textContent = textContent,
                )
            }
        }
    }
}

@Composable
private fun PluginMarkupTag(
    segment: PluginMarkupSegment.PluginTag,
    renderPluginXml: suspend (String, String) -> String?,
    textContent: @Composable (String) -> Unit,
) {
    var rendered by remember(segment.xml) { mutableStateOf<String?>(null) }
    var attempted by remember(segment.xml) { mutableStateOf(false) }
    LaunchedEffect(segment.xml) {
        rendered = runCatching { renderPluginXml(segment.tag, segment.xml) }.getOrNull()
        attempted = true
    }
    val raw = rendered
    if (!attempted) return
    if (raw == null) {
        if (segment.tag.equals("voice", ignoreCase = true)) {
            NativeVoiceBar(segment.xml)
        } else {
            textContent(segment.xml)
        }
        return
    }
    val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    val handled = parsed?.get("handled")?.jsonPrimitive?.booleanOrNull == true
    if (!handled) {
        textContent(segment.xml)
        return
    }
    if (parsed?.get("tree") != null) {
        PluginInlineUi(raw)
    } else {
        if (segment.tag.equals("voice", ignoreCase = true)) {
            NativeVoiceBar(segment.xml)
        } else {
            textContent(parsed?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}
