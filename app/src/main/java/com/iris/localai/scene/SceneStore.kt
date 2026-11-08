package com.iris.localai.scene

import android.content.Context
import android.net.Uri
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

enum class SceneBackgroundType(val label: String) {
    IMAGE("背景图"),
    VIDEO("背景视频"),
}

enum class StageBaseStyle(val label: String) {
    NONE("无底座"),
    ROUND("圆形舞台"),
    GRID("网格平台"),
    GLOW("光晕底座"),
}

data class SceneConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新场景",
    val intro: String = "",
    val backgroundType: SceneBackgroundType = SceneBackgroundType.IMAGE,
    val backgroundPath: String = "",
    val baseStyle: StageBaseStyle = StageBaseStyle.ROUND,
    val baseColor: Long = 0xFF7DB3FF,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 生活空间场景：名称、介绍、背景图/视频与 3D 底座样式。 */
class SceneStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "scenes").apply { mkdirs() }

    private val _scenes = MutableStateFlow(load())
    val scenes: StateFlow<List<SceneConfig>> = _scenes.asStateFlow()

    private val _activeId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> _scenes.value.any { it.id == id } }
            ?: _scenes.value.firstOrNull()?.id.orEmpty(),
    )
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    init {
        if (_scenes.value.isEmpty()) {
            val scene = SceneConfig(id = "default", name = "默认场景", intro = "在熟悉的房间里陪你聊天。")
            _scenes.value = listOf(scene)
            persist()
            setActive(scene.id)
        }
    }

    fun active(): SceneConfig? =
        _scenes.value.firstOrNull { it.id == _activeId.value } ?: _scenes.value.firstOrNull()

    fun create(name: String = "新场景"): SceneConfig {
        val scene = SceneConfig(name = name.ifBlank { "新场景" })
        _scenes.update { it + scene }
        persist()
        return scene
    }

    fun update(scene: SceneConfig) {
        _scenes.update { list -> list.map { if (it.id == scene.id) scene else it } }
        persist()
    }

    fun delete(id: String): Boolean {
        val scene = _scenes.value.firstOrNull { it.id == id } ?: return false
        scene.backgroundPath.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { File(path).delete() }
        }
        _scenes.update { list -> list.filterNot { it.id == id } }
        persist()
        if (_activeId.value == id) {
            _scenes.value.firstOrNull()?.let { setActive(it.id) } ?: run {
                _activeId.value = ""
                prefs.edit().putString(KEY_ACTIVE, "").apply()
            }
        }
        return true
    }

    fun setActive(id: String) {
        if (_scenes.value.none { it.id == id }) return
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun importBackground(
        context: Context,
        uri: Uri,
        sceneId: String,
        type: SceneBackgroundType,
    ): Result<String> = runCatching {
        if (sceneId.contains('/') || sceneId.contains("..")) error("invalid scene id")
        val extension = when (type) {
            SceneBackgroundType.VIDEO -> "mp4"
            SceneBackgroundType.IMAGE -> "png"
        }
        val target = File(dir, "$sceneId.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取背景文件")
        target.absolutePath
    }

    private fun load(): List<SceneConfig> {
        val raw = prefs.getString(KEY_SCENES, null) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                SceneConfig(
                    id = obj.string("id") ?: return@mapNotNull null,
                    name = obj.string("name") ?: "场景",
                    intro = obj.string("intro").orEmpty(),
                    backgroundType = runCatching {
                        SceneBackgroundType.valueOf(obj.string("backgroundType") ?: "")
                    }.getOrDefault(SceneBackgroundType.IMAGE),
                    backgroundPath = obj.string("backgroundPath").orEmpty(),
                    baseStyle = runCatching {
                        StageBaseStyle.valueOf(obj.string("baseStyle") ?: "")
                    }.getOrDefault(StageBaseStyle.ROUND),
                    baseColor = obj.long("baseColor") ?: 0xFF7DB3FF,
                    createdAt = obj.long("createdAt") ?: 0L,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun persist() {
        val json = buildJsonArray {
            _scenes.value.forEach { scene ->
                addJsonObject {
                    put("id", scene.id)
                    put("name", scene.name)
                    put("intro", scene.intro)
                    put("backgroundType", scene.backgroundType.name)
                    put("backgroundPath", scene.backgroundPath)
                    put("baseStyle", scene.baseStyle.name)
                    put("baseColor", scene.baseColor)
                    put("createdAt", scene.createdAt)
                }
            }
        }.toString()
        prefs.edit().putString(KEY_SCENES, json).apply()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val PREFS = "iris_scenes"
        const val KEY_SCENES = "scenes"
        const val KEY_ACTIVE = "active"
    }
}
