package com.iris.localai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.memory.DiaryEntry
import com.iris.localai.memory.KnowledgeDocument
import com.iris.localai.memory.MemoryItem
import com.iris.localai.memory.MemoryType
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MemoryPage(viewModel: MemoryViewModel = viewModel()) {
    val diary by viewModel.diary.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val knowledge by viewModel.knowledge.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var tab by remember { mutableStateOf(MemoryTab.DIARY) }
    var diaryDialog by remember { mutableStateOf<DiaryDialogState?>(null) }
    var memoryDialog by remember { mutableStateOf<MemoryDialogState?>(null) }
    var memoryDetail by remember { mutableStateOf<MemoryItem?>(null) }
    var knowledgeDialog by remember { mutableStateOf<KnowledgeDialogState?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var knowledgeQuery by remember { mutableStateOf("") }
    var memoryQuery by remember { mutableStateOf("") }
    var memoryFolder by remember { mutableStateOf<String?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > 4 * 1024 * 1024) null else String(bytes, Charsets.UTF_8)
                }
            }.getOrNull()
            if (text == null) {
                notice = "导入失败：仅支持 4 MB 以内的文本文件"
            } else {
                viewModel.importKnowledge(text, uri.lastPathSegment.orEmpty())
                notice = "已导入知识文档"
            }
        }
    }

    SettingsPage {
        SettingsCard("记忆与知识") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemoryTab.entries.forEach { item ->
                    FilterChip(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                    )
                }
            }
            Text(
                text = "日记 ${diary.size} · 记忆 ${memories.size} · 知识 ${knowledge.size}",
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
        }

        when (tab) {
            MemoryTab.DIARY -> {
                SettingsCard("日记") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { diaryDialog = DiaryDialogState.Create }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("写日记")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.generateMissingDiaries { count ->
                                    notice = if (count > 0) "已补生成 $count 篇日记" else "没有需要补生成的日记"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("生成缺失日记") }
                    }
                    if (diary.isEmpty()) {
                        Text(
                            text = "应用会根据打开过的日期自动整理当天对话；未打开应用的日子不会生成。日记不会进入常规提示词，只在你要求回忆时参与检索。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    diary.forEach { entry ->
                        DiaryRow(
                            entry = entry,
                            onEdit = { diaryDialog = DiaryDialogState.Edit(entry) },
                            onDelete = {
                                pendingDelete = PendingDelete("删除日记「${entry.title.ifBlank { entry.date }}」？") {
                                    viewModel.deleteDiary(entry.id)
                                }
                            },
                        )
                    }
                }
            }

            MemoryTab.MEMORY -> {
                val digest = viewModel.digestText()
                if (digest.isNotBlank()) {
                    SettingsCard("记忆摘要") {
                        Text(
                            text = digest,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "常规对话只注入这份摘要；需要精确回忆时，智能体会调用 memory 工具做详细向量检索。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                SettingsCard("记忆库") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { memoryDialog = MemoryDialogState.Create }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("添加记忆")
                        }
                        OutlinedButton(
                            onClick = {
                                notice = "正在整理最近对话…"
                                viewModel.autoCaptureRecent { count ->
                                    notice = if (count > 0) "已自动整理 $count 条记忆" else "最近对话没有提取到新记忆"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("整理最近对话") }
                    }
                    OutlinedTextField(
                        value = memoryQuery,
                        onValueChange = { memoryQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索记忆（标题、内容、标签、文件夹）") },
                        singleLine = true,
                    )
                    val folders = viewModel.folders()
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = memoryFolder == null,
                            onClick = { memoryFolder = null },
                            label = { Text("全部") },
                        )
                        folders.forEach { folder ->
                            FilterChip(
                                selected = memoryFolder == folder,
                                onClick = { memoryFolder = folder },
                                label = { Text(folder) },
                            )
                        }
                    }
                    val visible = viewModel.filterMemories(memoryQuery, memoryFolder)
                    if (memories.isEmpty()) {
                        Text(
                            text = "还没有长期记忆。事实与事件会进入摘要，并可在回忆时被详细检索。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (visible.isEmpty()) {
                        Text(
                            text = "没有匹配的记忆。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    visible.forEach { item ->
                        MemoryRow(
                            item = item,
                            onOpen = { memoryDetail = item },
                            onEnabled = { enabled -> viewModel.updateMemory(item.copy(enabled = enabled)) },
                            onEdit = { memoryDialog = MemoryDialogState.Edit(item) },
                            onDelete = {
                                pendingDelete = PendingDelete("删除这条记忆？") {
                                    viewModel.deleteMemory(item.id)
                                }
                            },
                        )
                    }
                }
            }

            MemoryTab.GRAPH -> {
                val graph = remember(memories, links, memoryFolder) {
                    viewModel.memoryGraph(memoryFolder)
                }
                SettingsCard("记忆关系图") {
                    Text(
                        text = "节点 ${graph.nodes.size} · 关系 ${graph.edges.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val count = viewModel.autoLinkAll()
                                notice = if (count > 0) "已补充 $count 条记忆关系" else "没有可补充的关系"
                            },
                        ) { Text("自动连边") }
                        OutlinedButton(
                            onClick = { linkDialog = true },
                        ) { Text("添加关系") }
                        OutlinedButton(
                            onClick = { memoryFolder = null },
                            modifier = Modifier.weight(1f),
                        ) { Text("显示全部") }
                    }
                    MemoryGraphView(
                        graph = graph,
                        selectedNodeId = memoryDetail?.id,
                        onNodeClick = { id ->
                            memoryDetail = memories.firstOrNull { it.id == id }
                        },
                    )
                    Text(
                        text = "拖动可平移，双指缩放；点击节点查看记忆详情。实线为同文件夹关系，虚线为跨文件夹关系。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsCard("关系列表") {
                    val titleById = memories.associate {
                        it.id to it.title.ifBlank { it.content.take(16) }
                    }
                    if (graph.edges.isEmpty()) {
                        Text(
                            text = "还没有关系。添加记忆时会按共享标签或同文件夹自动连边，也可点“自动连边”补全。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    graph.edges.take(16).forEach { link ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${titleById[link.sourceId] ?: "?"} → ${titleById[link.targetId] ?: "?"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${link.type} · 权重 ${String.format("%.2f", link.weight)}" +
                                        if (link.description.isBlank()) "" else " · ${link.description}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { viewModel.deleteLink(link.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除关系")
                            }
                        }
                    }
                }
            }

            MemoryTab.KNOWLEDGE -> {
                SettingsCard("知识库") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { importer.launch("*/*") },
                            modifier = Modifier.weight(1f),
                        ) { Text("导入文档") }
                        Button(
                            onClick = { knowledgeDialog = KnowledgeDialogState.Create },
                            modifier = Modifier.weight(1f),
                        ) { Text("添加笔记") }
                    }
                    OutlinedTextField(
                        value = knowledgeQuery,
                        onValueChange = { knowledgeQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索知识库") },
                        singleLine = true,
                    )
                    val visible = if (knowledgeQuery.isBlank()) {
                        knowledge
                    } else {
                        knowledge.filter { doc ->
                            doc.title.contains(knowledgeQuery, true) ||
                                doc.content.contains(knowledgeQuery, true) ||
                                doc.tags.any { it.contains(knowledgeQuery, true) }
                        }
                    }
                    if (visible.isEmpty()) {
                        Text(
                            text = "没有匹配的知识文档。支持导入 txt/md/json 等纯文本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    visible.forEach { doc ->
                        KnowledgeRow(
                            doc = doc,
                            onEnabled = { enabled ->
                                viewModel.updateKnowledge(doc.copy(enabled = enabled))
                            },
                            onEdit = { knowledgeDialog = KnowledgeDialogState.Edit(doc) },
                            onDelete = {
                                pendingDelete = PendingDelete("删除知识文档「${doc.title}」？") {
                                    viewModel.deleteKnowledge(doc.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    when (val state = diaryDialog) {
        null -> Unit
        DiaryDialogState.Create -> DiaryDialog(
            existing = null,
            onDismiss = { diaryDialog = null },
            onSave = { entry ->
                viewModel.addDiary(entry)
                diaryDialog = null
            },
        )
        is DiaryDialogState.Edit -> DiaryDialog(
            existing = state.entry,
            onDismiss = { diaryDialog = null },
            onSave = { entry ->
                viewModel.updateDiary(entry)
                diaryDialog = null
            },
        )
    }

    when (val state = memoryDialog) {
        null -> Unit
        MemoryDialogState.Create -> MemoryDialog(
            existing = null,
            onDismiss = { memoryDialog = null },
            onSave = { item ->
                viewModel.addMemory(item)
                memoryDialog = null
            },
        )
        is MemoryDialogState.Edit -> MemoryDialog(
            existing = state.item,
            onDismiss = { memoryDialog = null },
            onSave = { item ->
                viewModel.updateMemory(item)
                memoryDialog = null
            },
        )
    }

    memoryDetail?.let { item ->
        MemoryDetailDialog(
            item = item,
            onDismiss = { memoryDetail = null },
            onEdit = {
                memoryDetail = null
                memoryDialog = MemoryDialogState.Edit(item)
            },
        )
    }

    if (linkDialog) {
        MemoryLinkDialog(
            memories = memories,
            onDismiss = { linkDialog = false },
            onSave = { sourceId, targetId, type, weight, description ->
                val link = viewModel.linkMemories(sourceId, targetId, type, weight, description)
                notice = if (link != null) "已添加记忆关系" else "无法添加关系"
                linkDialog = false
            },
        )
    }

    when (val state = knowledgeDialog) {
        null -> Unit
        KnowledgeDialogState.Create -> KnowledgeDialog(
            existing = null,
            onDismiss = { knowledgeDialog = null },
            onSave = { doc ->
                viewModel.addKnowledge(doc)
                knowledgeDialog = null
            },
        )
        is KnowledgeDialogState.Edit -> KnowledgeDialog(
            existing = state.doc,
            onDismiss = { knowledgeDialog = null },
            onSave = { doc ->
                viewModel.updateKnowledge(doc)
                knowledgeDialog = null
            },
        )
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("确认删除？") },
            text = { Text(pending.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending.action()
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${entry.date} · ${entry.title.ifBlank { "日记" }}" +
                        if (entry.autoGenerated) " · 自动" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.content.take(80),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun MemoryRow(
    item: MemoryItem,
    onOpen: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
            ) {
                Text(
                    text = item.title.ifBlank { item.content.take(24) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.type.label} · 重要度 ${item.importance}" +
                        if (item.folder.isBlank()) "" else " · ${item.folder}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.tags.isNotEmpty()) {
                    Text(
                        text = item.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = item.enabled, onCheckedChange = onEnabled)
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun KnowledgeRow(
    doc: KnowledgeDocument,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = doc.content.take(90),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (doc.source.isNotBlank()) {
                    Text(
                        text = doc.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(checked = doc.enabled, onCheckedChange = onEnabled)
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun DiaryDialog(
    existing: DiaryEntry?,
    onDismiss: () -> Unit,
    onSave: (DiaryEntry) -> Unit,
) {
    var date by remember(existing?.id) { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var mood by remember(existing?.id) { mutableStateOf(existing?.mood.orEmpty()) }
    var tags by remember(existing?.id) { mutableStateOf(existing?.tags?.joinToString(", ").orEmpty()) }
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }

    EditorDialog(
        title = if (existing == null) "写日记" else "编辑日记",
        onDismiss = onDismiss,
        onSave = {
            val entry = (existing ?: DiaryEntry()).copy(
                date = date.trim().ifBlank { LocalDate.now().toString() },
                title = title.trim(),
                mood = mood.trim(),
                tags = splitTags(tags),
                content = content,
            )
            onSave(entry)
        },
        saveEnabled = content.isNotBlank(),
    ) {
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("日期（yyyy-MM-dd）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题") },
            singleLine = true,
        )
        OutlinedTextField(
            value = mood,
            onValueChange = { mood = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("心情") },
            singleLine = true,
        )
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标签（逗号分隔）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("内容") },
            minLines = 5,
            maxLines = 10,
        )
    }
}

@Composable
private fun MemoryDetailDialog(
    item: MemoryItem,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val time = remember(item.updatedAt) {
        if (item.updatedAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt))
        } else {
            "未知"
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.title.ifBlank { item.content.take(24) },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "${item.type.label} · 重要度 ${item.importance}" +
                        if (item.folder.isBlank()) "" else " · ${item.folder}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (item.tags.isNotEmpty()) {
                    Text(
                        text = "标签：${item.tags.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "来源：${item.source.ifBlank { "manual" }} · 更新：$time",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(item.content, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onEdit) { Text("编辑") }
                }
            }
        }
    }
}

@Composable
private fun MemoryLinkDialog(
    memories: List<MemoryItem>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Float, String) -> Unit,
) {
    if (memories.size < 2) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("无法创建关系") },
            text = { Text("至少需要两条记忆才能建立关系。") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("知道了") }
            },
        )
        return
    }
    val initialSource = memories.first().id
    val initialTarget = memories.firstOrNull { it.id != initialSource }?.id.orEmpty()
    var sourceId by remember { mutableStateOf(initialSource) }
    var targetId by remember { mutableStateOf(initialTarget) }
    var type by remember { mutableStateOf("RELATED") }
    var weight by remember { mutableFloatStateOf(0.7f) }
    var description by remember { mutableStateOf("") }

    EditorDialog(
        title = "添加记忆关系",
        onDismiss = onDismiss,
        onSave = {
            onSave(sourceId, targetId, type.trim(), weight, description.trim())
        },
        saveEnabled = sourceId.isNotBlank() && targetId.isNotBlank() &&
            sourceId != targetId && type.isNotBlank(),
    ) {
        MemoryPicker("源记忆", memories, sourceId) { sourceId = it }
        MemoryPicker("目标记忆", memories, targetId) { targetId = it }
        OutlinedTextField(
            value = type,
            onValueChange = { type = it.take(48) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("关系类型（如 RELATED / PART_OF / CAUSES）") },
            singleLine = true,
        )
        SliderRow(
            title = "关系权重",
            value = weight,
            range = 0f..1f,
            steps = 9,
            format = { String.format("%.1f", it) },
            onCommit = { weight = it },
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(240) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("关系说明（可选）") },
            minLines = 2,
            maxLines = 4,
        )
    }
}

@Composable
private fun MemoryPicker(
    label: String,
    memories: List<MemoryItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = memories.firstOrNull { it.id == selectedId }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            AssistChip(
                onClick = { expanded = true },
                label = {
                    Text(
                        text = selected?.title?.ifBlank { selected.content.take(20) } ?: "选择记忆",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                memories.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item.title.ifBlank { item.content.take(24) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onSelect(item.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryDialog(
    existing: MemoryItem?,
    onDismiss: () -> Unit,
    onSave: (MemoryItem) -> Unit,
) {
    var type by remember(existing?.id) { mutableStateOf(existing?.type ?: MemoryType.FACT) }
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var folder by remember(existing?.id) { mutableStateOf(existing?.folder.orEmpty()) }
    var source by remember(existing?.id) { mutableStateOf(existing?.source ?: "manual") }
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }
    var tags by remember(existing?.id) { mutableStateOf(existing?.tags?.joinToString(", ").orEmpty()) }
    var importance by remember(existing?.id) { mutableFloatStateOf((existing?.importance ?: 3).toFloat()) }
    var enabled by remember(existing?.id) { mutableStateOf(existing?.enabled ?: true) }

    EditorDialog(
        title = if (existing == null) "添加记忆" else "编辑记忆",
        onDismiss = onDismiss,
        onSave = {
            val item = (existing ?: MemoryItem()).copy(
                type = type,
                title = title.trim(),
                folder = folder.trim(),
                source = source.trim().ifBlank { "manual" },
                content = content,
                tags = splitTags(tags),
                importance = importance.toInt(),
                enabled = enabled,
            )
            onSave(item)
        },
        saveEnabled = content.isNotBlank(),
    ) {
        MemoryTypePicker(selected = type, onSelect = { type = it })
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("文件夹（如 生活/健康）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("记忆内容") },
            minLines = 3,
            maxLines = 6,
        )
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标签（逗号分隔）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("来源") },
            singleLine = true,
        )
        SliderRow(
            title = "重要度",
            value = importance,
            range = 1f..5f,
            steps = 3,
            format = { it.toInt().toString() },
            onCommit = { importance = it },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
    }
}

@Composable
private fun KnowledgeDialog(
    existing: KnowledgeDocument?,
    onDismiss: () -> Unit,
    onSave: (KnowledgeDocument) -> Unit,
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var source by remember(existing?.id) { mutableStateOf(existing?.source.orEmpty()) }
    var tags by remember(existing?.id) { mutableStateOf(existing?.tags?.joinToString(", ").orEmpty()) }
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }

    EditorDialog(
        title = if (existing == null) "添加知识" else "编辑知识",
        onDismiss = onDismiss,
        onSave = {
            val doc = (existing ?: KnowledgeDocument()).copy(
                title = title.trim().ifBlank { "未命名知识" },
                source = source.trim(),
                tags = splitTags(tags),
                content = content,
            )
            onSave(doc)
        },
        saveEnabled = content.isNotBlank(),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题") },
            singleLine = true,
        )
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("来源") },
            singleLine = true,
        )
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标签（逗号分隔）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("内容") },
            minLines = 6,
            maxLines = 12,
        )
    }
}

@Composable
private fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSave, enabled = saveEnabled) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun MemoryTypePicker(selected: MemoryType, onSelect: (MemoryType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("类型", modifier = Modifier.weight(1f))
        Box {
            AssistChip(onClick = { expanded = true }, label = { Text(selected.label) })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                MemoryType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = {
                            onSelect(type)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private enum class MemoryTab(val label: String) {
    DIARY("日记"),
    MEMORY("记忆库"),
    GRAPH("关系图"),
    KNOWLEDGE("知识库"),
}

private sealed interface DiaryDialogState {
    data object Create : DiaryDialogState
    data class Edit(val entry: DiaryEntry) : DiaryDialogState
}

private sealed interface MemoryDialogState {
    data object Create : MemoryDialogState
    data class Edit(val item: MemoryItem) : MemoryDialogState
}

private sealed interface KnowledgeDialogState {
    data object Create : KnowledgeDialogState
    data class Edit(val doc: KnowledgeDocument) : KnowledgeDialogState
}

private data class PendingDelete(val title: String, val action: () -> Unit)

private fun splitTags(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
