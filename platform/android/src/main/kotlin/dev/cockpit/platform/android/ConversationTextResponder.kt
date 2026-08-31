package dev.cockpit.platform.android

/** Optional local text response capability supplied by a build variant. */
fun interface ConversationTextResponder {
    suspend fun replyTo(text: String): String?
}
