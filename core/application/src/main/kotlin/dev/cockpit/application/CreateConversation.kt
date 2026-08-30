package dev.cockpit.application

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.api.AgentRepository
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationRepository
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.PersonaPersistenceState

class CreateConversation(
    private val agents: AgentRepository,
    private val conversations: ConversationRepository,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(agentId: AgentId): Conversation? {
        val agent = agents.load(agentId) ?: return null
        if (agent.archiveState != ArchiveState.ACTIVE) return null
        val conversation = Conversation(ConversationId(ids.nextId()), agentId, ConversationRevision(0))
        conversations.save(
            ConversationSnapshot(
                persona = PersonaPersistenceState(agent.personaId, agent.agent.persona),
                agent = agent,
                conversation = ConversationPersistenceState(conversation, ArchiveState.ACTIVE),
                messages = emptyList(),
                drafts = emptyList(),
            ),
        )
        return conversation
    }
}
