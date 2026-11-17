package com.iris.localai.memory

import com.iris.localai.data.CloudVendor
import com.iris.localai.data.ModelProfile
import com.iris.localai.data.ModelProvider
import com.iris.localai.data.ModelRole
import com.iris.localai.data.ProviderKind
import com.iris.localai.data.ProviderModel
import com.iris.localai.data.RoleConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryEmbeddingStatusTest {

    private fun profile(role: RoleConfig = RoleConfig(modelName = "embed")) =
        ModelProfile(id = "profile", name = "profile", roles = mapOf(ModelRole.EMBEDDING to role))

    private fun provider(
        kind: ProviderKind = ProviderKind.CLOUD,
        vendor: CloudVendor = CloudVendor.OPENAI,
        apiKey: String = "key",
        models: List<ProviderModel> = listOf(ProviderModel("embed")),
    ) = ModelProvider(
        id = "provider",
        name = "provider",
        kind = kind,
        vendor = vendor,
        apiKey = apiKey,
        models = models,
    )

    private fun status(profile: ModelProfile?, provider: ModelProvider?) =
        MemoryEmbeddingClient({ profile }) { provider }.status()

    @Test
    fun reportsMissingProfile() {
        assertEquals(EmbeddingStatus.NO_PROFILE, status(null, provider()))
    }

    @Test
    fun reportsMissingProvider() {
        assertEquals(EmbeddingStatus.NO_PROVIDER, status(profile(), null))
    }

    @Test
    fun reportsDisabledRole() {
        assertEquals(
            EmbeddingStatus.ROLE_DISABLED,
            status(profile(RoleConfig(enabled = false, modelName = "embed")), provider()),
        )
    }

    @Test
    fun reportsMissingModel() {
        assertEquals(
            EmbeddingStatus.NO_MODEL,
            status(profile(RoleConfig(modelName = "")), provider(models = emptyList())),
        )
    }

    @Test
    fun reportsLocalProvider() {
        assertEquals(EmbeddingStatus.NOT_CLOUD, status(profile(), provider(kind = ProviderKind.LOCAL)))
    }

    @Test
    fun reportsUnsupportedVendor() {
        assertEquals(
            EmbeddingStatus.UNSUPPORTED_VENDOR,
            status(profile(), provider(vendor = CloudVendor.ANTHROPIC)),
        )
    }

    @Test
    fun reportsMissingApiKey() {
        assertEquals(EmbeddingStatus.NO_KEY, status(profile(), provider(apiKey = "")))
    }

    @Test
    fun reportsReadyForCompatibleCloudProvider() {
        assertEquals(EmbeddingStatus.READY, status(profile(), provider()))
    }
}
