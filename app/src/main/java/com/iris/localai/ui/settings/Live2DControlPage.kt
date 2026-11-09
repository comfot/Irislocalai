package com.iris.localai.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.iris.localai.data.AppSettings

@Composable
internal fun Live2DControlPage(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsPage {
        SettingsCard(title = "表情") {
            SettingsSwitchRow(
                title = "启用表情映射",
                summary = "按回复情绪切换模型自带的 Expression（开心/生气/难过/惊讶/思考）",
                checked = settings.l2dExpressionsEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(l2dExpressionsEnabled = enabled) }
                },
            )
            Text(
                text = "模型没有 Expressions 分组时会自动跳过，不影响渲染。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "动作") {
            SettingsSwitchRow(
                title = "情绪动作",
                summary = "情绪变化时播对应 Motion 分组",
                checked = settings.l2dMotionsEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(l2dMotionsEnabled = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "待机动作",
                summary = "空闲时循环播放 Idle 分组动作",
                checked = settings.l2dIdleMotionEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(l2dIdleMotionEnabled = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "点击动作",
                summary = "点击角色时随机播放一个动作",
                checked = settings.l2dTapMotionEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(l2dTapMotionEnabled = enabled) }
                },
            )
        }

        SettingsCard(title = "口型") {
            SettingsSwitchRow(
                title = "说话口型",
                summary = "生成中或 TTS 朗读时驱动 ParamMouthOpenY",
                checked = settings.l2dMouthSyncEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(l2dMouthSyncEnabled = enabled) }
                },
            )
            Text(
                text = "表情与动作沿用 AIRI 的 Expression / Motion 分离思路：表情负责脸部，动作负责身体，互不阻塞。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
