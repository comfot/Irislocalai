package com.iris.localai.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.ThemePresetCodec
import com.iris.localai.data.toThemePreset
import com.iris.localai.data.withThemePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ThemePresetDialog(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fallback = remember(settings) { settings.toThemePreset() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = ThemePresetCodec.encode(settings.toThemePreset())
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { writeText(context, uri, text) }
            notice = result.fold(
                onSuccess = { "已导出当前主题预设" },
                onFailure = { "导出失败：${it.message ?: "未知错误"}" },
            )
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ThemePresetCodec.decode(readText(context, uri), fallback) }
            }
            result.onSuccess { preset ->
                viewModel.update { it.withThemePreset(preset) }
                notice = "已应用预设：${preset.name}"
            }.onFailure {
                notice = "导入失败：${it.message ?: "未知错误"}"
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("主题预设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "主题预设是一段 JSON，包含界面风格、强调色、明暗模式、气泡样式与角色对话开关。导入会立即应用并保存；导出会把当前外观写成预设文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "style：standard / glass / eink；accent：teal / blue / violet / amber / rose；themeMode：system / light / dark；bubbleStyle：rounded / square / outlined / minimal；roleChatCard：true / false。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = SAMPLE,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream"),
                            )
                        },
                        enabled = !busy,
                    ) {
                        Text(if (busy) "处理中…" else "导入预设")
                    }
                    OutlinedButton(
                        onClick = {
                            val name = "iris-theme-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"
                            exportLauncher.launch(name)
                        },
                        enabled = !busy,
                    ) {
                        Text("导出当前")
                    }
                }
                notice?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )
}

private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("无法读取文件")
    require(bytes.size <= 256 * 1024) { "预设文件超过 256 KB" }
    return String(bytes, Charsets.UTF_8)
}

private fun writeText(context: Context, uri: Uri, text: String): Result<Unit> = runCatching {
    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
        ?: context.contentResolver.openOutputStream(uri, "w")
        ?: error("无法写入文件")
    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

private const val SAMPLE = """{
  "app": "IRIS LocalAI",
  "schema": 1,
  "name": "雾青玻璃",
  "style": "glass",
  "accent": "teal",
  "themeMode": "system",
  "bubbleStyle": "rounded",
  "roleChatCard": true
}"""
