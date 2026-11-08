package com.iris.localai.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iris.localai.character.CharacterModelEntry
import com.iris.localai.character.PlazaCreator
import com.iris.localai.character.PlazaSite
import kotlinx.coroutines.delay

@Composable
internal fun CharacterPlazaSection(viewModel: CharacterPlazaViewModel = viewModel()) {
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    val creators by viewModel.creators.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val role by viewModel.role.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var searchSite by remember { mutableStateOf<PlazaSite?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message, error) {
        if (message != null || error != null) {
            delay(4000)
            viewModel.clearMessage()
            viewModel.clearError()
        }
    }

    SettingsCard("角色模型广场") {
        Text(
            text = "参照 MikuMikuAR 广场：远端配置可提供模之屋、Bowlroll、BOOTH、VRoid Hub 等站点与创作者名录；配置不可用时回退内置站点。广场不托管模型，安装前请遵守原作者的许可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::refresh,
                enabled = !loading,
                modifier = Modifier.weight(1f),
            ) { Text(if (loading) "更新中…" else "更新配置") }
            Button(
                onClick = { showImport = true },
                enabled = !importing,
                modifier = Modifier.weight(1f),
            ) { Text(if (importing) "导入中…" else "从链接导入") }
        }
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            text = "支持 ZIP（Live2D / MMD 模型包）、VRM、GLB、PMD/PMX；单文件上限 300 MB。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (models.isNotEmpty()) {
        SettingsCard("已安装角色模型") {
            models.forEach { model ->
                CharacterModelRow(
                    model = model,
                    active = model.id == activeId,
                    onUse = { viewModel.setActive(model.id) },
                )
            }
        }
    }

    SettingsCard("来源站点") {
        sites.groupBy { it.group }.forEach { (group, groupSites) ->
            Text(
                text = group,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            groupSites.forEach { site ->
                SiteRow(
                    site = site,
                    onOpen = { browserUrl = site.url },
                    onSearch = { if (site.searchUrl.isNotBlank()) searchSite = site else browserUrl = site.url },
                )
            }
        }
        if (creators.isEmpty() && !loading) {
            Text(
                text = "远端创作者名录暂不可用；仍可通过站点搜索或链接导入模型。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (creators.isNotEmpty()) {
        SettingsCard("创作者名录") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                RoleChip("全部", role == "all") { viewModel.onRoleChange("all") }
                RoleChip("官方", role == "official") { viewModel.onRoleChange("official") }
                RoleChip("创作者", role == "creator") { viewModel.onRoleChange("creator") }
                RoleChip("VUP", role == "vup") { viewModel.onRoleChange("vup") }
                RoleChip("OC", role == "oc") { viewModel.onRoleChange("oc") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("搜索创作者") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val siteLabels = sites.associate { it.id to it.label }
            val filtered = creators
                .asSequence()
                .filter { role == "all" || it.role == role }
                .filter {
                    query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true)
                }
                .take(80)
                .toList()
            filtered.forEach { creator ->
                CreatorRow(
                    creator = creator,
                    siteLabels = siteLabels,
                    onOpen = { creatorUrl(creator, sites)?.let { browserUrl = it } },
                )
            }
            if (filtered.isEmpty()) {
                Text(
                    text = "没有匹配的创作者。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (creators.size > filtered.size) {
                Text(
                    text = "仅显示前 ${filtered.size} 位创作者，可用搜索缩小范围。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showImport) {
        PlazaImportDialog(
            onDismiss = { showImport = false },
            onConfirm = { url, name ->
                showImport = false
                viewModel.importUrl(url, name)
            },
        )
    }

    searchSite?.let { site ->
        SiteSearchDialog(
            site = site,
            onDismiss = { searchSite = null },
            onSearch = { keyword ->
                searchSite = null
                val target = site.searchUrl.replace("{{q}}", Uri.encode(keyword))
                browserUrl = target
            },
        )
    }

    browserUrl?.let { target ->
        PlazaBrowserDialog(
            url = target,
            onDismiss = { browserUrl = null },
            onExternal = { openUrl(context, it) },
            onDownload = { downloadUrl ->
                browserUrl = null
                viewModel.importUrl(downloadUrl, "")
            },
        )
    }
}

@Composable
private fun CharacterModelRow(
    model: CharacterModelEntry,
    active: Boolean,
    onUse: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${model.type.label} · ${formatBytes(model.sizeBytes)}" + if (active) " · 当前使用" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!active) {
            TextButton(onClick = onUse) { Text("设为当前") }
        }
    }
}

@Composable
private fun SiteRow(
    site: PlazaSite,
    onOpen: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(site.icon.ifBlank { "🌐" }, style = MaterialTheme.typography.titleMedium)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(site.label, style = MaterialTheme.typography.bodyMedium)
            if (site.description.isNotBlank()) {
                Text(
                    text = site.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (site.searchUrl.isNotBlank()) {
            TextButton(onClick = onSearch) { Text("搜索") }
        }
        TextButton(onClick = onOpen) { Text("打开") }
    }
}

@Composable
private fun CreatorRow(
    creator: PlazaCreator,
    siteLabels: Map<String, String>,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Text(
                text = creator.name.take(1),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(creator.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    creator.description.takeIf { it.isNotBlank() },
                    creator.sites.mapNotNull { siteLabels[it] }.take(2).joinToString("/").takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(
            when (creator.role) {
                "official" -> "官方"
                "vup" -> "VUP"
                "oc" -> "OC"
                else -> "创作者"
            },
        )
    }
}

@Composable
private fun RoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun PlazaImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从链接导入角色模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("模型或 ZIP 地址") },
                    placeholder = { Text("https://…/model.vrm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "仅支持公网 HTTP/HTTPS。请确认模型许可允许下载与本地使用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url, name) }, enabled = url.isNotBlank()) { Text("下载并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SiteSearchDialog(
    site: PlazaSite,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索 ${site.label}") },
        text = {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSearch(keyword) }, enabled = keyword.isNotBlank()) { Text("在浏览器打开") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun creatorUrl(creator: PlazaCreator, sites: List<PlazaSite>): String? {
    val site = sites.firstOrNull { it.id in creator.sites && it.searchUrl.isNotBlank() }
    return when {
        site != null -> site.searchUrl.replace("{{q}}", Uri.encode(creator.name))
        creator.sites.isNotEmpty() -> sites.firstOrNull { it.id in creator.sites }?.url
        else -> sites.firstOrNull()?.url
    }
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // 没有可处理该链接的应用时保持当前页面。
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
