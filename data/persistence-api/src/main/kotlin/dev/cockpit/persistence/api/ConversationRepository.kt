package dev.cockpit.persistence.api

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.conversation.Draft
import dev.cockpit.domain.conversation.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class ArchiveState { ACTIVE, ARCHIVED }
enum class MessageRole { USER, AGENT, SYSTEM }
enum class MessageSource { USER, DEBUG, RUNTIME }
enum class MessageStatus { ACCEPTED, DELIVERED, FAILED }

data class PersonaPersistenceState(val id: String, val persona: Persona)
data class AgentPersistenceState(
    val agent: Agent,
    val personaId: String,
    val revision: Long,
    val archiveState: ArchiveState,
    val importSource: AgentImportSource? = null,
)
data class ConversationPersistenceState(
    val conversation: Conversation,
    val archiveState: ArchiveState,
    val agentRevision: Long = 0,
    val personaSnapshot: Persona? = null,
)
data class MessagePersistenceState(
    val id: String,
    val message: Message,
    val ordinal: Long,
    val role: MessageRole,
    val source: MessageSource,
    val status: MessageStatus,
)
data class ConversationSnapshot(
    val persona: PersonaPersistenceState,
    val agent: AgentPersistenceState,
    val conversation: ConversationPersistenceState,
    val messages: List<MessagePersistenceState>,
    val drafts: List<Draft>,
)
data class AgentReadFact(val persona: PersonaPersistenceState, val agent: AgentPersistenceState)
data class AgentDetailReadFact(val agent: AgentReadFact, val conversations: List<ConversationSnapshot>)
data class AgentDraftPersistenceState(
    val id: String,
    val definition: AgentDefinition,
    val providerProfileId: String?,
    val providerModelId: String? = null,
    val capabilitySummary: String,
    val importSource: AgentImportSource?,
    val updatedAtEpochMillis: Long,
)

interface AgentRepository {
    suspend fun save(state: AgentPersistenceState)
    suspend fun load(id: AgentId): AgentPersistenceState?

    suspend fun saveConfiguration(
        state: AgentPersistenceState,
        providerProfileId: String?,
        providerModelId: String? = null,
    ) = save(state)

    fun observeCreationDraft(): Flow<AgentDraftPersistenceState?> = flowOf(null)

    suspend fun saveCreationDraft(state: AgentDraftPersistenceState) = Unit

    suspend fun deleteCreationDraft(id: String) = Unit

    suspend fun findAgentByImportDigest(payloadDigest: String): AgentId? = null
}

interface AgentConversationReadRepository {
    fun observeConversation(id: ConversationId): Flow<ConversationSnapshot?>
    fun observeAgentDetail(id: AgentId): Flow<AgentDetailReadFact?>
    fun observeAgentFacts(): Flow<List<AgentReadFact>>
}

interface ConversationRepository : AgentConversationReadRepository {
    suspend fun save(snapshot: ConversationSnapshot)
    suspend fun load(conversationId: ConversationId): ConversationSnapshot?
}
