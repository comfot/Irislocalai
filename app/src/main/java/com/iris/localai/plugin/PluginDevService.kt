package com.iris.localai.plugin

import android.content.Context
import android.os.Environment
import com.iris.localai.data.RemoteFetch
import com.iris.localai.data.SettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 插件开发工程与打包服务。工程目录与导出目录是唯一可写范围；
 * 已安装插件只读，只能复制为工程后再改。
 */
class PluginDevService(
    context: Context,
    private val store: PluginStore,
    private val runtime: PluginRuntime,
    private val settings: SettingsRepository,
    private val reloadPlugin: suspend (String) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val devRoot = File(appContext.filesDir, ROOT).apply { mkdirs() }
    private val exportsFallback = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "exports")
        .apply { mkdirs() }

    fun projects(): List<File> =
        devRoot.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun createProject(project: String, template: String): String {
        val dir = projectDir(project, mustExist = false)
        if (dir.exists() && dir.listFiles().orEmpty().isNotEmpty()) error("工程已存在：${dir.name}")
        dir.mkdirs()
        val files = when (template.trim().lowercase()) {
            "ui", "ui_panel" -> uiTemplate(project)
            "hook" -> hookTemplate(project)
            "host", "host_runtime" -> hostRuntimeTemplate(project)
            else -> toolTemplate(project)
        }
        files.forEach { (path, content) -> writeFile(project, path, content) }
        return dir.absolutePath
    }

    fun listFiles(project: String): JsonObject {
        val dir = projectDir(project)
        val entries = buildJsonArray {
            dir.walkTopDown()
                .filter { it.isFile && it.name != META && !it.name.startsWith(".") && !it.path.contains("__MACOSX") }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
                .forEach { file ->
                    add(buildJsonObject {
                        put("path", file.relativeTo(dir).invariantSeparatorsPath)
                        put("size", file.length())
                    })
                }
        }
        return buildJsonObject {
            put("project", project)
            put("files", entries)
        }
    }

    fun readFile(project: String, path: String): String {
        val file = resolve(projectDir(project), path) ?: error("路径不在工程目录：$path")
        if (!file.isFile) error("文件不存在：$path")
        if (file.length() > MAX_TEXT_BYTES) error("文件超过 4 MB")
        return file.readText()
    }

    fun writeFile(project: String, path: String, content: String) {
        val file = resolve(projectDir(project), path) ?: error("路径不在工程目录：$path")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) error("写入内容超过 4 MB")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun deleteProject(project: String) {
        val dir = projectDir(project)
        if (!dir.deleteRecursively()) error("工程删除失败：$project")
    }

    fun validate(project: String): JsonObject {
        val dir = projectDir(project)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val manifestFile = findManifest(dir) ?: return result(false, errors.apply { add("缺少 manifest.json / manifest.hjson") }, warnings)
        if (manifestFile.length() > MAX_MANIFEST_BYTES) errors += "清单超过 4 MB"
        val manifest = runCatching { PluginManifestParser.parse(manifestFile.readText()) }
            .getOrElse { error ->
                return result(false, errors.apply { add("清单解析失败：${error.message}") }, warnings)
            }
        if (manifest.id.isBlank()) errors += "缺少 id"
        if (manifest.version.isBlank()) warnings += "manifest.version 为空"
        if (manifest.main.isNotBlank()) {
            val main = resolve(dir, manifest.main)
            if (main == null || !main.isFile) errors += "main 不存在：${manifest.main}"
            else if (main.length() > MAX_SCRIPT_BYTES) errors += "main 超过 4 MB"
        }
        manifest.subpackages.forEach { sub ->
            val entry = resolve(dir, sub.entry)
            if (entry == null || !entry.isFile) errors += "子包 ${sub.id} 的 entry 不存在：${sub.entry}"
            else if (entry.length() > MAX_SCRIPT_BYTES) errors += "子包 ${sub.id} 的 entry 超过 4 MB"
        }
        manifest.resources.forEach { resource ->
            val file = resolve(dir, resource.path)
            if (file == null || !file.exists()) errors += "资源 ${resource.key} 不存在：${resource.path}"
        }
        manifest.wasmModules.forEach { module ->
            val file = resolve(dir, module.path)
            if (file == null || !file.isFile) errors += "WASM ${module.id} 不存在：${module.path}"
        }
        val count = dir.walkTopDown().count { it.isFile }
        if (count > MAX_FILES) errors += "工程文件数超过 $MAX_FILES"
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (total > 512L * 1024 * 1024) errors += "工程超过 512 MB"
        return result(errors.isEmpty(), errors, warnings, manifest)
    }

    fun packageProject(project: String): File {
        val dir = projectDir(project)
        val validation = validate(project)
        if (validation["ok"]?.toString() != "true") {
            error("校验未通过：" + (validation["errors"] as? JsonArray)?.joinToString("；") { it.toString().trim('"') })
        }
        val manifest = PluginManifestParser.parse((findManifest(dir) ?: error("缺少清单")).readText())
        val target = File(appContext.cacheDir, "plugin-dev/${sanitize(manifest.id)}-${sanitize(manifest.version)}.zip")
        target.parentFile?.mkdirs()
        var total = 0L
        var entries = 0
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") && it.name != META }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
                .forEach { file ->
                    entries++
                    if (entries > MAX_FILES) error("工程文件数超过 $MAX_FILES")
                    val bytes = file.readBytes()
                    total += bytes.size
                    if (total > MAX_PACKAGE_BYTES) error("打包后超过 256 MB")
                    zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
                    zip.write(bytes)
                    zip.closeEntry()
                }
        }
        return target
    }

    /** 把 ZIP 推送到设置里的本地文件夹，返回实际落盘路径。 */
    fun pushToExportDir(zip: File, pluginId: String, version: String): File {
        val targetDir = resolveExportDir()
        val dir = if (ensureWritable(targetDir)) targetDir else exportsFallback
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "${sanitize(pluginId)}-${sanitize(version)}-$stamp.zip"
        val target = File(dir, name)
        zip.copyTo(target, overwrite = true)
        return target
    }

    fun packageAndPush(project: String): JsonObject {
        val zip = packageProject(project)
        val manifest = PluginManifestParser.parse((findManifest(projectDir(project)) ?: error("缺少清单")).readText())
        val pushed = pushToExportDir(zip, manifest.id, manifest.version)
        zip.delete()
        return buildJsonObject {
            put("ok", true)
            put("path", pushed.absolutePath)
            put("size", pushed.length())
        }
    }

    /** 把已安装插件导出为 ZIP 并推送到本地导出目录（只读源插件）。 */
    fun packageInstalled(pluginId: String): JsonObject {
        val project = "export-${sanitize(pluginId)}-${System.currentTimeMillis()}"
        copyInstalled(pluginId, project)
        return try {
            packageAndPush(project)
        } finally {
            runCatching { deleteProject(project) }
        }
    }

    suspend fun install(project: String, enabled: Boolean? = null): InstalledPlugin {
        val validation = validate(project)
        if (validation["ok"]?.toString() != "true") {
            error("校验未通过：" + (validation["errors"] as? JsonArray)?.joinToString("；") { it.toString().trim('"') })
        }
        return store.installDirectory(projectDir(project), source = "devkit", enabled = enabled)
    }

    suspend fun reload(pluginId: String) {
        runtime.close(pluginId)
        store.refresh()
        reloadPlugin(pluginId)
    }

    fun copyInstalled(pluginId: String, project: String): String {
        val plugin = store.plugins.value.firstOrNull { it.id == pluginId }
            ?: error("插件不存在：$pluginId")
        val source = store.pluginDir(plugin)
        val target = projectDir(project, mustExist = false)
        if (target.exists() && target.listFiles().orEmpty().isNotEmpty()) error("工程已存在：$project")
        target.mkdirs()
        val sourceRoot = source.canonicalFile
        source.walkTopDown()
            .filter { it.isFile && it.name != META && !it.name.startsWith(".") }
            .forEach { file ->
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
                if (!canonical.path.startsWith(sourceRoot.path + File.separator)) return@forEach
                val relative = canonical.relativeTo(sourceRoot).invariantSeparatorsPath
                val destination = File(target, relative)
                destination.parentFile?.mkdirs()
                destination.writeBytes(canonical.readBytes())
            }
        return target.absolutePath
    }

    fun capabilities(): JsonObject = buildJsonObject {
        put("host", "IRIS LocalAI")
        putJsonObject("versions") {
            put("plugin_api", 1)
            put("host_api", HOST_API_VERSION)
        }
        putJsonArray("registration_types") {
            listOf(
                "toolbox_ui", "ui_route", "navigation", "desktop_widget", "settings_entry", "plugin_slot",
                "ai_provider", "chat_input", "chat_view", "chat_message", "chat_message_menu_item",
                "chat_runtime", "tool_lifecycle", "input_menu_toggle", "app_lifecycle", "xml_render",
                "message_processing", "prompt_input", "prompt_history", "system_prompt_compose",
                "tool_prompt_compose", "prompt_finalize", "summary_generate",
            ).forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("slots") {
            SUPPORTED_SLOTS.forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("ui_components") {
            listOf(
                "Column", "Row", "Box", "LazyColumn", "LazyRow", "Card", "Surface", "Text", "Button",
                "TextField", "Switch", "Checkbox", "Slider", "Icon", "Image", "Divider", "Markdown",
                "AiChat", "WebView", "HostChat", "HostCharacter3DView", "HostModelPicker",
                "HostModelProfilePicker", "HostScenePicker", "HostCharacterPicker",
                "HostModelProviderPicker", "HostWorkspacePicker", "HostToolList", "HostSceneBackground", "HostMemorySearch",
                "HostSkillPicker", "HostLorebookPicker", "HostMemoryList", "HostCharacterAvatar", "HostStatus",
            ).forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("host_actions") {
            HostActionRegistry.names().forEach { add(JsonPrimitive(it)) }
        }
        putJsonObject("limits") {
            put("package_bytes", 256L * 1024 * 1024)
            put("extracted_bytes", 512L * 1024 * 1024)
            put("entries", 5000)
            put("script_bytes", 4L * 1024 * 1024)
            put("manifest_bytes", 4L * 1024 * 1024)
        }
        put("export_dir", currentExportDir().absolutePath)
        put("source_repo", settings.current.hostSourceRepo)
        put("source_branch", settings.current.hostSourceBranch)
    }

    fun status(): JsonObject {
        val plugins = buildJsonArray {
            store.plugins.value.forEach { plugin ->
                add(buildJsonObject {
                    put("id", plugin.id)
                    put("name", plugin.name)
                    put("version", plugin.version)
                    put("enabled", plugin.enabled)
                    put("source", plugin.source)
                    put("tools", plugin.toolCount)
                    put("directory", plugin.directory)
                })
            }
        }
        val errors = buildJsonObject {
            runtime.errors.value.forEach { (id, message) -> put(id, message) }
        }
        val projects = buildJsonArray { projects().forEach { add(JsonPrimitive(it.name)) } }
        return buildJsonObject {
            put("plugins", plugins)
            put("errors", errors)
            put("projects", projects)
        }
    }

    fun hostApi(): String {
        val dir = File("host_api")
        val names = runCatching { appContext.assets.list(dir.path) }.getOrNull().orEmpty()
            .filter { it.endsWith(".md") }
            .sorted()
        if (names.isEmpty()) return "宿主 API 参考未打包。"
        return names.joinToString("\n\n") { name ->
            val text = runCatching {
                appContext.assets.open("${dir.path}/$name").bufferedReader().use { it.readText() }
            }.getOrDefault("")
            "# $name\n\n$text"
        }
    }

    suspend fun hostSource(path: String): String {
        val repo = settings.current.hostSourceRepo.trim().trim('/')
        val branch = settings.current.hostSourceBranch.trim().ifBlank { "main" }
        if (!repo.matches(Regex("[\\w.-]+/[\\w.-]+"))) error("源码仓库格式应为 owner/repo")
        val relative = path.trim().trimStart('/')
        if (relative.isBlank() || relative.split('/').any { it == ".." }) error("源码路径非法")
        val cache = File(devRoot, ".source_cache/${repo.replace('/', '_')}/$branch/$relative")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < SOURCE_CACHE_MS) {
            return if (cache.length() > MAX_TEXT_BYTES) error("源码文件超过 4 MB") else cache.readText()
        }
        val url = "https://raw.githubusercontent.com/$repo/$branch/$relative"
        val bytes = RemoteFetch.bytes(url, MAX_TEXT_BYTES, timeoutMs = 20_000)
        val text = String(bytes, Charsets.UTF_8)
        cache.parentFile?.mkdirs()
        cache.writeText(text)
        return text
    }

    fun exportDir(): File = currentExportDir()

    private fun currentExportDir(): File {
        val target = resolveExportDir()
        return if (ensureWritable(target)) target else exportsFallback
    }

    private fun resolveExportDir(): File {
        val preferred = settings.current.pluginExportDir.trim()
        if (preferred.startsWith("/")) return File(preferred)
        if (preferred.isNotEmpty()) return File(Environment.getExternalStorageDirectory(), preferred)
        return File(Environment.getExternalStorageDirectory(), "Download/IRIS插件")
    }

    private fun ensureWritable(dir: File): Boolean =
        runCatching { (dir.mkdirs() || dir.isDirectory) && dir.canWrite() }.getOrDefault(false)

    private fun projectDir(project: String, mustExist: Boolean = true): File {
        if (!project.matches(Regex("[\\w.-]{1,64}"))) error("工程名只能包含字母、数字、点、下划线、中划线")
        val dir = File(devRoot, project)
        if (mustExist && !dir.isDirectory) error("工程不存在：$project")
        return dir
    }

    private fun findManifest(base: File): File? = base.walkTopDown()
        .maxDepth(1)
        .filter { it.isFile && (it.name == "manifest.json" || it.name == "manifest.hjson") }
        .minByOrNull { it.absolutePath.length }

    private fun resolve(base: File, relative: String): File? {
        val normalized = relative.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty() || normalized.split('/').any { it == ".." }) return null
        val root = runCatching { base.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, normalized).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('.', '_', '-')
            .ifBlank { "plugin" }

    private fun result(
        ok: Boolean,
        errors: List<String>,
        warnings: List<String>,
        manifest: PluginManifest? = null,
    ): JsonObject = buildJsonObject {
        put("ok", ok)
        putJsonArray("errors") { errors.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("warnings") { warnings.forEach { add(JsonPrimitive(it)) } }
        manifest?.let {
            putJsonObject("manifest") {
                put("id", it.id)
                put("name", it.name)
                put("version", it.version)
                put("main", it.main)
                put("subpackages", it.subpackages.size)
                put("tools", it.toolSpecs.size)
            }
        }
    }

    private fun toolTemplate(project: String): Map<String, String> {
        val id = "iris.${project.replace('.', '_')}"
        return mapOf(
            "manifest.json" to """
                {
                  "schema_version": 1,
                  "id": "$id",
                  "version": "1.0.0",
                  "api_version": "1",
                  "main": "main.js",
                  "subpackages": [{ "id": "tools", "entry": "tools.js" }],
                  "display_name": { "zh": "$project", "en": "$project" },
                  "description": { "zh": "由插件开发套件创建的示例插件" },
                  "author": ["you"],
                  "enabled_by_default": true
                }
            """.trimIndent(),
            "main.js" to """
                // 纯工具插件：工具声明在子包脚本的 METADATA 中。
                (function () {
                  try { irisHost.log('$id loaded') } catch (error) { }
                })();
            """.trimIndent(),
            "tools.js" to """
                /*
                METADATA
                {
                  "tools": [
                    {
                      "name": "hello",
                      "description": "返回一条示例消息",
                      "parameters": [
                        { "name": "name", "description": "称呼", "type": "string", "required": false }
                      ]
                    }
                  ]
                }
                */
                exports.hello = async function (args) {
                  return "你好，" + (args.name || "世界");
                };
            """.trimIndent(),
        )
    }

    private fun uiTemplate(project: String): Map<String, String> {
        val id = "iris.${project.replace('.', '_')}"
        return mapOf(
            "manifest.json" to """
                {
                  "schema_version": 1,
                  "id": "$id",
                  "version": "1.0.0",
                  "api_version": "1",
                  "main": "main.js",
                  "display_name": { "zh": "$project", "en": "$project" },
                  "description": { "zh": "带设置入口、侧栏入口与独立面板的插件" },
                  "author": ["you"],
                  "enabled_by_default": true
                }
            """.trimIndent(),
            "main.js" to """
                ToolPkg.registerSettingsEntry({
                  id: "settings",
                  title: "$project",
                  surface: "advanced",
                  screen: "ui/panel.js"
                });
                ToolPkg.registerNavigationEntry({
                  id: "nav",
                  title: "$project",
                  surface: "main_sidebar_plugins",
                  route: "nav"
                });
                ToolPkg.registerToolboxUiModule({
                  id: "panel",
                  title: "$project",
                  screen: "ui/panel.js"
                });
            """.trimIndent(),
            "ui/panel.js" to """
                module.exports = function (ctx) {
                  const [count, setCount] = ctx.useState("count", 0);
                  return ctx.UI.Column({ fillMaxWidth: true }, [
                    ctx.UI.Text({ text: "$project", bold: true }),
                    ctx.UI.Text({ text: "计数：" + count }),
                    ctx.UI.Button({
                      text: "加一",
                      onClick: function () { setCount(count + 1); }
                    })
                  ]);
                };
            """.trimIndent(),
        )
    }

    private fun hookTemplate(project: String): Map<String, String> {
        val id = "iris.${project.replace('.', '_')}"
        return mapOf(
            "manifest.json" to """
                {
                  "schema_version": 1,
                  "id": "$id",
                  "version": "1.0.0",
                  "api_version": "1",
                  "main": "main.js",
                  "display_name": { "zh": "$project", "en": "$project" },
                  "description": { "zh": "Hook 示例插件" },
                  "author": ["you"],
                  "enabled_by_default": true
                }
            """.trimIndent(),
            "main.js" to """
                ToolPkg.registerChatViewHook({
                  id: "view",
                  title: "$project",
                  function: async function (payload) {
                    return { handled: false };
                  }
                });
                ToolPkg.registerToolLifecycleHook({
                  id: "lifecycle",
                  function: async function (payload) {
                    return null;
                  }
                });
            """.trimIndent(),
        )
    }

    private fun hostRuntimeTemplate(project: String): Map<String, String> {
        val id = "iris.${project.replace('.', '_')}"
        return mapOf(
            "manifest.json" to """
                {
                  "schema_version": 1,
                  "id": "$id",
                  "version": "1.0.0",
                  "api_version": "1",
                  "main": "main.js",
                  "subpackages": [{ "id": "noop", "entry": "empty.js" }],
                  "display_name": { "zh": "$project", "en": "$project" },
                  "description": { "zh": "宿主运行时插件模板：能力由宿主提供，插件本身只做展示与开关" },
                  "author": ["you"],
                  "enabled_by_default": true
                }
            """.trimIndent(),
            "main.js" to """
                (function () {
                  try { irisHost.log('$id loaded') } catch (error) { }
                })();
            """.trimIndent(),
            "empty.js" to "// 占位子包：本插件没有工具。\n",
        )
    }

    companion object {
        const val ROOT = "plugin_dev"
        const val HOST_API_VERSION = 1
        const val META = "iris-plugin.json"
        const val MAX_TEXT_BYTES = 4L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        const val MAX_SCRIPT_BYTES = 4L * 1024 * 1024
        const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
        const val MAX_FILES = 5000
        const val SOURCE_CACHE_MS = 6 * 60 * 60 * 1000L
        val SUPPORTED_SLOTS = listOf(
            "settings.page.<id>",
            "settings.model", "settings.voice", "settings.character-card", "settings.memory",
            "chat.attachment", "chat.input", "chat.message-menu", "chat.empty",
            "space.work", "space.life", "space.operate", "space.composite",
            "space.work.footer", "space.life.footer", "space.operate.footer", "space.composite.overlay",
            "sidebar.bottom", "market.plugins", "market.models", "market.character-models", "character.detail",
        )
    }
}
