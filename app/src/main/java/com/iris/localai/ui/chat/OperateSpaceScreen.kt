package com.iris.localai.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.StepStatus
import com.iris.localai.data.OperationSurfaceMode
import com.iris.localai.permission.DevicePermissionState
import com.iris.localai.permission.OperationChannel
import kotlinx.coroutines.delay

private val quickActions = listOf(
    "打开设置",
    "返回上一页",
    "查看通知",
    "截屏",
)

@Composable
fun OperateSpaceScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val virtualDisplayRunning by viewModel.virtualDisplayRunning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showVoice by remember { mutableStateOf(false) }
    var showClearLogs by remember { mutableStateOf(false) }
    var previewFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var showFullPreview by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshPermissions() }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(virtualDisplayRunning) {
        while (virtualDisplayRunning) {
            val bitmap = viewModel.captureVirtualFrame()
            if (bitmap != null) previewFrame = bitmap.asImageBitmap()
            delay(200)
        }
        if (!virtualDisplayRunning) previewFrame = null
    }

    val steps = messages.flatMap { it.steps }
    val backgroundMode = settings.operateBackgroundMode
    val shizukuMode = settings.operateShizukuMode
    val backgroundBlocked = backgroundMode && !permissions.shizukuReady

    Column(modifier = modifier.imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OperateStatusCard(
                    permissions = permissions,
                    backgroundMode = backgroundMode,
                    shizukuMode = shizukuMode,
                    backgroundBlocked = backgroundBlocked,
                    onRefresh = viewModel::refreshPermissions,
                )
            }
            if (virtualDisplayRunning && settings.operationSurfaceMode == OperationSurfaceMode.ARIES_VIRTUAL_DISPLAY) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "虚拟屏预览",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::bringVirtualDisplayToMain) {
                                    Text("带回主屏")
                                }
                                TextButton(onClick = { showFullPreview = true }) {
                                    Text("全屏")
                                }
                            }
                            val frame = previewFrame
                            if (frame == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .background(Color.Black, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("等待虚拟屏画面…", color = Color.White)
                                }
                            } else {
                                Image(
                                    bitmap = frame,
                                    contentDescription = "虚拟屏画面",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(280.dp)
                                        .background(Color.Black, RoundedCornerShape(12.dp)),
                                )
                            }
                            Text(
                                text = "画面每 200ms 刷新；虚拟屏内的应用不会占用主屏。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SectionTitle("权限")
                PermissionCard(
                    icon = Icons.Outlined.Accessibility,
                    title = "无障碍服务",
                    description = "执行点击、滑动、返回等手机操作。",
                    status = when {
                        permissions.accessibilityConnected -> "已连接"
                        permissions.accessibilityEnabled -> "已开启 · 等待连接"
                        else -> "未开启"
                    },
                    ready = permissions.accessibilityConnected,
                    action = if (permissions.accessibilityConnected) "已连接" else if (permissions.accessibilityEnabled) "刷新" else "去开启",
                    onAction = {
                        if (permissions.accessibilityEnabled) {
                            viewModel.refreshPermissions()
                        } else {
                            launchSettings(
                                settingsLauncher,
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                            )
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                PermissionCard(
                    icon = Icons.Outlined.Layers,
                    title = "悬浮窗",
                    description = "在操作进行时显示进度和状态。",
                    status = if (permissions.overlayGranted) "已授予" else "未授予",
                    ready = permissions.overlayGranted,
                    action = if (permissions.overlayGranted) "已授予" else "去授权",
                    onAction = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        launchSettings(settingsLauncher, intent, context)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                PermissionCard(
                    icon = Icons.Default.Mic,
                    title = "麦克风",
                    description = "用语音下达手机操作指令。",
                    status = if (permissions.microphoneGranted) "已授予" else "未授予",
                    ready = permissions.microphoneGranted,
                    action = if (permissions.microphoneGranted) "已授予" else "去授权",
                    onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                PermissionCard(
                    icon = Icons.Outlined.Bolt,
                    title = "Shizuku",
                    description = "后台执行、系统级命令与自动开启无障碍依赖 Shizuku。",
                    status = when {
                        !permissions.shizukuInstalled -> "未安装"
                        !permissions.shizukuBinderConnected -> "已安装 · 未运行"
                        !permissions.shizukuAuthorized -> "运行中 · 未授权"
                        else -> "已授权"
                    },
                    ready = permissions.shizukuReady,
                    action = when {
                        !permissions.shizukuInstalled -> "未安装"
                        !permissions.shizukuBinderConnected -> "启动"
                        !permissions.shizukuAuthorized -> "授权"
                        else -> "已授权"
                    },
                    actionEnabled = permissions.shizukuInstalled,
                    onAction = {
                        when {
                            !permissions.shizukuBinderConnected -> viewModel.openShizuku()
                            !permissions.shizukuAuthorized -> viewModel.requestShizukuPermission()
                            else -> viewModel.refreshPermissions()
                        }
                    },
                )
                if (permissions.shizukuInstalled &&
                    (!permissions.shizukuReady || !permissions.accessibilityEnabled)
                ) {
                    TextButton(
                        onClick = viewModel::authorizeOperation,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("一键完成 Shizuku 授权并开启无障碍")
                    }
                }
            }
            item {
                SectionTitle("操作方式")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Smartphone,
                        title = "前台执行",
                        description = "在当前屏幕上操作，你可以看到每一步。",
                        selected = !backgroundMode,
                        onClick = {
                            viewModel.setOperateMode(false, shizukuMode)
                        },
                    )
                    ModeCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Layers,
                        title = "后台执行",
                        description = "使用 Shizuku 系统命令；虚拟屏隔离尚未接入。",
                        selected = backgroundMode,
                        enabled = permissions.shizukuInstalled,
                        onClick = {
                            viewModel.setOperateMode(true, shizukuMode)
                        },
                    )
                }
                if (backgroundBlocked) {
                    Spacer(Modifier.height(8.dp))
                    WarningRow("后台执行依赖 Shizuku：需已安装、服务运行并授权。")
                }
            }
            item {
                SectionTitle("后台运行方式")
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        OperationSurfaceMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setOperationSurfaceMode(mode) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = settings.operationSurfaceMode == mode,
                                    onClick = { viewModel.setOperationSurfaceMode(mode) },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (settings.operationSurfaceMode == OperationSurfaceMode.ARIES_VIRTUAL_DISPLAY) {
                            TextButton(onClick = viewModel::testVirtualDisplay) {
                                Text("启动/测试虚拟屏")
                            }
                        }
                        if (settings.operationSurfaceMode != OperationSurfaceMode.NORMAL) {
                            TextButton(onClick = viewModel::releaseVirtualDisplay) {
                                Text("释放当前虚拟屏")
                            }
                        }
                    }
                }
                Text(
                    text = "虚拟屏需要 Shizuku 授权；小窗使用系统 freeform，ROM 不支持时回退普通启动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }
            item {
                SectionTitle("交互方式")
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Shizuku 模式",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (shizukuMode) {
                                    "优先使用 Shizuku 完成系统级操作"
                                } else {
                                    "使用无障碍服务完成点击与滑动"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = shizukuMode,
                            enabled = permissions.shizukuInstalled,
                            onCheckedChange = { checked ->
                                viewModel.setOperateMode(backgroundMode, checked)
                            },
                        )
                    }
                }
            }
            item {
                SectionTitle("常用指令")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickActions) { action ->
                        AssistChip(
                            onClick = {
                                viewModel.onInputChange(action)
                                viewModel.send()
                            },
                            label = { Text(action, maxLines = 1) },
                        )
                    }
                }
            }
            item {
                ExecutionLogCard(
                    steps = steps,
                    onCopy = { copyLogs(context, steps) },
                    onClear = { showClearLogs = true },
                )
            }
            item {
                Text(
                    text = "操作工具已接入：优先使用 Shizuku，失败回退无障碍；执行记录来自 Agent 工具步骤。虚拟屏隔离与输入结果校验尚未完成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }

        OperateComposer(
            viewModel = viewModel,
            input = input,
            running = running,
            onVoice = { showVoice = true },
        )
    }

    if (showVoice) {
        VoiceInputDialog(
            onDismiss = { showVoice = false },
            onInsert = { text ->
                viewModel.onInputChange(text)
                showVoice = false
            },
            onTranscribe = viewModel::transcribeVoice,
        )
    }

    if (showClearLogs) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearLogs = false },
            title = { Text("清空执行记录？") },
            text = { Text("当前会话的工具执行记录会被清空，聊天消息本身保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearExecutionRecords()
                        showClearLogs = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogs = false }) { Text("取消") }
            },
        )
    }

    if (showFullPreview) {
        Dialog(
            onDismissRequest = { showFullPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "虚拟屏全屏预览",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showFullPreview = false }) { Text("关闭") }
                    }
                    val frame = previewFrame
                    if (frame != null) {
                        Image(
                            bitmap = frame,
                            contentDescription = "虚拟屏全屏画面",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().background(Color.Black),
                        )
                    } else {
                        Text("等待虚拟屏画面…", modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OperateStatusCard(
    permissions: DevicePermissionState,
    backgroundMode: Boolean,
    shizukuMode: Boolean,
    backgroundBlocked: Boolean,
    onRefresh: () -> Unit,
) {
    val channel = permissions.channel(backgroundMode, shizukuMode)
    val ready = channel != OperationChannel.NONE
    val title = when {
        ready -> "手机操作已就绪"
        backgroundBlocked -> "还需要 Shizuku 授权"
        else -> "完成手机操作授权"
    }
    val subtitle = buildString {
        append(if (backgroundMode) "后台执行" else "前台执行")
        append(" · ")
        append(channel.label)
        append(" · 权限 ")
        append("${permissions.readyCount}/3")
    }
    val container = if (ready) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val accent = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.14f)) {
                Box(modifier = Modifier.padding(9.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Smartphone,
                        contentDescription = null,
                        tint = accent,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(
                text = when {
                    ready -> "已就绪"
                    permissions.readyCount > 0 -> "部分就绪"
                    else -> "未就绪"
                },
                color = accent,
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "刷新权限状态",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
            )
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    status: String,
    ready: Boolean,
    action: String,
    actionEnabled: Boolean = !ready,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
            Box(modifier = Modifier.padding(8.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionEnabled) {
            TextButton(onClick = onAction) {
                Text(action, maxLines = 1)
            }
        } else {
            Text(
                text = action,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .heightIn(min = 116.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            scheme.primary.copy(alpha = 0.1f)
        } else {
            scheme.surface
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outline.copy(alpha = 0.14f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!enabled) {
                Text(
                    text = "需要 Shizuku",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun WarningRow(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun ExecutionLogCard(steps: List<AgentStep>, onCopy: () -> Unit, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "执行记录",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy, enabled = steps.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("复制")
                }
                TextButton(onClick = onClear, enabled = steps.isNotEmpty()) {
                    Text("清空")
                }
            }
            if (steps.isEmpty()) {
                Text(
                    text = "暂无执行记录。下达手机操作指令后，这里会显示每一步的结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    steps.takeLast(12).forEach { step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                text = if (step.status == StepStatus.OK) "✓" else "!",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (step.status == StepStatus.OK) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = step.tool,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = step.result.take(220),
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

@Composable
private fun OperateComposer(
    viewModel: ChatViewModel,
    input: String,
    running: Boolean,
    onVoice: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("描述要执行的手机操作") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
            )
            IconButton(onClick = onVoice) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = { if (running) viewModel.stop() else viewModel.send() }) {
                Icon(
                    imageVector = if (running) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (running) "停止" else "发送",
                )
            }
        }
    }
}

private fun launchSettings(
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    intent: Intent,
    context: Context? = null,
) {
    runCatching { launcher.launch(intent) }.onFailure {
        if (context != null) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

private fun copyLogs(context: Context, steps: List<AgentStep>) {
    val text = steps.joinToString("\n\n") { step ->
        val status = if (step.status == StepStatus.OK) "OK" else "ERROR"
        "[$status] ${step.tool}\n参数：${step.arguments}\n结果：${step.result.take(600)}"
    }
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("IRIS 操作记录", text))
    Toast.makeText(context, "执行记录已复制", Toast.LENGTH_SHORT).show()
}
