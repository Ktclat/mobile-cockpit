package dev.cockpit.mobile.debug

import dev.cockpit.platform.android.ConversationTextResponder

class DeterministicDemoAgent : ConversationTextResponder {
    override suspend fun replyTo(text: String): String = "Debug reply: $text"
}
