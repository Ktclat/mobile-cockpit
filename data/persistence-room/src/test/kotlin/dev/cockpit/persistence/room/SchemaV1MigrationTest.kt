package dev.cockpit.persistence.room

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
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.AgentReadFact
import dev.cockpit.persistence.api.AgentDetailReadFact
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus
import dev.cockpit.persistence.api.PersonaPersistenceState
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.room3.testing.MigrationTestHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

class SchemaV1MigrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private var database: CockpitDatabase? = null

    @AfterEach
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun agentConversationMessageDraftRoundTrip() {
        runBlocking {
        database = CockpitDatabase.open(temporaryDirectory.resolve("cockpit.db").toString())
        val repository = RoomConversationRepository(checkNotNull(database))
        val expected = ConversationSnapshot(
            persona = PersonaPersistenceState(
                id = "persona-nova",
                persona = Persona(
                    identity = "Nova",
                    presentation = "Midnight blue",
                    voice = "Calm",
                    behavioralTendency = "Methodical",
                    promptStyle = "Concise",
                ),
            ),
            agent = AgentPersistenceState(
                agent = Agent(
                    id = AgentId("agent-nova"),
                    persona = Persona(
                        identity = "Nova",
                        presentation = "Midnight blue",
                        voice = "Calm",
                        behavioralTendency = "Methodical",
                        promptStyle = "Concise",
                    ),
                    capabilities = AgentCapabilities("Local conversation continuity"),
                ),
                personaId = "persona-nova",
                revision = 17L,
                archiveState = ArchiveState.ARCHIVED,
            ),
            conversation = ConversationPersistenceState(
                conversation = Conversation(
                    id = ConversationId("conversation-amber"),
                    agentId = AgentId("agent-nova"),
                    revision = ConversationRevision(41L),
                ),
                archiveState = ArchiveState.ARCHIVED,
                personaSnapshot = Persona(
                    identity = "Nova",
                    presentation = "Midnight blue",
                    voice = "Calm",
                    behavioralTendency = "Methodical",
                    promptStyle = "Concise",
                ),
            ),
            messages = listOf(
                MessagePersistenceState(
                    id = "a-second",
                    message = Message(ConversationId("conversation-amber"), "Second by ordinal"),
                    ordinal = 20L,
                    role = MessageRole.AGENT,
                    source = MessageSource.DEBUG,
                    status = MessageStatus.DELIVERED,
                ),
                MessagePersistenceState(
                    id = "z-first",
                    message = Message(ConversationId("conversation-amber"), "First by ordinal"),
                    ordinal = 10L,
                    role = MessageRole.USER,
                    source = MessageSource.USER,
                    status = MessageStatus.ACCEPTED,
                ),
            ),
            drafts = listOf(
                Draft(
                    destination = ConversationMessageDestination(
                        conversationId = ConversationId("conversation-amber"),
                        expectedConversationRevision = ConversationRevision(39L),
                    ),
                    text = "Keep this exact destination",
                ),
            ),
        )

        repository.save(expected)
        val actual = requireNotNull(repository.load(ConversationId("conversation-amber")))

        assertEquals(expected.persona, actual.persona)
        assertEquals(expected.agent, actual.agent)
        assertEquals(expected.conversation, actual.conversation)
        assertEquals(listOf("z-first", "a-second"), actual.messages.map { it.id })
        assertEquals(listOf(10L, 20L), actual.messages.map { it.ordinal })
        assertEquals(expected.messages.sortedBy { it.ordinal }, actual.messages)
        assertEquals(expected.drafts, actual.drafts)
        assertEquals(ConversationRevision(39L), actual.drafts.single().destination.expectedConversationRevision)
        }
    }

    @Test
    fun orphanMessageIsRejectedBySQLiteForeignKey() {
        database = CockpitDatabase.open(temporaryDirectory.resolve("orphan.db").toString())

        assertThrows(SQLiteException::class.java) {
            runBlocking {
                checkNotNull(database).messageDao().insert(
                    MessageEntity("orphan", "missing-conversation", "No parent", 1L, "USER", "USER", "ACCEPTED"),
                )
            }
        }
    }

    @Test
    fun orphanAgentConversationAndDraftAreRejectedBySQLiteForeignKeys() {
        database = CockpitDatabase.open(temporaryDirectory.resolve("orphans.db").toString())
        val db = checkNotNull(database)

        assertThrows(SQLiteException::class.java) { runBlocking { db.agentDao().upsert(AgentEntity("orphan-agent", "missing-persona", "summary", 1L, "ACTIVE")) } }
        assertThrows(SQLiteException::class.java) { runBlocking { db.conversationDao().upsert(ConversationEntity("orphan-conversation", "missing-agent", 1L, "ACTIVE")) } }
        assertThrows(SQLiteException::class.java) { runBlocking { db.draftDao().upsert(DraftEntity("missing-conversation", 1L, "orphan")) } }
    }

    @Test
    fun duplicateConversationOrdinalIsRejectedBySQLite() {
        runBlocking {
            database = CockpitDatabase.open(temporaryDirectory.resolve("duplicate-ordinal.db").toString())
            val db = checkNotNull(database)
            val repository = RoomConversationRepository(db)
            repository.save(snapshot(ArchiveState.ACTIVE, listOf(message("first", 7L, "first")), emptyList()))

            assertThrows(SQLiteException::class.java) {
                runBlocking { db.messageDao().insert(MessageEntity("second", "conversation-repeat", "second", 7L, "USER", "USER", "ACCEPTED")) }
            }
        }
    }

    @Test
    fun exportedSchemaOneIsConsumableAndValidatesCockpitDatabase() {
        val schema = Path.of("schemas", "dev.cockpit.persistence.room.CockpitDatabase", "1.json")
        assertEquals(true, Files.exists(schema))
        val helper = MigrationTestHelper(
            schema.parent.parent,
            temporaryDirectory.resolve("schema-validation.db"),
            BundledSQLiteDriver(),
            CockpitDatabase::class,
        )
        runBlocking {
            helper.createDatabase(1).close()
            helper.runMigrationsAndValidate(1).close()
        }
    }

    @Test
    fun secondConversationSaveDoesNotOverwriteAuthoritativeAgent() {
        runBlocking {
        database = CockpitDatabase.open(temporaryDirectory.resolve("repeat.db").toString())
        val repository = RoomConversationRepository(checkNotNull(database))
        val first = snapshot(
            archiveState = ArchiveState.ARCHIVED,
            messages = listOf(message("z-last", 20L, "obsolete"), message("a-first", 10L, "keep")),
            drafts = listOf(Draft(ConversationMessageDestination(ConversationId("conversation-repeat"), ConversationRevision(3L)), "obsolete draft")),
        )
        val second = first.copy(
            agent = first.agent.copy(revision = 18L, archiveState = ArchiveState.ACTIVE),
            conversation = first.conversation.copy(archiveState = ArchiveState.ACTIVE),
            messages = listOf(message("m-updated", 5L, "updated")),
            drafts = emptyList(),
        )

        repository.save(first)
        repository.save(second)

        assertEquals(
            second.copy(agent = first.agent),
            requireNotNull(repository.load(ConversationId("conversation-repeat"))),
        )
        }
    }

    @Test
    fun agentFactsEmitInitialAndInvalidatedActiveAgentWithoutConversation() = runBlocking {
        database = CockpitDatabase.open(temporaryDirectory.resolve("agent-facts.db").toString())
        val repository = RoomConversationRepository(checkNotNull(database))
        val agent = AgentPersistenceState(
            Agent(AgentId("agent-alone"), Persona("Alone", "Blue", "Calm", "Exact", "Short"), AgentCapabilities("Local")),
            "persona-alone",
            0,
            ArchiveState.ACTIVE,
        )
        val initial = CompletableDeferred<Unit>()
        val views = mutableListOf<List<AgentReadFact>>()
        val job = launch {
            repository.observeAgentFacts().take(2).collect { view ->
                views += view
                if (views.size == 1) initial.complete(Unit)
            }
        }
        initial.await()
        repository.save(agent)
        job.join()
        assertEquals(emptyList<AgentPersistenceState>(), views.first().map { it.agent })
        assertEquals(listOf(agent), views.last().map { it.agent })
    }

    @Test
    fun conversationObservationEmitsInitialThenWholeAtomicReplacement() = runBlocking {
        database = CockpitDatabase.open(temporaryDirectory.resolve("conversation-observe.db").toString())
        val repository = RoomConversationRepository(checkNotNull(database))
        val initialSnapshot = snapshot(ArchiveState.ACTIVE, listOf(message("initial", 0L, "initial")), listOf(Draft(ConversationMessageDestination(ConversationId("conversation-repeat"), ConversationRevision(9L)), "initial draft")))
        val replacement = initialSnapshot.copy(
            conversation = initialSnapshot.conversation.copy(
                conversation = Conversation(ConversationId("conversation-repeat"), AgentId("agent-repeat"), ConversationRevision(10L)),
                archiveState = ArchiveState.ARCHIVED,
            ),
            messages = listOf(message("replacement", 4L, "replacement")),
            drafts = listOf(Draft(ConversationMessageDestination(ConversationId("conversation-repeat"), ConversationRevision(10L)), "replacement draft")),
        )
        repository.save(initialSnapshot)
        val initial = CompletableDeferred<Unit>()
        val values = mutableListOf<ConversationSnapshot?>()
        val job = launch {
            repository.observeConversation(ConversationId("conversation-repeat")).take(2).collect { value ->
                values += value
                if (values.size == 1) initial.complete(Unit)
            }
        }
        initial.await()
        repository.save(replacement)
        job.join()

        assertEquals(initialSnapshot, values.first())
        assertEquals(replacement, values.last())
    }

    @Test
    fun agentDetailFactsAndConversationListEmitBeforeAndAfterMutations() = runBlocking {
        database = CockpitDatabase.open(temporaryDirectory.resolve("agent-detail-observe.db").toString())
        val repository = RoomConversationRepository(checkNotNull(database))
        val initialAgent = AgentPersistenceState(Agent(AgentId("agent-detail"), Persona("Detail", "Blue", "Calm", "Exact", "Short"), AgentCapabilities("Local")), "persona-detail", 6, ArchiveState.ACTIVE)
        val initialSnapshot = ConversationSnapshot(PersonaPersistenceState("persona-detail", initialAgent.agent.persona), initialAgent, ConversationPersistenceState(Conversation(ConversationId("detail-conversation"), initialAgent.agent.id, ConversationRevision(2)), ArchiveState.ACTIVE, personaSnapshot = initialAgent.agent.persona), listOf(MessagePersistenceState("initial-message", Message(ConversationId("detail-conversation"), "initial"), 1, MessageRole.AGENT, MessageSource.RUNTIME, MessageStatus.DELIVERED)), listOf(Draft(ConversationMessageDestination(ConversationId("detail-conversation"), ConversationRevision(2)), "initial draft")))
        val replacementAgent = initialAgent.copy(revision = 7, archiveState = ArchiveState.ARCHIVED)
        val replacement = initialSnapshot.copy(agent = replacementAgent, conversation = ConversationPersistenceState(Conversation(ConversationId("detail-conversation"), replacementAgent.agent.id, ConversationRevision(3)), ArchiveState.ARCHIVED), messages = listOf(MessagePersistenceState("replacement-message", Message(ConversationId("detail-conversation"), "replacement"), 4, MessageRole.USER, MessageSource.USER, MessageStatus.ACCEPTED)), drafts = listOf(Draft(ConversationMessageDestination(ConversationId("detail-conversation"), ConversationRevision(3)), "replacement draft")))
        repository.save(initialSnapshot)
        val emissions = Channel<AgentDetailReadFact?>(Channel.UNLIMITED)
        val job = launch { repository.observeAgentDetail(initialAgent.agent.id).collect { emissions.send(it) } }
        try {
            assertEquals(AgentDetailReadFact(AgentReadFact(initialSnapshot.persona, initialSnapshot.agent), listOf(initialSnapshot)), emissions.receive())
            repository.save(replacement)
            val persistedReplacement = replacement.copy(
                agent = initialAgent,
                conversation = replacement.conversation.copy(
                    personaSnapshot = initialAgent.agent.persona,
                ),
            )
            assertEquals(
                AgentDetailReadFact(
                    AgentReadFact(initialSnapshot.persona, initialAgent),
                    listOf(persistedReplacement),
                ),
                emissions.receive(),
            )
            assertEquals(null, withTimeoutOrNull(250) { emissions.receive() })
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun snapshot(
        archiveState: ArchiveState,
        messages: List<MessagePersistenceState>,
        drafts: List<Draft>,
    ) = ConversationSnapshot(
        PersonaPersistenceState("persona-repeat", Persona("Repeat", "Blue", "Calm", "Exact", "Short")),
        AgentPersistenceState(Agent(AgentId("agent-repeat"), Persona("Repeat", "Blue", "Calm", "Exact", "Short"), AgentCapabilities("summary")), "persona-repeat", 17L, archiveState),
        ConversationPersistenceState(
            Conversation(ConversationId("conversation-repeat"), AgentId("agent-repeat"), ConversationRevision(9L)),
            archiveState,
            personaSnapshot = Persona("Repeat", "Blue", "Calm", "Exact", "Short"),
        ),
        messages,
        drafts,
    )

    private fun message(id: String, ordinal: Long, text: String) = MessagePersistenceState(
        id, Message(ConversationId("conversation-repeat"), text), ordinal, MessageRole.USER, MessageSource.USER, MessageStatus.ACCEPTED,
    )
}
