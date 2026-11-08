package com.iris.localai.agent.tools

import android.content.Context
import com.iris.localai.agent.Tool
import com.iris.localai.code.CodeSettingsStore
import com.iris.localai.code.ToolAuthorization
import com.iris.localai.workspace.WorkspaceFs
import com.iris.localai.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 文件工具共用入口：读取只受工作区沙盒与 DENY 限制；
 * 写入还要求「编程管理」已开启且文件授权为 ALLOW。
 */
private class WorkspaceAccess(
    context: Context,
    private val settings: CodeSettingsStore,
    private val workspaces: WorkspaceStore,
) {
    private val appContext = context.applicationContext

    fun fs(requireWrite: Boolean = false): WorkspaceFs {
        val code = settings.current
        if (code.fileAuthorization == ToolAuthorization.DENY) {
            throw IllegalStateException("文件工具授权为拒绝：请在「编程管理 › 工具授权」调整")
        }
        if (requireWrite) {
            if (!code.enabled) {
                throw IllegalStateException("文件写入未启用：请到「编程管理」开启")
            }
            if (code.fileAuthorization != ToolAuthorization.ALLOW) {
                throw IllegalStateException("文件写入需要授权：请在「编程管理 › 工具授权」设为允许")
            }
        }
        val workspace = workspaces.active()
            ?: throw IllegalStateException("当前没有工作区，请先选择或创建工作区")
        return WorkspaceFs(appContext, workspace)
    }
}

class ReadFileTool(
    context: Context,
    settings: CodeSettingsStore,
    workspaces: WorkspaceStore,
) : Tool {

    private val access = WorkspaceAccess(context, settings, workspaces)

    override val name: String = "read_file"

    override val description: String =
        "读取工作区文本文件并返回带行号的内容；参数：" +
            "{\"path\":\"README.md\",\"offset\":1,\"limit\":200}，offset 从 1 开始，limit 默认 200。"

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments.string("path").trim()
        if (path.isEmpty()) return "缺少 path 参数"
        val offset = arguments.int("offset") ?: 1
        val limit = arguments.int("limit") ?: 200
        return withContext(Dispatchers.IO) {
            access.fs().readNumbered(path, offset, limit)
        }
    }
}

class WriteFileTool(
    context: Context,
    settings: CodeSettingsStore,
    workspaces: WorkspaceStore,
) : Tool {

    private val access = WorkspaceAccess(context, settings, workspaces)

    override val name: String = "write_file"

    override val description: String =
        "在工作区创建或覆盖文本文件；参数：{\"path\":\"src/main.kt\",\"content\":\"...\"}。" +
            "上级目录需已存在，写入需在「编程管理」开启并允许文件授权。"

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments.string("path").trim()
        if (path.isEmpty()) return "缺少 path 参数"
        if (!arguments.containsKey("content")) return "缺少 content 参数"
        val content = arguments.string("content")
        return withContext(Dispatchers.IO) {
            access.fs(requireWrite = true).write(path, content)
        }
    }
}

class EditFileTool(
    context: Context,
    settings: CodeSettingsStore,
    workspaces: WorkspaceStore,
) : Tool {

    private val access = WorkspaceAccess(context, settings, workspaces)

    override val name: String = "edit_file"

    override val description: String =
        "按精确文本替换工作区文件内容；参数：" +
            "{\"path\":\"README.md\",\"old_string\":\"原文\",\"new_string\":\"新文\",\"replace_all\":false}。" +
            "old_string 必须唯一匹配，替换多处时显式设置 replace_all=true。"

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments.string("path").trim()
        if (path.isEmpty()) return "缺少 path 参数"
        val oldText = arguments.string("old_string")
        if (oldText.isEmpty()) return "缺少 old_string 参数"
        val newText = arguments.string("new_string")
        val replaceAll = (arguments["replace_all"] as? JsonPrimitive)?.booleanOrNull ?: false
        return withContext(Dispatchers.IO) {
            access.fs(requireWrite = true).edit(path, oldText, newText, replaceAll)
        }
    }
}

class ListFilesTool(
    context: Context,
    settings: CodeSettingsStore,
    workspaces: WorkspaceStore,
) : Tool {

    private val access = WorkspaceAccess(context, settings, workspaces)

    override val name: String = "list_files"

    override val description: String =
        "列出工作区目录下的文件与大小；参数：{\"path\":\"src/main\"}，空路径表示根目录。"

    override suspend fun execute(arguments: JsonObject): String {
        val path = arguments.string("path").trim()
        return withContext(Dispatchers.IO) {
            access.fs().list(path)
        }
    }
}

class SearchFilesTool(
    context: Context,
    settings: CodeSettingsStore,
    workspaces: WorkspaceStore,
) : Tool {

    private val access = WorkspaceAccess(context, settings, workspaces)

    override val name: String = "search_files"

    override val description: String =
        "在工作区按关键词搜索文件名与内容，返回匹配文件与行号；参数：{\"query\":\"TODO\"}。"

    override suspend fun execute(arguments: JsonObject): String {
        val query = arguments.string("query").trim()
        if (query.isEmpty()) return "缺少 query 参数"
        return withContext(Dispatchers.IO) {
            access.fs().search(query)
        }
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
