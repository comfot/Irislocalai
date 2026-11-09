package com.iris.localai.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.data.AppSettings
import com.iris.localai.data.PetLongPressAction
import com.iris.localai.data.PetTapAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PetSettingsPage(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as IrisApp).graph }
    val models by graph.petModels.models.collectAsStateWithLifecycle()
    val characters by graph.characters.cards.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            importError = null
            scope.launch {
                val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
                val result = withContext(Dispatchers.IO) { graph.petModels.import(uri, name) }
                importing = false
                result.onFailure { importError = it.message ?: "导入失败" }
            }
        }
    }
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
            Text(
                text = "启用开关在上一级「系统管理 › 交互」，这里配置桌面上的形象与互动方式。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Text(
                text = "形象使用与生活空间相同的渲染器（Live2D / MMD / VRM），不支持时回退为角色卡头像。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "萌化形象") {
            SettingsSwitchRow(
                title = "启用萌化角色",
                summary = "开启时用桌宠模型库里的萌化模型，关闭时用角色绑定的模型；两者都是实时渲染",
                checked = settings.petCuteAvatar,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(petCuteAvatar = enabled) }
                },
            )
            Text(
                text = "形象模型（桌宠模型库，与角色模型互不同步）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            OptionRow(
                selected = settings.petModelId.isBlank(),
                title = "自动",
                summary = "使用桌宠模型库里最近导入的模型",
                onClick = { viewModel.update { it.copy(petModelId = "") } },
            )
            models.forEach { model ->
                OptionRow(
                    selected = settings.petModelId == model.id,
                    title = model.name,
                    summary = "${model.type.label} · ${model.sizeBytes / 1024 / 1024} MB",
                    onClick = { viewModel.update { it.copy(petModelId = model.id) } },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !importing,
                ) {
                    Text(if (importing) "导入中…" else "导入萌化模型")
                }
                if (settings.petModelId.isNotBlank()) {
                    TextButton(onClick = {
                        graph.petModels.delete(settings.petModelId)
                        viewModel.update { it.copy(petModelId = "") }
                    }) {
                        Text("删除所选")
                    }
                }
            }
            importError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (models.isEmpty()) {
                Text(
                    text = "还没有导入萌化模型，可直接导入 Live2D ZIP、VRM、GLB 或 MMD（PMX/PMD）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (characters.isEmpty()) {
                Text(
                    text = "没有可用角色卡，关闭萌化后将无法显示角色模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingsCard(title = "动作设置") {
            Text(
                text = "形象大小",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            SliderRow(
                title = "缩放",
                value = settings.petScale,
                range = 0.6f..1.8f,
                steps = 5,
                format = { String.format("%.1fx", it) },
                onCommit = { value -> viewModel.update { it.copy(petScale = value) } },
            )
            SettingsSwitchRow(
                title = "待机浮动",
                summary = "形象在桌面上轻微上下浮动",
                checked = settings.petIdleAnimation,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(petIdleAnimation = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "允许拖动",
                summary = "关闭后桌宠固定在初始位置",
                checked = settings.petDraggable,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(petDraggable = enabled) }
                },
            )
            Text(
                text = "单击",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            PetTapAction.entries.forEach { action ->
                OptionRow(
                    selected = settings.petTapAction == action,
                    title = action.label,
                    summary = when (action) {
                        PetTapAction.BUBBLE -> "随机说一句话"
                        PetTapAction.OPEN_APP -> "打开应用回到对话"
                        PetTapAction.NONE -> "不做任何反应"
                    },
                    onClick = { viewModel.update { it.copy(petTapAction = action) } },
                )
            }
            Text(
                text = "长按",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            PetLongPressAction.entries.forEach { action ->
                OptionRow(
                    selected = settings.petLongPressAction == action,
                    title = action.label,
                    summary = when (action) {
                        PetLongPressAction.MENU -> "展开喂食、唱歌、叫醒等互动"
                        PetLongPressAction.OPEN_APP -> "打开应用回到对话"
                        PetLongPressAction.HIDE -> "隐藏悬浮形象"
                    },
                    onClick = { viewModel.update { it.copy(petLongPressAction = action) } },
                )
            }
        }

        SettingsCard(title = "桌宠操作") {
            SettingsSwitchRow(
                title = "喂食",
                summary = "投喂后形象会弹跳并回应",
                checked = settings.petFeedEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(petFeedEnabled = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "唱歌",
                summary = "形象摇摆并通过系统 TTS 哼唱",
                checked = settings.petSingEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(petSingEnabled = enabled) }
                },
            )
            SettingsSwitchRow(
                title = "叫醒",
                summary = "形象晃动并播放起床语音",
                checked = settings.petWakeEnabled,
                onCheckedChange = { enabled ->
                    viewModel.update { it.copy(petWakeEnabled = enabled) }
                },
            )
            Text(
                text = "互动项会出现在长按菜单中，按需勾选。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = "pet-model"
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
            }
    }
    return name
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
