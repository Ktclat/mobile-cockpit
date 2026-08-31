package dev.cockpit.application

import dev.cockpit.domain.ConversationId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runs submitted mutations to one Conversation in order on a process-owned scope. */
class ConversationMutationCoordinator(
    private val processScope: CoroutineScope,
) {
    private val locks = ConcurrentHashMap<ConversationId, Mutex>()

    suspend fun <T> submit(conversationId: ConversationId, mutation: suspend () -> T): T {
        val submitted = processScope.async {
            locks.computeIfAbsent(conversationId) { Mutex() }.withLock {
                mutation()
            }
        }
        return submitted.await()
    }
}
