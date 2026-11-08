package com.iris.localai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.lorebook.Lorebook
import com.iris.localai.lorebook.LorebookEntry
import com.iris.localai.lorebook.LorebookPosition
import com.iris.localai.lorebook.SelectiveLogic
import androidx.compose.foundation.text.KeyboardOptions
import java.util.UUID

@Composable
internal fun WorldBookPage(viewModel: WorldBookViewModel = viewModel()) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    var bookDialog by remember { mutableStateOf<BookDialog?>(null) }
    var entryEditor by remember { mutableStateOf<EntryEdit?>(null) }
    var pendingDeleteBook by remember { mutableStateOf<Lorebook?>(null) }
    var pendingDeleteEntry by remember { mutableStateOf<Pair<Lorebook, LorebookEntry>?>(null) }
    var exportPayload by remember { mutableStateOf<BookExport?>(null) }

    val selected = books.firstOrNull { it.id == selectedId } ?: books.firstOrNull()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (text == null) {
                viewModel.clearError()
            } else {
                viewModel.importJson(text, uri.lastPathSegment.orEmpty())
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = exportPayload
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.bytes) }
            }
        }
        exportPayload = null
    }

    SettingsPage {
        SettingsCard("世界书") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { bookDialog = BookDialog.Create },
                    modifier = Modifier.weight(1f),
                ) { Text("新建世界书") }
                Button(
                    onClick = { importer.launch("*/*") },
                    modifier = Modifier.weight(1f),
                ) { Text("导入世界书") }
            }
            Text(
                text = "支持 SillyTavern world info JSON（entries 对象或数组）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message != null) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (error != null) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (books.isEmpty()) {
            Text(
                text = "还没有世界书，先新建或导入一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        books.forEach { book ->
            BookRow(
                book = book,
                selected = book.id == selected?.id,
                onClick = { selectedId = book.id },
                onEnabled = { on -> viewModel.update(book.copy(globalEnabled = on)) },
                onRename = { bookDialog = BookDialog.Rename(book) },
                onExport = {
                    val bytes = viewModel.exportJson(book.id)
                    if (bytes == null) {
                        viewModel.clearError()
                    } else {
                        val payload = BookExport(safeName(book.name) + ".json", bytes)
                        exportPayload = payload
                        exporter.launch(payload.name)
                    }
                },
                onDelete = { pendingDeleteBook = book },
            )
            Spacer(Modifier.height(8.dp))
        }

        selected?.let { book ->
            SettingsCard("条目（${book.entries.size}）") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = book.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { entryEditor = EntryEdit(book.id, null) },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("添加条目")
                    }
                }

                if (book.entries.isEmpty()) {
                    Text(
                        text = "暂无条目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                book.entries.forEach { entry ->
                    EntryRow(
                        entry = entry,
                        onEnabled = { on ->
                            viewModel.update(
                                book.copy(
                                    entries = book.entries.map {
                                        if (it.id == entry.id) it.copy(enabled = on) else it
                                    },
                                ),
                            )
                        },
                        onEdit = { entryEditor = EntryEdit(book.id, entry) },
                        onDelete = { pendingDeleteEntry = book to entry },
                    )
                }
            }
        }

        SettingsCard("全局设置") {
            WorldNumberField(
                label = "扫描深度（最近消息数）",
                value = settings.scanDepth,
                onCommit = { value -> viewModel.updateSettings { it.copy(scanDepth = value) } },
            )
            WorldNumberField(
                label = "Token 预算",
                value = settings.tokenBudget,
                onCommit = { value -> viewModel.updateSettings { it.copy(tokenBudget = value) } },
            )
            WorldNumberField(
                label = "最少激活条目",
                value = settings.minActivations,
                onCommit = { value -> viewModel.updateSettings { it.copy(minActivations = value) } },
            )
            WorldNumberField(
                label = "最大递归深度",
                value = settings.maxRecursion,
                onCommit = { value -> viewModel.updateSettings { it.copy(maxRecursion = value) } },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("包含常驻条目", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "constant = true 的条目始终注入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.includeConstant,
                    onCheckedChange = { on ->
                        viewModel.updateSettings { it.copy(includeConstant = on) }
                    },
                )
            }
        }
    }

    when (val dialog = bookDialog) {
        null -> Unit
        BookDialog.Create -> NameOnlyDialog(
            title = "新建世界书",
            initial = "新世界书",
            onDismiss = { bookDialog = null },
            onConfirm = { name ->
                val book = viewModel.create(name)
                selectedId = book.id
                bookDialog = null
            },
        )
        is BookDialog.Rename -> NameOnlyDialog(
            title = "重命名世界书",
            initial = dialog.book.name,
            onDismiss = { bookDialog = null },
            onConfirm = { name ->
                viewModel.update(dialog.book.copy(name = name))
                bookDialog = null
            },
        )
    }

    entryEditor?.let { edit ->
        val book = books.firstOrNull { it.id == edit.bookId }
        if (book != null) {
            EntryEditorDialog(
                existing = edit.entry,
                onDismiss = { entryEditor = null },
                onSave = { entry ->
                    val entries = if (edit.entry == null) {
                        book.entries + entry
                    } else {
                        book.entries.map { if (it.id == edit.entry.id) entry else it }
                    }
                    viewModel.update(book.copy(entries = entries))
                    entryEditor = null
                },
            )
        }
    }

    pendingDeleteBook?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDeleteBook = null },
            title = { Text("删除世界书？") },
            text = { Text("「${book.name}」及 ${book.entries.size} 个条目会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(book.id)
                        if (selectedId == book.id) selectedId = null
                        pendingDeleteBook = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBook = null }) { Text("取消") }
            },
        )
    }

    pendingDeleteEntry?.let { (book, entry) ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text("删除条目？") },
            text = { Text("「${entry.title.ifBlank { entry.keys.firstOrNull() ?: "未命名" }}」会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.update(
                            book.copy(entries = book.entries.filterNot { it.id == entry.id }),
                        )
                        pendingDeleteEntry = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntry = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BookRow(
    book: Lorebook,
    selected: Boolean,
    onClick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(book.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${book.entries.size} 个条目" +
                        if (book.globalEnabled) "" else " · 已停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = book.globalEnabled, onCheckedChange = onEnabled)
            IconButton(onClick = onRename) {
                Icon(Icons.Outlined.Edit, contentDescription = "重命名")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("导出 JSON") },
                        onClick = {
                            menu = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: LorebookEntry,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.keys.firstOrNull() ?: "未命名条目" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (entry.constant) append("常驻 · ")
                    if (entry.keys.isNotEmpty()) append(entry.keys.joinToString(" / "))
                    if (entry.keys.isEmpty()) append("无关键词")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = entry.enabled, onCheckedChange = onEnabled)
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除")
        }
    }
}

@Composable
private fun EntryEditorDialog(
    existing: LorebookEntry?,
    onDismiss: () -> Unit,
    onSave: (LorebookEntry) -> Unit,
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var keys by remember(existing?.id) { mutableStateOf(existing?.keys?.joinToString(", ").orEmpty()) }
    var secondaryKeys by remember(existing?.id) {
        mutableStateOf(existing?.secondaryKeys?.joinToString(", ").orEmpty())
    }
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }
    var enabled by remember(existing?.id) { mutableStateOf(existing?.enabled ?: true) }
    var constant by remember(existing?.id) { mutableStateOf(existing?.constant ?: false) }
    var selective by remember(existing?.id) { mutableStateOf(existing?.selective ?: false) }
    var logic by remember(existing?.id) { mutableStateOf(existing?.logic ?: SelectiveLogic.AND_ANY) }
    var matchWholeWords by remember(existing?.id) {
        mutableStateOf(existing?.matchWholeWords ?: false)
    }
    var caseSensitive by remember(existing?.id) { mutableStateOf(existing?.caseSensitive ?: false) }
    var useRegex by remember(existing?.id) { mutableStateOf(existing?.useRegex ?: false) }
    var scanDepth by remember(existing?.id) { mutableStateOf(existing?.scanDepth) }
    var probability by remember(existing?.id) { mutableStateOf(existing?.probability ?: 100) }
    var order by remember(existing?.id) { mutableStateOf(existing?.order ?: 100) }
    var group by remember(existing?.id) { mutableStateOf(existing?.group.orEmpty()) }
    var position by remember(existing?.id) {
        mutableStateOf(existing?.position ?: LorebookPosition.AFTER_CHAR)
    }
    var depth by remember(existing?.id) { mutableStateOf(existing?.depth ?: 4) }
    var sticky by remember(existing?.id) { mutableStateOf(existing?.sticky ?: 0) }
    var cooldown by remember(existing?.id) { mutableStateOf(existing?.cooldown ?: 0) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (existing == null) "新建世界书条目" else "编辑世界书条目",
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题 / 备注") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("主关键词（逗号分隔）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = secondaryKeys,
                    onValueChange = { secondaryKeys = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("二级关键词（逗号分隔）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内容") },
                    minLines = 4,
                    maxLines = 8,
                )

                ToggleEditor("启用", enabled) { enabled = it }
                ToggleEditor("常驻激活（无需关键词）", constant) { constant = it }
                ToggleEditor("启用二级关键词逻辑", selective) { selective = it }
                if (selective) {
                    DropdownEditor(
                        label = "二级逻辑",
                        selected = logic,
                        options = SelectiveLogic.entries,
                        optionLabel = { it.label },
                    ) { logic = it }
                }
                ToggleEditor("整词匹配", matchWholeWords) { matchWholeWords = it }
                ToggleEditor("区分大小写", caseSensitive) { caseSensitive = it }
                ToggleEditor("关键词按正则解析", useRegex) { useRegex = it }

                OutlinedTextField(
                    value = scanDepth?.toString().orEmpty(),
                    onValueChange = { text ->
                        if (text.isBlank()) {
                            scanDepth = null
                        } else {
                            text.toIntOrNull()?.let { scanDepth = it }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("条目扫描深度（留空 = 跟随全局）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                WorldNumberField(
                    label = "触发概率（0-100）",
                    value = probability,
                    onCommit = { probability = it.coerceIn(0, 100) },
                )
                WorldNumberField(
                    label = "顺序",
                    value = order,
                    onCommit = { order = it },
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分组") },
                    singleLine = true,
                )
                DropdownEditor(
                    label = "插入位置",
                    selected = position,
                    options = LorebookPosition.entries,
                    optionLabel = { it.label },
                ) { position = it }
                WorldNumberField(
                    label = "插入深度",
                    value = depth,
                    onCommit = { depth = it },
                )
                WorldNumberField(
                    label = "粘滞轮数",
                    value = sticky,
                    onCommit = { sticky = it },
                )
                WorldNumberField(
                    label = "冷却轮数",
                    value = cooldown,
                    onCommit = { cooldown = it },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val entry = (existing ?: LorebookEntry()).copy(
                                title = title.trim(),
                                keys = splitKeys(keys),
                                secondaryKeys = splitKeys(secondaryKeys),
                                content = content,
                                enabled = enabled,
                                constant = constant,
                                selective = selective,
                                logic = logic,
                                matchWholeWords = matchWholeWords,
                                caseSensitive = caseSensitive,
                                useRegex = useRegex,
                                scanDepth = scanDepth,
                                probability = probability,
                                order = order,
                                group = group.trim(),
                                position = position,
                                depth = depth,
                                sticky = sticky,
                                cooldown = cooldown,
                            )
                            onSave(entry)
                        },
                        enabled = content.isNotBlank() || keys.isNotBlank(),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ToggleEditor(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> DropdownEditor(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            AssistChip(
                onClick = { expanded = true },
                label = { Text(optionLabel(selected)) },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorldNumberField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 7 && input.all { it.isDigit() }) {
                text = input
                input.toIntOrNull()?.let(onCommit)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun NameOnlyDialog(
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

private sealed interface BookDialog {
    data object Create : BookDialog
    data class Rename(val book: Lorebook) : BookDialog
}

private data class EntryEdit(val bookId: String, val entry: LorebookEntry?)

private data class BookExport(val name: String, val bytes: ByteArray)

private fun splitKeys(text: String): List<String> =
    text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun safeName(name: String): String = name.trim()
    .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
    .joinToString("")
    .ifBlank { "worldbook" }
