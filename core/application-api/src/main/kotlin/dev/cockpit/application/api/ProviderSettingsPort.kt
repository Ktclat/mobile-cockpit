package dev.cockpit.application.api

import dev.cockpit.domain.AgentId
import kotlinx.coroutines.flow.Flow

enum class ProviderProfileKindInput {
    OPENAI,
    OPENAI_COMPATIBLE,
    ANTHROPIC,
}

enum class ProviderProtocol {
    OPENAI_RESPONSES,
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
}

enum class ProviderAuthenticationType { BEARER, X_API_KEY }

enum class ProviderCredentialUpdate { KEEP, REPLACE, DELETE }

enum class ProviderVendor {
    OPENAI,
    DEEPSEEK,
    GEMINI,
    GLM,
    ANTHROPIC,
    CUSTOM,
}

enum class ProviderProbeState { NOT_TESTED, AVAILABLE, UNAVAILABLE, INCONCLUSIVE }

enum class ProviderModelSource { DISCOVERED, MANUAL, MIGRATED }

enum class ProviderModelDiscoveryState { CURRENT, STALE, NOT_DISCOVERED }

data class ProviderModelRouteView(
    val connectionId: String,
    val modelId: String,
)

data class ProviderModelOptionView(
    val id: String,
    val connectionId: String,
    val remoteModelId: String,
    val displayName: String,
    val enabled: Boolean,
    val source: ProviderModelSource,
    val discoveryState: ProviderModelDiscoveryState,
    val discoveredAtEpochMillis: Long?,
)

data class ProviderProfileInput(
    val id: String? = null,
    val displayName: String,
    val vendor: ProviderVendor,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val apiKey: String = "",
    val credentialUpdate: ProviderCredentialUpdate = ProviderCredentialUpdate.KEEP,
    val note: String = "",
    val authenticationType: ProviderAuthenticationType = ProviderAuthenticationType.BEARER,
    val maxOutputTokens: Int = 4096,
    val enabled: Boolean = true,
    val anthropicVersion: String = "2023-06-01",
    val organizationId: String = "",
    val projectId: String = "",
    val workspaceId: String = "",
)

data class ProviderBatchEntryInput(
    val displayName: String,
    val apiKey: String,
)

data class ProviderBatchInput(
    val vendor: ProviderVendor,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val authenticationType: ProviderAuthenticationType,
    val entries: List<ProviderBatchEntryInput>,
    val maxOutputTokens: Int = 4096,
    val anthropicVersion: String = "2023-06-01",
)

data class ProviderBatchItemResult(
    val displayName: String,
    val success: Boolean,
    val message: String,
    val profileId: String? = null,
)

data class ProviderBatchResult(val items: List<ProviderBatchItemResult>) {
    val savedCount: Int get() = items.count { it.success }
}

data class ProviderProfileView(
    val id: String,
    val displayName: String,
    val vendor: ProviderVendor,
    val kind: ProviderProfileKindInput,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val note: String,
    val model: String,
    val preferredModelId: String?,
    val models: List<ProviderModelOptionView>,
    val authenticationType: ProviderAuthenticationType,
    val maxOutputTokens: Int,
    val enabled: Boolean,
    val credentialConfigured: Boolean,
    val credentialHint: String,
    val anthropicVersion: String,
    val organizationId: String,
    val projectId: String,
    val workspaceId: String,
    val probeState: ProviderProbeState,
    val probeMessage: String?,
    val lastTestModelId: String?,
    val lastProbedAtEpochMillis: Long?,
    val lastTestElapsedMillis: Long?,
    val referencedAgentCount: Int,
    val referencedConversationCount: Int,
    val isGlobalDefault: Boolean,
)

data class AgentProviderBindingView(
    val agentId: AgentId,
    val providerProfileId: String,
    val modelId: String?,
)

data class ProviderSettingsSnapshot(
    val profiles: List<ProviderProfileView> = emptyList(),
    val bindings: List<AgentProviderBindingView> = emptyList(),
    val globalDefaultRoute: ProviderModelRouteView? = null,
)

enum class ProviderOperationCode {
    SUCCEEDED,
    FAILED,
    PROVIDER_CONFIG_TRANSACTION_FAILED,
    PROVIDER_CREDENTIAL_CLEANUP_PENDING,
}

data class ProviderOperationResult(
    val success: Boolean,
    val message: String,
    val profileId: String? = null,
    val modelId: String? = null,
    val code: ProviderOperationCode = if (success) {
        ProviderOperationCode.SUCCEEDED
    } else {
        ProviderOperationCode.FAILED
    },
)

interface ProviderSettingsPort {
    fun observeSettings(): Flow<ProviderSettingsSnapshot>
    suspend fun saveProfile(input: ProviderProfileInput): ProviderOperationResult
    suspend fun saveBatch(input: ProviderBatchInput): ProviderBatchResult
    suspend fun setProfileEnabled(id: String, enabled: Boolean): ProviderOperationResult
    suspend fun deleteProfile(id: String): ProviderOperationResult
    suspend fun discoverModels(id: String): ProviderOperationResult
    suspend fun addModel(id: String, remoteModelId: String, displayName: String = ""): ProviderOperationResult
    suspend fun setModelEnabled(id: String, modelId: String, enabled: Boolean): ProviderOperationResult
    suspend fun setPreferredModel(id: String, modelId: String): ProviderOperationResult
    suspend fun setGlobalDefault(route: ProviderModelRouteView?): ProviderOperationResult
    suspend fun probeProfile(id: String, modelId: String? = null): ProviderOperationResult
    suspend fun bindAgent(
        agentId: AgentId,
        profileId: String,
        modelId: String? = null,
    ): ProviderOperationResult
}
