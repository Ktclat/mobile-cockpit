package dev.cockpit.application

import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.conversation.Draft
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationRepository

class SaveConversationDraft(private val repository: ConversationRepository) {
    suspend operator fun invoke(destination: ConversationMessageDestination, text: String): Boolean {
        val snapshot = repository.load(destination.conversationId) ?: return false
        repository.save(snapshot.copy(drafts = snapshot.drafts.filterNot { it.destination == destination } + Draft(destination, text)))
        return true
    }
}

class ArchiveConversation(private val repository: ConversationRepository) {
    suspend operator fun invoke(id: ConversationId): Boolean = update(id, ArchiveState.ARCHIVED)
    private suspend fun update(id: ConversationId, state: ArchiveState): Boolean {
        val snapshot = repository.load(id) ?: return false
        repository.save(snapshot.copy(conversation = snapshot.conversation.copy(archiveState = state)))
        return true
    }
}

class RestoreConversation(private val repository: ConversationRepository) {
    suspend operator fun invoke(id: ConversationId): Boolean {
        val snapshot = repository.load(id) ?: return false
        repository.save(snapshot.copy(conversation = snapshot.conversation.copy(archiveState = ArchiveState.ACTIVE)))
        return true
    }
}
