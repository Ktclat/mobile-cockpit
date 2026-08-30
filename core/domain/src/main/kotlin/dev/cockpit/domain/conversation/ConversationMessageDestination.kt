package dev.cockpit.domain.conversation

import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision

data class ConversationMessageDestination(
    val conversationId: ConversationId,
    val expectedConversationRevision: ConversationRevision,
) : ComposerDestination
