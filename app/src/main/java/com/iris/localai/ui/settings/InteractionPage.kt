package com.iris.localai.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.iris.localai.data.AppSettings
import com.iris.localai.pet.PetOverlayController

@Composable
internal fun InteractionPage(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onOpenPet: () -> Unit,
    onOpenLive2D: () -> Unit,
    onOpen3D: () -> Unit,
) {
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestOverlay() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        Toast.makeText(context, "请允许显示在其他应用上层", Toast.LENGTH_SHORT).show()
    }

    SettingsPage {
        SettingsCard(title = "桌宠穿透") {
            SettingsSwitchRow(
                title = "启用桌宠",
                summary = "退出应用后角色仍停留在桌面，可拖动与互动",
                checked = settings.petOverlayEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        requestOverlay()
                        return@SettingsSwitchRow
                    }
                    viewModel.update { it.copy(petOverlayEnabled = enabled) }
                    if (enabled) {
                        PetOverlayController.start(context)
                    } else {
                        PetOverlayController.stop(context)
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPet)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("桌宠设置", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "萌化形象、模型、动作与互动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (overlayGranted) "悬浮窗权限已授予" else "悬浮窗权限未授予",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overlayGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!overlayGranted) {
                    TextButton(onClick = { requestOverlay() }) { Text("去授权") }
                }
            }
        }

        SettingsCard(title = "模型控制") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLive2D)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live2D 控制", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "表情、动作、待机与口型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen3D)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("3D 控制", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "动作模型、服务地址与模型位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Live2D 直接在本机执行；3D 动作模型（Kimodo / HAST-SEC）需在电脑端运行，通过服务地址接入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "发送与手势") {
            Text(
                text = "聊天发送方式、手势与长按菜单沿用输入栏与角色设置，桌宠的单击/长按行为在「桌宠设置」中单独配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
