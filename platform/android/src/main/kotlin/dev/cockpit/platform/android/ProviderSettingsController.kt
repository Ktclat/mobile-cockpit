package dev.cockpit.platform.android

import dev.cockpit.application.api.AgentProviderBindingView
import dev.cockpit.application.api.ProviderAuthenticationType
import dev.cockpit.application.api.ProviderBatchInput
import dev.cockpit.application.api.ProviderBatchItemResult
import dev.cockpit.application.api.ProviderBatchResult
import dev.cockpit.application.api.ProviderCredentialUpdate
import dev.cockpit.application.api.ProviderModelDiscoveryState
import dev.cockpit.application.api.ProviderModelOptionView
import dev.cockpit.application.api.ProviderModelRouteView
import dev.cockpit.application.api.ProviderModelSource
import dev.cockpit.application.api.ProviderOperationResult
import dev.cockpit.application.api.ProviderProbeState
import dev.cockpit.application.api.ProviderProfileInput
import dev.cockpit.application.api.ProviderProfileKindInput
import dev.cockpit.application.api.ProviderProfileView
import dev.cockpit.application.api.ProviderProtocol
import dev.cockpit.application.api.ProviderSettingsPort
import dev.cockpit.application.api.ProviderSettingsSnapshot
import dev.cockpit.application.api.ProviderVendor
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.credential.CredentialReference
import dev.cockpit.persistence.api.AgentProviderBindingPersistenceState
import dev.cockpit.persistence.api.ProviderConfigurationRepository
import dev.cockpit.persistence.api.ProviderModelOptionPersistenceState
import dev.cockpit.persistence.api.ProviderModelRoutePersistenceState
import dev.cockpit.persistence.api.ProviderProfilePersistenceState
import dev.cockpit.provider.api.NormalizedProviderRequest
import dev.cockpit.provider.api.ProviderAuthenticationType as RuntimeAuthenticationType
import dev.cockpit.provider.api.ProviderCapabilitySupport
import dev.cockpit.provider.api.ProviderEndpointResolver
import dev.cockpit.provider.api.ProviderErrorCode
import dev.cockpit.provider.api.ProviderInvocationId
import dev.cockpit.provider.api.ProviderKind
import dev.cockpit.provider.api.ProviderMessage
import dev.cockpit.provider.api.ProviderMessageRole
import dev.cockpit.provider.api.ProviderModelDiscoveryResult
import dev.cockpit.provider.api.ProviderProfile
import dev.cockpit.provider.api.ProviderProfileId
import dev.cockpit.provider.api.ProviderStreamEvent
import dev.cockpit.runtime.coordinator.ProviderInvocationGate
import dev.cockpit.security.vault.api.CredentialAdminPort
import dev.cockpit.security.vault.api.CredentialMetadata
import dev.cockpit.security.vault.api.NewCredential
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class ProviderSettingsController(
    private val repository: ProviderConfigurationRepository,
    private val credentials: CredentialAdminPort,
    private val invocationGate: ProviderInvocationGate,
) : ProviderSettingsPort {
    override fun observeSettings(): Flow<ProviderSettingsSnapshot> =
        repository.observeConfiguration().map { configuration ->
            val modelsByConnection = configuration.models.groupBy { it.connectionId }
            ProviderSettingsSnapshot(
                profiles = configuration.profiles.map { profile ->
                    val models = modelsByConnection[profile.id].orEmpty()
                    profile.toView(
                        models = models,
                        credentialConfigured = credentials.metadata(
                            CredentialReference(profile.credentialReference),
                        )?.rotation == profile.credentialRotation,
                        referencedAgentCount = configuration.bindings.count {
                            it.providerProfileId == profile.id
                        },
                        referencedConversationCount = configuration.conversationRoutes.count {
                            it.providerProfileId == profile.id
                        },
                        isGlobalDefault = configuration.globalDefaultRoute?.connectionId == profile.id,
                    )
                },
                bindings = configuration.bindings.map {
                    AgentProviderBindingView(it.agentId, it.providerProfileId, it.modelId)
                },
                globalDefaultRoute = configuration.globalDefaultRoute?.let {
                    ProviderModelRouteView(it.connectionId, it.modelId)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun saveProfile(input: ProviderProfileInput): ProviderOperationResult =
        withContext(Dispatchers.IO) {
            try {
                val existing = input.id?.let { repository.loadProfile(it) }
                if (input.id != null && existing == null) return@withContext failure("Connection not found.")
                if (input.displayName.isBlank()) return@withContext failure("Enter a configuration name.")
                val endpoint = ProviderEndpointResolver.normalizePrefix(input.baseUrl)
                val kind = input.protocol.toProviderKind()
                val auth = input.authenticationType.validFor(input.protocol)
                    ?: return@withContext failure("This authentication method is not valid for the selected protocol.")
                if (input.maxOutputTokens !in 1..131_072) {
                    return@withContext failure("Maximum output tokens must be between 1 and 131072.")
                }

                val id = existing?.id ?: UUID.randomUUID().toString()
                val credentialUpdate = if (existing == null) {
                    ProviderCredentialUpdate.REPLACE
                } else {
                    input.credentialUpdate
                }
                if (credentialUpdate == ProviderCredentialUpdate.REPLACE && input.apiKey.isBlank()) {
                    return@withContext failure("Enter an API key to replace the saved credential.")
                }
                if (existing == null && input.apiKey.isBlank()) {
                    return@withContext failure("An API key is required for a new configuration.")
                }

                var createdCredential: CredentialMetadata? = null
                val credential = when (credentialUpdate) {
                    ProviderCredentialUpdate.KEEP -> existing?.let {
                        credentials.metadata(CredentialReference(it.credentialReference))
                    } ?: return@withContext failure("Enter the API key again to repair this configuration.")
                    ProviderCredentialUpdate.REPLACE -> credentials.create(
                        NewCredential("Provider $id", input.apiKey.toCharArray()),
                    ).also { createdCredential = it }
                    ProviderCredentialUpdate.DELETE -> existing?.let {
                        CredentialMetadata(
                            CredentialReference(it.credentialReference),
                            "Provider $id",
                            it.credentialRotation,
                        )
                    } ?: return@withContext failure("A new configuration requires an API key.")
                }

                val requestChanged = existing == null ||
                    existing.kind != kind.name ||
                    existing.baseUrl != endpoint.apiPrefix ||
                    existing.authenticationType != auth.name ||
                    existing.anthropicVersion != input.anthropicVersion.trim() ||
                    existing.organizationId != input.organizationId.trim() ||
                    existing.projectId != input.projectId.trim() ||
                    existing.workspaceId != input.workspaceId.trim() ||
                    credentialUpdate != ProviderCredentialUpdate.KEEP
                val revision = when {
                    existing == null -> 0L
                    !requestChanged -> existing.revision
                    existing.revision == Long.MAX_VALUE -> return@withContext failure("Configuration revision is exhausted.")
                    else -> existing.revision + 1L
                }
                val now = System.currentTimeMillis()
                val candidate = ProviderProfilePersistenceState(
                    id = id,
                    displayName = input.displayName.trim(),
                    vendor = input.vendor.name,
                    kind = kind.name,
                    baseUrl = endpoint.apiPrefix,
                    model = existing?.model.orEmpty(),
                    credentialReference = credential.reference.value,
                    credentialRotation = credential.rotation,
                    maxOutputTokens = input.maxOutputTokens,
                    revision = revision,
                    streamingCapability = if (requestChanged) ProviderCapabilitySupport.UNKNOWN.name
                        else existing?.streamingCapability ?: ProviderCapabilitySupport.UNKNOWN.name,
                    toolCapability = if (requestChanged) ProviderCapabilitySupport.UNKNOWN.name
                        else existing?.toolCapability ?: ProviderCapabilitySupport.UNKNOWN.name,
                    lastProbeErrorCode = if (requestChanged) null else existing?.lastProbeErrorCode,
                    lastProbeMessage = if (requestChanged) null else existing?.lastProbeMessage,
                    lastProbedAtEpochMillis = if (requestChanged) null else existing?.lastProbedAtEpochMillis,
                    note = input.note.trim(),
                    enabled = input.enabled && credentialUpdate != ProviderCredentialUpdate.DELETE,
                    authenticationType = auth.name,
                    anthropicVersion = input.anthropicVersion.trim().ifBlank { "2023-06-01" },
                    organizationId = input.organizationId.trim(),
                    projectId = input.projectId.trim(),
                    workspaceId = input.workspaceId.trim(),
                    preferredModelId = existing?.preferredModelId,
                    credentialHint = when (credentialUpdate) {
                        ProviderCredentialUpdate.REPLACE -> credentialHint(input.apiKey)
                        ProviderCredentialUpdate.DELETE -> ""
                        ProviderCredentialUpdate.KEEP -> existing?.credentialHint.orEmpty()
                    },
                    createdAtEpochMillis = existing?.createdAtEpochMillis?.takeIf { it > 0L } ?: now,
                    updatedAtEpochMillis = now,
                    lastTestModelId = if (requestChanged) null else existing?.lastTestModelId,
                    lastTestElapsedMillis = if (requestChanged) null else existing?.lastTestElapsedMillis,
                )
                try {
                    repository.saveProfile(candidate)
                    if (requestChanged && existing != null) {
                        repository.saveModels(
                            repository.modelsForProfile(id).map { model ->
                                if (model.source == ProviderModelSource.MANUAL.name) model else model.copy(
                                    discoveryState = ProviderModelDiscoveryState.STALE.name,
                                )
                            },
                        )
                    }
                } catch (error: Exception) {
                    createdCredential?.let { credentials.delete(it.reference) }
                    throw error
                }
                if (credentialUpdate == ProviderCredentialUpdate.REPLACE && existing != null) {
                    runCatching { credentials.delete(CredentialReference(existing.credentialReference)) }
                } else if (credentialUpdate == ProviderCredentialUpdate.DELETE && existing != null) {
                    credentials.delete(CredentialReference(existing.credentialReference))
                }

                val suffixMessage = endpoint.removedGenerationSuffix?.let {
                    " The full $it endpoint was converted to its API prefix."
                }.orEmpty()
                ProviderOperationResult(
                    true,
                    "Configuration saved. It remains unverified until you run a conversation test.$suffixMessage",
                    id,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                failure(error.message ?: "Use a valid HTTPS API prefix.")
            } catch (_: Exception) {
                failure("The configuration could not be saved safely.")
            }
        }

    override suspend fun saveBatch(input: ProviderBatchInput): ProviderBatchResult =
        withContext(Dispatchers.IO) {
            val seen = mutableSetOf<String>()
            val results = input.entries.map { entry ->
                val key = entry.apiKey.trim()
                when {
                    entry.displayName.isBlank() -> ProviderBatchItemResult(
                        entry.displayName,
                        false,
                        "A configuration name is required.",
                    )
                    key.isBlank() -> ProviderBatchItemResult(
                        entry.displayName,
                        false,
                        "An API key is required.",
                    )
                    !seen.add(key) -> ProviderBatchItemResult(
                        entry.displayName,
                        false,
                        "This key is duplicated in the current batch.",
                    )
                    else -> {
                        val saved = saveProfile(
                            ProviderProfileInput(
                                displayName = entry.displayName,
                                vendor = input.vendor,
                                baseUrl = input.baseUrl,
                                protocol = input.protocol,
                                apiKey = key,
                                credentialUpdate = ProviderCredentialUpdate.REPLACE,
                                authenticationType = input.authenticationType,
                                maxOutputTokens = input.maxOutputTokens,
                                anthropicVersion = input.anthropicVersion,
                            ),
                        )
                        ProviderBatchItemResult(
                            entry.displayName,
                            saved.success,
                            saved.message,
                            saved.profileId,
                        )
                    }
                }
            }
            ProviderBatchResult(results)
        }

    override suspend fun setProfileEnabled(id: String, enabled: Boolean): ProviderOperationResult =
        withContext(Dispatchers.IO) {
            val profile = repository.loadProfile(id) ?: return@withContext failure("Configuration not found.")
            if (enabled) {
                val credential = credentials.metadata(CredentialReference(profile.credentialReference))
                    ?: return@withContext failure("Enter the API key again before enabling this configuration.")
                if (credential.rotation != profile.credentialRotation) {
                    return@withContext failure("The saved API key needs to be repaired before enabling.")
                }
                if (repository.modelsForProfile(id).none { it.enabled }) {
                    return@withContext failure("Enable at least one model before enabling this configuration.")
                }
            }
            repository.saveProfile(
                profile.copy(enabled = enabled, updatedAtEpochMillis = System.currentTimeMillis()),
            )
            ProviderOperationResult(true, if (enabled) "Configuration enabled." else "Configuration disabled.", id)
        }

    override suspend fun deleteProfile(id: String): ProviderOperationResult = withContext(Dispatchers.IO) {
        try {
            val profile = repository.loadProfile(id) ?: return@withContext failure("Configuration not found.")
            val configuration = repository.loadConfiguration()
            val agentCount = configuration.bindings.count { it.providerProfileId == id }
            val conversationCount = configuration.conversationRoutes.count { it.providerProfileId == id }
            val isDefault = configuration.globalDefaultRoute?.connectionId == id
            if (agentCount > 0 || conversationCount > 0 || isDefault) {
                val uses = buildList {
                    if (isDefault) add("the global default")
                    if (agentCount > 0) add("$agentCount Agent(s)")
                    if (conversationCount > 0) add("$conversationCount conversation(s)")
                }.joinToString(", ")
                return@withContext failure("This configuration is used by $uses. Change those routes before deleting it.")
            }
            repository.deleteProfile(id)
            credentials.delete(CredentialReference(profile.credentialReference))
            ProviderOperationResult(true, "Configuration and encrypted credential deleted.")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failure("The configuration could not be deleted.")
        }
    }

    override suspend fun discoverModels(id: String): ProviderOperationResult = withContext(Dispatchers.IO) {
        val persisted = repository.loadProfile(id) ?: return@withContext failure("Configuration not found.")
        val profile = runCatching { persisted.toProviderProfile() }.getOrNull()
            ?: return@withContext failure("The configuration is invalid. Edit and save it again.")
        when (val result = invocationGate.discoverModels(profile)) {
            is ProviderModelDiscoveryResult.Available -> {
                val now = System.currentTimeMillis()
                val existing = repository.modelsForProfile(id)
                val existingByRemoteId = existing.associateBy { it.remoteModelId }
                val discoveredIds = result.models.map { it.remoteModelId }.toSet()
                val merged = buildList {
                    existing.forEach { model ->
                        add(
                            when {
                                model.source == ProviderModelSource.MANUAL.name -> model
                                model.remoteModelId in discoveredIds -> model.copy(
                                    discoveryState = ProviderModelDiscoveryState.CURRENT.name,
                                    discoveredAtEpochMillis = now,
                                )
                                else -> model.copy(discoveryState = ProviderModelDiscoveryState.STALE.name)
                            },
                        )
                    }
                    result.models.forEach { remote ->
                        if (existingByRemoteId[remote.remoteModelId] == null) {
                            add(
                                ProviderModelOptionPersistenceState(
                                    id = UUID.randomUUID().toString(),
                                    connectionId = id,
                                    remoteModelId = remote.remoteModelId,
                                    displayName = remote.displayName,
                                    enabled = false,
                                    source = ProviderModelSource.DISCOVERED.name,
                                    discoveredAtEpochMillis = now,
                                    discoveryState = ProviderModelDiscoveryState.CURRENT.name,
                                ),
                            )
                        }
                    }
                }
                repository.saveModels(merged)
                ProviderOperationResult(
                    true,
                    if (result.models.isEmpty()) {
                        "The provider returned an empty model list. You can add an exact model ID manually."
                    } else {
                        "Found ${result.models.size} model(s). Choose which ones to enable."
                    },
                    id,
                )
            }
            is ProviderModelDiscoveryResult.Unavailable -> ProviderOperationResult(
                false,
                result.error.safeMessage + " You can still add a model ID manually.",
                id,
            )
            is ProviderModelDiscoveryResult.Unsupported -> ProviderOperationResult(
                false,
                result.message + " Add a model ID manually.",
                id,
            )
        }
    }

    override suspend fun addModel(
        id: String,
        remoteModelId: String,
        displayName: String,
    ): ProviderOperationResult = withContext(Dispatchers.IO) {
        val profile = repository.loadProfile(id) ?: return@withContext failure("Configuration not found.")
        val remoteId = remoteModelId.trim()
        if (remoteId.isBlank() || remoteId.any { it == '\r' || it == '\n' }) {
            return@withContext failure("Enter an exact model ID on one line.")
        }
        val existing = repository.modelsForProfile(id).firstOrNull { it.remoteModelId == remoteId }
        val model = existing?.copy(
            enabled = true,
            displayName = displayName.trim().ifBlank { existing.displayName },
        ) ?: ProviderModelOptionPersistenceState(
            id = UUID.randomUUID().toString(),
            connectionId = id,
            remoteModelId = remoteId,
            displayName = displayName.trim().ifBlank { remoteId },
            enabled = true,
            source = ProviderModelSource.MANUAL.name,
            discoveredAtEpochMillis = null,
            discoveryState = ProviderModelDiscoveryState.NOT_DISCOVERED.name,
        )
        repository.saveModel(model)
        if (profile.preferredModelId == null) {
            repository.saveProfile(
                profile.copy(
                    model = model.remoteModelId,
                    preferredModelId = model.id,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        ProviderOperationResult(true, "Model added and enabled.", id, model.id)
    }

    override suspend fun setModelEnabled(
        id: String,
        modelId: String,
        enabled: Boolean,
    ): ProviderOperationResult = withContext(Dispatchers.IO) {
        val model = repository.loadModel(modelId)
            ?.takeIf { it.connectionId == id }
            ?: return@withContext failure("Model not found.")
        if (!enabled) {
            val config = repository.loadConfiguration()
            if (config.globalDefaultRoute?.modelId == modelId ||
                config.bindings.any { it.modelId == modelId } ||
                config.conversationRoutes.any { it.modelId == modelId }
            ) {
                return@withContext failure("This model is in use. Change the affected route before disabling it.")
            }
        }
        repository.saveModel(model.copy(enabled = enabled))
        ProviderOperationResult(true, if (enabled) "Model enabled." else "Model disabled.", id, modelId)
    }

    override suspend fun setPreferredModel(id: String, modelId: String): ProviderOperationResult =
        withContext(Dispatchers.IO) {
            val profile = repository.loadProfile(id) ?: return@withContext failure("Configuration not found.")
            val model = repository.loadModel(modelId)
                ?.takeIf { it.connectionId == id && it.enabled }
                ?: return@withContext failure("Choose an enabled model from this configuration.")
            repository.saveProfile(
                profile.copy(
                    model = model.remoteModelId,
                    preferredModelId = model.id,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            ProviderOperationResult(true, "Preferred model updated. The global default was not changed.", id, modelId)
        }

    override suspend fun setGlobalDefault(route: ProviderModelRouteView?): ProviderOperationResult =
        withContext(Dispatchers.IO) {
            if (route == null) {
                repository.setGlobalDefault(null)
                return@withContext ProviderOperationResult(true, "Global default cleared.")
            }
            val profile = repository.loadProfile(route.connectionId)
                ?.takeIf { it.enabled }
                ?: return@withContext failure("Choose an enabled configuration.")
            val model = repository.loadModel(route.modelId)
                ?.takeIf { it.connectionId == profile.id && it.enabled }
                ?: return@withContext failure("Choose an enabled model from that configuration.")
            val credential = credentials.metadata(CredentialReference(profile.credentialReference))
            if (credential?.rotation != profile.credentialRotation) {
                return@withContext failure("The selected configuration does not have a usable saved credential.")
            }
            repository.setGlobalDefault(
                ProviderModelRoutePersistenceState(route.connectionId, route.modelId),
            )
            ProviderOperationResult(true, "Global default model updated.", route.connectionId, route.modelId)
        }

    override suspend fun probeProfile(id: String, modelId: String?): ProviderOperationResult =
        withContext(Dispatchers.IO) {
            val persisted = repository.loadProfile(id) ?: return@withContext failure("Configuration not found.")
            val selectedModelId = modelId ?: persisted.preferredModelId
                ?: return@withContext failure("Choose a model before running a conversation test.")
            val model = repository.loadModel(selectedModelId)
                ?.takeIf { it.connectionId == id && it.enabled }
                ?: return@withContext failure("Choose an enabled model from this configuration.")
            val profile = runCatching {
                persisted.copy(model = model.remoteModelId).toProviderProfile()
            }.getOrNull() ?: return@withContext failure("The configuration is invalid. Edit and save it again.")
            val invocationId = ProviderInvocationId(UUID.randomUUID().toString())
            val started = System.currentTimeMillis()
            val buffer = StringBuilder()
            var completed = false
            var failureCode: String? = null
            var failureMessage: String? = null
            try {
                invocationGate.stream(
                    NormalizedProviderRequest(
                        invocationId = invocationId,
                        profile = profile,
                        systemInstruction = "",
                        messages = listOf(
                            ProviderMessage(ProviderMessageRole.USER, "Please reply with only OK."),
                        ),
                        maxOutputTokens = profile.maxOutputTokens.coerceAtMost(TEST_MAX_OUTPUT_TOKENS),
                    ),
                ).collect { event ->
                    when (event) {
                        is ProviderStreamEvent.TextDelta -> if (buffer.length < 512) buffer.append(event.text)
                        is ProviderStreamEvent.Completed -> completed = true
                        is ProviderStreamEvent.Failed -> {
                            failureCode = event.error.code.name
                            failureMessage = event.error.safeMessage
                        }
                        is ProviderStreamEvent.ToolProposalDelta -> Unit
                    }
                }
            } catch (error: CancellationException) {
                invocationGate.cancel(profile.kind, invocationId)
                throw error
            }
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
            val inconclusive = completed && buffer.isBlank()
            val passed = completed && buffer.isNotBlank() && failureCode == null
            val message = when {
                passed -> "Conversation test passed in ${elapsed} ms · ${buffer.toString().trim().take(80)}"
                inconclusive -> "The request completed but returned no visible text; the result is inconclusive."
                else -> failureMessage ?: "The conversation test ended before completion."
            }
            repository.saveProfile(
                persisted.copy(
                    streamingCapability = if (passed) ProviderCapabilitySupport.SUPPORTED.name
                        else ProviderCapabilitySupport.UNKNOWN.name,
                    lastProbeErrorCode = when {
                        passed -> null
                        inconclusive -> INCONCLUSIVE_CODE
                        else -> failureCode ?: ProviderErrorCode.UNKNOWN_PROVIDER_ERROR.name
                    },
                    lastProbeMessage = message,
                    lastProbedAtEpochMillis = System.currentTimeMillis(),
                    lastTestModelId = model.id,
                    lastTestElapsedMillis = elapsed,
                ),
            )
            ProviderOperationResult(passed, message, id, model.id)
        }

    override suspend fun bindAgent(
        agentId: AgentId,
        profileId: String,
        modelId: String?,
    ): ProviderOperationResult = withContext(Dispatchers.IO) {
        try {
            val profile = repository.loadProfile(profileId)
                ?: return@withContext failure("Configuration not found.")
            val resolvedModelId = modelId ?: profile.preferredModelId
                ?: return@withContext failure("Choose a model for this Agent.")
            val model = repository.loadModel(resolvedModelId)
                ?.takeIf { it.connectionId == profileId && it.enabled }
                ?: return@withContext failure("Choose an enabled model for this Agent.")
            repository.bindAgent(
                AgentProviderBindingPersistenceState(agentId, profileId, model.id),
            )
            ProviderOperationResult(true, "Model route assigned to Agent.", profileId, model.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failure("The model route could not be assigned to this Agent.")
        }
    }

    private fun failure(message: String) = ProviderOperationResult(false, message)

    private companion object {
        const val INCONCLUSIVE_CODE = "INCONCLUSIVE"
        const val TEST_MAX_OUTPUT_TOKENS = 32
    }
}

internal fun ProviderProfilePersistenceState.toProviderProfile() = ProviderProfile(
    id = ProviderProfileId(id),
    displayName = displayName,
    kind = ProviderKind.valueOf(kind),
    baseUrl = baseUrl,
    model = model,
    credentialReference = CredentialReference(credentialReference),
    credentialRotation = credentialRotation,
    maxOutputTokens = maxOutputTokens,
    revision = revision,
    authenticationType = RuntimeAuthenticationType.valueOf(authenticationType),
    anthropicVersion = anthropicVersion,
    organizationId = organizationId,
    projectId = projectId,
    workspaceId = workspaceId,
)

private fun ProviderProfilePersistenceState.toView(
    models: List<ProviderModelOptionPersistenceState>,
    credentialConfigured: Boolean,
    referencedAgentCount: Int,
    referencedConversationCount: Int,
    isGlobalDefault: Boolean,
): ProviderProfileView {
    val modelViews = models.map { it.toView() }
    val preferred = modelViews.firstOrNull { it.id == preferredModelId }
    return ProviderProfileView(
        id = id,
        displayName = displayName,
        vendor = persistedVendor(),
        kind = when (ProviderKind.valueOf(kind)) {
            ProviderKind.OPENAI_RESPONSES -> ProviderProfileKindInput.OPENAI
            ProviderKind.OPENAI_COMPATIBLE -> ProviderProfileKindInput.OPENAI_COMPATIBLE
            ProviderKind.ANTHROPIC -> ProviderProfileKindInput.ANTHROPIC
        },
        protocol = ProviderKind.valueOf(kind).toProtocol(),
        baseUrl = baseUrl,
        note = note,
        model = preferred?.remoteModelId ?: model,
        preferredModelId = preferredModelId,
        models = modelViews,
        authenticationType = runCatching { ProviderAuthenticationType.valueOf(authenticationType) }
            .getOrDefault(ProviderAuthenticationType.BEARER),
        maxOutputTokens = maxOutputTokens,
        enabled = enabled,
        credentialConfigured = credentialConfigured,
        credentialHint = credentialHint,
        anthropicVersion = anthropicVersion,
        organizationId = organizationId,
        projectId = projectId,
        workspaceId = workspaceId,
        probeState = when {
            lastProbedAtEpochMillis == null -> ProviderProbeState.NOT_TESTED
            lastProbeErrorCode == null -> ProviderProbeState.AVAILABLE
            lastProbeErrorCode == "INCONCLUSIVE" -> ProviderProbeState.INCONCLUSIVE
            else -> ProviderProbeState.UNAVAILABLE
        },
        probeMessage = lastProbeMessage,
        lastTestModelId = lastTestModelId,
        lastProbedAtEpochMillis = lastProbedAtEpochMillis,
        lastTestElapsedMillis = lastTestElapsedMillis,
        referencedAgentCount = referencedAgentCount,
        referencedConversationCount = referencedConversationCount,
        isGlobalDefault = isGlobalDefault,
    )
}

private fun ProviderModelOptionPersistenceState.toView() = ProviderModelOptionView(
    id = id,
    connectionId = connectionId,
    remoteModelId = remoteModelId,
    displayName = displayName,
    enabled = enabled,
    source = runCatching { ProviderModelSource.valueOf(source) }.getOrDefault(ProviderModelSource.MANUAL),
    discoveryState = runCatching { ProviderModelDiscoveryState.valueOf(discoveryState) }
        .getOrDefault(ProviderModelDiscoveryState.NOT_DISCOVERED),
    discoveredAtEpochMillis = discoveredAtEpochMillis,
)

private fun ProviderProfilePersistenceState.persistedVendor(): ProviderVendor =
    runCatching { ProviderVendor.valueOf(vendor) }.getOrElse {
        when (ProviderKind.valueOf(kind)) {
            ProviderKind.OPENAI_RESPONSES -> ProviderVendor.OPENAI
            ProviderKind.ANTHROPIC -> ProviderVendor.ANTHROPIC
            ProviderKind.OPENAI_COMPATIBLE -> ProviderVendor.CUSTOM
        }
    }

private fun ProviderProtocol.toProviderKind() = when (this) {
    ProviderProtocol.OPENAI_RESPONSES -> ProviderKind.OPENAI_RESPONSES
    ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> ProviderKind.OPENAI_COMPATIBLE
    ProviderProtocol.ANTHROPIC_MESSAGES -> ProviderKind.ANTHROPIC
}

private fun ProviderKind.toProtocol() = when (this) {
    ProviderKind.OPENAI_RESPONSES -> ProviderProtocol.OPENAI_RESPONSES
    ProviderKind.OPENAI_COMPATIBLE -> ProviderProtocol.OPENAI_CHAT_COMPLETIONS
    ProviderKind.ANTHROPIC -> ProviderProtocol.ANTHROPIC_MESSAGES
}

private fun ProviderAuthenticationType.validFor(
    protocol: ProviderProtocol,
): RuntimeAuthenticationType? = when (this) {
    ProviderAuthenticationType.BEARER -> RuntimeAuthenticationType.BEARER
    ProviderAuthenticationType.X_API_KEY -> if (protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
        RuntimeAuthenticationType.X_API_KEY
    } else {
        null
    }
}

private fun credentialHint(secret: String): String = when {
    secret.length >= 8 -> "•••• ${secret.takeLast(4)}"
    secret.length >= 4 -> "•••• ${secret.takeLast(2)}"
    else -> "••••"
}
