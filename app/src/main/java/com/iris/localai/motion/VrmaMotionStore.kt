package com.iris.localai.motion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.iris.localai.storage.AppStorage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VrmaAsset(
    val id: String,
    val name: String,
    val sizeBytes: Long,
)

/**
 * 本地 VRMA 动作库。新文件写入外置 `IRIS/motion_assets/vrma`，
 * 旧 `filesDir/motion_assets/vrma` 继续读取；WebView 通过 /motionassets/ 读取。
 */
class VrmaMotionStore(
    context: Context,
    private val storage: AppStorage,
) {

    private val resolver = context.applicationContext.contentResolver
    private val root = storage.writeDir("motion_assets/vrma")
    private val _assets = MutableStateFlow<List<VrmaAsset>>(emptyList())
    val assets: StateFlow<List<VrmaAsset>> = _assets.asStateFlow()

    init {
        refresh()
    }

    fun storageRoot(): File = root

    fun refresh() {
        val seen = mutableSetOf<String>()
        _assets.value = storage.readDirs("motion_assets/vrma")
            .asSequence()
            .flatMap { dirRoot ->
                val base = runCatching { dirRoot.canonicalFile }.getOrNull()
                    ?: return@flatMap emptySequence()
                dirRoot.walkTopDown()
                    .filter { it.isFile && it.extension.equals("vrma", ignoreCase = true) }
                    .map { file ->
                        val relative = runCatching {
                            file.canonicalFile.relativeTo(base).invariantSeparatorsPath
                        }.getOrNull()
                        relative?.let { it to file }
                    }
                    .filterNotNull()
            }
            .filter { (relative, _) -> seen.add(relative) }
            .map { (relative, file) ->
                VrmaAsset(
                    id = "vrma/$relative",
                    name = file.nameWithoutExtension,
                    sizeBytes = file.length(),
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun findByName(value: String): VrmaAsset? {
        val wanted = value.trim()
        if (wanted.isBlank()) return null
        return _assets.value.firstOrNull {
            it.name.equals(wanted, ignoreCase = true) || it.id.equals(wanted, ignoreCase = true)
        } ?: _assets.value.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
    }

    fun import(uri: Uri): Result<VrmaAsset> = runCatching {
        val displayName = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "motion.vrma"
        require(displayName.substringAfterLast('.', "").equals("vrma", ignoreCase = true)) {
            "只支持 .vrma 动作文件"
        }
        val base = safeFileName(displayName.substringBeforeLast('.'))
        var target = File(root, "$base.vrma")
        var index = 1
        while (target.exists()) {
            target = File(root, "$base-$index.vrma")
            index += 1
        }
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件")
        refresh()
        _assets.value.firstOrNull { it.id == "vrma/${target.name}" }
            ?: error("导入后未找到动作文件")
    }

    fun delete(asset: VrmaAsset): Boolean {
        val relative = asset.id.removePrefix("vrma/")
        val removed = storage.readDirs("motion_assets/vrma").fold(false) { acc, dirRoot ->
            val file = File(dirRoot, relative)
            val deleted = file.isFile && file.delete()
            acc || deleted
        }
        if (removed) refresh()
        return removed
    }

    fun virtualUrl(asset: VrmaAsset): String {
        val encoded = asset.id.split('/').joinToString("/") { Uri.encode(it) }
        return "https://iris.local/motionassets/$encoded"
    }

    private fun queryName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun safeFileName(value: String): String {
        val clean = value.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").trim('_')
        return clean.ifBlank { "motion" }.take(80)
    }
}
