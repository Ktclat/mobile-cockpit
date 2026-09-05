package dev.cockpit.domain.conversation

import dev.cockpit.domain.ConversationId

data class Message(
    val conversationId: ConversationId,
    val text: String,
)
