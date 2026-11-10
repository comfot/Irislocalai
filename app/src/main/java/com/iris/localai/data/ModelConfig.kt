package com.iris.localai.data

enum class ProviderKind(val label: String) {
    LOCAL("本地"),
    CLOUD("云端"),
}

enum class CloudVendor(
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest"),
    GOOGLE("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    CUSTOM("自定义", "", ""),
}

data class ModelCapabilities(
    val vision: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
    val webSearch: Boolean = false,
)

/** 提供商下拉中的一个模型；能力与启用状态按模型单独保存。 */
data class ProviderModel(
    val name: String,
    val enabled: Boolean = true,
    val capabilities: ModelCapabilities = ModelCapabilities(),
)

/**
 * 模型提供商独立于配置档案：切换档案只改变角色选用的提供商/模型，提供商本身不随档案切换。
 * [roles] 表示该提供商可承担的模型类型，用于模型配置里过滤可选提供商。
 */
data class ModelProvider(
    val id: String,
    val name: String,
    val kind: ProviderKind = ProviderKind.CLOUD,
    val vendor: CloudVendor = CloudVendor.OPENAI,
    val apiKey: String = "",
    val endpoint: String = CloudVendor.OPENAI.defaultEndpoint,
    val localModelPath: String = "",
    val roles: Set<ModelRole> = ModelRole.entries.toSet(),
    val models: List<ProviderModel> = emptyList(),
) {
    fun supports(role: ModelRole): Boolean = role in roles

    fun model(name: String): ProviderModel? =
        models.firstOrNull { it.name == name }

    fun firstModel(): ProviderModel? = models.firstOrNull { it.enabled }
}

data class RoleConfig(
    val providerId: String = "",
    val enabled: Boolean = true,
    val modelName: String = "",
    val contextLength: Int = 8192,
    val maxOutputTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val summaryEnabled: Boolean = true,
    val summaryModel: String = "",
    val thinkingEnabled: Boolean = true,
    val thinkingBudget: Int = 1024,
    val customHeaders: Map<String, String> = emptyMap(),
    val toolCalling: Boolean = true,
)

enum class ModelRole(val label: String, val description: String) {
    LANGUAGE("语言模型", "主对话、工具调用与上下文推理"),
    VISION("视觉模型", "图片理解与截图分析"),
    CREATIVE("创作模型", "长文、角色扮演与创意写作"),
    EMBEDDING("Embedding 模型", "记忆与知识库向量检索"),
    ACTION("动作模型", "手机操作与工具编排"),
}

fun defaultRoleConfig(role: ModelRole): RoleConfig = when (role) {
    ModelRole.LANGUAGE -> RoleConfig(
        contextLength = 8192,
        maxOutputTokens = 2048,
        temperature = 0.7f,
        summaryEnabled = true,
        thinkingEnabled = true,
        toolCalling = true,
    )
    ModelRole.VISION -> RoleConfig(
        contextLength = 8192,
        maxOutputTokens = 1024,
        temperature = 0.3f,
        summaryEnabled = false,
        thinkingEnabled = false,
        toolCalling = false,
    )
    ModelRole.CREATIVE -> RoleConfig(
        contextLength = 16384,
        maxOutputTokens = 4096,
        temperature = 0.9f,
        summaryEnabled = true,
        thinkingEnabled = true,
        toolCalling = false,
    )
    ModelRole.EMBEDDING -> RoleConfig(
        contextLength = 512,
        maxOutputTokens = 0,
        temperature = 0f,
        topP = 1f,
        summaryEnabled = false,
        thinkingEnabled = false,
        toolCalling = false,
    )
    ModelRole.ACTION -> RoleConfig(
        contextLength = 8192,
        maxOutputTokens = 1024,
        temperature = 0.2f,
        summaryEnabled = false,
        thinkingEnabled = false,
        toolCalling = true,
    )
}

fun defaultRoles(providerId: String = DEFAULT_PROVIDER_ID): Map<ModelRole, RoleConfig> =
    ModelRole.entries.associateWith { role ->
        defaultRoleConfig(role).copy(providerId = providerId)
    }

data class ModelProfile(
    val id: String,
    val name: String,
    val globalEnabled: Boolean = false,
    val useApiKeyPool: Boolean = false,
    val applyWorkspaceIds: Set<String> = emptySet(),
    val applyCharacterIds: Set<String> = emptySet(),
    val roles: Map<ModelRole, RoleConfig> = defaultRoles(),
) {
    fun role(role: ModelRole): RoleConfig = roles[role] ?: defaultRoleConfig(role)
}

const val DEFAULT_PROVIDER_ID = "provider-default"

fun defaultModelProvider(
    id: String = DEFAULT_PROVIDER_ID,
    name: String = "OpenAI",
): ModelProvider = ModelProvider(
    id = id,
    name = name,
    kind = ProviderKind.CLOUD,
    vendor = CloudVendor.OPENAI,
    endpoint = CloudVendor.OPENAI.defaultEndpoint,
    roles = ModelRole.entries.toSet(),
    models = listOf(ProviderModel(CloudVendor.OPENAI.defaultModel)),
)

fun defaultModelProfile(id: String = "default", name: String = "默认配置"): ModelProfile =
    ModelProfile(
        id = id,
        name = name,
        roles = defaultRoles(),
    )
