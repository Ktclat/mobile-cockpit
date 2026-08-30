package dev.cockpit.domain.conversation

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConversationDestinationTest {
    @Test
    fun messageRequiresExpectedConversationRevision() {
        val conversation = Conversation(
            id = ConversationId("conversation-1"),
            agentId = AgentId("agent-1"),
            revision = ConversationRevision(4),
        )
        val destination = ConversationMessageDestination(
            conversationId = ConversationId("conversation-1"),
            expectedConversationRevision = ConversationRevision(4),
        )
        val message = Message(
            conversationId = ConversationId("conversation-1"),
            text = "Keep this message in its original conversation.",
        )
        val draft = Draft(
            destination = destination,
            text = "Keep this draft tied to the destination revision.",
        )

        val accepted = conversation.accept(message, destination)

        assertEquals(ConversationRevision(5), accepted.revision)
        assertEquals(destination, draft.destination)
        assertEquals(ConversationRevision(4), draft.destination.expectedConversationRevision)

        val staleFailure = assertThrows(ConversationDestinationRejected::class.java) {
            accepted.accept(message, destination)
        }
        assertSame(accepted, staleFailure.conversation)

        assertThrows(ConversationDestinationRejected::class.java) {
            conversation.accept(
                message,
                ConversationMessageDestination(
                    conversationId = ConversationId("conversation-2"),
                    expectedConversationRevision = ConversationRevision(4),
                ),
            )
        }
        assertThrows(ConversationDestinationRejected::class.java) {
            conversation.accept(
                message.copy(conversationId = ConversationId("conversation-2")),
                destination,
            )
        }
        assertEquals(ConversationRevision(4), conversation.revision)
    }

    @Test
    fun messageRejectsExhaustedConversationRevision() {
        val conversation = Conversation(
            id = ConversationId("conversation-1"),
            agentId = AgentId("agent-1"),
            revision = ConversationRevision(Long.MAX_VALUE),
        )
        val destination = ConversationMessageDestination(
            conversationId = ConversationId("conversation-1"),
            expectedConversationRevision = ConversationRevision(Long.MAX_VALUE),
        )
        val message = Message(
            conversationId = ConversationId("conversation-1"),
            text = "Do not wrap the conversation revision.",
        )

        val failure = assertThrows(ConversationDestinationRejected::class.java) {
            conversation.accept(message, destination)
        }

        assertSame(conversation, failure.conversation)
        assertEquals(ConversationRevision(Long.MAX_VALUE), conversation.revision)
    }
}
