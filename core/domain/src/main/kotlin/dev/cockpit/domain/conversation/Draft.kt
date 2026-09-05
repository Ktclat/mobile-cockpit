package dev.cockpit.domain.conversation

data class Draft(
    val destination: ConversationMessageDestination,
    val text: String,
)
