package dev.cockpit.application

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.conversation.Message
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.api.AgentRepository
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationRepository
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus
import dev.cockpit.persistence.api.PersonaPersistenceState

class CreateConversation(
    private val agents: AgentRepository,
    private val conversations: ConversationRepository,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(agentId: AgentId): Conversation? {
        val agent = agents.load(agentId) ?: return null
        if (agent.archiveState != ArchiveState.ACTIVE) return null
        val conversationId = ConversationId(ids.nextId())
        val definition = agent.agent.persona.definition
        val greeting = definition?.firstMessage
            ?.takeIf(String::isNotBlank)
            ?.let { AgentPromptBuilder.renderDialogueText(it, definition) }
            .orEmpty()
        val initialRevision = if (greeting.isBlank()) 0L else 1L
        val conversation = Conversation(conversationId, agentId, ConversationRevision(initialRevision))
        val initialMessages = if (greeting.isBlank()) {
            emptyList()
        } else {
            listOf(
                MessagePersistenceState(
                    id = ids.nextId(),
                    message = Message(conversationId, greeting),
                    ordinal = 1L,
                    role = MessageRole.AGENT,
                    source = MessageSource.RUNTIME,
                    status = MessageStatus.DELIVERED,
                ),
            )
        }
        conversations.save(
            ConversationSnapshot(
                persona = PersonaPersistenceState(agent.personaId, agent.agent.persona),
                agent = agent,
                conversation = ConversationPersistenceState(
                    conversation = conversation,
                    archiveState = ArchiveState.ACTIVE,
                    agentRevision = agent.revision,
                    personaSnapshot = agent.agent.persona,
                ),
                messages = initialMessages,
                drafts = emptyList(),
            ),
        )
        return conversation
    }
}
