package dev.cockpit.application

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.conversation.Draft
import dev.cockpit.domain.conversation.Message
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.api.AgentRepository
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.AgentReadFact
import dev.cockpit.persistence.api.AgentDetailReadFact
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationRepository
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus
import dev.cockpit.persistence.api.PersonaPersistenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConversationUseCasesTest {
    @Test
    fun createAgentUsesInjectedId() = runBlocking {
        val repository = RecordingAgentRepository()
        val useCase = CreateAgent(repository, object : IdGenerator {
            override fun nextId() = "agent-deterministic"
        })

        val agent = useCase(CreateAgentCommand(persona(), AgentCapabilities("Local continuity")))

        assertEquals(AgentId("agent-deterministic"), agent.id)
        assertEquals(ArchiveState.ACTIVE, repository.state?.archiveState)
        assertEquals(0L, repository.state?.revision)
    }

    @Test
    fun sendRejectsStaleDestinationWithoutWriting() = runBlocking {
        val repository = RecordingConversationRepository(snapshot())
        val send = SendConversationMessage(repository, object : IdGenerator {
            override fun nextId() = "message-1"
        })

        val result = send(
            ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(3)),
            "Do not send stale text",
        )

        assertEquals(SendConversationMessageResult.Rejected, result)
        assertEquals(0, repository.writes)
        assertEquals(ConversationRevision(4), repository.current.conversation.conversation.revision)
        assertEquals(emptyList<MessagePersistenceState>(), repository.current.messages)
    }

    @Test
    fun saveDraftRetainsItsExactHistoricalDestination() = runBlocking {
        val repository = RecordingConversationRepository(snapshot())
        val destination = ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(3))

        SaveConversationDraft(repository)(destination, "Keep revision three")

        assertEquals(listOf(Draft(destination, "Keep revision three")), repository.current.drafts)
    }

    @Test
    fun archiveThenRestorePreservesConversationFacts() = runBlocking {
        val original = snapshot().copy(
            messages = listOf(MessagePersistenceState("message-1", Message(ConversationId("conversation-1"), "Keep me"), 0, MessageRole.USER, MessageSource.USER, MessageStatus.ACCEPTED)),
            drafts = listOf(Draft(ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(2)), "Keep draft")),
        )
        val repository = RecordingConversationRepository(original)

        ArchiveConversation(repository)(ConversationId("conversation-1"))
        RestoreConversation(repository)(ConversationId("conversation-1"))

        assertEquals(original, repository.current)
    }

    @Test
    fun createConversationRejectsArchivedAuthoritativeAgent() = runBlocking {
        val agent = AgentPersistenceState(Agent(AgentId("agent-1"), persona(), AgentCapabilities("Local")), "persona-1", 7, ArchiveState.ARCHIVED)
        val agents = RecordingAgentRepository().also { it.state = agent }
        val conversations = RecordingConversationRepository(snapshot())

        val result = CreateConversation(agents, conversations, object : IdGenerator { override fun nextId() = "conversation-1" })(AgentId("agent-1"))

        assertEquals(null, result)
        assertEquals(0, conversations.writes)
    }

    @Test
    fun createConversationUsesInjectedIdForActiveAuthoritativeAgent() = runBlocking {
        val agent = AgentPersistenceState(Agent(AgentId("agent-1"), persona(), AgentCapabilities("Local")), "persona-1", 7, ArchiveState.ACTIVE)
        val agents = RecordingAgentRepository().also { it.state = agent }
        val conversations = RecordingConversationRepository(snapshot())

        val result = CreateConversation(agents, conversations, object : IdGenerator { override fun nextId() = "conversation-deterministic" })(AgentId("agent-1"))

        assertEquals(ConversationId("conversation-deterministic"), result?.id)
        assertEquals(1, conversations.writes)
        assertEquals(agent, conversations.current.agent)
        assertEquals(ConversationId("conversation-deterministic"), conversations.current.conversation.conversation.id)
        assertEquals(AgentId("agent-1"), conversations.current.conversation.conversation.agentId)
        assertEquals(ConversationRevision(0), conversations.current.conversation.conversation.revision)
        assertEquals(ArchiveState.ACTIVE, conversations.current.conversation.archiveState)
        assertEquals(emptyList<MessagePersistenceState>(), conversations.current.messages)
        assertEquals(emptyList<Draft>(), conversations.current.drafts)
    }

    @Test
    fun sendExactDestinationPersistsOneUserMessageAndAdvancesRevisionOnce() = runBlocking {
        val original = snapshot().copy(messages = listOf(MessagePersistenceState("existing", Message(ConversationId("conversation-1"), "before"), 8, MessageRole.AGENT, MessageSource.RUNTIME, MessageStatus.DELIVERED)))
        val repository = RecordingConversationRepository(original)
        val result = SendConversationMessage(repository, object : IdGenerator { override fun nextId() = "message-1" })(
            ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(4)),
            "Send exact",
        )

        assertEquals(SendConversationMessageResult.Sent, result)
        assertEquals(1, repository.writes)
        assertEquals(ConversationRevision(5), repository.current.conversation.conversation.revision)
        assertEquals(listOf("existing", "message-1"), repository.current.messages.map { it.id })
        assertEquals(listOf(8L, 9L), repository.current.messages.map { it.ordinal })
        assertEquals(listOf(MessageRole.AGENT, MessageRole.USER), repository.current.messages.map { it.role })
        val appended = repository.current.messages.last()
        assertEquals("message-1", appended.id)
        assertEquals(ConversationId("conversation-1"), appended.message.conversationId)
        assertEquals("Send exact", appended.message.text)
        assertEquals(9L, appended.ordinal)
        assertEquals(MessageRole.USER, appended.role)
        assertEquals(MessageSource.USER, appended.source)
        assertEquals(MessageStatus.ACCEPTED, appended.status)
        assertEquals(listOf("existing"), original.messages.map { it.id })
        assertEquals(ConversationRevision(4), original.conversation.conversation.revision)
    }

    @Test
    fun sendRejectsExhaustedRevisionWithoutWriting() = runBlocking {
        val exhausted = snapshot().copy(conversation = ConversationPersistenceState(
            Conversation(ConversationId("conversation-1"), AgentId("agent-1"), ConversationRevision(Long.MAX_VALUE)),
            ArchiveState.ACTIVE,
        ))
        val repository = RecordingConversationRepository(exhausted)

        val result = SendConversationMessage(repository, object : IdGenerator { override fun nextId() = "must-not-be-used" })(
            ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(Long.MAX_VALUE)),
            "Do not wrap",
        )

        assertEquals(SendConversationMessageResult.Rejected, result)
        assertEquals(0, repository.writes)
    }

    @Test
    fun sendRejectsExhaustedOrdinalWithoutWritingOrConsumingId() = runBlocking {
        val repository = RecordingConversationRepository(snapshot().copy(messages = listOf(MessagePersistenceState("max", Message(ConversationId("conversation-1"), "max"), Long.MAX_VALUE, MessageRole.USER, MessageSource.USER, MessageStatus.ACCEPTED))))
        var ids = 0

        val result = SendConversationMessage(repository, object : IdGenerator { override fun nextId() = "id-${++ids}" })(ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(4)), "no wrap")

        assertEquals(SendConversationMessageResult.Rejected, result)
        assertEquals(0, repository.writes)
        assertEquals(0, ids)
    }

    @Test
    fun saveDraftReplacesOnlyTheSameCompleteDestination() = runBlocking {
        val repository = RecordingConversationRepository(snapshot().copy(drafts = listOf(Draft(ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(3)), "old"))))
        val same = ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(3))
        val differentRevision = ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(2))

        SaveConversationDraft(repository)(same, "new")
        SaveConversationDraft(repository)(differentRevision, "historic")

        assertEquals(listOf(Draft(same, "new"), Draft(differentRevision, "historic")), repository.current.drafts)
    }

    @Test
    fun archivePublishesArchivedStateBeforeRestore() = runBlocking {
        val repository = RecordingConversationRepository(snapshot())

        ArchiveConversation(repository)(ConversationId("conversation-1"))
        assertEquals(ArchiveState.ARCHIVED, repository.current.conversation.archiveState)
        RestoreConversation(repository)(ConversationId("conversation-1"))

        assertEquals(ArchiveState.ACTIVE, repository.current.conversation.archiveState)
    }

    @Test
    fun sendRejectsMissingArchivedAndWrongIdWithoutWriting() = runBlocking {
        val missing = RecordingConversationRepository(snapshot())
        val archived = RecordingConversationRepository(snapshot().copy(conversation = snapshot().conversation.copy(archiveState = ArchiveState.ARCHIVED)))
        val wrongIdThatLoadsAuthoritativeFact = RecordingConversationRepository(snapshot(), loadAnyId = true)
        val ids = object : IdGenerator { override fun nextId() = error("rejected sends must not allocate") }

        assertEquals(SendConversationMessageResult.Rejected, SendConversationMessage(missing, ids)(ConversationMessageDestination(ConversationId("missing"), ConversationRevision(4)), "missing"))
        assertEquals(SendConversationMessageResult.Rejected, SendConversationMessage(archived, ids)(ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(4)), "archived"))
        assertEquals(SendConversationMessageResult.Rejected, SendConversationMessage(wrongIdThatLoadsAuthoritativeFact, ids)(ConversationMessageDestination(ConversationId("wrong-id"), ConversationRevision(4)), "wrong"))
        assertEquals(0, missing.writes + archived.writes + wrongIdThatLoadsAuthoritativeFact.writes)
    }

    @Test
    fun successfulSendRemovesOnlyItsExactPersistedDraft() = runBlocking {
        val exact = ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(4))
        val historic = ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(3))
        val repository = RecordingConversationRepository(snapshot().copy(drafts = listOf(Draft(exact, "sent"), Draft(historic, "keep"))))

        assertEquals(SendConversationMessageResult.Sent, SendConversationMessage(repository, object : IdGenerator { override fun nextId() = "message-1" })(exact, "sent"))
        assertEquals(listOf(Draft(historic, "keep")), repository.current.drafts)
    }

    @Test
    fun appendAgentMessageRequiresExactPostUserRevision() = runBlocking {
        val acceptedUserDestination =
            ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(4))
        val afterUser = snapshot().copy(
            conversation = ConversationPersistenceState(
                Conversation(ConversationId("conversation-1"), AgentId("agent-1"), ConversationRevision(5)),
                ArchiveState.ACTIVE,
            ),
            messages = listOf(
                MessagePersistenceState(
                    "user-message",
                    Message(ConversationId("conversation-1"), "user text"),
                    8,
                    MessageRole.USER,
                    MessageSource.USER,
                    MessageStatus.ACCEPTED,
                ),
            ),
        )
        val repository = RecordingConversationRepository(afterUser)

        val appended = AppendConversationAgentMessage(
            repository,
            object : IdGenerator { override fun nextId() = "agent-message" },
        )(acceptedUserDestination, "ordinary response")

        assertEquals(true, appended)
        assertEquals(1, repository.writes)
        assertEquals(ConversationRevision(6), repository.current.conversation.conversation.revision)
        assertEquals(listOf(8L, 9L), repository.current.messages.map { it.ordinal })
        assertEquals(MessageRole.AGENT, repository.current.messages.last().role)
        assertEquals(MessageSource.DEBUG, repository.current.messages.last().source)
        assertEquals(MessageStatus.DELIVERED, repository.current.messages.last().status)
        assertEquals("ordinary response", repository.current.messages.last().message.text)
    }

    @Test
    fun appendAgentMessageRejectsAnyNonPostUserOrExhaustedState() = runBlocking {
        val acceptedUserDestination =
            ConversationMessageDestination(ConversationId("conversation-1"), ConversationRevision(4))
        val stale = RecordingConversationRepository(snapshot())
        val exhausted = RecordingConversationRepository(
            snapshot().copy(
                conversation = ConversationPersistenceState(
                    Conversation(
                        ConversationId("conversation-1"),
                        AgentId("agent-1"),
                        ConversationRevision(5),
                    ),
                    ArchiveState.ACTIVE,
                ),
                messages = listOf(
                    MessagePersistenceState(
                        "max",
                        Message(ConversationId("conversation-1"), "existing"),
                        Long.MAX_VALUE,
                        MessageRole.USER,
                        MessageSource.USER,
                        MessageStatus.ACCEPTED,
                    ),
                ),
            ),
        )
        val ids = object : IdGenerator {
            override fun nextId(): String = error("rejected append must not allocate")
        }

        assertEquals(
            false,
            AppendConversationAgentMessage(stale, ids)(acceptedUserDestination, "stale"),
        )
        assertEquals(
            false,
            AppendConversationAgentMessage(exhausted, ids)(acceptedUserDestination, "no wrap"),
        )
        assertEquals(0, stale.writes + exhausted.writes)
    }

    private class RecordingAgentRepository : AgentRepository {
        var state: AgentPersistenceState? = null
        override suspend fun save(state: AgentPersistenceState) { this.state = state }
        override suspend fun load(id: AgentId): AgentPersistenceState? = state?.takeIf { it.agent.id == id }
    }

    private class RecordingConversationRepository(initial: ConversationSnapshot, private val loadAnyId: Boolean = false) : ConversationRepository {
        var current = initial
        var writes = 0
        override suspend fun save(snapshot: ConversationSnapshot) { writes++; current = snapshot }
        override suspend fun load(conversationId: ConversationId) = current.takeIf { loadAnyId || it.conversation.conversation.id == conversationId }
        override fun observeConversation(id: ConversationId): Flow<ConversationSnapshot?> = flowOf(current.takeIf { it.conversation.conversation.id == id })
        override fun observeAgentDetail(id: AgentId): Flow<AgentDetailReadFact?> {
            val detail = AgentDetailReadFact(AgentReadFact(current.persona, current.agent), listOf(current))
            return flowOf<AgentDetailReadFact?>(if (current.agent.agent.id == id) detail else null)
        }
        override fun observeAgentFacts(): Flow<List<AgentReadFact>> = flowOf(listOf(AgentReadFact(current.persona, current.agent)))
    }

    private fun snapshot() = ConversationSnapshot(
        PersonaPersistenceState("persona-1", persona()),
        AgentPersistenceState(Agent(AgentId("agent-1"), persona(), AgentCapabilities("Local")), "persona-1", 0, ArchiveState.ACTIVE),
        ConversationPersistenceState(Conversation(ConversationId("conversation-1"), AgentId("agent-1"), ConversationRevision(4)), ArchiveState.ACTIVE),
        emptyList(),
        emptyList(),
    )

    private fun persona() = Persona("Nova", "Blue", "Calm", "Exact", "Short")
}
