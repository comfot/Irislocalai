package com.iris.localai.data

import android.content.Context
import com.iris.localai.data.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

/**
 * 模型提供商与配置档案的持久化。
 *
 * 提供商是跨档案复用的全局实体；档案只保存每个角色选中的 providerId、模型名与高级参数。
 * 旧版本把提供商直接放在档案里，读取时会自动迁移为独立提供商。
 */
class ModelProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val loaded = load()
    private val _providers = MutableStateFlow(loaded.providers)
    val providers: StateFlow<List<ModelProvider>> = _providers.asStateFlow()

    private val _profiles = MutableStateFlow(loaded.profiles)
    val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> _profiles.value.any { it.id == id } }
            ?: _profiles.value.first().id,
    )
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    init {
        // 旧数据迁移得到的提供商必须立刻落盘，否则下次启动会丢失 API Key。
        persistProviders()
        persistProfiles()
    }

    fun active(): ModelProfile =
        _profiles.value.firstOrNull { it.id == _activeId.value } ?: _profiles.value.first()

    fun provider(id: String?): ModelProvider? =
        id?.takeIf { it.isNotBlank() }?.let { wanted -> _providers.value.firstOrNull { it.id == wanted } }

    fun providersFor(role: ModelRole): List<ModelProvider> {
        val matching = _providers.value.filter { it.supports(role) }
        return if (matching.isNotEmpty()) matching else _providers.value
    }

    fun providerFor(profile: ModelProfile, role: ModelRole): ModelProvider {
        val config = profile.role(role)
        return provider(config.providerId)
            ?.takeIf { it.supports(role) }
            ?: providersFor(role).firstOrNull()
            ?: _providers.value.first()
    }

    fun modelFor(profile: ModelProfile, role: ModelRole): ProviderModel? {
        val provider = providerFor(profile, role)
        val selected = profile.role(role).modelName.trim()
        return provider.model(selected)?.takeIf { it.enabled }
            ?: provider.firstModel()
    }

    fun modelNameFor(profile: ModelProfile, role: ModelRole): String =
        modelFor(profile, role)?.name
            ?: providerFor(profile, role).vendor.defaultModel
            .ifBlank { role.label }

    /** 全局档案优先，其次按当前生活角色卡、工作空间匹配，最后回退到手动选中的档案。 */
    fun resolve(space: Space, characterId: String = "", workspaceId: String = ""): ModelProfile {
        val all = _profiles.value
        all.firstOrNull { it.globalEnabled }?.let { return it }
        if (space == Space.LIFE && characterId.isNotBlank()) {
            all.firstOrNull { characterId in it.applyCharacterIds }?.let { return it }
        }
        if (workspaceId.isNotBlank()) {
            all.firstOrNull { workspaceId in it.applyWorkspaceIds }?.let { return it }
        }
        return active()
    }

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
        persistProfiles()
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
        persistProfiles()
        if (_activeId.value == id) setActive(_profiles.value.first().id)
        return true
    }

    fun update(profile: ModelProfile) {
        _profiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
        persistProfiles()
    }

    fun updateActive(transform: (ModelProfile) -> ModelProfile) {
        update(transform(active()))
    }

    /** 全局配置同时只能由一个档案承担。 */
    fun setGlobalEnabled(id: String, enabled: Boolean) {
        _profiles.update { list ->
            list.map { profile ->
                when {
                    profile.id == id -> profile.copy(globalEnabled = enabled)
                    enabled -> profile.copy(globalEnabled = false)
                    else -> profile
                }
            }
        }
        persistProfiles()
    }

    fun setApplyTargets(id: String, workspaceIds: Set<String>, characterIds: Set<String>) {
        _profiles.update { list ->
            list.map {
                if (it.id == id) {
                    it.copy(applyWorkspaceIds = workspaceIds, applyCharacterIds = characterIds)
                } else {
                    it
                }
            }
        }
        persistProfiles()
    }

    fun isProviderNameAvailable(name: String, exceptId: String? = null): Boolean {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return false
        return _providers.value.none {
            it.id != exceptId && it.name.equals(cleaned, ignoreCase = true)
        }
    }

    fun addProvider(name: String, kind: ProviderKind, roles: Set<ModelRole>): ModelProvider? {
        val cleaned = name.trim()
        if (!isProviderNameAvailable(cleaned)) return null
        val provider = ModelProvider(
            id = "provider-${UUID.randomUUID()}",
            name = cleaned,
            kind = kind,
            vendor = if (kind == ProviderKind.CLOUD) CloudVendor.OPENAI else CloudVendor.CUSTOM,
            endpoint = if (kind == ProviderKind.CLOUD) CloudVendor.OPENAI.defaultEndpoint else "",
            roles = roles.ifEmpty { ModelRole.entries.toSet() },
            models = if (kind == ProviderKind.CLOUD) {
                listOf(ProviderModel(CloudVendor.OPENAI.defaultModel))
            } else {
                emptyList()
            },
        )
        _providers.update { it + provider }
        persistProviders()
        return provider
    }

    fun updateProvider(provider: ModelProvider): Boolean {
        if (!isProviderNameAvailable(provider.name, provider.id)) return false
        _providers.update { list -> list.map { if (it.id == provider.id) provider else it } }
        persistProviders()
        return true
    }

    fun deleteProvider(id: String): Boolean {
        if (_providers.value.size <= 1) return false
        if (_providers.value.none { it.id == id }) return false
        _providers.update { list -> list.filterNot { it.id == id } }
        val remaining = _providers.value
        _profiles.update { profiles ->
            profiles.map { profile ->
                profile.copy(
                    roles = profile.roles.mapValues { (role, config) ->
                        if (config.providerId != id) {
                            config
                        } else {
                            val fallback = remaining.firstOrNull { it.supports(role) } ?: remaining.first()
                            config.copy(providerId = fallback.id, modelName = "")
                        }
                    },
                )
            }
        }
        persistProviders()
        persistProfiles()
        return true
    }

    fun addModel(providerId: String, name: String): Boolean {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return false
        val provider = provider(providerId) ?: return false
        if (provider.models.any { it.name.equals(cleaned, ignoreCase = true) }) return false
        updateProvider(provider.copy(models = provider.models + ProviderModel(cleaned)))
        return true
    }

    fun addModels(providerId: String, names: List<String>): Int {
        val provider = provider(providerId) ?: return 0
        val existing = provider.models.map { it.name.lowercase() }.toMutableSet()
        val added = names.map { it.trim() }
            .filter { it.isNotEmpty() && existing.add(it.lowercase()) }
            .map { ProviderModel(it) }
        if (added.isEmpty()) return 0
        updateProvider(provider.copy(models = provider.models + added))
        return added.size
    }

    fun setModelEnabled(providerId: String, modelName: String, enabled: Boolean) {
        val provider = provider(providerId) ?: return
        updateProvider(
            provider.copy(
                models = provider.models.map {
                    if (it.name == modelName) it.copy(enabled = enabled) else it
                },
            ),
        )
    }

    fun updateModelCapabilities(
        providerId: String,
        modelName: String,
        transform: (ModelCapabilities) -> ModelCapabilities,
    ) {
        val provider = provider(providerId) ?: return
        updateProvider(
            provider.copy(
                models = provider.models.map {
                    if (it.name == modelName) it.copy(capabilities = transform(it.capabilities)) else it
                },
            ),
        )
    }

    fun removeModel(providerId: String, modelName: String) {
        val provider = provider(providerId) ?: return
        updateProvider(provider.copy(models = provider.models.filterNot { it.name == modelName }))
    }

    /** 市场下载完成或“设为本地模型”时调用：写入本地提供商并绑定当前档案的语言模型。 */
    fun assignLocalModel(file: File) {
        val existing = _providers.value.firstOrNull {
            it.kind == ProviderKind.LOCAL && it.name == DEFAULT_LOCAL_NAME
        }
        val provider = if (existing == null) {
            ModelProvider(
                id = "provider-${UUID.randomUUID()}",
                name = DEFAULT_LOCAL_NAME,
                kind = ProviderKind.LOCAL,
                vendor = CloudVendor.CUSTOM,
                localModelPath = file.absolutePath,
                roles = ModelRole.entries.toSet(),
                models = listOf(ProviderModel(file.nameWithoutExtension)),
            )
        } else {
            existing.copy(
                localModelPath = file.absolutePath,
                models = if (existing.models.any { it.name == file.nameWithoutExtension }) {
                    existing.models
                } else {
                    existing.models + ProviderModel(file.nameWithoutExtension)
                },
            )
        }
        if (existing == null) {
            _providers.update { it + provider }
        } else {
            _providers.update { list -> list.map { if (it.id == provider.id) provider else it } }
        }
        persistProviders()
        val profile = active()
        val language = profile.role(ModelRole.LANGUAGE).copy(
            providerId = provider.id,
            modelName = file.nameWithoutExtension,
        )
        update(profile.copy(roles = profile.roles + (ModelRole.LANGUAGE to language)))
    }

    private fun persistProfiles() {
        prefs.edit().putString(KEY_PROFILES, encodeProfiles(_profiles.value)).apply()
    }

    private fun persistProviders() {
        prefs.edit().putString(KEY_PROVIDERS, encodeProviders(_providers.value)).apply()
    }

    /** 备份用：提供商不写 apiKey，密钥留给本机；档案完整保留。 */
    fun snapshotForBackup(): JsonObject = buildJsonObject {
        put("active", _activeId.value)
        put("providers", Json.parseToJsonElement(encodeProviders(_providers.value, includeApiKey = false)))
        put("profiles", Json.parseToJsonElement(encodeProfiles(_profiles.value, includeSecrets = false)))
    }

    /** 恢复提供商与档案；与本机 id 相同的提供商沿用本机 apiKey，避免密钥被清空。 */
    fun restoreFromBackup(json: JsonObject) {
        val editor = prefs.edit()
        (json["providers"] as? JsonArray)?.let { providers ->
            val knownKeys = _providers.value.associate { it.id to it.apiKey }
            val merged = buildJsonArray {
                providers.forEach { element ->
                    val obj = element as? JsonObject ?: return@forEach
                    addJsonObject {
                        obj.forEach { (key, value) ->
                            if (key != "apiKey") put(key, value)
                        }
                        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                        val key = knownKeys[id].orEmpty()
                        if (key.isNotBlank()) put("apiKey", key)
                    }
                }
            }
            editor.putString(KEY_PROVIDERS, merged.toString())
        }
        (json["profiles"] as? JsonArray)?.let { editor.putString(KEY_PROFILES, it.toString()) }
        (json["active"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { editor.putString(KEY_ACTIVE, it) }
        editor.apply()
        reload()
    }

    /** 外部写入偏好后重新读取提供商、档案与当前档案。 */
    fun reload() {
        val loaded = load()
        _providers.value = loaded.providers
        _profiles.value = loaded.profiles
        _activeId.value = prefs.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> _profiles.value.any { it.id == id } }
            ?: _profiles.value.first().id
    }

    private fun encodeProviders(providers: List<ModelProvider>, includeApiKey: Boolean = true): String = buildJsonArray {
        providers.forEach { provider ->
            addJsonObject {
                put("id", provider.id)
                put("name", provider.name)
                put("kind", provider.kind.name)
                put("vendor", provider.vendor.name)
                if (includeApiKey) put("apiKey", provider.apiKey)
                put("endpoint", provider.endpoint)
                put("localModelPath", provider.localModelPath)
                putJsonArray("roles") {
                    provider.roles.sortedBy { it.ordinal }.forEach { add(it.name) }
                }
                putJsonArray("models") {
                    provider.models.forEach { model ->
                        addJsonObject {
                            put("name", model.name)
                            put("enabled", model.enabled)
                            put("vision", model.capabilities.vision)
                            put("audio", model.capabilities.audio)
                            put("video", model.capabilities.video)
                            put("webSearch", model.capabilities.webSearch)
                        }
                    }
                }
            }
        }
    }.toString()

    private fun encodeProfiles(profiles: List<ModelProfile>, includeSecrets: Boolean = true): String = buildJsonArray {
        profiles.forEach { profile ->
            addJsonObject {
                put("id", profile.id)
                put("name", profile.name)
                put("globalEnabled", profile.globalEnabled)
                put("useApiKeyPool", profile.useApiKeyPool)
                putJsonArray("applyWorkspaceIds") { profile.applyWorkspaceIds.forEach { add(it) } }
                putJsonArray("applyCharacterIds") { profile.applyCharacterIds.forEach { add(it) } }
                putJsonObject("roles") {
                    ModelRole.entries.forEach { role ->
                        val config = profile.role(role)
                        putJsonObject(role.name) {
                            put("providerId", config.providerId)
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
                                config.customHeaders.forEach { (key, value) ->
                                    if (includeSecrets || !isSensitiveHeader(key)) put(key, value)
                                }
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    private fun isSensitiveHeader(key: String): Boolean {
        val name = key.lowercase()
        return listOf("authorization", "key", "token", "secret").any { it in name }
    }

    private fun load(): Loaded {
        val providerObjects = rawArray(KEY_PROVIDERS)
        val migrated = providerObjects.mapNotNull { element ->
            (element as? JsonObject)?.let(::decodeProvider)
        }.toMutableList()

        val profileObjects = rawArray(KEY_PROFILES)
        if (migrated.isEmpty() && profileObjects.isNotEmpty()) migrated += defaultModelProvider()
        val roleModelNames = mutableMapOf<String, MutableSet<String>>()
        val legacyCapabilities = mutableMapOf<String, ModelCapabilities>()
        val profiles = profileObjects.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val legacy = obj["provider"] as? JsonObject
            var legacyProviderId: String? = null
            if (legacy != null) {
                val candidate = decodeLegacyProvider(legacy)
                val existing = migrated.firstOrNull { sameProvider(it, candidate) }
                val provider = existing ?: candidate.copy(
                    id = "provider-${UUID.randomUUID()}",
                    name = uniqueProviderName(candidate.name, migrated),
                )
                if (existing == null) migrated += provider
                legacyProviderId = provider.id
                legacyCapabilities[provider.id] = candidate.models.firstOrNull()?.capabilities
                    ?: ModelCapabilities()
                val names = roleModelNames.getOrPut(provider.id) { mutableSetOf() }
                candidate.models.mapTo(names) { it.name }
            }
            decodeProfile(obj, legacyProviderId, migrated).also { profile ->
                profile.roles.forEach { (_, config) ->
                    if (config.modelName.isNotBlank()) {
                        roleModelNames.getOrPut(config.providerId) { mutableSetOf() }
                            .add(config.modelName)
                    }
                }
            }
        }.ifEmpty { listOf(defaultModelProfile()) }

        if (migrated.isEmpty()) migrated += defaultModelProvider()
        val mergedProviders = migrated.map { provider ->
            val names = roleModelNames[provider.id].orEmpty()
                .filter { it.isNotBlank() }
                .filterNot { name -> provider.models.any { it.name == name } }
            if (names.isEmpty()) {
                provider
            } else {
                val capabilities = legacyCapabilities[provider.id] ?: ModelCapabilities()
                provider.copy(
                    models = provider.models + names.map {
                        ProviderModel(it, capabilities = capabilities)
                    },
                )
            }
        }
        val activeId = prefs.getString(KEY_ACTIVE, null)
        val normalized = normalizeGlobalProfiles(profiles, activeId)
        return Loaded(mergedProviders, normalized)
    }

    private fun decodeProfile(
        obj: JsonObject,
        legacyProviderId: String?,
        providers: List<ModelProvider>,
    ): ModelProfile {
        val rolesObj = obj["roles"] as? JsonObject
        val roles = ModelRole.entries.associateWith { role ->
            val roleObj = rolesObj?.get(role.name) as? JsonObject
            if (roleObj == null) {
                defaultRoleConfig(role).copy(
                    providerId = legacyProviderId
                        ?: providers.firstOrNull { it.supports(role) }?.id
                        ?: providers.first().id,
                )
            } else {
                val providerId = roleObj.string("providerId").ifBlank {
                    legacyProviderId
                        ?: providers.firstOrNull { it.supports(role) }?.id
                        ?: providers.first().id
                }
                RoleConfig(
                    providerId = providerId,
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
        return ModelProfile(
            id = obj.string("id", UUID.randomUUID().toString()),
            name = obj.string("name", "未命名配置"),
            globalEnabled = obj.bool("globalEnabled", false),
            useApiKeyPool = obj.bool("useApiKeyPool", false),
            applyWorkspaceIds = obj.stringSet("applyWorkspaceIds"),
            applyCharacterIds = obj.stringSet("applyCharacterIds"),
            roles = roles,
        )
    }

    private fun decodeProvider(obj: JsonObject): ModelProvider {
        val kind = obj.enumValue("kind", ProviderKind.CLOUD)
        val rolesJson = obj["roles"] as? JsonArray
        val parsedRoles = rolesJson?.mapNotNull { element ->
            val name = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ModelRole.entries.firstOrNull { it.name == name }
        }?.toSet().orEmpty()
        val roles = if (parsedRoles.isEmpty()) ModelRole.entries.toSet() else parsedRoles
        val modelsJson = obj["models"] as? JsonArray
        val models = modelsJson?.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val name = model.string("name")
            if (name.isBlank()) {
                null
            } else {
                ProviderModel(
                    name = name,
                    enabled = model.bool("enabled", true),
                    capabilities = ModelCapabilities(
                        vision = model.bool("vision"),
                        audio = model.bool("audio"),
                        video = model.bool("video"),
                        webSearch = model.bool("webSearch"),
                    ),
                )
            }
        }.orEmpty()
        return ModelProvider(
            id = obj.string("id", "provider-${UUID.randomUUID()}"),
            name = obj.string("name", if (kind == ProviderKind.LOCAL) DEFAULT_LOCAL_NAME else "云端模型"),
            kind = kind,
            vendor = obj.enumValue("vendor", CloudVendor.OPENAI),
            apiKey = obj.string("apiKey"),
            endpoint = obj.string("endpoint"),
            localModelPath = obj.string("localModelPath"),
            roles = roles,
            models = models,
        )
    }

    /** 旧结构：provider 直接保存 kind/vendor/modelName/capabilities。 */
    private fun decodeLegacyProvider(obj: JsonObject): ModelProvider {
        val kind = obj.enumValue("kind", ProviderKind.CLOUD)
        val vendor = obj.enumValue("vendor", CloudVendor.OPENAI)
        val modelName = obj.string("modelName")
        val capabilities = ModelCapabilities(
            vision = obj.bool("vision"),
            audio = obj.bool("audio"),
            video = obj.bool("video"),
            webSearch = obj.bool("webSearch"),
        )
        return ModelProvider(
            id = "legacy",
            name = if (kind == ProviderKind.LOCAL) DEFAULT_LOCAL_NAME else vendor.label,
            kind = kind,
            vendor = vendor,
            apiKey = obj.string("apiKey"),
            endpoint = obj.string("endpoint").ifBlank { vendor.defaultEndpoint },
            localModelPath = obj.string("localModelPath"),
            roles = ModelRole.entries.toSet(),
            models = if (modelName.isBlank()) emptyList() else {
                listOf(ProviderModel(modelName, capabilities = capabilities))
            },
        )
    }

    private fun sameProvider(left: ModelProvider, right: ModelProvider): Boolean =
        left.kind == right.kind &&
            left.vendor == right.vendor &&
            left.endpoint == right.endpoint &&
            left.localModelPath == right.localModelPath &&
            left.apiKey == right.apiKey &&
            left.models.map { it.name } == right.models.map { it.name }

    private fun uniqueProviderName(base: String, providers: List<ModelProvider>): String {
        val cleaned = base.ifBlank { "模型提供商" }
        if (providers.none { it.name.equals(cleaned, ignoreCase = true) }) return cleaned
        var index = 2
        while (providers.any { it.name.equals("$cleaned ($index)", ignoreCase = true) }) index += 1
        return "$cleaned ($index)"
    }

    private fun normalizeGlobalProfiles(profiles: List<ModelProfile>, activeId: String?): List<ModelProfile> {
        val global = profiles.firstOrNull { it.id == activeId && it.globalEnabled }
            ?: profiles.firstOrNull { it.globalEnabled }
            ?: return profiles
        return profiles.map {
            if (it.globalEnabled && it.id != global.id) it.copy(globalEnabled = false) else it
        }
    }

    private fun rawArray(key: String): List<kotlinx.serialization.json.JsonElement> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(raw).jsonArray.toList() }.getOrDefault(emptyList())
    }

    private fun JsonObject.string(key: String, default: String = ""): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: default

    private fun JsonObject.int(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    private fun JsonObject.float(key: String, default: Float): Float =
        (this[key] as? JsonPrimitive)?.floatOrNull ?: default

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun JsonObject.stringSet(key: String): Set<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(key: String, fallback: T): T {
        val name = string(key)
        return enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }

    private data class Loaded(
        val providers: List<ModelProvider>,
        val profiles: List<ModelProfile>,
    )

    private companion object {
        const val FILE = "iris_model_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_PROVIDERS = "providers"
        const val KEY_ACTIVE = "active"
        const val DEFAULT_LOCAL_NAME = "本地模型"
    }
}
