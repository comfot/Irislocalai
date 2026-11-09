package com.iris.localai.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.iris.localai.data.AppSettings

@Composable
internal fun NotificationPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage {
        SettingsCard(title = "对话通知") {
            SettingsSwitchRow(
                title = "对话在通知栏显示",
                summary = "助手回复完成后在通知栏保留一条会话提醒",
                checked = settings.notifyReplies,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(notifyReplies = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "锁屏通知",
                summary = "关闭后锁屏界面只显示「内容已隐藏」",
                checked = settings.notifyLockScreen,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(notifyLockScreen = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "悬浮通知",
                summary = "以横幅形式从屏幕顶部弹出，等待回复时可直接查看",
                checked = settings.notifyHeadsUp,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(notifyHeadsUp = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "灵动岛",
                summary = "在屏幕顶部居中显示胶囊式摘要，需要悬浮窗权限",
                checked = settings.notifyIsland,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(notifyIsland = enabled) }
                },
            )
            Text(
                text = "应用处于前台时不会重复提醒，避免与聊天界面冲突。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "权限与系统设置") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (notificationGranted) "通知权限已授予" else "通知权限未授予",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notificationGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!notificationGranted) {
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("去授权")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (overlayGranted) "悬浮窗权限已授予" else "悬浮窗权限未授予（灵动岛需要）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overlayGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!overlayGranted) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                        Toast.makeText(context, "请允许显示在其他应用上层", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("去授权")
                    }
                }
            }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        },
                    )
                }
            }) {
                Text("打开系统通知设置")
            }
            Text(
                text = "通道分为「对话通知」与「对话通知（悬浮）」；悬浮通道的重要性由系统固定，切换开关后即时生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
