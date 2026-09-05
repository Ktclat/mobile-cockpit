package dev.cockpit.persistence.room

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.conversation.Message
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.AgentProviderBindingPersistenceState
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationProviderRouteResolution
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.MessagePersistenceState
import dev.cockpit.persistence.api.MessageRole
import dev.cockpit.persistence.api.MessageSource
import dev.cockpit.persistence.api.MessageStatus
import dev.cockpit.persistence.api.PersonaPersistenceState
import dev.cockpit.persistence.api.ProviderModelOptionPersistenceState
import dev.cockpit.persistence.api.ProviderProfilePersistenceState
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProviderRouteRevisionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private var database: CockpitDatabase? = null

    @AfterEach
    fun closeDatabase() {
        database?.close()
    }

    @Test
    fun routeMismatchRemainsLockedUntilExplicitMigration() = runBlocking {
        val db = CockpitDatabase.open(temporaryDirectory.resolve("route.db").toString())
            .also { database = it }
        val conversations = RoomConversationRepository(db)
        val providers = RoomProviderConfigurationRepository(db)
        conversations.save(conversation())
        providers.saveProfile(profile(revision = 4, baseUrl = "https://old.example/v1"))
        providers.saveModel(model())
        providers.bindAgent(AgentProviderBindingPersistenceState(AGENT_ID, "profile-1", "model-1"))

        val first = providers.resolveConversationRoute(CONVERSATION_ID, AGENT_ID)
        assertInstanceOf(ConversationProviderRouteResolution.Ready::class.java, first)
        assertEquals(4L, providers.loadConfiguration().conversationRoutes.single().requestRevision)

        providers.saveProfile(profile(revision = 5, baseUrl = "https://new.example/v1"))
        val blocked = providers.resolveConversationRoute(CONVERSATION_ID, AGENT_ID)
        val mismatch = assertInstanceOf(
            ConversationProviderRouteResolution.RevisionMismatch::class.java,
            blocked,
        )
        assertEquals(4L, mismatch.route.requestRevision)
        assertEquals(5L, mismatch.currentProfileRevision)
        assertEquals(4L, providers.loadConfiguration().conversationRoutes.single().requestRevision)

        val migrated = providers.migrateConversationRoute(CONVERSATION_ID)
        assertInstanceOf(ConversationProviderRouteResolution.Ready::class.java, migrated)
        assertEquals(5L, providers.loadConfiguration().conversationRoutes.single().requestRevision)
        assertInstanceOf(
            ConversationProviderRouteResolution.Ready::class.java,
            providers.resolveConversationRoute(CONVERSATION_ID, AGENT_ID),
        )
        Unit
    }

    @Test
    fun unboundConversationWithUserHistoryDoesNotAutoBindToCurrentRoute() = runBlocking {
        val db = CockpitDatabase.open(temporaryDirectory.resolve("legacy-history.db").toString())
            .also { database = it }
        val conversations = RoomConversationRepository(db)
        val providers = RoomProviderConfigurationRepository(db)
        val userMessage = MessagePersistenceState(
            id = "message-1",
            message = Message(CONVERSATION_ID, "private history"),
            ordinal = 0L,
            role = MessageRole.USER,
            source = MessageSource.USER,
            status = MessageStatus.ACCEPTED,
        )
        conversations.save(conversation(messages = listOf(userMessage)))
        providers.saveProfile(profile(revision = 1, baseUrl = "https://new.example/v1"))
        providers.saveModel(model())
        providers.bindAgent(AgentProviderBindingPersistenceState(AGENT_ID, "profile-1", "model-1"))

        assertEquals(
            ConversationProviderRouteResolution.Missing,
            providers.resolveConversationRoute(CONVERSATION_ID, AGENT_ID),
        )
        assertEquals(emptyList<Any>(), providers.loadConfiguration().conversationRoutes)

        assertInstanceOf(
            ConversationProviderRouteResolution.Ready::class.java,
            providers.migrateConversationRoute(CONVERSATION_ID),
        )
        assertEquals(1L, providers.loadConfiguration().conversationRoutes.single().requestRevision)
    }

    @Test
    fun generatedInitialGreetingMayBindOnFirstSend() = runBlocking {
        val db = CockpitDatabase.open(temporaryDirectory.resolve("initial-greeting.db").toString())
            .also { database = it }
        val conversations = RoomConversationRepository(db)
        val providers = RoomProviderConfigurationRepository(db)
        val greeting = MessagePersistenceState(
            id = "message-1",
            message = Message(CONVERSATION_ID, "Hello"),
            ordinal = 1L,
            role = MessageRole.AGENT,
            source = MessageSource.RUNTIME,
            status = MessageStatus.DELIVERED,
        )
        conversations.save(conversation(messages = listOf(greeting)))
        providers.saveProfile(profile(revision = 1, baseUrl = "https://current.example/v1"))
        providers.saveModel(model())
        providers.bindAgent(AgentProviderBindingPersistenceState(AGENT_ID, "profile-1", "model-1"))

        assertInstanceOf(
            ConversationProviderRouteResolution.Ready::class.java,
            providers.resolveConversationRoute(CONVERSATION_ID, AGENT_ID),
        )
    }

    private fun conversation(
        messages: List<MessagePersistenceState> = emptyList(),
    ): ConversationSnapshot {
        val persona = Persona("Nova", "", "", "", "")
        return ConversationSnapshot(
            PersonaPersistenceState("persona-1", persona),
            AgentPersistenceState(
                Agent(AGENT_ID, persona, AgentCapabilities("chat")),
                "persona-1",
                0,
                ArchiveState.ACTIVE,
            ),
            ConversationPersistenceState(
                Conversation(CONVERSATION_ID, AGENT_ID, ConversationRevision(0)),
                ArchiveState.ACTIVE,
            ),
            messages,
            emptyList(),
        )
    }

    private fun profile(revision: Long, baseUrl: String) = ProviderProfilePersistenceState(
        id = "profile-1",
        displayName = "Primary",
        vendor = "CUSTOM",
        kind = "OPENAI_COMPATIBLE",
        baseUrl = baseUrl,
        model = "",
        credentialReference = "credential-1",
        credentialRotation = 1,
        maxOutputTokens = 1024,
        revision = revision,
        streamingCapability = "UNKNOWN",
        toolCapability = "UNKNOWN",
        lastProbeErrorCode = null,
        lastProbeMessage = null,
        lastProbedAtEpochMillis = null,
    )

    private fun model() = ProviderModelOptionPersistenceState(
        id = "model-1",
        connectionId = "profile-1",
        remoteModelId = "model-remote",
        displayName = "Model",
        enabled = true,
        source = "MANUAL",
        discoveredAtEpochMillis = null,
        discoveryState = "AVAILABLE",
    )

    private companion object {
        val AGENT_ID = AgentId("agent-1")
        val CONVERSATION_ID = ConversationId("conversation-1")
    }
}
