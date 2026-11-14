package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.BubbleStyle
import com.iris.localai.ui.chat.bubbleVisual

@Composable
internal fun ComponentPage(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsPage {
        SettingsCard("气泡样式") {
            Text(
                text = "应用到工作空间与生活空间的聊天消息气泡。",
                style = MaterialTheme.typography.bodySmall,
            )
            BubbleStyle.entries.forEach { style ->
                OptionRow(
                    selected = settings.bubbleStyle == style,
                    title = style.label,
                    summary = style.summary,
                    onClick = { viewModel.update { it.copy(bubbleStyle = style) } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(true, false).forEach { isUser ->
                    val visual = bubbleVisual(isUser = isUser, style = settings.bubbleStyle)
                    Surface(
                        shape = visual.shape,
                        color = visual.color,
                        contentColor = visual.contentColor,
                        border = visual.border,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (isUser) "用户消息" else "角色回复",
                            modifier = Modifier.padding(visual.padding),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        SettingsCard("角色对话") {
            SettingsSwitchRow(
                title = "显示角色对话页签",
                summary = "AI 回复后的日期、时间、地点与角色状态卡片；关闭后只保留正文。",
                checked = settings.roleChatCardEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(roleChatCardEnabled = enabled) }
                },
            )
        }
    }
}
