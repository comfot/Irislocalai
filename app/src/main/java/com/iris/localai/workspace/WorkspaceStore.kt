package com.iris.localai.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

data class Workspace(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uri: String = "",
    val path: String = "",
    val appFolder: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 工作区是真实文件夹：
 * - SAF 文件夹通过系统文件管理器选择，持久化 URI 权限；
 * - 容器内工作区创建在 `filesDir/workspaces/<name>`。
 * 删除条目不会删除用户文件夹内容。
 */
class WorkspaceStore(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "workspaces").apply { mkdirs() }

    private val _workspaces = MutableStateFlow(load())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _activeId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> _workspaces.value.any { it.id == id } }
            ?: _workspaces.value.firstOrNull()?.id.orEmpty(),
    )
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    init {
        if (_workspaces.value.isEmpty()) {
            ensureDefaultWorkspace()
        }
    }

    private fun ensureDefaultWorkspace() {
        val folder = File(root, "default").apply { mkdirs() }
        val workspace = Workspace(
            name = "默认工作区",
            path = folder.absolutePath,
            appFolder = true,
        )
        _workspaces.value = listOf(workspace)
        persist()
        setActive(workspace.id)
    }

    fun active(): Workspace? =
        _workspaces.value.firstOrNull { it.id == _activeId.value } ?: _workspaces.value.firstOrNull()

    fun setActive(id: String) {
        if (_workspaces.value.none { it.id == id }) return
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun importTree(uri: Uri): Result<Workspace> = runCatching {
        val existing = _workspaces.value.firstOrNull { it.uri == uri.toString() }
        if (existing != null) {
            setActive(existing.id)
            return@runCatching existing
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrElse { uri.lastPathSegment.orEmpty() }
        val name = documentId.substringAfterLast('/').substringAfter(':').ifBlank { "所选文件夹" }
        val workspace = Workspace(
            name = name,
            uri = uri.toString(),
            path = documentId.substringAfter(':'),
            appFolder = false,
        )
        add(workspace)
        workspace
    }

    fun createAppFolder(name: String): Workspace {
        val clean = name.trim().ifBlank { "新工作区" }
        val base = slug(clean)
        var folder = File(root, base)
        var index = 1
        while (
            folder.exists() &&
            (!folder.isDirectory ||
                folder.listFiles()?.isNotEmpty() == true ||
                _workspaces.value.any { it.path == folder.absolutePath })
        ) {
            folder = File(root, "$base-$index")
            index++
        }
        require(folder.mkdirs() || folder.isDirectory) { "无法创建工作区目录：${folder.absolutePath}" }
        val workspace = Workspace(
            name = clean,
            path = folder.absolutePath,
            appFolder = true,
        )
        add(workspace)
        return workspace
    }

    fun rename(id: String, name: String) {
        _workspaces.update { list ->
            list.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        }
        persist()
    }

    /** 只移除工作区条目，不删除用户文件夹。 */
    fun remove(id: String) {
        _workspaces.update { list -> list.filterNot { it.id == id } }
        persist()
        if (_workspaces.value.isEmpty()) {
            ensureDefaultWorkspace()
            return
        }
        if (_activeId.value == id) {
            setActive(_workspaces.value.first().id)
        }
    }

    private fun add(workspace: Workspace) {
        _workspaces.update { it + workspace }
        persist()
        setActive(workspace.id)
    }

    private fun persist() {
        val json = buildJsonArray {
            _workspaces.value.forEach { workspace ->
                addJsonObject {
                    put("id", workspace.id)
                    put("name", workspace.name)
                    put("uri", workspace.uri)
                    put("path", workspace.path)
                    put("appFolder", workspace.appFolder)
                    put("createdAt", workspace.createdAt)
                }
            }
        }.toString()
        prefs.edit().putString(KEY_WORKSPACES, json).apply()
    }

    private fun load(): List<Workspace> {
        val raw = prefs.getString(KEY_WORKSPACES, null) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                Workspace(
                    id = obj.string("id") ?: return@mapNotNull null,
                    name = obj.string("name") ?: "工作区",
                    uri = obj.string("uri").orEmpty(),
                    path = obj.string("path").orEmpty(),
                    appFolder = obj.string("appFolder") == "true",
                    createdAt = 0L,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun slug(name: String): String = name
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifBlank { "workspace" }

    private companion object {
        const val PREFS = "iris_workspaces"
        const val KEY_WORKSPACES = "workspaces"
        const val KEY_ACTIVE = "active"
    }
}
