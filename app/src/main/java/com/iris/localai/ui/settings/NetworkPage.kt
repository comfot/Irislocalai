package com.iris.localai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.DownloadSources

@Composable
internal fun NetworkPage(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsPage {
        SettingsCard(title = "下载策略") {
            SettingsSwitchRow(
                title = "启用备用下载源",
                summary = "官方源超时或失败时自动回退 hf-mirror、jsDelivr 等镜像；关闭后只访问官方源。",
                checked = settings.mirrorEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(mirrorEnabled = enabled) }
                },
            )
            InfoRow("模型下载源", settings.downloadSource.label)
            Text(
                text = "模型下载源在「模型」页切换；选择「hf-mirror」时即使关闭备用源也会走镜像。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DownloadSources.groups.forEach { group ->
            SettingsCard(title = group.title) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.endpoints.forEach { endpoint ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(endpoint.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = endpoint.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = endpoint.usage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                endpoint.backup?.let { backup ->
                                    Text(
                                        text = "备用：$backup",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
