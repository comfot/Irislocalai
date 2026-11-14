package com.iris.localai.data

import android.content.Context
import android.content.SharedPreferences
import com.iris.localai.storage.AppStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * iris_settings 的可读镜像。只同步能从文件管理器编辑的普通设置，密钥字段两边都不处理；
 * 启动时仅在文件比上次写入更新时导入，避免应用状态覆盖用户的手工修改。
 */
internal class SettingsMirror(
    private val storage: AppStorage,
    private val prefs: SharedPreferences,
    context: Context,
) {

    private val statePrefs = context.applicationContext
        .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private val file: File get() = storage.settingsFile

    fun importIfNewer(): Boolean {
        val target = file
        if (!target.isFile || target.length() == 0L) return false
        val last = statePrefs.getLong(KEY_LAST_WRITE, 0L)
        if (target.lastModified() <= last) return false
        return runCatching { applyFile(target); true }.getOrElse {
            val backup = File(target.parentFile, "${target.name}.invalid-${System.currentTimeMillis()}")
            runCatching { target.renameTo(backup) }
            false
        }
    }

    fun import(force: Boolean = false): Result<String> = runCatching {
        val target = file
        require(target.isFile) { "设置文件不存在：${target.path}" }
        require(target.length() <= MAX_BYTES) { "设置文件超过 2 MB，已拒绝导入" }
        if (!force && target.lastModified() <= statePrefs.getLong(KEY_LAST_WRITE, 0L)) {
            return@runCatching "设置文件没有新改动"
        }
        applyFile(target)
        "已从 ${target.name} 加载设置"
    }

    fun export(): Result<String> = runCatching {
        val dir = storage.settings
        require(dir.isDirectory || dir.mkdirs()) { "无法创建设置目录：${dir.path}" }
        val json = buildJsonObject {
            prefs.all.forEach { (key, value) ->
                if (key in SECRET_KEYS) return@forEach
                when (value) {
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Float -> put(key, value)
                    is String -> put(key, value)
                    is Set<*> -> putJsonArray(key) {
                        value.filterIsInstance<String>().forEach { add(it) }
                    }
                }
            }
        }
        val text = PRETTY.encodeToString(JsonObject.serializer(), json)
        val target = file
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
        statePrefs.edit().putLong(KEY_LAST_WRITE, target.lastModified()).apply()
        "已导出设置到 ${target.name}"
    }

    private fun applyFile(target: File) {
        val raw = Json.parseToJsonElement(target.readText()).jsonObject
        val existing = prefs.all
        val editor = prefs.edit()
        raw.forEach { (key, element) ->
            if (key in SECRET_KEYS) return@forEach
            when (val current = existing[key]) {
                is Boolean -> (element as? JsonPrimitive)?.booleanOrNull
                    ?.let { editor.putBoolean(key, it) }
                is Int -> (element as? JsonPrimitive)?.intOrNull
                    ?.let { editor.putInt(key, it) }
                is Long -> (element as? JsonPrimitive)?.longOrNull
                    ?.let { editor.putLong(key, it) }
                is Float -> (element as? JsonPrimitive)?.floatOrNull
                    ?.let { editor.putFloat(key, it) }
                is String -> (element as? JsonPrimitive)?.contentOrNull
                    ?.let { editor.putString(key, it) }
                is Set<*> -> (element as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.toSet()
                    ?.let { editor.putStringSet(key, it) }
            }
        }
        editor.apply()
        statePrefs.edit().putLong(KEY_LAST_WRITE, target.lastModified()).apply()
    }

    internal companion object {
        const val STATE_PREFS = "iris_storage"
        const val KEY_LAST_WRITE = "settings_mirror_write_at"
        const val MAX_BYTES = 2L * 1024 * 1024

        val PRETTY = Json { prettyPrint = true }

        val SECRET_KEYS = setOf(
            "api_key",
            "motion_api_key",
            "asset_token",
            "tts_cloud_key",
            "stt_cloud_key",
        )
    }
}
