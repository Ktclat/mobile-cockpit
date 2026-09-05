package dev.cockpit.platform.android

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.cockpit.application.AppendConversationAgentMessage
import dev.cockpit.application.AgentPromptBuilder
import dev.cockpit.application.ArchiveConversation
import dev.cockpit.application.CharacterCardJsonExporter
import dev.cockpit.application.CreateAgent
import dev.cockpit.application.CreateAgentCommand
import dev.cockpit.application.CreateConversation
import dev.cockpit.application.ConversationMutationCoordinator
import dev.cockpit.application.RestoreConversation
import dev.cockpit.application.SaveConversationDraft
import dev.cockpit.application.SendConversationMessage
import dev.cockpit.application.SendConversationMessageResult
import dev.cockpit.application.UpdateAgent
import dev.cockpit.application.UpdateAgentCommand
import dev.cockpit.application.api.AgentApplicationPort
import dev.cockpit.application.api.AgentDraftView
import dev.cockpit.application.api.AgentExportDocument
import dev.cockpit.application.api.AgentProfileInput
import dev.cockpit.application.api.AgentTestMessage
import dev.cockpit.application.api.AgentTestResult
import dev.cockpit.application.api.AgentTestRole
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.application.api.ProviderSettingsPort
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.agent.editableDefinition
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.room.CockpitDatabase
import dev.cockpit.persistence.room.RoomConversationRepository
import dev.cockpit.persistence.room.RoomProviderConfigurationRepository
import dev.cockpit.persistence.room.migration.Migration1To2
import dev.cockpit.persistence.room.migration.Migration2To3
import dev.cockpit.persistence.room.migration.Migration3To4
import dev.cockpit.persistence.room.migration.Migration4To5
import dev.cockpit.persistence.room.migration.Migration5To6
import dev.cockpit.persistence.api.AgentDraftPersistenceState
import dev.cockpit.provider.ProviderAdapterRegistry
import dev.cockpit.provider.api.ProviderAdapterResolver
import dev.cockpit.provider.api.NormalizedProviderRequest
import dev.cockpit.provider.api.ProviderInvocationId
import dev.cockpit.provider.api.ProviderKind
import dev.cockpit.provider.api.ProviderMessage
import dev.cockpit.provider.api.ProviderMessageRole
import dev.cockpit.provider.api.ProviderStreamEvent
import dev.cockpit.projection.ConversationProjector
import dev.cockpit.runtime.coordinator.ProviderInvocationGate
import dev.cockpit.security.vault.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class CockpitProcessComponent internal constructor(
    private val responder: ConversationTextResponder?,
    databaseFactory: () -> CockpitDatabase,
    mutationScope: CoroutineScope,
    credentialVaultFactory: () -> CredentialVault = ::createEphemeralProviderCredentialVault,
    providerAdapterResolver: ProviderAdapterResolver = ProviderAdapterRegistry(),
) {
    constructor(responder: ConversationTextResponder? = null) : this(
        responder = responder,
        databaseFactory = { CockpitDatabase.open("cockpit-local.db") },
        mutationScope = newConversationMutationScope(),
    )

    constructor(context: Context, responder: ConversationTextResponder? = null) : this(
        responder = responder,
        databaseFactory = {
            Room.databaseBuilder(
                context.applicationContext,
                CockpitDatabase::class.java,
                "cockpit-local.db",
            ).setDriver(BundledSQLiteDriver())
                .addMigrations(
                    Migration1To2,
                    Migration2To3,
                    Migration3To4,
                    Migration4To5,
                    Migration5To6,
                )
                .build()
        },
        mutationScope = newConversationMutationScope(),
        credentialVaultFactory = { createAndroidProviderCredentialVault(context) },
    )

    val shellAppName: String = "Cockpit"

    private val database by lazy(databaseFactory)
    private val repository by lazy { RoomConversationRepository(database) }
    private val providerRepository by lazy { RoomProviderConfigurationRepository(database) }
    private val credentialVault by lazy(credentialVaultFactory)
    private val providerInvocationGate by lazy {
        ProviderInvocationGate(credentialVault, providerAdapterResolver)
    }
    private val ids = object : IdGenerator {
        override fun nextId(): String = UUID.randomUUID().toString()
    }
    private val projector by lazy { ConversationProjector(repository) }
    private val createAgentUseCase by lazy { CreateAgent(repository, ids) }
    private val updateAgentUseCase by lazy { UpdateAgent(repository) }
    private val createConversationUseCase by lazy { CreateConversation(repository, repository, ids) }
    private val archiveConversationUseCase by lazy { ArchiveConversation(repository) }
    private val restoreConversationUseCase by lazy { RestoreConversation(repository) }
    private val saveDraftUseCase by lazy { SaveConversationDraft(repository) }
    private val sendMessageUseCase by lazy { SendConversationMessage(repository, ids) }
    private val appendAgentMessageUseCase by lazy { AppendConversationAgentMessage(repository, ids) }
    private val conversationMutations = ConversationMutationCoordinator(mutationScope)
    private val providerConversationRuntime by lazy {
        ProviderConversationRuntime(
            conversations = repository,
            providers = providerRepository,
            invocationGate = providerInvocationGate,
            responder = responder,
            appendAgentMessage = appendAgentMessageUseCase,
            mutations = conversationMutations,
            processScope = mutationScope,
        )
    }
    private val providerSettings: ProviderSettingsPort by lazy {
        ProviderSettingsController(
            repository = providerRepository,
            credentials = credentialVault,
            invocationGate = providerInvocationGate,
        )
    }

    private val agentPreviewLock = Any()
    @Volatile
    private var activeAgentPreview: Pair<ProviderKind, ProviderInvocationId>? = null

    private val agents: AgentApplicationPort = object : AgentApplicationPort {
        override suspend fun createAgent(input: AgentProfileInput): AgentId? = withContext(Dispatchers.IO) {
            val trimmed = input.identity.trim()
            if (trimmed.isEmpty()) null else createAgentUseCase(
                CreateAgentCommand(
                    persona = input.copy(identity = trimmed).toPersona(),
                    capabilities = AgentCapabilities(
                        input.capabilitySummary.ifBlank { "Local conversation only" },
                    ),
                    providerProfileId = input.providerProfileId,
                    providerModelId = input.providerModelId,
                    importSource = input.importSource,
                ),
            ).id
        }

        override suspend fun updateAgent(id: AgentId, input: AgentProfileInput): Boolean =
            withContext(Dispatchers.IO) {
                val trimmed = input.identity.trim()
                if (trimmed.isEmpty()) return@withContext false
                updateAgentUseCase(
                    UpdateAgentCommand(
                        id = id,
                        persona = input.copy(identity = trimmed).toPersona(),
                        capabilities = AgentCapabilities(
                            input.capabilitySummary.ifBlank { "Local conversation only" },
                        ),
                        providerProfileId = input.providerProfileId,
                        providerModelId = input.providerModelId,
                        importSource = input.importSource,
                    ),
                )
            }

        override fun observeCreationDraft() = repository.observeCreationDraft().map { draft ->
            draft?.let {
                AgentDraftView(
                    profile = it.definition.toProfile(
                        providerProfileId = it.providerProfileId,
                        providerModelId = it.providerModelId,
                        importSource = it.importSource,
                        capabilitySummary = it.capabilitySummary,
                    ),
                    updatedAtEpochMillis = it.updatedAtEpochMillis,
                )
            }
        }.flowOn(Dispatchers.IO)

        override suspend fun saveCreationDraft(input: AgentProfileInput): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    repository.saveCreationDraft(
                        AgentDraftPersistenceState(
                            id = RoomConversationRepository.CREATION_DRAFT_ID,
                            definition = input.toDefinition(),
                            providerProfileId = input.providerProfileId,
                            providerModelId = input.providerModelId,
                            capabilitySummary = input.capabilitySummary,
                            importSource = input.importSource,
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    true
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            }

        override suspend fun discardCreationDraft(): Boolean = withContext(Dispatchers.IO) {
            try {
                repository.deleteCreationDraft(RoomConversationRepository.CREATION_DRAFT_ID)
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }

        override suspend fun findAgentByImportDigest(payloadDigest: String): AgentId? =
            withContext(Dispatchers.IO) { repository.findAgentByImportDigest(payloadDigest) }

        override suspend fun exportAgent(id: AgentId): AgentExportDocument? =
            withContext(Dispatchers.IO) {
                val state = repository.load(id) ?: return@withContext null
                val definition = state.agent.persona.editableDefinition()
                val result = CharacterCardJsonExporter.export(definition, state.importSource)
                val safeName = definition.name
                    .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
                    .trim('-')
                    .ifBlank { "agent" }
                    .take(80)
                AgentExportDocument(
                    fileName = "$safeName.character.json",
                    json = result.json,
                    warning = if (result.preservedOriginal) {
                        "Imported extension fields were preserved where possible."
                    } else {
                        "Exported as a Character Card V2 JSON document."
                    },
                )
            }

        override suspend fun testAgent(
            input: AgentProfileInput,
            messages: List<AgentTestMessage>,
        ): AgentTestResult = withContext(Dispatchers.IO) {
            val configuration = providerRepository.observeConfiguration().first()
            val route = input.providerProfileId?.let { selected ->
                val connection = configuration.profiles.firstOrNull { it.id == selected }
                val modelId = input.providerModelId ?: connection?.preferredModelId
                modelId?.let { selected to it }
            } ?: configuration.globalDefaultRoute?.let { it.connectionId to it.modelId }
            val persistedConnection = route?.first?.let { selected ->
                configuration.profiles.firstOrNull { it.id == selected && it.enabled }
            }
            val selectedModel = route?.second?.let { modelId ->
                configuration.models.firstOrNull {
                    it.id == modelId && it.connectionId == persistedConnection?.id && it.enabled
                }
            }
            val persisted = if (persistedConnection != null && selectedModel != null) {
                persistedConnection.copy(
                    model = selectedModel.remoteModelId,
                    preferredModelId = selectedModel.id,
                )
            } else null
            persisted ?: return@withContext AgentTestResult(
                    success = false,
                    message = "Choose an enabled model route before starting a preview chat.",
                )
            val profile = runCatching { persisted.toProviderProfile() }.getOrNull()
                ?: return@withContext AgentTestResult(
                    success = false,
                    message = "The selected API account is invalid. Edit and save it again.",
                )
            val invocationId = ProviderInvocationId(UUID.randomUUID().toString())
            val claimed = synchronized(agentPreviewLock) {
                if (activeAgentPreview != null) false else {
                    activeAgentPreview = profile.kind to invocationId
                    true
                }
            }
            if (!claimed) {
                return@withContext AgentTestResult(false, message = "Another preview is already running.")
            }
            val buffer = StringBuilder()
            var failure: String? = null
            var completed = false
            try {
                val prompt = AgentPromptBuilder.build(
                    persona = input.toPersona(),
                    conversationText = messages.map { it.text },
                )
                val request = NormalizedProviderRequest(
                    invocationId = invocationId,
                    profile = profile,
                    systemInstruction = prompt.systemInstruction,
                    messages = messages.map {
                        ProviderMessage(
                            role = when (it.role) {
                                AgentTestRole.USER -> ProviderMessageRole.USER
                                AgentTestRole.AGENT -> ProviderMessageRole.ASSISTANT
                            },
                            text = it.text,
                        )
                    },
                )
                providerInvocationGate.stream(request).collect { event ->
                    when (event) {
                        is ProviderStreamEvent.TextDelta -> buffer.append(event.text)
                        is ProviderStreamEvent.Failed -> failure = event.error.safeMessage
                        is ProviderStreamEvent.Completed -> completed = true
                        is ProviderStreamEvent.ToolProposalDelta -> Unit
                    }
                }
                when {
                    failure != null -> AgentTestResult(false, buffer.toString(), failure.orEmpty())
                    !completed || buffer.isBlank() -> AgentTestResult(
                        false,
                        buffer.toString(),
                        "The provider completed without a text response.",
                    )
                    else -> AgentTestResult(true, buffer.toString())
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                providerInvocationGate.cancel(profile.kind, invocationId)
                throw error
            } catch (_: Exception) {
                AgentTestResult(false, buffer.toString(), "The preview request could not be completed.")
            } finally {
                synchronized(agentPreviewLock) {
                    if (activeAgentPreview?.second == invocationId) activeAgentPreview = null
                }
            }
        }

        override suspend fun cancelAgentTest(): Boolean {
            val current = synchronized(agentPreviewLock) { activeAgentPreview } ?: return false
            providerInvocationGate.cancel(current.first, current.second)
            return true
        }
    }

    private val conversations: ConversationApplicationPort = object : ConversationApplicationPort {
        override suspend fun createConversation(agentId: AgentId): ConversationId? =
            withContext(Dispatchers.IO) { createConversationUseCase(agentId)?.id }

        override suspend fun archiveConversation(id: ConversationId): Boolean =
            conversationMutations.submit(id) { archiveConversationUseCase(id) }

        override suspend fun restoreConversation(id: ConversationId): Boolean =
            conversationMutations.submit(id) { restoreConversationUseCase(id) }

        override suspend fun saveDraft(destination: ConversationMessageDestination, text: String): Boolean =
            conversationMutations.submit(destination.conversationId) {
                saveDraftUseCase(destination, text)
            }

        override suspend fun sendMessage(destination: ConversationMessageDestination, text: String): Boolean {
            val accepted = conversationMutations.submit(destination.conversationId) {
                sendMessageUseCase(destination, text) == SendConversationMessageResult.Sent
            }
            if (!accepted) return false
            providerConversationRuntime.startAfterAccepted(destination, text)
            return true
        }

        override suspend fun cancelReply(id: ConversationId): Boolean =
            providerConversationRuntime.cancel(id)

        override suspend fun retryReply(id: ConversationId): Boolean =
            withContext(Dispatchers.IO) { providerConversationRuntime.retry(id) }
    }

    private val projectedQueries: AgentConversationQueryPort = object : AgentConversationQueryPort {
        override fun home() = projector.home().flowOn(Dispatchers.IO)
        override fun agent(id: AgentId) = projector.agent(id).flowOn(Dispatchers.IO)
        override fun conversation(id: ConversationId) = projector.conversation(id).flowOn(Dispatchers.IO)
    }
    private val queries: AgentConversationQueryPort by lazy {
        ProviderAwareAgentConversationQueries(
            delegate = projectedQueries,
            configurations = providerRepository.observeConfiguration(),
            replies = providerConversationRuntime.replies,
        )
    }

    // Keep inner API modules off :app's compile classpath while entry glue forwards narrow handles.
    val agentApplicationPortHandle: Any get() = agents
    val conversationApplicationPortHandle: Any get() = conversations
    val agentConversationQueryPortHandle: Any get() = queries
    val providerSettingsPortHandle: Any get() = providerSettings
}

private fun newConversationMutationScope() =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun AgentProfileInput.toDefinition() = AgentDefinition(
    mode = mode,
    name = identity.trim(),
    nickname = nickname.trim(),
    summary = summary.trim(),
    avatarRef = avatarRef,
    description = description,
    personality = personality,
    scenario = scenario,
    firstMessage = firstMessage,
    alternateGreetings = alternateGreetings.filter(String::isNotBlank),
    exampleDialogue = exampleDialogue,
    systemPrompt = systemPrompt,
    postHistoryInstructions = postHistoryInstructions,
    tags = tags.map(String::trim).filter(String::isNotBlank).distinct(),
    creator = creator.trim(),
    characterVersion = characterVersion.trim(),
    creatorNotes = creatorNotes,
    lorebookEntries = lorebookEntries,
    lorebookScanDepth = lorebookScanDepth.coerceIn(1, 100),
    lorebookTokenBudget = lorebookTokenBudget.coerceIn(0, 100_000),
)

private fun AgentProfileInput.toPersona(): Persona {
    val definition = toDefinition()
    return Persona(
        identity = definition.name,
        presentation = definition.summary.ifBlank { definition.description }.ifBlank { "Local Agent" },
        voice = definition.personality.ifBlank { "Clear" },
        behavioralTendency = definition.personality.ifBlank { "Helpful" },
        promptStyle = definition.systemPrompt.ifBlank {
            AgentPromptBuilder.defaultSystemPrompt(definition.mode)
        },
        definition = definition,
    )
}

private fun AgentDefinition.toProfile(
    providerProfileId: String?,
    providerModelId: String?,
    importSource: dev.cockpit.domain.agent.AgentImportSource?,
    capabilitySummary: String,
) = AgentProfileInput(
    identity = name,
    mode = mode,
    summary = summary,
    avatarRef = avatarRef,
    description = description,
    personality = personality,
    scenario = scenario,
    firstMessage = firstMessage,
    alternateGreetings = alternateGreetings,
    exampleDialogue = exampleDialogue,
    systemPrompt = systemPrompt,
    postHistoryInstructions = postHistoryInstructions,
    tags = tags,
    nickname = nickname,
    creator = creator,
    characterVersion = characterVersion,
    creatorNotes = creatorNotes,
    lorebookEntries = lorebookEntries,
    lorebookScanDepth = lorebookScanDepth,
    lorebookTokenBudget = lorebookTokenBudget,
    providerProfileId = providerProfileId,
    providerModelId = providerModelId,
    importSource = importSource,
    capabilitySummary = capabilitySummary,
)
