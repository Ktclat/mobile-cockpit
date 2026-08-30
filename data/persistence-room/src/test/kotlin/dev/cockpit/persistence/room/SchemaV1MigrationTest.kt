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
    fun secondSaveReplacesTheWholeConversationSnapshot() {
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

        assertEquals(second, requireNotNull(repository.load(ConversationId("conversation-repeat"))))
        }
    }

    private fun snapshot(
        archiveState: ArchiveState,
        messages: List<MessagePersistenceState>,
        drafts: List<Draft>,
    ) = ConversationSnapshot(
        PersonaPersistenceState("persona-repeat", Persona("Repeat", "Blue", "Calm", "Exact", "Short")),
        AgentPersistenceState(Agent(AgentId("agent-repeat"), Persona("Repeat", "Blue", "Calm", "Exact", "Short"), AgentCapabilities("summary")), "persona-repeat", 17L, archiveState),
        ConversationPersistenceState(Conversation(ConversationId("conversation-repeat"), AgentId("agent-repeat"), ConversationRevision(9L)), archiveState),
        messages,
        drafts,
    )

    private fun message(id: String, ordinal: Long, text: String) = MessagePersistenceState(
        id, Message(ConversationId("conversation-repeat"), text), ordinal, MessageRole.USER, MessageSource.USER, MessageStatus.ACCEPTED,
    )
}
