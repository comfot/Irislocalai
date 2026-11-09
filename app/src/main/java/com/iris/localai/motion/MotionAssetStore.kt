package com.iris.localai.motion

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

enum class MotionCategory(val label: String) {
    IDLE("待机"),
    GREET("打招呼"),
    INTERACT("互动"),
    WALK("走路"),
    DANCE("舞蹈"),
    CAMERA("镜头"),
    OTHER("其他"),
}

data class MotionAsset(
    val id: String,
    val name: String,
    val category: MotionCategory,
    val file: File,
    val bgm: File? = null,
) {
    val virtualPath: String
        get() = "motionassets/" + id.split('/').joinToString("/") { Uri.encode(it) }

    val sizeLabel: String
        get() = when {
            file.length() >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", file.length() / 1048576f)
            file.length() >= 1024 -> String.format(Locale.US, "%.0f KB", file.length() / 1024f)
            else -> "${file.length()} B"
        }
}

/**
 * 本地动作素材：VMD 动作与镜头、配乐都放在 filesDir/motion_assets 下，
 * 分类规则沿用 xiaoke.ai 的文件名关键词约定，默认不把未知动作当作舞蹈。
 */
class MotionAssetStore(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "motion_assets").apply { mkdirs() }
    private val motionsDir = File(root, "motions").apply { mkdirs() }
    private val camerasDir = File(root, "cameras").apply { mkdirs() }
    private val bgmDir = File(root, "bgm").apply { mkdirs() }

    private val _assets = MutableStateFlow(scan())
    val assets: StateFlow<List<MotionAsset>> = _assets.asStateFlow()

    fun motionsDir(): File = motionsDir

    fun camerasDir(): File = camerasDir

    fun bgmDir(): File = bgmDir

    fun storageRoot(): File = root

    fun refresh() {
        _assets.value = scan()
    }

    fun find(id: String): MotionAsset? = _assets.value.firstOrNull { it.id == id }

    fun findByName(name: String): MotionAsset? {
        val wanted = name.trim()
        if (wanted.isBlank()) return null
        return _assets.value.firstOrNull {
            it.name.equals(wanted, ignoreCase = true) || it.id.equals(wanted, ignoreCase = true)
        } ?: _assets.value.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
    }

    fun import(uri: Uri, categoryHint: MotionCategory? = null): Result<MotionAsset> = runCatching {
        val displayName = queryName(uri) ?: error("无法读取文件名")
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension == "vmd" || extension == "vpd") { "动作素材只支持 VMD 或 VPD 文件" }
        val destination = if (categoryHint == MotionCategory.CAMERA || looksLikeCamera(displayName)) {
            camerasDir
        } else {
            motionsDir
        }
        val baseName = displayName.substringBeforeLast('.', displayName)
        val outputName = if (extension == "vpd") "$baseName.vmd" else displayName
        val target = uniqueFile(destination, sanitize(outputName))
        if (extension == "vpd") {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取所选文件")
            target.writeBytes(VpdToVmd.convert(bytes))
        } else {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选文件")
        }
        if (target.length() <= 0) {
            target.delete()
            error("所选文件为空")
        }
        refresh()
        find(target.relativeTo(root).invariantSeparatorsPath) ?: error("导入后未找到素材")
    }

    fun importBgm(uri: Uri): Result<File> = runCatching {
        val displayName = queryName(uri) ?: error("无法读取文件名")
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension in AUDIO_EXTENSIONS) { "配乐只支持 mp3/wav/m4a/ogg/flac" }
        val target = uniqueFile(bgmDir, sanitize(displayName))
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件")
        if (target.length() <= 0) {
            target.delete()
            error("所选文件为空")
        }
        refresh()
        target
    }

    fun delete(asset: MotionAsset): Boolean {
        val removed = runCatching { asset.file.delete() }.getOrDefault(false)
        if (removed) refresh()
        return removed
    }

    private fun scan(): List<MotionAsset> {
        val bgmFiles = bgmDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .orEmpty()
        val list = mutableListOf<MotionAsset>()
        for (dir in listOf(motionsDir, camerasDir)) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension.equals("vmd", ignoreCase = true) }
                .forEach { file ->
                    val relative = runCatching {
                        file.canonicalFile.relativeTo(root).invariantSeparatorsPath
                    }.getOrNull() ?: return@forEach
                    val camera = dir == camerasDir || looksLikeCamera(relative)
                    val category = if (camera) MotionCategory.CAMERA else classify(file.name)
                    list += MotionAsset(
                        id = relative,
                        name = displayName(file.nameWithoutExtension),
                        category = category,
                        file = file,
                        bgm = if (category == MotionCategory.DANCE) pairBgm(file.nameWithoutExtension, bgmFiles) else null,
                    )
                }
        }
        return list.sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))
    }

    private fun classify(fileName: String): MotionCategory {
        val name = fileName.lowercase()
        return when {
            name.containsAny("待机", "待機", "idle", "呼吸", "loop") -> MotionCategory.IDLE
            name.containsAny("打招呼", "挥手", "摆手", "greet", "wave", "hello") -> MotionCategory.GREET
            name.containsAny("走路", "行走", "walk") -> MotionCategory.WALK
            name.containsAny("舞蹈", "dance", "跳", "极乐净土", "恋爱循环", "寄明月", "极乐") -> MotionCategory.DANCE
            name.containsAny("互动", "interact", "点头", "nod", "摇头", "shake", "思考", "摸头", "比心") -> MotionCategory.INTERACT
            else -> MotionCategory.OTHER
        }
    }

    private fun looksLikeCamera(fileName: String): Boolean {
        val name = fileName.lowercase()
        return name.containsAny(
            "镜头", "カメラ", "相机", "camera", "cam-", "cam_", "运镜", "特写", "环绕", "推拉", "定镜",
        )
    }

    private fun pairBgm(motionName: String, bgmFiles: List<File>): File? {
        val wanted = normalize(motionName)
        return bgmFiles.firstOrNull { normalize(it.nameWithoutExtension) == wanted }
            ?: bgmFiles.firstOrNull {
                val bgm = normalize(it.nameWithoutExtension)
                bgm.isNotBlank() && (wanted.contains(bgm) || bgm.contains(wanted))
            }
    }

    private fun queryName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return cleaned.ifBlank { "motion.vmd" }.take(120)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext")
            index += 1
        }
        return candidate
    }

    private fun displayName(name: String): String =
        name.replace(Regex("^\\[[^\\]]+\\]\\s*"), "").trim().ifBlank { name }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\s_\\-·.,，。()（）\\[\\]]"), "")

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "ogg", "flac")
    }
}
