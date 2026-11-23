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

    init {
        migrateFromRenamedPackage()
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

    companion object {
        const val ROOT_DIR = "IRIS"
        const val SETTINGS_FILE = "settings.json"
        const val MCP_SERVERS_FILE = "servers.json"

        /** 更名前的 applicationId；libiris_llama.so 的 JNI 符号仍绑定此包名的 LlamaNative。 */
        const val PREVIOUS_PACKAGE = "com.iris.localai"
    }

    /**
     * 包名从 com.iris.localai 改为 com.iris.moew 后外置根路径随之变化。
     * 首次安装且已授予「所有文件访问」（能 listFiles）时，把旧包外置根整体搬迁过来；
     * 无法读取（未授权、旧包已清）则静默跳过，旧包 filesDir 的私有数据不可迁移，需用备份导出。
     */
    private fun migrateFromRenamedPackage() {
        val external = externalRoot ?: return
        if (external.list()?.isNotEmpty() == true) return
        val legacy = File(
            external.parentFile?.parentFile?.parentFile ?: return,
            "${PREVIOUS_PACKAGE}/files/$ROOT_DIR",
        )
        if (!legacy.isDirectory || runCatching { legacy.list() }.getOrNull().isNullOrEmpty()) return

        val report = mutableListOf<String>()
        legacy.listFiles().orEmpty().forEach { item ->
            val target = File(external, item.name)
            val moved = runCatching { item.renameTo(target) && target.exists() }
                .getOrDefault(false)
            if (moved) return@forEach
            val copied = runCatching {
                if (item.isDirectory) {
                    item.copyRecursively(target, overwrite = true)
                    item.deleteRecursively()
                } else {
                    item.copyTo(target, overwrite = true).let { item.delete() }
                }
            }.isSuccess
            if (!copied) report += "${item.name}：搬迁失败，请手动从旧目录复制"
        }
        runCatching {
            val marker = File(legacy.parentFile, "$ROOT_DIR.migrated-${System.currentTimeMillis()}")
            legacy.renameTo(marker) || legacy.deleteRecursively()
        }
        runCatching {
            File(external, "migrated_from_${PREVIOUS_PACKAGE}.txt")
                .writeText(
                    buildString {
                        appendLine("已从 ${PREVIOUS_PACKAGE} 的外置目录搬迁到 $external")
                        if (report.isEmpty()) appendLine("全部搬迁成功") else report.forEach(::appendLine)
                        appendLine("旧包 filesDir 里的私有数据（会话、记忆、技能等）不会自动迁移；")
                        appendLine("如需保留请在旧版本用 数据管理 › 备份 导出后在此导入。")
                    },
                )
        }
    }
}
