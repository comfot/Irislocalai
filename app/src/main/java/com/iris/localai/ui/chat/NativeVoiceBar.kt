package com.iris.localai.ui.chat

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

private data class VoiceTag(val src: String, val duration: Float, val text: String)

private fun parseVoiceTag(xml: String): VoiceTag {
    val attrs = Regex("""([A-Za-z][\w:-]*)\s*=\s*"([^"]*)"""").findAll(xml)
        .associate { match -> match.groupValues[1].lowercase() to match.groupValues[2] }
    val src = attrs["src"].orEmpty().replace("&quot;", "\"")
    val duration = attrs["dur"]?.toFloatOrNull() ?: 0f
    val text = attrs["text"].orEmpty()
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
    return VoiceTag(src, duration, text)
}

@Composable
internal fun NativeVoiceBar(xml: String) {
    val voice = remember(xml) { parseVoiceTag(xml) }
    if (voice.src.isBlank()) {
        Text(xml, style = MaterialTheme.typography.bodySmall)
        return
    }
    var player by remember(voice.src) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(voice.src) { mutableStateOf(false) }
    var expanded by remember(voice.src) { mutableStateOf(false) }
    var duration by remember(voice.src) { mutableStateOf(voice.duration) }

    DisposableEffect(voice.src) {
        val loaded = runCatching {
            MediaPlayer().apply {
                val path = voice.src.removePrefix("file://")
                setDataSource(File(path).absolutePath)
                prepare()
            }
        }.getOrNull()
        player = loaded
        if (loaded != null) {
            duration = loaded.duration / 1000f
            loaded.setOnCompletionListener { playing = false }
        }
        onDispose {
            loaded?.release()
            if (player === loaded) player = null
        }
    }

    LaunchedEffect(playing) {
        val current = player ?: return@LaunchedEffect
        runCatching {
            if (playing) current.start() else if (current.isPlaying) current.pause()
        }.onFailure { playing = false }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(22.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(
                    onClick = { if (player != null) playing = !playing },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Waveform(
                    seed = voice.src.hashCode(),
                    active = playing,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (duration > 0f) "%.1fs".format(duration) else "语音",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = voice.text.isNotBlank(),
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = "展开文字",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (expanded && voice.text.isNotBlank()) {
            Text(
                text = voice.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Waveform(seed: Int, active: Boolean, modifier: Modifier = Modifier) {
    var value = seed
    val bars = remember(seed) {
        List(26) {
            value = value * 1103515245 + 12345
            val normalized = ((value ushr 16) and 0x7fff) / 32767f
            val height = (10f + normalized * 18f).dp
            height
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEach { height ->
            Surface(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(1.5.dp)),
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
            ) {}
        }
    }
}
