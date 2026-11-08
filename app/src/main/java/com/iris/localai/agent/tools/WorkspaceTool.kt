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
import kotlinx.serialization.json.contentOrNull

/**
 * 读写当前工作区文件，是否可用由「编程管理 › 工具授权」中的文件授权控制。
 */
class WorkspaceTool(
    private val context: Context,
    private val settings: CodeSettingsStore,
    private val workspaces: WorkspaceStore,
) : Tool {

    override val name: String = "workspace"

    override val description: String =
        "读写当前工作区文件，参数：{\"op\":\"list|read|write|mkdir|delete|search\"," +
            "\"path\":\"相对路径\",\"content\":\"写入内容\",\"query\":\"搜索关键词\"}"

    override suspend fun execute(arguments: JsonObject): String {
        val code = settings.current
        val op = arguments.string("op").trim().lowercase()
        val path = arguments.string("path")
        val content = arguments.string("content")
        val query = arguments.string("query")
        if (code.fileAuthorization == ToolAuthorization.DENY) {
            return "文件工具授权为拒绝：请在「编程管理 › 工具授权」调整"
        }
        if (op in setOf("write", "mkdir", "delete")) {
            if (!code.enabled) return "文件写入未启用：请到「编程管理」开启"
            if (code.fileAuthorization != ToolAuthorization.ALLOW) {
                return "文件写入需要授权：请在「编程管理 › 工具授权」设为允许"
            }
        }

        val workspace = workspaces.active() ?: return "当前没有工作区，请先选择或创建工作区"
        val fs = WorkspaceFs(context, workspace)

        return withContext(Dispatchers.IO) {
            when (op) {
                "list" -> fs.list(path)
                "read" -> fs.read(path)
                "write" -> fs.write(path, content)
                "mkdir" -> fs.mkdir(path)
                "delete" -> fs.delete(path)
                "search" -> fs.search(query)
                "" -> "缺少 op 参数：list/read/write/mkdir/delete/search"
                else -> "不支持的操作「$op」：可用 list/read/write/mkdir/delete/search"
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
