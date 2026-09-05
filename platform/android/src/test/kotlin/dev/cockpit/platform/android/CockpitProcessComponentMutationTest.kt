package dev.cockpit.platform.android

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cockpit.application.api.AgentApplicationPort
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.persistence.room.CockpitDatabase
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.TimelineItemProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CockpitProcessComponentMutationTest {
    private var database: CockpitDatabase? = null
    private var processScope: CoroutineScope? = null

    @After
    fun closeResources() {
        processScope?.cancel()
        database?.close()
    }

    @Test
    fun submittedSendSurvivesCallerCancellationAndSerializesOnlyItsConversation() = runBlocking {
        val responderEntered = CompletableDeferred<Unit>()
        val releaseResponder = CompletableDeferred<Unit>()
        val responder = ConversationTextResponder { text ->
            responderEntered.complete(Unit)
            releaseResponder.await()
            "agent reply to $text"
        }
        val context = RuntimeEnvironment.getApplication()
        val testDatabase = Room.inMemoryDatabaseBuilder(
            context,
            CockpitDatabase::class.java,
        ).setDriver(AndroidSQLiteDriver()).build().also { database = it }
        val testProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            .also { processScope = it }
        val component = CockpitProcessComponent(
            responder = responder,
            databaseFactory = { testDatabase },
            mutationScope = testProcessScope,
        )
        val agents = component.agentApplicationPortHandle as AgentApplicationPort
        val conversations = component.conversationApplicationPortHandle as ConversationApplicationPort
        val queries = component.agentConversationQueryPortHandle as AgentConversationQueryPort
        val agentId = checkNotNull(agents.createAgent("Coordinator test agent"))
        val conversationA = checkNotNull(conversations.createConversation(agentId))
        val conversationB = checkNotNull(conversations.createConversation(agentId))
        val destinationA = withTimeout(5_000) {
            queries.conversation(conversationA).first().messageDestination
        }
        val destinationB = withTimeout(5_000) {
            queries.conversation(conversationB).first().messageDestination
        }

        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            conversations.sendMessage(destinationA, "hello A")
        }
        withTimeout(5_000) { responderEntered.await() }
        caller.cancelAndJoin()

        val archiveA = async(start = CoroutineStart.UNDISPATCHED) {
            conversations.archiveConversation(conversationA)
        }
        val saveDraftB = async(start = CoroutineStart.UNDISPATCHED) {
            conversations.saveDraft(destinationB, "draft B")
        }

        assertTrue(withTimeout(5_000) { saveDraftB.await() })
        assertFalse(archiveA.isCompleted)

        releaseResponder.complete(Unit)
        assertTrue(withTimeout(5_000) { archiveA.await() })

        val finalA = withTimeout(5_000) {
            queries.conversation(conversationA).first {
                it.archiveState == ArchiveProjectionState.ARCHIVED && it.timeline.size == 2
            }
        }
        assertEquals(
            listOf("hello A", "agent reply to hello A"),
            finalA.timeline.map { (it as TimelineItemProjection.MessageItem).message.text },
        )
        val finalB = withTimeout(5_000) {
            queries.conversation(conversationB).first { it.drafts.size == 1 }
        }
        assertEquals(destinationB, finalB.drafts.single().destination)
        assertEquals("draft B", finalB.drafts.single().text)
    }
}
