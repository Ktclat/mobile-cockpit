package dev.cockpit.application

import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.conversation.Message
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationRepository
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus

/** Appends an ordinary Agent message immediately after an accepted user destination. */
class AppendConversationAgentMessage(
    private val repository: ConversationRepository,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(
        acceptedUserDestination: ConversationMessageDestination,
        text: String,
        source: MessageSource = MessageSource.DEBUG,
    ): Boolean {
        if (source == MessageSource.USER) return false
        val snapshot = repository.load(acceptedUserDestination.conversationId) ?: return false
        if (snapshot.conversation.archiveState != ArchiveState.ACTIVE) return false
        val acceptedRevision = acceptedUserDestination.expectedConversationRevision.value
        if (acceptedRevision == Long.MAX_VALUE) return false
        val expectedRevision = ConversationRevision(acceptedRevision + 1)
        if (snapshot.conversation.conversation.revision != expectedRevision) return false
        val destination = ConversationMessageDestination(acceptedUserDestination.conversationId, expectedRevision)
        val message = Message(acceptedUserDestination.conversationId, text)
        val accepted = try {
            snapshot.conversation.conversation.accept(message, destination)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val ordinal = snapshot.messages.maxOfOrNull { it.ordinal }?.let {
            if (it == Long.MAX_VALUE) return false else it + 1
        } ?: 0L
        repository.save(
            snapshot.copy(
                conversation = snapshot.conversation.copy(conversation = accepted),
                messages = snapshot.messages + MessagePersistenceState(
                    id = ids.nextId(),
                    message = message,
                    ordinal = ordinal,
                    role = MessageRole.AGENT,
                    source = source,
                    status = MessageStatus.DELIVERED,
                ),
            ),
        )
        return true
    }
}
