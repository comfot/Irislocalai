package com.iris.localai.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iris.localai.data.AppSettings
import java.io.File

@Composable
internal fun UserPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val avatarBitmap = remember(settings.userAvatarPath) {
        settings.userAvatarPath.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap()
        }
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@rememberLauncherForActivityResult
            val ext = when {
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
                bytes.size > 12 &&
                    String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> "webp"
                else -> "png"
            }
            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            val file = File(dir, "user.$ext")
            file.writeBytes(bytes)
            viewModel.update { it.copy(userAvatarPath = file.absolutePath) }
        }
    }

    SettingsPage {
        SettingsCard("用户资料") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.size(84.dp)) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = "用户头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(84.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = settings.userName.take(1).ifBlank { "我" },
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(onClick = { picker.launch("image/*") }) {
                        Text("更换头像")
                    }
                    if (settings.userAvatarPath.isNotBlank()) {
                        TextButton(
                            onClick = { viewModel.update { it.copy(userAvatarPath = "") } },
                        ) { Text("移除头像") }
                    }
                }
            }

            OutlinedTextField(
                value = settings.userName,
                onValueChange = { name -> viewModel.update { it.copy(userName = name) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名称") },
                singleLine = true,
            )

            OutlinedTextField(
                value = settings.userNarrative,
                onValueChange = { text -> viewModel.update { it.copy(userNarrative = text) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("叙事描述") },
                placeholder = { Text("你的身份、背景与当前处境") },
                minLines = 3,
                maxLines = 6,
            )

            OutlinedTextField(
                value = settings.userVisualTags,
                onValueChange = { text -> viewModel.update { it.copy(userVisualTags = text) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("视觉提示标签") },
                placeholder = { Text("例如：黑发、风衣、夜晚城市") },
                supportingText = { Text("用于生图与角色视觉描述，逗号分隔") },
                singleLine = true,
            )

            Text(
                text = "用户资料会保存在本机，并在角色扮演提示词与生图提示中使用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
