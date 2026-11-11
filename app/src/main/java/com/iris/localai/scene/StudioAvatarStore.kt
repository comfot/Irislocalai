package com.iris.localai.scene

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 综合空间里一个小人的外观配置；名字为空时回退到角色卡名。 */
data class StudioAvatar(
    val name: String = "",
    val style: String = STYLE_STANDARD,
    val color: String = "",
    /** 绑定 StudioModelStore 中的 YSM 模型 id；空串使用程序化小人。 */
    val modelId: String = "",
) {
    companion object {
        const val STYLE_STANDARD = "standard"
        const val STYLE_LONG_HAIR = "longhair"
        const val STYLE_OUTFIT = "outfit"
        const val STYLE_DRESS = "dress"

        val styles = listOf(
            STYLE_STANDARD to "标准",
            STYLE_LONG_HAIR to "长发",
            STYLE_OUTFIT to "工装",
            STYLE_DRESS to "礼服",
        )
    }
}

/**
 * 按角色卡 id 保存综合空间的小人名字、造型与颜色；家在构建时按角色卡数量生成小人。
 */
class StudioAvatarStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _avatars = MutableStateFlow(load())
    val avatars: StateFlow<Map<String, StudioAvatar>> = _avatars.asStateFlow()

    fun of(characterId: String): StudioAvatar = _avatars.value[characterId] ?: StudioAvatar()

    fun update(characterId: String, avatar: StudioAvatar) {
        if (characterId.isBlank()) return
        _avatars.value = _avatars.value + (characterId to avatar)
        persist()
    }

    fun reset(characterId: String) {
        if (!_avatars.value.containsKey(characterId)) return
        _avatars.value = _avatars.value - characterId
        persist()
    }

    private fun load(): Map<String, StudioAvatar> {
        val raw = prefs.getString(KEY_AVATARS, null) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).jsonObject.mapNotNull { (id, value) ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                id to StudioAvatar(
                    name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    style = obj["style"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { style -> StudioAvatar.styles.any { it.first == style } }
                        ?: StudioAvatar.STYLE_STANDARD,
                    color = obj["color"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    modelId = obj["modelId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun persist() {
        val json = buildJsonObject {
            _avatars.value.forEach { (id, avatar) ->
                putJsonObject(id) {
                    put("name", avatar.name)
                    put("style", avatar.style)
                    put("color", avatar.color)
                    put("modelId", avatar.modelId)
                }
            }
        }.toString()
        prefs.edit().putString(KEY_AVATARS, json).apply()
    }

    private companion object {
        const val PREFS = "iris_studio_avatars"
        const val KEY_AVATARS = "avatars"
    }
}
