package dev.cockpit.application

import dev.cockpit.domain.ConversationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationMutationCoordinatorTest {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val coordinator = ConversationMutationCoordinator(processScope)

    @AfterEach
    fun cancelProcessScope() {
        processScope.cancel()
    }

    @Test
    fun sameConversationWaitsForFullSendWhileDifferentConversationCanMutate() = runBlocking {
        val conversationA = ConversationId("conversation-a")
        val conversationB = ConversationId("conversation-b")
        val releaseResponder = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val send = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submit(conversationA) {
                events += "user accepted"
                releaseResponder.await()
                events += "agent delivered"
                true
            }
        }
        val archiveSameConversation = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submit(conversationA) {
                events += "archive same conversation"
                true
            }
        }
        val saveOtherConversation = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submit(conversationB) {
                events += "save other conversation"
                true
            }
        }

        assertEquals(listOf("user accepted", "save other conversation"), events)
        assertFalse(archiveSameConversation.isCompleted)
        assertTrue(saveOtherConversation.await())

        releaseResponder.complete(Unit)

        assertTrue(send.await())
        assertTrue(archiveSameConversation.await())
        assertEquals(
            listOf(
                "user accepted",
                "save other conversation",
                "agent delivered",
                "archive same conversation",
            ),
            events,
        )
    }

    @Test
    fun cancellingCallerDoesNotCancelSubmittedMutationOrReleaseItsConversationEarly() = runBlocking {
        val conversation = ConversationId("conversation")
        val releaseResponder = CompletableDeferred<Unit>()
        val sendFinished = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submit(conversation) {
                events += "user accepted"
                releaseResponder.await()
                events += "agent delivered"
                sendFinished.complete(Unit)
            }
        }
        caller.cancelAndJoin()

        val saveAfterCancelledAwait = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submit(conversation) {
                events += "save after send"
            }
        }

        assertEquals(listOf("user accepted"), events)
        assertFalse(sendFinished.isCompleted)
        assertFalse(saveAfterCancelledAwait.isCompleted)

        releaseResponder.complete(Unit)
        withTimeout(5_000) { sendFinished.await() }
        saveAfterCancelledAwait.await()

        assertEquals(listOf("user accepted", "agent delivered", "save after send"), events)
    }

    @Test
    fun failedMutationReleasesConversationAndDoesNotCancelProcessScope() = runBlocking {
        val conversation = ConversationId("conversation")

        val failure = runCatching {
            coordinator.submit(conversation) {
                error("mutation failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("mutation failed", failure?.message)
        assertEquals(
            "same conversation recovered",
            coordinator.submit(conversation) { "same conversation recovered" },
        )
        assertEquals(
            "process scope survived",
            coordinator.submit(ConversationId("other-conversation")) { "process scope survived" },
        )
    }
}
