package dev.cockpit.projection

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.conversation.Message
import dev.cockpit.persistence.api.AgentConversationReadRepository
import dev.cockpit.persistence.api.AgentReadFact
import dev.cockpit.persistence.api.AgentDetailReadFact
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus
import dev.cockpit.persistence.api.PersonaPersistenceState
import dev.cockpit.projection.model.TimelineItemProjection
import dev.cockpit.projection.model.ConversationSummaryProjection
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConversationProjectorTest {
    @Test
    fun onlyMessagesAreTopLevelItems() = runBlocking {
        val projection = ConversationProjector(
            object : AgentConversationReadRepository {
                override fun observeConversation(id: ConversationId) = flowOf(snapshot())
                override fun observeAgentDetail(id: AgentId) = flowOf(AgentDetailReadFact(AgentReadFact(snapshot().persona, snapshot().agent), listOf(snapshot())))
                override fun observeAgentFacts() = flowOf(listOf(AgentReadFact(snapshot().persona, snapshot().agent)))
            },
        ).conversation(ConversationId("conversation-1")).first()

        assertEquals(
            listOf("first by ordinal", "second by ordinal"),
            projection.timeline.map { (it as TimelineItemProjection.MessageItem).message.text },
        )
    }

    @Test
    fun homeIncludesActiveAgentWithoutConversation() = runBlocking {
        val activeAgent = AgentPersistenceState(
            Agent(AgentId("agent-alone"), Persona("Alone", "Blue", "Calm", "Exact", "Short"), AgentCapabilities("Local")),
            "persona-alone",
            0,
            ArchiveState.ACTIVE,
        )
        val archivedAgent = activeAgent.copy(agent = activeAgent.agent.copy(id = AgentId("agent-archived")), archiveState = ArchiveState.ARCHIVED)
        val home = ConversationProjector(object : AgentConversationReadRepository {
            override fun observeConversation(id: ConversationId) = flowOf<ConversationSnapshot?>(null)
            override fun observeAgentDetail(id: AgentId) = flowOf<AgentDetailReadFact?>(null)
            override fun observeAgentFacts() = flowOf(listOf(
                AgentReadFact(PersonaPersistenceState("persona-alone", activeAgent.agent.persona), activeAgent),
                AgentReadFact(PersonaPersistenceState("persona-archived", archivedAgent.agent.persona), archivedAgent),
            ))
        }).home().first()

        assertEquals(listOf(AgentId("agent-alone")), home.agents.map { it.id })
    }

    @Test
    fun agentDetailPreservesArchivedConversationFacts() = runBlocking {
        val archived = snapshot().copy(conversation = snapshot().conversation.copy(archiveState = ArchiveState.ARCHIVED))
        val detail = ConversationProjector(object : AgentConversationReadRepository {
            override fun observeConversation(id: ConversationId) = flowOf<ConversationSnapshot?>(archived)
            override fun observeAgentDetail(id: AgentId) = flowOf(AgentDetailReadFact(AgentReadFact(archived.persona, archived.agent), listOf(archived)))
            override fun observeAgentFacts() = flowOf(listOf(AgentReadFact(archived.persona, archived.agent)))
        }).agent(AgentId("agent-1")).first()

        assertEquals(ArchiveState.ARCHIVED.name, detail.conversations.single().archiveState.name)
        assertEquals(ConversationRevision(2), detail.conversations.single().revision)
    }

    @Test
    fun agentDetailPreservesActiveAndArchivedConversationSummaries() = runBlocking {
        val active = snapshot()
        val archived = snapshot().copy(conversation = ConversationPersistenceState(Conversation(ConversationId("conversation-archived"), AgentId("agent-1"), ConversationRevision(7)), ArchiveState.ARCHIVED))
        val detail = ConversationProjector(object : AgentConversationReadRepository {
            override fun observeConversation(id: ConversationId) = flowOf<ConversationSnapshot?>(null)
            override fun observeAgentDetail(id: AgentId) = flowOf(AgentDetailReadFact(AgentReadFact(active.persona, active.agent), listOf(active, archived)))
            override fun observeAgentFacts() = flowOf(listOf(AgentReadFact(active.persona, active.agent)))
        }).agent(AgentId("agent-1")).first()

        assertEquals(AgentId("agent-1"), detail.id)
        assertEquals("Nova", detail.name)
        assertEquals(0L, detail.revision)
        assertEquals(ArchiveState.ACTIVE.name, detail.archiveState.name)
        assertEquals(listOf(ConversationId("conversation-1"), ConversationId("conversation-archived")), detail.conversations.map { it.id })
        assertEquals(listOf(ConversationRevision(2), ConversationRevision(7)), detail.conversations.map { it.revision })
        assertEquals(listOf(ArchiveState.ACTIVE.name, ArchiveState.ARCHIVED.name), detail.conversations.map { it.archiveState.name })
    }

    @Test
    fun agentDetailUsesAuthoritativeAgentFactsWithoutConversations() = runBlocking {
        val agent = AgentPersistenceState(
            Agent(AgentId("agent-detail-alone"), Persona("Detail", "Blue", "Calm", "Exact", "Short"), AgentCapabilities("Local")),
            "persona-detail-alone",
            5,
            ArchiveState.ACTIVE,
        )
        val detail = ConversationProjector(object : AgentConversationReadRepository {
            override fun observeConversation(id: ConversationId) = flowOf<ConversationSnapshot?>(null)
            override fun observeAgentDetail(id: AgentId) = flowOf(AgentDetailReadFact(AgentReadFact(PersonaPersistenceState("persona-detail-alone", agent.agent.persona), agent), emptyList()))
            override fun observeAgentFacts() = flowOf<List<AgentReadFact>>(listOf(AgentReadFact(PersonaPersistenceState("persona-detail-alone", agent.agent.persona), agent)))
        }).agent(agent.agent.id).first()

        assertEquals(AgentId("agent-detail-alone"), detail.id)
        assertEquals("Detail", detail.name)
        assertEquals(5L, detail.revision)
        assertEquals(ArchiveState.ACTIVE.name, detail.archiveState.name)
        assertEquals(emptyList<ConversationSummaryProjection>(), detail.conversations)
    }

    @Test
    fun projectorUsesReadOnlyFactsWithoutWrites() = runBlocking {
        val probe = object : AgentConversationReadRepository {
            var writes = 0
            override fun observeConversation(id: ConversationId) = flowOf<ConversationSnapshot?>(snapshot())
            override fun observeAgentDetail(id: AgentId) = flowOf(AgentDetailReadFact(AgentReadFact(snapshot().persona, snapshot().agent), listOf(snapshot())))
            override fun observeAgentFacts() = flowOf(listOf(AgentReadFact(snapshot().persona, snapshot().agent)))
        }

        ConversationProjector(probe).conversation(ConversationId("conversation-1")).first()

        assertEquals(
            AgentConversationReadRepository::class.java,
            ConversationProjector::class.java.constructors.single().parameterTypes.single(),
        )
        assertEquals(0, probe.writes)
    }

    private fun snapshot() = ConversationSnapshot(
        persona = PersonaPersistenceState("persona-1", Persona("Nova", "Blue", "Calm", "Exact", "Short")),
        agent = AgentPersistenceState(
            Agent(AgentId("agent-1"), Persona("Nova", "Blue", "Calm", "Exact", "Short"), AgentCapabilities("Local")),
            "persona-1",
            0,
            ArchiveState.ACTIVE,
        ),
        conversation = ConversationPersistenceState(
            Conversation(ConversationId("conversation-1"), AgentId("agent-1"), ConversationRevision(2)),
            ArchiveState.ACTIVE,
        ),
        messages = listOf(
            MessagePersistenceState("later-id", Message(ConversationId("conversation-1"), "second by ordinal"), 20, MessageRole.USER, MessageSource.USER, MessageStatus.ACCEPTED),
            MessagePersistenceState("earlier-id", Message(ConversationId("conversation-1"), "first by ordinal"), 10, MessageRole.AGENT, MessageSource.RUNTIME, MessageStatus.DELIVERED),
        ),
        drafts = emptyList(),
    )
}
