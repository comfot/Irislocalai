package com.iris.localai.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * 模型配置档案的持久化。支持多份配置（新建/重命名/删除/切换），
 * 数据以 JSON 存在 SharedPreferences，后续可平滑迁移到 Room。
 */
class ModelProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(decode(prefs.getString(KEY_PROFILES, null)))
    val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> _profiles.value.any { it.id == id } }
            ?: _profiles.value.first().id,
    )
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    fun active(): ModelProfile =
        _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.first()

    fun setActive(id: String) {
        if (_profiles.value.none { it.id == id }) return
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun create(name: String): ModelProfile {
        val profile = defaultModelProfile(
            id = "profile-${UUID.randomUUID()}",
            name = name.ifBlank { "新配置" },
        )
        _profiles.update { it + profile }
        persist()
        setActive(profile.id)
        return profile
    }

    fun rename(id: String, name: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        update(profile.copy(name = name.ifBlank { "未命名配置" }))
    }

    fun delete(id: String): Boolean {
        if (_profiles.value.size <= 1) return false
        if (_profiles.value.none { it.id == id }) return false
        _profiles.update { list -> list.filterNot { it.id == id } }
        persist()
        if (_activeId.value == id) setActive(_profiles.value.first().id)
        return true
    }

    fun update(profile: ModelProfile) {
        _profiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
        persist()
    }

    fun updateActive(transform: (ModelProfile) -> ModelProfile) {
        update(transform(active()))
    }

    private fun persist() {
        prefs.edit().putString(KEY_PROFILES, encode(_profiles.value)).apply()
    }

    private fun encode(profiles: List<ModelProfile>): String = buildJsonArray {
        profiles.forEach { profile ->
            addJsonObject {
                put("id", profile.id)
                put("name", profile.name)
                put("globalEnabled", profile.globalEnabled)
                put("useApiKeyPool", profile.useApiKeyPool)
                putJsonObject("provider") {
                    put("kind", profile.provider.kind.name)
                    put("vendor", profile.provider.vendor.name)
                    put("apiKey", profile.provider.apiKey)
                    put("endpoint", profile.provider.endpoint)
                    put("modelName", profile.provider.modelName)
                    put("localModelPath", profile.provider.localModelPath)
                    put("vision", profile.provider.capabilities.vision)
                    put("audio", profile.provider.capabilities.audio)
                    put("video", profile.provider.capabilities.video)
                    put("webSearch", profile.provider.capabilities.webSearch)
                }
                putJsonObject("roles") {
                    ModelRole.entries.forEach { role ->
                        val config = profile.role(role)
                        putJsonObject(role.name) {
                            put("enabled", config.enabled)
                            put("modelName", config.modelName)
                            put("contextLength", config.contextLength)
                            put("maxOutputTokens", config.maxOutputTokens)
                            put("temperature", config.temperature)
                            put("topP", config.topP)
                            put("summaryEnabled", config.summaryEnabled)
                            put("summaryModel", config.summaryModel)
                            put("thinkingEnabled", config.thinkingEnabled)
                            put("thinkingBudget", config.thinkingBudget)
                            put("toolCalling", config.toolCalling)
                            putJsonObject("customHeaders") {
                                config.customHeaders.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    private fun decode(raw: String?): List<ModelProfile> {
        if (raw.isNullOrBlank()) return listOf(defaultModelProfile())
        return runCatching {
            val profiles = Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val providerObj = obj["provider"] as? JsonObject
                val provider = if (providerObj == null) {
                    ProviderConfig()
                } else {
                    ProviderConfig(
                        kind = providerObj.enumValue("kind", ProviderKind.CLOUD),
                        vendor = providerObj.enumValue("vendor", CloudVendor.OPENAI),
                        apiKey = providerObj.string("apiKey"),
                        endpoint = providerObj.string("endpoint"),
                        modelName = providerObj.string("modelName"),
                        localModelPath = providerObj.string("localModelPath"),
                        capabilities = ModelCapabilities(
                            vision = providerObj.bool("vision"),
                            audio = providerObj.bool("audio"),
                            video = providerObj.bool("video"),
                            webSearch = providerObj.bool("webSearch"),
                        ),
                    )
                }
                val rolesObj = obj["roles"] as? JsonObject
                val roles = ModelRole.entries.associateWith { role ->
                    val roleObj = rolesObj?.get(role.name) as? JsonObject
                    if (roleObj == null) {
                        defaultRoleConfig(role)
                    } else {
                        RoleConfig(
                            enabled = roleObj.bool("enabled", true),
                            modelName = roleObj.string("modelName"),
                            contextLength = roleObj.int("contextLength", 8192),
                            maxOutputTokens = roleObj.int("maxOutputTokens", 2048),
                            temperature = roleObj.float("temperature", 0.7f),
                            topP = roleObj.float("topP", 0.95f),
                            summaryEnabled = roleObj.bool("summaryEnabled", true),
                            summaryModel = roleObj.string("summaryModel"),
                            thinkingEnabled = roleObj.bool("thinkingEnabled", true),
                            thinkingBudget = roleObj.int("thinkingBudget", 1024),
                            toolCalling = roleObj.bool("toolCalling", true),
                            customHeaders = (roleObj["customHeaders"] as? JsonObject)
                                ?.mapValues { (_, value) ->
                                    (value as? JsonPrimitive)?.contentOrNull ?: ""
                                }
                                ?: emptyMap(),
                        )
                    }
                }
                ModelProfile(
                    id = obj.string("id", UUID.randomUUID().toString()),
                    name = obj.string("name", "未命名配置"),
                    provider = provider,
                    globalEnabled = obj.bool("globalEnabled", true),
                    useApiKeyPool = obj.bool("useApiKeyPool", false),
                    roles = roles,
                )
            }
            profiles.ifEmpty { listOf(defaultModelProfile()) }
        }.getOrElse { listOf(defaultModelProfile()) }
    }

    private fun JsonObject.string(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    private fun JsonObject.int(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    private fun JsonObject.float(key: String, default: Float): Float =
        (this[key] as? JsonPrimitive)?.floatOrNull ?: default

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(key: String, fallback: T): T {
        val name = string(key)
        return enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }

    private companion object {
        const val FILE = "iris_model_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active"
    }
}
