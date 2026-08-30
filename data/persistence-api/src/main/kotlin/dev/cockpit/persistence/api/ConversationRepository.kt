package dev.cockpit.persistence.api

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.conversation.Draft
import dev.cockpit.domain.conversation.Message
import kotlinx.coroutines.flow.Flow

enum class ArchiveState { ACTIVE, ARCHIVED }
enum class MessageRole { USER, AGENT, SYSTEM }
enum class MessageSource { USER, DEBUG, RUNTIME }
enum class MessageStatus { ACCEPTED, DELIVERED, FAILED }

data class PersonaPersistenceState(val id: String, val persona: Persona)
data class AgentPersistenceState(val agent: Agent, val personaId: String, val revision: Long, val archiveState: ArchiveState)
data class ConversationPersistenceState(val conversation: Conversation, val archiveState: ArchiveState)
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

interface AgentRepository {
    suspend fun save(state: AgentPersistenceState)
    suspend fun load(id: AgentId): AgentPersistenceState?
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
