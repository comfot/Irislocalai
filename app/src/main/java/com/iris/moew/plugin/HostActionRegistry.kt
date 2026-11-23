package com.iris.moew.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.iris.moew.character.CharacterModelStore
import com.iris.moew.data.SettingsRepository
import com.iris.moew.memory.MemoryStore
import com.iris.moew.motion.MotionBridge
import com.iris.moew.voice.TtsController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 插件请求宿主打开设置页；MainScreen 订阅后切换页面。 */
object HostNavigator {

    private val _settingsRoutes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val settingsRoutes: SharedFlow<String> = _settingsRoutes.asSharedFlow()

    fun openSettings(route: String) {
        _settingsRoutes.tryEmit(route.trim())
    }
}

/**
 * 插件可调用的宿主动作白名单。参数校验与异常信息都在这里完成，
 * 插件只拿到 JSON 结果。
 */
class HostActionRegistry(
    private val context: Context,
    private val tts: TtsController,
    private val memory: MemoryStore,
    private val characterModels: CharacterModelStore,
    private val settings: SettingsRepository,
) {

    suspend fun invoke(id: String, arguments: JsonObject): JsonObject = runCatching {
        when (id) {
            "motion.play" -> {
                val action = arguments.text("action") ?: arguments.text("alias") ?: error("缺少 action")
                MotionBridge.play(action)
                ok(buildJsonObject { put("action", action) })
            }
            "expression.set" -> {
                val name = arguments.text("expression") ?: arguments.text("name") ?: error("缺少 expression")
                MotionBridge.playExpression(name)
                ok(buildJsonObject { put("expression", name) })
            }
            "speech.speak" -> {
                val text = arguments.text("text") ?: error("缺少 text")
                tts.speak(text, settings.current.ttsSpeed)
                ok(buildJsonObject { put("text", text.take(200)) })
            }
            "memory.recall" -> {
                val query = arguments.text("query") ?: error("缺少 query")
                val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 6
                val result = memory.recall(query, limit)
                ok(buildJsonObject {
                    put("query", result.query)
                    put("mode", result.vectorMode)
                    put("hits", buildJsonArray {
                        result.hits.forEach { hit ->
                            add(buildJsonObject {
                                put("kind", hit.kind.name)
                                put("id", hit.id)
                                put("title", hit.title)
                                put("content", hit.content.take(1000))
                                put("score", hit.score)
                            })
                        }
                    })
                })
            }
            "character.listModels" -> ok(buildJsonObject {
                put("models", buildJsonArray {
                    characterModels.models.value.forEach { model ->
                        add(buildJsonObject {
                            put("id", model.id)
                            put("name", model.name)
                            put("type", model.type.name)
                        })
                    }
                })
                put("activeId", characterModels.active()?.id.orEmpty())
            })
            "character.activeModel" -> {
                val active = characterModels.active()
                ok(buildJsonObject {
                    put("id", active?.id.orEmpty())
                    put("name", active?.name.orEmpty())
                    put("type", active?.type?.name.orEmpty())
                })
            }
            "settings.open" -> {
                val route = arguments.text("route") ?: error("缺少 route")
                HostNavigator.openSettings(route)
                ok(buildJsonObject { put("route", route) })
            }
            "toast.show" -> {
                val message = arguments.text("message") ?: error("缺少 message")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                ok(buildJsonObject { put("message", message.take(200)) })
            }
            "host.capabilities" -> ok(buildJsonObject {
                put("version", PluginDevService.HOST_API_VERSION)
                put("actions", buildJsonArray { names().forEach { add(JsonPrimitive(it)) } })
            })
            else -> error("不支持的宿主动作：$id")
        }
    }.getOrElse { error ->
        buildJsonObject {
            put("ok", false)
            put("error", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun ok(payload: JsonObject): JsonObject = buildJsonObject {
        put("ok", true)
        payload.forEach { (key, value) -> put(key, value) }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        fun names(): List<String> = listOf(
            "motion.play",
            "expression.set",
            "speech.speak",
            "memory.recall",
            "character.listModels",
            "character.activeModel",
            "settings.open",
            "toast.show",
            "host.capabilities",
        )
    }
}
