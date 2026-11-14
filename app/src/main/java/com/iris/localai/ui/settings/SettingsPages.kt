package com.iris.localai.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.R
import com.iris.localai.agent.Conversation
import com.iris.localai.agent.tools.DeviceToolNames
import com.iris.localai.data.AppSettings
import com.iris.localai.data.OperationSurfaceMode
import com.iris.localai.data.PermissionMode
import com.iris.localai.data.Space
import com.iris.localai.data.ThemeAccent
import com.iris.localai.data.ThemeMode
import com.iris.localai.data.ThemeStyle
import com.iris.localai.data.ToolPermission
import com.iris.localai.server.ApiState
import com.iris.localai.workspace.Workspace

@Composable
internal fun ThemePage(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onOpenPreset: () -> Unit,
) {
    SettingsPage {
        SettingsCard("外观模式") {
            ThemeMode.entries.forEach { mode ->
                OptionRow(
                    selected = settings.themeMode == mode,
                    title = mode.label,
                    summary = when (mode) {
                        ThemeMode.SYSTEM -> "跟随系统深浅色"
                        ThemeMode.LIGHT -> "始终使用浅色"
                        ThemeMode.DARK -> "始终使用深色"
                    },
                    onClick = { viewModel.update { it.copy(themeMode = mode) } },
                )
            }
        }
        SettingsCard("界面风格") {
            ThemeStyle.entries.forEach { style ->
                OptionRow(
                    selected = settings.themeStyle == style,
                    title = style.label,
                    summary = style.summary,
                    onClick = { viewModel.update { it.copy(themeStyle = style) } },
                )
            }
            if (settings.themeStyle == ThemeStyle.E_INK) {
                Text(
                    text = "墨水屏会忽略强调色，改用黑白灰与细描边。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsCard("强调色") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ThemeAccent.entries.forEach { accent ->
                    AccentChoice(
                        accent = accent,
                        selected = settings.themeAccent == accent,
                    ) {
                        viewModel.update { it.copy(themeAccent = accent) }
                    }
                }
            }
            Text(
                text = if (settings.themeStyle == ThemeStyle.E_INK) {
                    "墨水屏下强调色不生效。"
                } else {
                    "当前：${settings.themeAccent.label}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsCard("主题预设") {
            Text(
                text = "预设是可导入/导出的 JSON，包含界面风格、强调色、明暗模式、气泡样式与角色对话开关。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenPreset) {
                Text("打开主题预设")
            }
        }
        SettingsCard("预览") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.surfaceVariant,
                ).forEach { color ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(color),
                    )
                }
            }
            Text(
                text = "主色 / 主色容器 / 卡片 / 次要表面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccentChoice(
    accent: ThemeAccent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.onBackground.luminance() > 0.5f
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(accent.primary(dark)))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
        Text(accent.label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun SpacePage(
    settings: AppSettings,
    conversations: List<Conversation>,
    viewModel: SettingsViewModel,
    onOpenStudioSpace: () -> Unit,
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val activeWorkspaceId by viewModel.activeWorkspaceId.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    var createFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Workspace?>(null) }
    var deleteTarget by remember { mutableStateOf<Workspace?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.importWorkspace(uri)
                .onSuccess { notice = "已添加工作区：${it.name}" }
                .onFailure { notice = "添加失败：${it.message}" }
        }
    }

    SettingsPage {
        SettingsCard("综合空间") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenStudioSpace),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Home, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("家与小人的模型", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "按角色卡生成居民，设置名字、造型与颜色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingsCard("工作区（文件夹）") {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.weight(1f),
                ) { Text("选择文件夹") }
                Button(
                    onClick = { createFolder = true },
                    modifier = Modifier.weight(1f),
                ) { Text("新建文件夹") }
            }
            Text(
                text = "选择系统文件管理器中的文件夹，或在应用容器内创建 workspace 文件夹。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notice != null) {
                Text(
                    text = notice.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            workspaces.forEach { workspace ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setActiveWorkspace(workspace.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(workspace.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = workspace.path.ifBlank { workspace.uri },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (workspace.id == activeWorkspaceId) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "当前工作区",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { renameTarget = workspace }) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "重命名",
                        )
                    }
                    IconButton(onClick = { deleteTarget = workspace }) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "移除",
                        )
                    }
                }
            }
            Text(
                text = "移除工作区条目不会删除文件夹内容。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsCard("空间") {
            Space.entries.forEach { space ->
                OptionRow(
                    selected = settings.space == space,
                    title = space.label,
                    summary = "${space.description} · ${conversations.count { it.space == space }} 个会话",
                    onClick = { viewModel.update { it.copy(space = space) } },
                )
            }
        }
        SettingsCard("说明") {
            Text(
                text = "切换空间会同时切换聊天页的当前会话；会话按空间隔离。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (createFolder) {
        NameDialog(
            title = "新建工作区文件夹",
            initial = "新工作区",
            onDismiss = { createFolder = false },
            onConfirm = { name ->
                val workspace = viewModel.createWorkspace(name)
                notice = "已创建：${workspace.path}"
                createFolder = false
            },
        )
    }

    renameTarget?.let { workspace ->
        NameDialog(
            title = "重命名工作区",
            initial = workspace.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renameWorkspace(workspace.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { workspace ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("移除工作区？") },
            text = { Text("「${workspace.name}」只会从列表移除，文件夹内容不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteWorkspace(workspace.id)
                        deleteTarget = null
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun PermissionPage(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshPermissions() }
    val channel = permissions.channel(settings.operateBackgroundMode, settings.operateShizukuMode)

    LaunchedEffect(Unit) { viewModel.refreshPermissions() }

    SettingsPage {
        SettingsCard("操作运行状态") {
            InfoRow(
                "无障碍服务",
                when {
                    permissions.accessibilityConnected -> "已连接"
                    permissions.accessibilityEnabled -> "已开启 · 等待连接"
                    else -> "未开启"
                },
            )
            InfoRow(
                "Shizuku",
                when {
                    !permissions.shizukuInstalled -> "未安装"
                    !permissions.shizukuBinderConnected -> "已安装 · 未运行"
                    !permissions.shizukuAuthorized -> "运行中 · 未授权"
                    else -> "已授权"
                },
            )
            InfoRow("实际通道", channel.label)
            InfoRow("执行方式", if (settings.operateBackgroundMode) "后台" else "前台")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshPermissions, modifier = Modifier.weight(1f)) {
                    Text("刷新状态")
                }
                if (permissions.shizukuInstalled) {
                    Button(
                        onClick = {
                            when {
                                !permissions.shizukuBinderConnected -> viewModel.openShizuku()
                                !permissions.shizukuAuthorized -> viewModel.requestShizukuPermission()
                                !permissions.accessibilityEnabled -> viewModel.enableAccessibilityViaShizuku()
                                else -> viewModel.refreshPermissions()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("一键授权") }
                }
            }
            if (settings.operateBackgroundMode && !permissions.shizukuReady) {
                Text(
                    text = "后台执行需要 Shizuku 已安装、运行并授权；当前会阻止操作工具执行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingsCard("权限") {
            PermissionSettingRow(
                title = "无障碍服务",
                summary = "点击、滑动、返回与文本输入",
                status = if (permissions.accessibilityConnected) "已连接" else if (permissions.accessibilityEnabled) "等待连接" else "未开启",
                ready = permissions.accessibilityConnected,
                action = if (permissions.accessibilityEnabled) "刷新" else "去开启",
                onAction = {
                    if (permissions.accessibilityEnabled) {
                        viewModel.refreshPermissions()
                    } else {
                        runCatching {
                            settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                },
            )
            PermissionSettingRow(
                title = "悬浮窗",
                summary = "操作进度与状态浮层",
                status = if (permissions.overlayGranted) "已授予" else "未授予",
                ready = permissions.overlayGranted,
                action = if (permissions.overlayGranted) "已授予" else "去授权",
                onAction = {
                    runCatching {
                        settingsLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            )
            PermissionSettingRow(
                title = "麦克风",
                summary = "语音指令输入",
                status = if (permissions.microphoneGranted) "已授予" else "未授予",
                ready = permissions.microphoneGranted,
                action = if (permissions.microphoneGranted) "已授予" else "去授权",
                onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionSettingRow(
                    title = "通知",
                    summary = "后台任务与生成完成提醒",
                    status = if (permissions.notificationGranted) "已授予" else "未授予",
                    ready = permissions.notificationGranted,
                    action = if (permissions.notificationGranted) "已授予" else "去授权",
                    onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
            PermissionSettingRow(
                title = "Shizuku",
                summary = "系统级命令与自动开启无障碍",
                status = when {
                    !permissions.shizukuInstalled -> "未安装"
                    !permissions.shizukuBinderConnected -> "未运行"
                    !permissions.shizukuAuthorized -> "未授权"
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
        }

        SettingsCard("执行与交互") {
            OptionRow(
                selected = !settings.operateBackgroundMode,
                title = "前台执行",
                summary = "在当前屏幕执行，你能看到每一步",
                onClick = { viewModel.setOperateMode(false, settings.operateShizukuMode) },
            )
            OptionRow(
                selected = settings.operateBackgroundMode,
                title = "后台执行",
                summary = "使用 Shizuku 系统命令；可配合虚拟屏或本机小窗，虚拟屏暂不支持文本输入",
                onClick = { viewModel.setOperateMode(true, settings.operateShizukuMode) },
            )
            OptionRow(
                selected = settings.operateShizukuMode,
                title = "优先 Shizuku",
                summary = "系统命令优先，失败时回退无障碍",
                onClick = {
                    viewModel.setOperateMode(
                        settings.operateBackgroundMode,
                        !settings.operateShizukuMode,
                    )
                },
            )
            OperationSurfaceMode.entries.forEach { mode ->
                OptionRow(
                    selected = settings.operationSurfaceMode == mode,
                    title = mode.label,
                    summary = mode.description,
                    onClick = { viewModel.setOperationSurfaceMode(mode) },
                )
            }
            Text(
                text = "这里与操作空间共用同一份设置；修改后两处立即同步。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("智能体权限模式") {
            PermissionMode.entries.forEach { mode ->
                OptionRow(
                    selected = settings.permissionMode == mode,
                    title = mode.label,
                    summary = mode.description,
                    onClick = { viewModel.update { it.copy(permissionMode = mode) } },
                )
            }
            Text(
                text = "与智能体页共用；工具级权限在下方“操作工具授权”单独配置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("操作工具授权") {
            DeviceToolNames.all.forEach { tool ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deviceToolLabel(tool), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = tool,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ToolPermission.entries.forEach { permission ->
                        val current = settings.toolPermissions[tool] ?: ToolPermission.ALLOW
                        FilterChip(
                            selected = current == permission,
                            onClick = { viewModel.setToolPermission(tool, permission) },
                            label = { Text(permission.label) },
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
            Text(
                text = "工具权限与智能体页共用；操作工具仍需要上方运行通道就绪。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionSettingRow(
    title: String,
    summary: String,
    status: String,
    ready: Boolean,
    action: String,
    actionEnabled: Boolean = !ready,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$summary · $status",
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction, enabled = actionEnabled) { Text(action) }
    }
}

private fun deviceToolLabel(name: String): String = when (name) {
    DeviceToolNames.OPEN_APP -> "打开应用"
    DeviceToolNames.TAP -> "点击"
    DeviceToolNames.SWIPE -> "滑动"
    DeviceToolNames.BACK -> "返回"
    DeviceToolNames.HOME -> "回到桌面"
    DeviceToolNames.INPUT_TEXT -> "输入文本"
    DeviceToolNames.SCREENSHOT -> "截图"
    else -> name
}

@Composable
internal fun AboutPage() {
    val uriHandler = LocalUriHandler.current

    SettingsPage {
        AppIconHeader()
        SettingsCard("应用") {
            InfoRow("名称", "IRIS LocalAI")
            InfoRow("版本", "0.1.0")
            InfoRow("包名", "com.iris.localai")
            InfoRow("形态", "单模块应用")
        }
        SettingsCard("技术栈") {
            Text(
                text = "Kotlin · Jetpack Compose · Material 3 · 协程 · kotlinx.serialization\n" +
                    "推理、Agent、HTTP 三层均为可替换接口。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingsCard("参考项目") {
            Text(
                text = "工程结构与设置分层参照 IRIDIS（Apache-2.0）。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { uriHandler.openUri("https://github.com/WWRJ3321/IRIDIS") }) {
                Text("查看 IRIDIS 源码")
            }
        }
    }
}

@Composable
private fun AppIconHeader() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = "IRIS LocalAI 图标",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
    }
}

@Composable
internal fun PlaceholderPage(entry: SettingsEntry) {
    SettingsPage {
        SettingsCard {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(entry.title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Text(
                    text = "界面占位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        SettingsCard("计划接入") {
            Text(planFor(entry.id), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun planFor(id: String): String = when (id) {
    "character-card" -> "SillyTavern V2/V3 角色卡导入、开场白切换与绑定模型。"
    "lorebook" -> "关键词与正则触发、扫描深度、预算与递归控制。"
    "memory" -> "长期记忆分层、向量检索与滚动压缩。"
    "character-model" -> "按角色覆盖模型、采样参数与专属系统提示词。"
    "voice" -> "sherpa-onnx 离线识别与合成、音色切换与免提对话。"
    "scene" -> "场景预设、开场白与多角色编排。"
    "mcp" -> "MCP 客户端（HTTP / WebSocket）与工具注入。"
    "plugin" -> "插件清单、权限声明与启用状态。"
    "market" -> "模型与扩展的下载源、校验与更新。"
    "skill" -> "SKILL.md 安装、触发词与按需加载。"
    "code" -> "代码执行沙盒与工作区文件管理。"
    "notification" -> "生成完成、后台任务与定时提醒。"
    "interaction" -> "回车发送、长按菜单与手势操作。"
    "network" -> "代理、超时、镜像源与下载策略。"
    "integration" -> "外部 Intent、自动化触发与深链。"
    else -> "此页面已接入导航，具体能力待后续实现。"
}

