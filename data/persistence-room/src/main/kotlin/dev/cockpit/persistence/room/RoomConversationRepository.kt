package dev.cockpit.persistence.room

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.conversation.Draft
import dev.cockpit.domain.conversation.Message
import dev.cockpit.persistence.api.AgentDetailReadFact
import dev.cockpit.persistence.api.AgentDraftPersistenceState
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.AgentReadFact
import dev.cockpit.persistence.api.AgentRepository
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationRepository
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.GenerationAttemptPersistenceState
import dev.cockpit.persistence.api.GenerationAttemptRepository
import dev.cockpit.persistence.api.GenerationAttemptStatus
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus
import dev.cockpit.persistence.api.PersonaPersistenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConversationRepository(
    private val database: CockpitDatabase,
) : ConversationRepository, AgentRepository, GenerationAttemptRepository {
    override suspend fun save(state: AgentPersistenceState) = database.withWriteTransaction {
        saveAgentRows(state)
    }

    override suspend fun saveConfiguration(
        state: AgentPersistenceState,
        providerProfileId: String?,
        providerModelId: String?,
    ) = database.withWriteTransaction {
        saveAgentRows(state)
        if (providerProfileId == null) {
            database.agentProviderBindingDao().deleteForAgent(state.agent.id.value)
        } else {
            database.agentProviderBindingDao().upsert(
                AgentProviderBindingEntity(state.agent.id.value, providerProfileId, providerModelId),
            )
        }
    }

    override suspend fun load(id: AgentId): AgentPersistenceState? = database.withReadTransaction {
        loadAgent(id.value)
    }

    override suspend fun save(snapshot: ConversationSnapshot) = database.withWriteTransaction {
        // A conversation keeps its own definition snapshot. Never overwrite a newer agent revision
        // when an older conversation is saved after a reply or draft change.
        if (database.agentDao().find(snapshot.agent.agent.id.value) == null) {
            saveAgentRows(snapshot.agent)
        }
        database.conversationDao().upsert(snapshot.conversation.toEntity())
        database.messageDao().deleteForConversation(snapshot.conversation.conversation.id.value)
        database.draftDao().deleteForConversation(snapshot.conversation.conversation.id.value)
        snapshot.messages.forEach { database.messageDao().insert(it.toEntity()) }
        snapshot.drafts.forEach { database.draftDao().upsert(it.toEntity()) }
    }

    override suspend fun load(conversationId: ConversationId): ConversationSnapshot? =
        database.withReadTransaction { loadConversation(conversationId.value) }

    override fun observeConversation(id: ConversationId): Flow<ConversationSnapshot?> =
        database.invalidationTracker
            .createFlow(
                "personas",
                "agents",
                "agent_import_sources",
                "conversations",
                "messages",
                "drafts",
            )
            .map { load(id) }

    override fun observeAgentDetail(id: AgentId): Flow<AgentDetailReadFact?> =
        database.invalidationTracker
            .createFlow(
                "personas",
                "agents",
                "agent_import_sources",
                "conversations",
                "messages",
                "drafts",
            )
            .map {
                database.withReadTransaction {
                    loadAgent(id.value)?.let { agent ->
                        AgentDetailReadFact(
                            agent = AgentReadFact(
                                PersonaPersistenceState(agent.personaId, agent.agent.persona),
                                agent,
                            ),
                            conversations = database.conversationDao().forAgent(id.value).mapNotNull {
                                loadConversation(it.id)
                            },
                        )
                    }
                }
            }

    override fun observeAgentFacts(): Flow<List<AgentReadFact>> =
        database.invalidationTracker
            .createFlow("personas", "agents", "agent_import_sources")
            .map {
                database.withReadTransaction {
                    database.agentDao().all().mapNotNull { entity ->
                        loadAgent(entity.id)?.let { state ->
                            AgentReadFact(
                                PersonaPersistenceState(state.personaId, state.agent.persona),
                                state,
                            )
                        }
                    }
                }
            }

    override fun observeCreationDraft(): Flow<AgentDraftPersistenceState?> =
        database.invalidationTracker.createFlow("agent_drafts").map {
            database.withReadTransaction {
                database.agentDraftDao().find(CREATION_DRAFT_ID)?.toState()
            }
        }

    override suspend fun saveCreationDraft(state: AgentDraftPersistenceState) =
        database.withWriteTransaction { database.agentDraftDao().upsert(state.toEntity()) }

    override suspend fun deleteCreationDraft(id: String) =
        database.withWriteTransaction { database.agentDraftDao().delete(id) }

    override suspend fun findAgentByImportDigest(payloadDigest: String): AgentId? =
        database.withReadTransaction {
            database.agentImportSourceDao().findAgentId(payloadDigest)?.let(::AgentId)
        }

    override fun observeGenerationAttempt(
        conversationId: ConversationId,
    ): Flow<GenerationAttemptPersistenceState?> =
        database.invalidationTracker.createFlow("generation_attempts").map {
            loadGenerationAttempt(conversationId)
        }

    override suspend fun loadGenerationAttempt(
        conversationId: ConversationId,
    ): GenerationAttemptPersistenceState? = database.withReadTransaction {
        database.generationAttemptDao().forConversation(conversationId.value)?.toState()
    }

    override suspend fun startGenerationAttempt(
        attempt: GenerationAttemptPersistenceState,
    ): Boolean = database.withWriteTransaction {
        val current = database.generationAttemptDao().forConversation(attempt.conversationId.value)
        if (current?.status == GenerationAttemptStatus.STARTED.name) {
            false
        } else {
            database.generationAttemptDao().upsert(attempt.toEntity())
            true
        }
    }

    override suspend fun finishGenerationAttempt(
        conversationId: ConversationId,
        attemptId: String,
        status: GenerationAttemptStatus,
        errorCode: String?,
        updatedAtEpochMillis: Long,
    ): Boolean {
        require(status != GenerationAttemptStatus.STARTED) { "A generation attempt needs a terminal status" }
        return database.withWriteTransaction {
            database.generationAttemptDao().finishStarted(
                conversationId = conversationId.value,
                attemptId = attemptId,
                status = status.name,
                errorCode = errorCode,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ) == 1
        }
    }

    override suspend fun interruptStartedGenerationAttempts(updatedAtEpochMillis: Long): Int =
        database.withWriteTransaction {
            database.generationAttemptDao().interruptAllStarted(updatedAtEpochMillis)
        }

    private suspend fun saveAgentRows(state: AgentPersistenceState) {
        val definitionJson = state.agent.persona.definition
            ?.let(AgentDefinitionJsonCodec::encode)
            ?: "{}"
        database.personaDao().upsert(
            PersonaPersistenceState(state.personaId, state.agent.persona).toEntity(),
        )
        database.agentDao().upsert(state.toEntity())
        if (state.agent.persona.definition != null) {
            database.agentDefinitionRevisionDao().insert(
                AgentDefinitionRevisionEntity(
                    agentId = state.agent.id.value,
                    revision = state.revision,
                    definitionJson = definitionJson,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        state.importSource?.let { source ->
            database.agentImportSourceDao().upsert(source.toEntity(state.agent.id.value))
        }
    }

    private suspend fun loadAgent(id: String): AgentPersistenceState? {
        val agent = database.agentDao().find(id) ?: return null
        val persona = database.personaDao().find(agent.personaId) ?: return null
        return agent.toState(persona, database.agentImportSourceDao().forAgent(id)?.toState())
    }

    private suspend fun loadConversation(id: String): ConversationSnapshot? {
        val conversation = database.conversationDao().find(id) ?: return null
        val currentAgent = loadAgent(conversation.agentId) ?: return null
        val snapshotDefinition = AgentDefinitionJsonCodec.decode(conversation.personaSnapshotJson)
        val snapshotPersona = snapshotDefinition?.toPersona() ?: currentAgent.agent.persona
        val snapshotAgent = currentAgent.copy(
            agent = currentAgent.agent.copy(persona = snapshotPersona),
            revision = conversation.agentRevision.takeIf { it > 0L } ?: currentAgent.revision,
        )
        return ConversationSnapshot(
            persona = PersonaPersistenceState(currentAgent.personaId, snapshotPersona),
            agent = snapshotAgent,
            conversation = conversation.toState(snapshotPersona),
            messages = database.messageDao().forConversation(id).map { it.toState() },
            drafts = database.draftDao().forConversation(id).map {
                Draft(
                    ConversationMessageDestination(
                        ConversationId(it.conversationId),
                        ConversationRevision(it.expectedConversationRevision),
                    ),
                    it.text,
                )
            },
        )
    }

    companion object {
        const val CREATION_DRAFT_ID = "agent-creation"
    }
}

private fun PersonaPersistenceState.toEntity() = PersonaEntity(
    id = id,
    identity = persona.identity,
    presentation = persona.presentation,
    voice = persona.voice,
    behavioralTendency = persona.behavioralTendency,
    promptStyle = persona.promptStyle,
    definitionJson = persona.definition?.let(AgentDefinitionJsonCodec::encode) ?: "{}",
)

private fun AgentPersistenceState.toEntity() = AgentEntity(
    id = agent.id.value,
    personaId = personaId,
    capabilitySummary = agent.capabilities.summary,
    revision = revision,
    archiveState = archiveState.name,
)

private fun ConversationPersistenceState.toEntity() = ConversationEntity(
    id = conversation.id.value,
    agentId = conversation.agentId.value,
    revision = conversation.revision.value,
    archiveState = archiveState.name,
    agentRevision = agentRevision,
    personaSnapshotJson = personaSnapshot?.definition?.let(AgentDefinitionJsonCodec::encode).orEmpty(),
)

private fun MessagePersistenceState.toEntity() = MessageEntity(
    id = id,
    conversationId = message.conversationId.value,
    text = message.text,
    ordinal = ordinal,
    role = role.name,
    source = source.name,
    status = status.name,
)

private fun GenerationAttemptPersistenceState.toEntity() = GenerationAttemptEntity(
    conversationId = conversationId.value,
    attemptId = attemptId,
    providerProfileId = providerProfileId,
    modelId = modelId,
    providerRevision = providerRevision,
    acceptedUserRevision = acceptedUserRevision,
    status = status.name,
    errorCode = errorCode,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun GenerationAttemptEntity.toState() = GenerationAttemptPersistenceState(
    attemptId = attemptId,
    conversationId = ConversationId(conversationId),
    providerProfileId = providerProfileId,
    modelId = modelId,
    providerRevision = providerRevision,
    acceptedUserRevision = acceptedUserRevision,
    status = GenerationAttemptStatus.valueOf(status),
    errorCode = errorCode,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun Draft.toEntity() = DraftEntity(
    conversationId = destination.conversationId.value,
    expectedConversationRevision = destination.expectedConversationRevision.value,
    text = text,
)

private fun PersonaEntity.toState() = PersonaPersistenceState(
    id = id,
    persona = Persona(
        identity = identity,
        presentation = presentation,
        voice = voice,
        behavioralTendency = behavioralTendency,
        promptStyle = promptStyle,
        definition = AgentDefinitionJsonCodec.decode(definitionJson),
    ),
)

private fun AgentEntity.toState(
    persona: PersonaEntity,
    importSource: AgentImportSource?,
) = AgentPersistenceState(
    agent = Agent(
        id = AgentId(id),
        persona = persona.toState().persona,
        capabilities = AgentCapabilities(capabilitySummary),
    ),
    personaId = personaId,
    revision = revision,
    archiveState = ArchiveState.valueOf(archiveState),
    importSource = importSource,
)

private fun ConversationEntity.toState(snapshotPersona: Persona?) = ConversationPersistenceState(
    conversation = Conversation(
        ConversationId(id),
        AgentId(agentId),
        ConversationRevision(revision),
    ),
    archiveState = ArchiveState.valueOf(archiveState),
    agentRevision = agentRevision,
    personaSnapshot = snapshotPersona,
)

private fun MessageEntity.toState() = MessagePersistenceState(
    id = id,
    message = Message(ConversationId(conversationId), text),
    ordinal = ordinal,
    role = MessageRole.valueOf(role),
    source = MessageSource.valueOf(source),
    status = MessageStatus.valueOf(status),
)

private fun AgentDefinition.toPersona() = Persona(
    identity = name,
    presentation = summary.ifBlank { description },
    voice = personality,
    behavioralTendency = personality,
    promptStyle = systemPrompt,
    definition = this,
)

private fun AgentImportSource.toEntity(agentId: String) = AgentImportSourceEntity(
    agentId = agentId,
    sourceFileName = sourceFileName,
    payloadDigest = payloadDigest,
    detectedContainer = detectedContainer,
    detectedSpec = detectedSpec,
    originalJson = originalJson,
    warningsJson = AgentDefinitionJsonCodec.encodeStrings(warnings),
    preservedFieldCount = preservedFieldCount,
    downgraded = downgraded,
)

private fun AgentImportSourceEntity.toState() = AgentImportSource(
    sourceFileName = sourceFileName,
    payloadDigest = payloadDigest,
    detectedContainer = detectedContainer,
    detectedSpec = detectedSpec,
    originalJson = originalJson,
    warnings = AgentDefinitionJsonCodec.decodeStrings(warningsJson),
    preservedFieldCount = preservedFieldCount,
    downgraded = downgraded,
)

private fun AgentDraftPersistenceState.toEntity() = AgentDraftEntity(
    id = id,
    definitionJson = AgentDefinitionJsonCodec.encode(definition),
    providerProfileId = providerProfileId,
    providerModelId = providerModelId,
    capabilitySummary = capabilitySummary,
    sourceFileName = importSource?.sourceFileName,
    payloadDigest = importSource?.payloadDigest,
    detectedContainer = importSource?.detectedContainer,
    detectedSpec = importSource?.detectedSpec,
    originalJson = importSource?.originalJson,
    warningsJson = importSource?.let { AgentDefinitionJsonCodec.encodeStrings(it.warnings) },
    preservedFieldCount = importSource?.preservedFieldCount ?: 0,
    downgraded = importSource?.downgraded ?: false,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun AgentDraftEntity.toState(): AgentDraftPersistenceState? {
    val definition = AgentDefinitionJsonCodec.decode(definitionJson) ?: return null
    val source = payloadDigest?.let {
        AgentImportSource(
            sourceFileName = sourceFileName,
            payloadDigest = it,
            detectedContainer = detectedContainer.orEmpty(),
            detectedSpec = detectedSpec.orEmpty(),
            originalJson = originalJson.orEmpty(),
            warnings = AgentDefinitionJsonCodec.decodeStrings(warningsJson),
            preservedFieldCount = preservedFieldCount,
            downgraded = downgraded,
        )
    }
    return AgentDraftPersistenceState(
        id = id,
        definition = definition,
        providerProfileId = providerProfileId,
        providerModelId = providerModelId,
        capabilitySummary = capabilitySummary,
        importSource = source,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
