package com.iris.localai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.localai.agent.AgentStep
import com.iris.localai.agent.AttachmentKind
import com.iris.localai.agent.ChatMessage
import com.iris.localai.agent.ChatRole
import com.iris.localai.agent.StepStatus
import com.iris.localai.plugin.PluginHookDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale

@Composable
internal fun WorkMessageList(
    messages: List<ChatMessage>,
    running: Boolean,
    pluginMenuItems: List<PluginHookDescriptor> = emptyList(),
    onPluginMenu: (PluginHookDescriptor, ChatMessage, Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val signature = messages.lastOrNull()?.let { "${it.id}:${it.text.length}:${it.steps.size}" }.orEmpty()
    val lastIndex = messages.lastIndex
    val tailIsUser = messages.lastOrNull()?.role == ChatRole.USER

    val followTail by remember(messages.size) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.isEmpty() || visible.last().index >= lastIndex - 1
        }
    }

    LaunchedEffect(signature) {
        // 末尾放了一个占位项，滚到它等于贴住内容底部，长回答不会被顶到开头。
        if (messages.isNotEmpty() && (followTail || tailIsUser)) listState.scrollToItem(messages.size)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
            val streaming = running && message.id == messages.last().id
            if (message.role == ChatRole.USER) {
                WorkUserBubble(message, pluginMenuItems, onPluginMenu, index)
            } else {
                WorkAssistantMessage(message, streaming, pluginMenuItems, onPluginMenu, index)
            }
        }
        item(key = "work-bottom-anchor") {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkUserBubble(
    message: ChatMessage,
    pluginMenuItems: List<PluginHookDescriptor>,
    onPluginMenu: (PluginHookDescriptor, ChatMessage, Int) -> Unit,
    index: Int,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 5.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = if (pluginMenuItems.isNotEmpty()) {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { menuOpen = true },
                )
            } else {
                Modifier
            },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                if (message.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        )
                    }
                }
                message.attachments.forEachIndexed { index, attachment ->
                    if (message.text.isNotBlank() || index > 0) {
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (attachment.kind == AttachmentKind.IMAGE) {
                                Icons.Outlined.Image
                            } else {
                                Icons.Outlined.AttachFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = attachment.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        }
        PluginMessageMenu(
            expanded = menuOpen,
            items = pluginMenuItems,
            onDismiss = { menuOpen = false },
            onSelect = { item ->
                menuOpen = false
                onPluginMenu(item, message, index)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkAssistantMessage(
    message: ChatMessage,
    streaming: Boolean,
    pluginMenuItems: List<PluginHookDescriptor>,
    onPluginMenu: (PluginHookDescriptor, ChatMessage, Int) -> Unit,
    index: Int,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    Column(
        modifier = if (pluginMenuItems.isNotEmpty()) {
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = "IRIS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            if (streaming) {
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "生成中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (message.reasoning.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            WorkReasoningCard(
                text = message.reasoning,
                thinking = streaming && message.text.isBlank(),
            )
        }

        if (message.steps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.steps.forEach { step -> WorkStepCard(step) }
            }
        }

        if (message.text.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            WorkMarkdown(
                text = message.text,
                modifier = Modifier.fillMaxWidth(),
                streaming = streaming,
            )
        } else if (streaming) {
            Spacer(Modifier.height(10.dp))
            TypingDots()
        }
    }
        PluginMessageMenu(
            expanded = menuOpen,
            items = pluginMenuItems,
            onDismiss = { menuOpen = false },
            onSelect = { item ->
                menuOpen = false
                onPluginMenu(item, message, index)
            },
        )
    }
}

@Composable
private fun PluginMessageMenu(
    expanded: Boolean,
    items: List<PluginHookDescriptor>,
    onDismiss: () -> Unit,
    onSelect: (PluginHookDescriptor) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.title.ifBlank { item.id }) },
                onClick = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun WorkReasoningCard(text: String, thinking: Boolean) {
    var expanded by remember(thinking) { mutableStateOf(thinking) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (thinking) "思考中…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起思考" else "展开思考",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = text.trim(),
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "work-typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        CircleShape,
                    ),
            )
        }
    }
}

private data class ToolMeta(val label: String, val icon: ImageVector)

private fun toolMeta(name: String): ToolMeta = when {
    name == "shell" -> ToolMeta("执行命令", Icons.Outlined.Terminal)
    name == "workspace" -> ToolMeta("工作区文件", Icons.Outlined.FolderOpen)
    name == "read_file" -> ToolMeta("读取文件", Icons.AutoMirrored.Outlined.Article)
    name == "write_file" -> ToolMeta("写入文件", Icons.Outlined.Save)
    name == "edit_file" -> ToolMeta("修改文件", Icons.Outlined.Edit)
    name == "list_files" -> ToolMeta("浏览目录", Icons.Outlined.FolderOpen)
    name == "search_files" -> ToolMeta("搜索文件", Icons.Outlined.Search)
    name == "fetch_url" -> ToolMeta("抓取网页", Icons.Outlined.Language)
    name == "memory" -> ToolMeta("长期记忆", Icons.Outlined.Memory)
    name == "use_skill" -> ToolMeta("读取技能", Icons.Outlined.Extension)
    name == "current_time" -> ToolMeta("当前时间", Icons.Outlined.Schedule)
    name == "device_info" -> ToolMeta("设备信息", Icons.Outlined.Smartphone)
    name == "random_pick" -> ToolMeta("随机选择", Icons.Outlined.Casino)
    name.startsWith("mcp") || name.contains("__") -> ToolMeta("MCP 工具", Icons.Outlined.Hub)
    else -> ToolMeta(name, Icons.Outlined.Build)
}

@Composable
internal fun WorkStepCard(step: AgentStep) {
    val context = LocalContext.current
    val meta = toolMeta(step.tool)
    val ok = step.status == StepStatus.OK
    var expanded by remember(step) { mutableStateOf(!ok) }
    var copied by remember(step) { mutableStateOf(false) }
    val accent = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = meta.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = meta.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = if (ok) "完成" else "失败",
                            tint = accent,
                            modifier = Modifier.size(13.dp),
                        )
                        val duration = formatDuration(step.durationMs)
                        if (duration.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = duration,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val summary = argumentSummary(step.tool, step.arguments)
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SectionLabel("参数")
                    CodeSurface(if (step.arguments.isBlank()) "{}" else prettyArguments(step.arguments))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("输出")
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                copyToClipboard(context, step.result)
                                copied = true
                            },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                contentDescription = "复制输出",
                                tint = if (copied) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    CodeSurface(step.result.ifBlank { "（无输出）" })
                }
            }
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CodeSurface(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun prettyArguments(raw: String): String = runCatching {
    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
}.getOrDefault(raw)

private fun argumentSummary(tool: String, raw: String): String {
    val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return raw.take(80)
    val text = when (tool) {
        "shell" -> obj.string("command")
        "workspace" -> listOf(obj.string("op"), obj.string("path"))
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        "read_file", "write_file", "edit_file" -> obj.string("path")
        "list_files" -> obj.string("path").ifBlank { "/" }
        "search_files" -> obj.string("query")
        "fetch_url" -> obj.string("url")
        "memory" -> listOf(obj.string("op"), obj.string("query").ifBlank { obj.string("content") })
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        "use_skill" -> obj.string("name")
        "current_time" -> obj.string("timezone")
        "device_info" -> obj.string("field")
        else -> obj.entries.take(2).joinToString(" · ") { (key, value) ->
            "$key=${(value as? JsonPrimitive)?.contentOrNull ?: value}"
        }
    }
    return text.ifBlank { raw }.take(80)
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> ""
    ms < 1000L -> "${ms}ms"
    ms < 60_000L -> String.format(Locale.US, "%.1fs", ms / 1000f)
    else -> "${ms / 60_000L}m${(ms % 60_000L) / 1000L}s"
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("IRIS", text))
}
