package dev.cockpit.domain.conversation

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision

data class Conversation(
    val id: ConversationId,
    val agentId: AgentId,
    val revision: ConversationRevision,
) {
    fun accept(
        message: Message,
        destination: ConversationMessageDestination,
    ): Conversation {
        if (
            revision.value == Long.MAX_VALUE ||
            destination.conversationId != id ||
            message.conversationId != id ||
            message.conversationId != destination.conversationId ||
            destination.expectedConversationRevision != revision
        ) {
            throw ConversationDestinationRejected(this)
        }

        return copy(revision = ConversationRevision(revision.value + 1))
    }
}

class ConversationDestinationRejected(
    val conversation: Conversation,
) : IllegalArgumentException("Message destination does not match the conversation")
