package dev.cockpit.platform.android

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cockpit.application.api.ProviderCredentialUpdate
import dev.cockpit.application.api.ProviderOperationCode
import dev.cockpit.application.api.ProviderProfileInput
import dev.cockpit.application.api.ProviderProtocol
import dev.cockpit.application.api.ProviderVendor
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.Conversation
import dev.cockpit.domain.credential.CredentialReference
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.domain.time.AppClock
import dev.cockpit.domain.time.InstantValue
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.AgentProviderBindingPersistenceState
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationPersistenceState
import dev.cockpit.persistence.api.ConversationProviderRouteResolution
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.persistence.api.PersonaPersistenceState
import dev.cockpit.persistence.api.ProviderConfigurationRepository
import dev.cockpit.persistence.api.ProviderModelOptionPersistenceState
import dev.cockpit.persistence.api.ProviderProfileMutation
import dev.cockpit.persistence.api.ProviderProfilePersistenceState
import dev.cockpit.persistence.room.CockpitDatabase
import dev.cockpit.persistence.room.RoomConversationRepository
import dev.cockpit.persistence.room.RoomProviderConfigurationRepository
import dev.cockpit.provider.api.ProviderAdapterResolver
import dev.cockpit.runtime.coordinator.ProviderInvocationGate
import dev.cockpit.security.vault.api.CredentialAdminPort
import dev.cockpit.security.vault.api.CredentialMetadata
import dev.cockpit.security.vault.api.DeviceAuthPolicy
import dev.cockpit.security.vault.api.NewCredential
import dev.cockpit.security.vault.api.ProviderCredentialLeasePort
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderSettingsControllerTransactionTest {
    private lateinit var database: CockpitDatabase
    private lateinit var repository: RoomProviderConfigurationRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CockpitDatabase::class.java,
        ).setDriver(AndroidSQLiteDriver()).build()
        repository = RoomProviderConfigurationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun newCredentialFailureLeavesDatabaseAndOldCredentialUnchanged() = runBlocking {
        seedProvider()
        val credentials = FakeCredentialAdmin().apply {
            seed("credential-old")
            failCreate = true
        }

        val result = controller(repository, credentials).saveProfile(replacementInput())

        assertFalse(result.success)
        assertEquals(profile(), repository.loadProfile(PROFILE_ID))
        assertEquals(model(), repository.loadModel(MODEL_ID))
        assertTrue(credentials.contains("credential-old"))
    }

    @Test
    fun failedDatabaseMutationCleansNewCredentialAndKeepsOldFacts() = runBlocking {
        seedProvider()
        val credentials = FakeCredentialAdmin().apply { seed("credential-old") }
        val failing = object : ProviderConfigurationRepository by repository {
            override suspend fun saveProfileMutation(mutation: ProviderProfileMutation) {
                throw IllegalStateException("injected transaction failure")
            }
        }

        val result = controller(failing, credentials).saveProfile(replacementInput())

        assertFalse(result.success)
        assertEquals(ProviderOperationCode.PROVIDER_CONFIG_TRANSACTION_FAILED, result.code)
        assertEquals(profile(), repository.loadProfile(PROFILE_ID))
        assertEquals(model(), repository.loadModel(MODEL_ID))
        assertTrue(credentials.contains("credential-old"))
        assertFalse(credentials.contains("credential-new-1"))
    }

    @Test
    fun successfulReplacementCommitsNewCredentialAndThenDeletesOldCredential() = runBlocking {
        seedProvider()
        val credentials = FakeCredentialAdmin().apply { seed("credential-old") }

        val result = controller(repository, credentials).saveProfile(replacementInput())
        val saved = requireNotNull(repository.loadProfile(PROFILE_ID))

        assertTrue(result.success)
        assertEquals(5L, saved.revision)
        assertEquals("credential-new-1", saved.credentialReference)
        assertTrue(credentials.contains("credential-new-1"))
        assertFalse(credentials.contains("credential-old"))
    }

    @Test
    fun oldCredentialCleanupFailureKeepsNewConfigurationAndReturnsWarning() = runBlocking {
        seedProvider()
        val credentials = FakeCredentialAdmin().apply {
            seed("credential-old")
            failDelete += "credential-old"
        }

        val result = controller(repository, credentials).saveProfile(replacementInput())
        val saved = requireNotNull(repository.loadProfile(PROFILE_ID))

        assertTrue(result.success)
        assertEquals(ProviderOperationCode.PROVIDER_CREDENTIAL_CLEANUP_PENDING, result.code)
        assertEquals("credential-new-1", saved.credentialReference)
        assertTrue(credentials.contains("credential-new-1"))
        assertTrue(credentials.contains("credential-old"))
    }

    @Test
    fun profileDeleteCleanupFailureReportsCommittedDeleteAsWarning() = runBlocking {
        seedProvider()
        val credentials = FakeCredentialAdmin().apply {
            seed("credential-old")
            failDelete += "credential-old"
        }

        val result = controller(repository, credentials).deleteProfile(PROFILE_ID)

        assertTrue(result.success)
        assertEquals(ProviderOperationCode.PROVIDER_CREDENTIAL_CLEANUP_PENDING, result.code)
        assertEquals(null, repository.loadProfile(PROFILE_ID))
        assertEquals(null, repository.loadModel(MODEL_ID))
        assertTrue(credentials.contains("credential-old"))
    }

    @Test
    fun displayMetadataKeepsRevisionWhileMaxTokensInvalidatesLockedRoute() = runBlocking {
        seedProvider()
        seedConversationRoute()
        val credentials = FakeCredentialAdmin().apply { seed("credential-old") }
        val controller = controller(repository, credentials)

        val metadataOnly = controller.saveProfile(
            replacementInput().copy(
                displayName = "Renamed",
                note = "UI note",
                apiKey = "",
                credentialUpdate = ProviderCredentialUpdate.KEEP,
            ),
        )
        assertTrue(metadataOnly.success)
        assertEquals(4L, repository.loadProfile(PROFILE_ID)?.revision)
        assertTrue(
            repository.resolveConversationRoute(CONVERSATION_ID, AGENT_ID) is
                ConversationProviderRouteResolution.Ready,
        )

        val requestChange = controller.saveProfile(
            replacementInput().copy(
                displayName = "Renamed",
                note = "UI note",
                apiKey = "",
                credentialUpdate = ProviderCredentialUpdate.KEEP,
                maxOutputTokens = 8_192,
            ),
        )
        assertTrue(requestChange.success)
        assertEquals(5L, repository.loadProfile(PROFILE_ID)?.revision)
        assertTrue(
            repository.resolveConversationRoute(CONVERSATION_ID, AGENT_ID) is
                ConversationProviderRouteResolution.RevisionMismatch,
        )
    }

    private suspend fun seedProvider() {
        repository.saveProfile(profile())
        repository.saveModel(model())
    }

    private suspend fun seedConversationRoute() {
        val persona = Persona("Route agent", "", "", "", "")
        RoomConversationRepository(database).save(
            ConversationSnapshot(
                persona = PersonaPersistenceState("persona-route", persona),
                agent = AgentPersistenceState(
                    Agent(AGENT_ID, persona, AgentCapabilities("chat")),
                    "persona-route",
                    1,
                    ArchiveState.ACTIVE,
                ),
                conversation = ConversationPersistenceState(
                    Conversation(CONVERSATION_ID, AGENT_ID, ConversationRevision(0)),
                    ArchiveState.ACTIVE,
                ),
                messages = emptyList(),
                drafts = emptyList(),
            ),
        )
        repository.bindAgent(AgentProviderBindingPersistenceState(AGENT_ID, PROFILE_ID, MODEL_ID))
        assertTrue(
            repository.resolveConversationRoute(CONVERSATION_ID, AGENT_ID) is
                ConversationProviderRouteResolution.Ready,
        )
    }

    private fun controller(
        providers: ProviderConfigurationRepository,
        credentials: CredentialAdminPort,
    ) = ProviderSettingsController(providers, credentials, unusedInvocationGate())

    private fun unusedInvocationGate() = ProviderInvocationGate(
        credentialLeases = object : ProviderCredentialLeasePort {
            override suspend fun acquire(authority: dev.cockpit.provider.api.ProviderInvocationAuthority) = null
        },
        adapters = ProviderAdapterResolver { error("Provider adapter is not used by these tests") },
        clock = object : AppClock {
            override fun now() = InstantValue(1)
        },
        ids = object : IdGenerator {
            private var next = 0
            override fun nextId(): String = "test-${next++}"
        },
    )

    private fun replacementInput() = ProviderProfileInput(
        id = PROFILE_ID,
        displayName = "Primary",
        vendor = ProviderVendor.CUSTOM,
        baseUrl = "https://provider.example/v1",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        apiKey = "new-secret",
        credentialUpdate = ProviderCredentialUpdate.REPLACE,
        maxOutputTokens = 4_096,
    )

    private fun profile() = ProviderProfilePersistenceState(
        id = PROFILE_ID,
        displayName = "Primary",
        vendor = ProviderVendor.CUSTOM.name,
        kind = "OPENAI_COMPATIBLE",
        baseUrl = "https://provider.example/v1",
        model = "",
        credentialReference = "credential-old",
        credentialRotation = 1,
        maxOutputTokens = 4_096,
        revision = 4,
        streamingCapability = "UNKNOWN",
        toolCapability = "UNKNOWN",
        lastProbeErrorCode = null,
        lastProbeMessage = null,
        lastProbedAtEpochMillis = null,
        preferredModelId = MODEL_ID,
    )

    private fun model() = ProviderModelOptionPersistenceState(
        id = MODEL_ID,
        connectionId = PROFILE_ID,
        remoteModelId = "model-remote",
        displayName = "Model",
        enabled = true,
        source = "DISCOVERED",
        discoveredAtEpochMillis = 1,
        discoveryState = "CURRENT",
    )

    private class FakeCredentialAdmin : CredentialAdminPort {
        private val metadata = linkedMapOf<String, CredentialMetadata>()
        var failCreate = false
        val failDelete = mutableSetOf<String>()
        private var next = 0

        fun seed(reference: String) {
            metadata[reference] = CredentialMetadata(CredentialReference(reference), reference, 1)
        }

        fun contains(reference: String): Boolean = metadata.containsKey(reference)

        override suspend fun create(
            input: NewCredential,
            authPolicy: DeviceAuthPolicy,
        ): CredentialMetadata {
            check(input.consume { _ -> })
            if (failCreate) throw IllegalStateException("injected credential failure")
            val reference = "credential-new-${++next}"
            return CredentialMetadata(CredentialReference(reference), reference, 1).also {
                metadata[reference] = it
            }
        }

        override suspend fun metadata(reference: CredentialReference): CredentialMetadata? =
            metadata[reference.value]

        override suspend fun rotate(
            reference: CredentialReference,
            replacement: NewCredential,
        ): CredentialMetadata? = null

        override suspend fun delete(reference: CredentialReference) {
            if (reference.value in failDelete) throw IllegalStateException("injected cleanup failure")
            metadata.remove(reference.value)
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-atomic"
        const val MODEL_ID = "model-atomic"
        val AGENT_ID = AgentId("agent-route")
        val CONVERSATION_ID = ConversationId("conversation-route")
    }
}
