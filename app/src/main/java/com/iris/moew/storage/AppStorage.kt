package com.iris.moew.storage

import android.content.Context
import java.io.File

/**
 * 应用数据根目录：
 * - 新数据写入外置应用专属目录 `Android/data/<包名>/files/IRIS`，无需存储权限；
 * - 旧的 `filesDir` 数据不迁移，作为兼容读取根保留；
 * - 外置目录不可用时回退到 `filesDir`。
 */
class AppStorage(context: Context) {

    private val appContext = context.applicationContext
    private val privateRoot: File = appContext.filesDir

    private val externalRoot: File? = appContext.getExternalFilesDir(null)
        ?.let { File(it, ROOT_DIR) }
        ?.takeIf { runCatching { it.mkdirs() || it.isDirectory }.getOrDefault(false) }

    val root: File = externalRoot ?: privateRoot
    val isExternal: Boolean = externalRoot != null
    val legacyRoot: File get() = privateRoot

    /**
     * 包名从 com.iris.localai 改为 com.iris.moew 后外置根路径随之变化。
     * 外置根为空（全新安装）且已授予「所有文件访问」时自动整包搬迁；
     * 否则可用设置页的迁移按钮按资源目录合并，不覆盖新包已有文件。
     */
    init {
        val external = externalRoot
        if (external != null && external.list()?.isNotEmpty() == false) {
            migrateFromLegacyPackage(includeConfig = true)
        }
    }

    val models: File get() = writeDir("models")
    val characters: File get() = writeDir("characters")
    val characterModels: File get() = writeDir("character_models")
    val petModels: File get() = writeDir("pet_models")
    val voiceModels: File get() = writeDir("voice_models")
    val motionAssets: File get() = writeDir("motion_assets")
    val stageAssets: File get() = writeDir("stage_assets")
    val scenes: File get() = writeDir("scenes")
    val settings: File get() = writeDir("settings")
    val mcp: File get() = writeDir("mcp")

    val settingsFile: File get() = File(settings, SETTINGS_FILE)
    val mcpServersFile: File get() = File(mcp, MCP_SERVERS_FILE)

    fun writeDir(name: String): File = File(root, name).apply { mkdirs() }

    /** 外置根在前、私有根在后；读取时外置同名条目优先。 */
    fun readDirs(name: String): List<File> = buildList {
        externalRoot?.let { add(File(it, name)) }
        add(File(privateRoot, name))
    }.distinctBy { it.absolutePath }

    fun findRootFor(name: String, directory: String): File? =
        readDirs(name).firstOrNull { File(it, directory).isDirectory }

    fun findFile(name: String, relative: String): File? =
        readDirs(name).asSequence()
            .map { File(it, relative) }
            .firstOrNull { it.isFile }

    /** 返回 [file] 所属的存储根，用于计算 WebView 虚拟 URL 的相对路径。 */
    fun containingRoot(name: String, file: File): File? {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return readDirs(name).asSequence()
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .firstOrNull { root ->
                target.path.startsWith(root.path + File.separator) ||
                    target.path == root.path
            }
    }

    fun modelFile(name: String, directory: String, entryFile: String): File? =
        findRootFor(name, directory)?.let { root ->
            File(root, "$directory/$entryFile").takeIf { it.isFile }
        }

    /** 下载资源类目录：迁移时按文件级合并，目标已有同名文件跳过。 */
    private val MIGRATABLE_DIRS = listOf(
        "models", "character_models", "voice_models", "motion_assets",
        "pet_models", "stage_assets", "studio_models", "scenes",
    )

    companion object {
        const val ROOT_DIR = "IRIS"
        const val SETTINGS_FILE = "settings.json"
        const val MCP_SERVERS_FILE = "servers.json"

        /** 更名前的 applicationId；libiris_llama.so 的 JNI 符号仍绑定此包名的 LlamaNative。 */
        const val PREVIOUS_PACKAGE = "com.iris.localai"
    }

    /**
     * 从旧包 com.iris.localai 合并迁移已下载的模型与素材，避免重复下载。
     * 返回人类可读的报告；无旧数据返回提示。幂等，可重复触发。
     */
    fun migrateFromLegacyPackage(includeConfig: Boolean = false): String {
        val external = externalRoot
            ?: return "外置存储不可用，已回退私有目录，无法迁移"
        val dataDir = external.parentFile?.parentFile
            ?: return "外置根路径异常"
        val legacy = File(dataDir, "${PREVIOUS_PACKAGE}/files/$ROOT_DIR")
        if (!legacy.isDirectory || runCatching { legacy.list() }.getOrNull().isNullOrEmpty()) {
            return "未找到旧包 ${PREVIOUS_PACKAGE} 的下载数据"
        }

        var movedFiles = 0
        var skippedFiles = 0
        val failures = mutableListOf<String>()
        val dirs = if (includeConfig) MIGRATABLE_DIRS + listOf("settings", "mcp") else MIGRATABLE_DIRS
        dirs.forEach { name ->
            val source = File(legacy, name)
            if (!source.isDirectory) return@forEach
            val target = File(external, name).apply { mkdirs() }
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(source).path
                val destination = File(target, relative)
                if (destination.exists()) {
                    skippedFiles++
                    return@forEach
                }
                destination.parentFile?.mkdirs()
                val moved = runCatching {
                    file.renameTo(destination) && destination.isFile
                }.getOrDefault(false)
                if (moved) {
                    movedFiles++
                } else {
                    val copied = runCatching {
                        file.copyTo(destination, overwrite = true).isFile
                    }.getOrDefault(false)
                    if (copied) movedFiles++ else failures += "$name/$relative"
                }
            }
        }
        return buildString {
            appendLine("从旧包 ${PREVIOUS_PACKAGE} 合并完成")
            appendLine("新搬入：$movedFiles 个文件")
            if (skippedFiles > 0) appendLine("已存在跳过：$skippedFiles 个")
            if (failures.isNotEmpty()) {
                appendLine("失败 ${failures.size} 个：")
                failures.take(5).forEach { appendLine("- $it") }
            }
            appendLine("旧包私有目录（会话、记忆、技能、HAST 动作）不在迁移范围；如需保留请在旧版备份导出。")
        }.trim()
    }
}
