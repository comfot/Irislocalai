package com.iris.moew.agent.tools

import com.iris.moew.agent.Tool
import com.iris.moew.plugin.PluginDevService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 插件开发套件的宿主工具。所有写操作限制在 plugin_dev/<project> 内，
 * 打包产物推送到设置的本地导出目录；已安装插件只读。
 */
class PluginDevTool(private val service: PluginDevService) : Tool {

    override val name: String = "plugin_dev"

    override val description: String =
        "插件开发套件。op 支持 list_projects/create_project/list_files/read_file/write_file/delete_project/" +
            "copy_installed/validate_project/package_project/install_project/reload_plugin/status/capabilities/" +
            "host_api/host_source。写操作只作用于开发工程目录；package_project 会把 ZIP 推送到本地导出文件夹。"

    override suspend fun execute(arguments: JsonObject): String = runCatching {
        when (val op = arguments.op()?.lowercase() ?: error("缺少 op（收到字段：${arguments.keys.joinToString()}" + "）")) {
            "list_projects" -> buildJsonObject {
                put("projects", buildJsonArray { service.projects().forEach { add(JsonPrimitive(it.name)) } })
            }.toString()
            "create_project" -> {
                val project = arguments.text("project") ?: error("缺少 project")
                val template = arguments.text("template") ?: "tool"
                buildJsonObject {
                    put("ok", true)
                    put("path", service.createProject(project, template))
                }.toString()
            }
            "list_files" -> service.listFiles(requireProject(arguments)).toString()
            "read_file" -> service.readFile(
                requireProject(arguments),
                arguments.text("path") ?: arguments.text("file") ?: arguments.text("file_path") ?: error("缺少 path"),
            )
            "write_file" -> {
                service.writeFile(
                    requireProject(arguments),
                    arguments.text("path") ?: arguments.text("file") ?: arguments.text("file_path") ?: error("缺少 path"),
                    arguments.text("content") ?: error("缺少 content"),
                )
                buildJsonObject { put("ok", true) }.toString()
            }
            "delete_project" -> {
                service.deleteProject(requireProject(arguments))
                buildJsonObject { put("ok", true) }.toString()
            }
            "copy_installed" -> buildJsonObject {
                put(
                    "path",
                    service.copyInstalled(
                        arguments.text("pluginId") ?: arguments.text("plugin") ?: error("缺少 pluginId"),
                        arguments.text("project") ?: error("缺少 project"),
                    ),
                )
            }.toString()
            "validate_project" -> service.validate(requireProject(arguments)).toString()
            "package_project" -> service.packageAndPush(requireProject(arguments)).toString()
            "install_project" -> {
                val enabled = (arguments["enabled"] as? JsonPrimitive)?.booleanOrNull
                val plugin = service.install(requireProject(arguments), enabled)
                buildJsonObject {
                    put("ok", true)
                    put("pluginId", plugin.id)
                    put("name", plugin.name)
                    put("version", plugin.version)
                    put("enabled", plugin.enabled)
                    put("tools", buildJsonArray { plugin.tools.forEach { add(JsonPrimitive(it.name)) } })
                }.toString()
            }
            "reload_plugin" -> {
                val id = arguments.text("pluginId") ?: arguments.text("plugin") ?: error("缺少 pluginId")
                service.reload(id)
                buildJsonObject { put("ok", true); put("pluginId", id) }.toString()
            }
            "status" -> service.status().toString()
            "capabilities" -> service.capabilities().toString()
            "host_api" -> service.hostApi()
            "host_source" -> service.hostSource(
                arguments.text("path") ?: arguments.text("file") ?: arguments.text("file_path") ?: error("缺少 path"),
            )
            "export_dir" -> buildJsonObject {
                put("dir", service.capabilities()["export_dir"]?.toString()?.trim('"').orEmpty())
            }.toString()
            "templates" -> buildJsonObject {
                put("templates", buildJsonArray {
                    listOf("tool", "ui", "hook", "host").forEach { add(JsonPrimitive(it)) }
                })
            }.toString()
            else -> error("不支持的 op：$op")
        }
    }.getOrElse { error ->
        buildJsonObject {
            put("ok", false)
            put("error", error.message ?: error.javaClass.simpleName)
        }.toString()
    }

    private fun JsonObject.op(): String? {
        val direct = text("op") ?: text("operation") ?: text("action")
        if (direct != null) return direct
        for (key in listOf("arguments", "params", "payload")) {
            val nested = this[key] as? JsonObject
            val value = nested?.text("op") ?: nested?.text("operation") ?: nested?.text("action")
            if (value != null) return value
        }
        return null
    }

    private fun requireProject(arguments: JsonObject): String =
        arguments.text("project") ?: arguments.text("project_name") ?: arguments.text("name") ?: error("缺少 project")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
