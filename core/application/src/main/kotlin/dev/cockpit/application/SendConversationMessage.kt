package dev.cockpit.application

import dev.cockpit.domain.conversation.ConversationDestinationRejected
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.conversation.Message
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationRepository
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus

enum class SendConversationMessageResult { Sent, Rejected }

class SendConversationMessage(private val repository: ConversationRepository, private val ids: IdGenerator) {
    suspend operator fun invoke(destination: ConversationMessageDestination, text: String): SendConversationMessageResult {
        val snapshot = repository.load(destination.conversationId) ?: return SendConversationMessageResult.Rejected
        if (snapshot.conversation.archiveState == ArchiveState.ARCHIVED) return SendConversationMessageResult.Rejected
        val ordinal = snapshot.messages.maxOfOrNull { it.ordinal }?.let { if (it == Long.MAX_VALUE) return SendConversationMessageResult.Rejected else it + 1 } ?: 0L
        val message = Message(destination.conversationId, text)
        val accepted = try { snapshot.conversation.conversation.accept(message, destination) } catch (_: ConversationDestinationRejected) { return SendConversationMessageResult.Rejected }
        repository.save(snapshot.copy(conversation = ConversationPersistenceState(accepted, snapshot.conversation.archiveState), messages = snapshot.messages + MessagePersistenceState(ids.nextId(), message, ordinal, MessageRole.USER, MessageSource.USER, MessageStatus.ACCEPTED), drafts = snapshot.drafts.filterNot { it.destination == destination }))
        return SendConversationMessageResult.Sent
    }
}
