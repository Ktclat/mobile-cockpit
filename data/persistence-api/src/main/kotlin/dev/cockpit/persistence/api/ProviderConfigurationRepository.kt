package dev.cockpit.persistence.api

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import kotlinx.coroutines.flow.Flow

data class ProviderProfilePersistenceState(
    val id: String,
    val displayName: String,
    val vendor: String,
    val kind: String,
    val baseUrl: String,
    val model: String,
    val credentialReference: String,
    val credentialRotation: Long,
    val maxOutputTokens: Int,
    val revision: Long,
    val streamingCapability: String,
    val toolCapability: String,
    val lastProbeErrorCode: String?,
    val lastProbeMessage: String?,
    val lastProbedAtEpochMillis: Long?,
    val note: String = "",
    val enabled: Boolean = true,
    val authenticationType: String = "BEARER",
    val anthropicVersion: String = "2023-06-01",
    val organizationId: String = "",
    val projectId: String = "",
    val workspaceId: String = "",
    val preferredModelId: String? = null,
    val credentialHint: String = "",
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val lastTestModelId: String? = null,
    val lastTestElapsedMillis: Long? = null,
)

data class ProviderModelOptionPersistenceState(
    val id: String,
    val connectionId: String,
    val remoteModelId: String,
    val displayName: String,
    val enabled: Boolean,
    val source: String,
    val discoveredAtEpochMillis: Long?,
    val discoveryState: String,
    val textCapability: String = "UNKNOWN",
    val visionCapability: String = "UNKNOWN",
    val toolCapability: String = "UNKNOWN",
    val reasoningCapability: String = "UNKNOWN",
    val capabilitySource: String = "UNKNOWN",
)

data class ProviderModelRoutePersistenceState(
    val connectionId: String,
    val modelId: String,
)

data class AgentProviderBindingPersistenceState(
    val agentId: AgentId,
    val providerProfileId: String,
    val modelId: String? = null,
)

data class ConversationProviderRoutePersistenceState(
    val conversationId: ConversationId,
    val providerProfileId: String,
    val modelId: String,
    val requestRevision: Long,
)

sealed interface ConversationProviderRouteResolution {
    data class Ready(
        val profile: ProviderProfilePersistenceState,
        val route: ConversationProviderRoutePersistenceState,
    ) : ConversationProviderRouteResolution

    data class RevisionMismatch(
        val route: ConversationProviderRoutePersistenceState,
        val currentProfileRevision: Long,
    ) : ConversationProviderRouteResolution

    data object Missing : ConversationProviderRouteResolution
}

data class ProviderConfigurationSnapshot(
    val profiles: List<ProviderProfilePersistenceState>,
    val models: List<ProviderModelOptionPersistenceState>,
    val bindings: List<AgentProviderBindingPersistenceState>,
    val globalDefaultRoute: ProviderModelRoutePersistenceState?,
    val conversationRoutes: List<ConversationProviderRoutePersistenceState>,
)

interface ProviderConfigurationRepository {
    fun observeConfiguration(): Flow<ProviderConfigurationSnapshot>
    suspend fun loadConfiguration(): ProviderConfigurationSnapshot
    suspend fun loadProfile(id: String): ProviderProfilePersistenceState?
    suspend fun loadModel(id: String): ProviderModelOptionPersistenceState?
    suspend fun modelsForProfile(id: String): List<ProviderModelOptionPersistenceState>
    suspend fun profileForAgent(agentId: AgentId): ProviderProfilePersistenceState?
    suspend fun resolveConversationRoute(
        conversationId: ConversationId,
        agentId: AgentId,
    ): ConversationProviderRouteResolution
    suspend fun migrateConversationRoute(
        conversationId: ConversationId,
    ): ConversationProviderRouteResolution
    suspend fun saveProfile(profile: ProviderProfilePersistenceState)
    suspend fun saveModels(models: List<ProviderModelOptionPersistenceState>)
    suspend fun saveModel(model: ProviderModelOptionPersistenceState)
    suspend fun deleteProfile(id: String)
    suspend fun setGlobalDefault(route: ProviderModelRoutePersistenceState?)
    suspend fun bindAgent(binding: AgentProviderBindingPersistenceState)
}
