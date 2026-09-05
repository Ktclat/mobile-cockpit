package dev.cockpit.persistence.room

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.persistence.api.AgentProviderBindingPersistenceState
import dev.cockpit.persistence.api.ConversationProviderRoutePersistenceState
import dev.cockpit.persistence.api.ConversationProviderRouteResolution
import dev.cockpit.persistence.api.ProviderConfigurationRepository
import dev.cockpit.persistence.api.ProviderConfigurationSnapshot
import dev.cockpit.persistence.api.ProviderModelOptionPersistenceState
import dev.cockpit.persistence.api.ProviderModelRoutePersistenceState
import dev.cockpit.persistence.api.ProviderProfilePersistenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomProviderConfigurationRepository(
    private val database: CockpitDatabase,
) : ProviderConfigurationRepository {
    override fun observeConfiguration(): Flow<ProviderConfigurationSnapshot> =
        database.invalidationTracker
            .createFlow(
                "provider_profiles",
                "provider_model_options",
                "provider_settings",
                "agent_provider_bindings",
                "conversation_provider_routes",
            )
            .map { loadConfiguration() }

    override suspend fun loadConfiguration(): ProviderConfigurationSnapshot =
        database.withReadTransaction { configurationSnapshot() }

    override suspend fun loadProfile(id: String): ProviderProfilePersistenceState? =
        database.withReadTransaction { database.providerProfileDao().find(id)?.toState() }

    override suspend fun loadModel(id: String): ProviderModelOptionPersistenceState? =
        database.withReadTransaction { database.providerModelOptionDao().find(id)?.toState() }

    override suspend fun modelsForProfile(id: String): List<ProviderModelOptionPersistenceState> =
        database.withReadTransaction {
            database.providerModelOptionDao().forConnection(id).map { it.toState() }
        }

    override suspend fun profileForAgent(agentId: AgentId): ProviderProfilePersistenceState? =
        database.withReadTransaction { resolveAgentRoute(agentId)?.resolvedProfile() }

    override suspend fun resolveConversationRoute(
        conversationId: ConversationId,
        agentId: AgentId,
    ): ConversationProviderRouteResolution = database.withWriteTransaction {
        val existing = database.conversationProviderRouteDao().forConversation(conversationId.value)
        if (existing != null) return@withWriteTransaction existing.resolveLockedRoute()

        val existingMessages = database.messageDao().forConversation(conversationId.value)
        if (!existingMessages.isSafeForInitialRouteBinding()) {
            return@withWriteTransaction ConversationProviderRouteResolution.Missing
        }

        val resolved = resolveAgentRoute(agentId)
            ?: return@withWriteTransaction ConversationProviderRouteResolution.Missing
        val profile = resolved.resolvedProfile()
            ?: return@withWriteTransaction ConversationProviderRouteResolution.Missing
        val route = ConversationProviderRouteEntity(
            conversationId = conversationId.value,
            providerProfileId = resolved.connectionId,
            modelId = resolved.modelId,
            requestRevision = profile.revision,
        )
        database.conversationProviderRouteDao().upsert(route)
        ConversationProviderRouteResolution.Ready(profile, route.toState())
    }

    override suspend fun migrateConversationRoute(
        conversationId: ConversationId,
    ): ConversationProviderRouteResolution = database.withWriteTransaction {
        val existing = database.conversationProviderRouteDao().forConversation(conversationId.value)
        if (existing == null) {
            val conversation = database.conversationDao().find(conversationId.value)
                ?: return@withWriteTransaction ConversationProviderRouteResolution.Missing
            val route = resolveAgentRoute(AgentId(conversation.agentId))
                ?: return@withWriteTransaction ConversationProviderRouteResolution.Missing
            val resolved = route.resolvedProfile()
                ?: return@withWriteTransaction ConversationProviderRouteResolution.Missing
            val bound = ConversationProviderRouteEntity(
                conversationId = conversationId.value,
                providerProfileId = route.connectionId,
                modelId = route.modelId,
                requestRevision = resolved.revision,
            )
            database.conversationProviderRouteDao().upsert(bound)
            return@withWriteTransaction ConversationProviderRouteResolution.Ready(resolved, bound.toState())
        }
        val resolved = ProviderModelRoutePersistenceState(
            existing.providerProfileId,
            existing.modelId,
        ).resolvedProfile() ?: return@withWriteTransaction ConversationProviderRouteResolution.Missing
        val migrated = existing.copy(requestRevision = resolved.revision)
        database.conversationProviderRouteDao().upsert(migrated)
        ConversationProviderRouteResolution.Ready(resolved, migrated.toState())
    }

    override suspend fun saveProfile(profile: ProviderProfilePersistenceState) =
        database.withWriteTransaction { database.providerProfileDao().upsert(profile.toEntity()) }

    override suspend fun saveModels(models: List<ProviderModelOptionPersistenceState>) {
        if (models.isEmpty()) return
        database.withWriteTransaction {
            database.providerModelOptionDao().upsertAll(models.map { it.toEntity() })
        }
    }

    override suspend fun saveModel(model: ProviderModelOptionPersistenceState) =
        database.withWriteTransaction { database.providerModelOptionDao().upsert(model.toEntity()) }

    override suspend fun deleteProfile(id: String) =
        database.withWriteTransaction { database.providerProfileDao().delete(id) }

    override suspend fun setGlobalDefault(route: ProviderModelRoutePersistenceState?) =
        database.withWriteTransaction {
            if (route == null) {
                database.providerSettingsDao().clear()
            } else {
                database.providerSettingsDao().upsert(
                    ProviderSettingsEntity(
                        defaultConnectionId = route.connectionId,
                        defaultModelId = route.modelId,
                    ),
                )
            }
        }

    override suspend fun bindAgent(binding: AgentProviderBindingPersistenceState) =
        database.withWriteTransaction {
            database.agentProviderBindingDao().upsert(
                AgentProviderBindingEntity(
                    binding.agentId.value,
                    binding.providerProfileId,
                    binding.modelId,
                ),
            )
        }

    private suspend fun configurationSnapshot(): ProviderConfigurationSnapshot {
        val settings = database.providerSettingsDao().get()
        return ProviderConfigurationSnapshot(
            profiles = database.providerProfileDao().all().map { it.toState() },
            models = database.providerModelOptionDao().all().map { it.toState() },
            bindings = database.agentProviderBindingDao().all().map {
                AgentProviderBindingPersistenceState(
                    AgentId(it.agentId),
                    it.providerProfileId,
                    it.modelId,
                )
            },
            globalDefaultRoute = settings?.defaultConnectionId?.let { connectionId ->
                settings.defaultModelId?.let { modelId ->
                    ProviderModelRoutePersistenceState(connectionId, modelId)
                }
            },
            conversationRoutes = database.conversationProviderRouteDao().all().map {
                ConversationProviderRoutePersistenceState(
                    ConversationId(it.conversationId),
                    it.providerProfileId,
                    it.modelId,
                    it.requestRevision,
                )
            },
        )
    }

    private suspend fun resolveAgentRoute(agentId: AgentId): ProviderModelRoutePersistenceState? {
        val binding = database.agentProviderBindingDao().forAgent(agentId.value)
        if (binding != null) {
            val connection = database.providerProfileDao().find(binding.providerProfileId) ?: return null
            val modelId = binding.modelId ?: connection.preferredModelId ?: return null
            return ProviderModelRoutePersistenceState(connection.id, modelId)
        }
        val settings = database.providerSettingsDao().get() ?: return null
        val connectionId = settings.defaultConnectionId ?: return null
        val modelId = settings.defaultModelId ?: return null
        return ProviderModelRoutePersistenceState(connectionId, modelId)
    }

    private suspend fun ProviderModelRoutePersistenceState.resolvedProfile(): ProviderProfilePersistenceState? {
        val connection = database.providerProfileDao().find(connectionId) ?: return null
        if (!connection.enabled) return null
        val model = database.providerModelOptionDao().find(modelId) ?: return null
        if (!model.enabled || model.connectionId != connection.id || model.remoteModelId.isBlank()) return null
        return connection.toState().copy(model = model.remoteModelId, preferredModelId = model.id)
    }

    private suspend fun ConversationProviderRouteEntity.resolveLockedRoute(): ConversationProviderRouteResolution {
        val profile = database.providerProfileDao().find(providerProfileId)
            ?: return ConversationProviderRouteResolution.Missing
        if (profile.revision != requestRevision) {
            return ConversationProviderRouteResolution.RevisionMismatch(
                route = toState(),
                currentProfileRevision = profile.revision,
            )
        }
        val resolved = ProviderModelRoutePersistenceState(providerProfileId, modelId).resolvedProfile()
            ?: return ConversationProviderRouteResolution.Missing
        return ConversationProviderRouteResolution.Ready(resolved, toState())
    }

    private fun ConversationProviderRouteEntity.toState() = ConversationProviderRoutePersistenceState(
        conversationId = ConversationId(conversationId),
        providerProfileId = providerProfileId,
        modelId = modelId,
        requestRevision = requestRevision,
    )
}

private fun List<MessageEntity>.isSafeForInitialRouteBinding(): Boolean {
    if (isEmpty()) return true
    val message = singleOrNull() ?: return false
    return message.ordinal == 1L &&
        message.role == "AGENT" &&
        message.source == "RUNTIME" &&
        message.status == "DELIVERED"
}

private fun ProviderProfilePersistenceState.toEntity() = ProviderProfileEntity(
    id = id,
    displayName = displayName,
    vendor = vendor,
    kind = kind,
    baseUrl = baseUrl,
    model = model,
    credentialReference = credentialReference,
    credentialRotation = credentialRotation,
    maxOutputTokens = maxOutputTokens,
    revision = revision,
    streamingCapability = streamingCapability,
    toolCapability = toolCapability,
    lastProbeErrorCode = lastProbeErrorCode,
    lastProbeMessage = lastProbeMessage,
    lastProbedAtEpochMillis = lastProbedAtEpochMillis,
    note = note,
    enabled = enabled,
    authenticationType = authenticationType,
    anthropicVersion = anthropicVersion,
    organizationId = organizationId,
    projectId = projectId,
    workspaceId = workspaceId,
    preferredModelId = preferredModelId,
    credentialHint = credentialHint,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    lastTestModelId = lastTestModelId,
    lastTestElapsedMillis = lastTestElapsedMillis,
)

private fun ProviderProfileEntity.toState() = ProviderProfilePersistenceState(
    id = id,
    displayName = displayName,
    vendor = vendor,
    kind = kind,
    baseUrl = baseUrl,
    model = model,
    credentialReference = credentialReference,
    credentialRotation = credentialRotation,
    maxOutputTokens = maxOutputTokens,
    revision = revision,
    streamingCapability = streamingCapability,
    toolCapability = toolCapability,
    lastProbeErrorCode = lastProbeErrorCode,
    lastProbeMessage = lastProbeMessage,
    lastProbedAtEpochMillis = lastProbedAtEpochMillis,
    note = note,
    enabled = enabled,
    authenticationType = authenticationType,
    anthropicVersion = anthropicVersion,
    organizationId = organizationId,
    projectId = projectId,
    workspaceId = workspaceId,
    preferredModelId = preferredModelId,
    credentialHint = credentialHint,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    lastTestModelId = lastTestModelId,
    lastTestElapsedMillis = lastTestElapsedMillis,
)

private fun ProviderModelOptionPersistenceState.toEntity() = ProviderModelOptionEntity(
    id = id,
    connectionId = connectionId,
    remoteModelId = remoteModelId,
    displayName = displayName,
    enabled = enabled,
    source = source,
    discoveredAtEpochMillis = discoveredAtEpochMillis,
    discoveryState = discoveryState,
    textCapability = textCapability,
    visionCapability = visionCapability,
    toolCapability = toolCapability,
    reasoningCapability = reasoningCapability,
    capabilitySource = capabilitySource,
)

private fun ProviderModelOptionEntity.toState() = ProviderModelOptionPersistenceState(
    id = id,
    connectionId = connectionId,
    remoteModelId = remoteModelId,
    displayName = displayName,
    enabled = enabled,
    source = source,
    discoveredAtEpochMillis = discoveredAtEpochMillis,
    discoveryState = discoveryState,
    textCapability = textCapability,
    visionCapability = visionCapability,
    toolCapability = toolCapability,
    reasoningCapability = reasoningCapability,
    capabilitySource = capabilitySource,
)
