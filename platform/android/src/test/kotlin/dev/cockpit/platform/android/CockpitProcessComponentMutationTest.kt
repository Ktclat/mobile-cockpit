package dev.cockpit.platform.android

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.cockpit.application.api.AgentApplicationPort
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.application.api.ProviderCredentialUpdate
import dev.cockpit.application.api.ProviderProfileInput
import dev.cockpit.application.api.ProviderProtocol
import dev.cockpit.application.api.ProviderSettingsPort
import dev.cockpit.application.api.ProviderVendor
import dev.cockpit.persistence.room.CockpitDatabase
import dev.cockpit.persistence.api.GenerationAttemptStatus
import dev.cockpit.projection.model.ConversationProviderRouteState
import dev.cockpit.projection.model.TimelineItemProjection
import dev.cockpit.provider.api.ProviderAdapter
import dev.cockpit.provider.api.ProviderAuthorizationHandle
import dev.cockpit.provider.api.ProviderCapabilities
import dev.cockpit.provider.api.ProviderCapabilitySupport
import dev.cockpit.provider.api.ProviderKind
import dev.cockpit.provider.api.ProviderModelDiscoveryResult
import dev.cockpit.provider.api.ProviderProbeResult
import dev.cockpit.provider.api.ProviderProfile
import dev.cockpit.provider.api.ProviderStreamEvent
import dev.cockpit.provider.api.NormalizedProviderRequest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun missingModelRouteRejectsSendWithoutPersistingMessageAndStillAllowsDraft() = runBlocking {
        val component = component(EchoProviderAdapter())
        val agents = component.agentApplicationPortHandle as AgentApplicationPort
        val conversations = component.conversationApplicationPortHandle as ConversationApplicationPort
        val queries = component.agentConversationQueryPortHandle as AgentConversationQueryPort
        val agentId = checkNotNull(agents.createAgent("No provider agent"))
        val conversationId = checkNotNull(conversations.createConversation(agentId))
        val initial = withTimeout(5_000) { queries.conversation(conversationId).first() }

        assertTrue(conversations.saveDraft(initial.messageDestination, "saved locally"))
        assertFalse(conversations.sendMessage(initial.messageDestination, "must not be persisted"))

        val final = withTimeout(5_000) {
            queries.conversation(conversationId).first { it.drafts.isNotEmpty() }
        }
        assertEquals(ConversationProviderRouteState.MISSING, final.providerRouteState)
        assertEquals("saved locally", final.drafts.single().text)
        assertTrue(final.timeline.isEmpty())
    }

    @Test
    fun configuredModelRouteInvokesRuntimeAndPersistsReply() = runBlocking {
        val adapter = EchoProviderAdapter()
        val component = component(adapter)
        val agents = component.agentApplicationPortHandle as AgentApplicationPort
        val conversations = component.conversationApplicationPortHandle as ConversationApplicationPort
        val queries = component.agentConversationQueryPortHandle as AgentConversationQueryPort
        val settings = component.providerSettingsPortHandle as ProviderSettingsPort
        val agentId = checkNotNull(agents.createAgent("Configured agent"))
        val profile = settings.saveProfile(
            ProviderProfileInput(
                displayName = "Test API",
                vendor = ProviderVendor.CUSTOM,
                baseUrl = "https://provider.example/v1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                apiKey = "secret",
                credentialUpdate = ProviderCredentialUpdate.REPLACE,
            ),
        )
        assertTrue(profile.success)
        val profileId = checkNotNull(profile.profileId)
        val model = settings.addModel(profileId, "test-model")
        assertTrue(model.success)
        assertTrue(settings.bindAgent(agentId, profileId, checkNotNull(model.modelId)).success)
        val conversationId = checkNotNull(conversations.createConversation(agentId))
        val destination = withTimeout(5_000) {
            queries.conversation(conversationId).first {
                it.providerRouteState == ConversationProviderRouteState.READY
            }.messageDestination
        }

        assertTrue(conversations.sendMessage(destination, "hello"))
        val final = withTimeout(5_000) {
            queries.conversation(conversationId).first { it.timeline.size == 2 }
        }

        assertNotNull(adapter.lastRequest.get())
        assertEquals(
            listOf("hello", "agent reply"),
            final.timeline.map { (it as TimelineItemProjection.MessageItem).message.text },
        )
        assertEquals(
            GenerationAttemptStatus.COMPLETED.name,
            checkNotNull(database).generationAttemptDao().forConversation(conversationId.value)?.status,
        )
    }

    @Test
    fun providerFlowWithoutTerminalEventLeavesAnExplicitError() = runBlocking {
        val adapter = EchoProviderAdapter(emitTerminal = false)
        val component = component(adapter)
        val agents = component.agentApplicationPortHandle as AgentApplicationPort
        val conversations = component.conversationApplicationPortHandle as ConversationApplicationPort
        val queries = component.agentConversationQueryPortHandle as AgentConversationQueryPort
        val settings = component.providerSettingsPortHandle as ProviderSettingsPort
        val agentId = checkNotNull(agents.createAgent("Silent provider agent"))
        val profileId = checkNotNull(settings.saveProfile(
            ProviderProfileInput(
                displayName = "Silent API",
                vendor = ProviderVendor.CUSTOM,
                baseUrl = "https://provider.example/v1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                apiKey = "secret",
                credentialUpdate = ProviderCredentialUpdate.REPLACE,
            ),
        ).profileId)
        val modelId = checkNotNull(settings.addModel(profileId, "test-model").modelId)
        assertTrue(settings.bindAgent(agentId, profileId, modelId).success)
        val conversationId = checkNotNull(conversations.createConversation(agentId))
        val destination = withTimeout(5_000) {
            queries.conversation(conversationId).first {
                it.providerRouteState == ConversationProviderRouteState.READY
            }.messageDestination
        }

        assertTrue(conversations.sendMessage(destination, "must report a terminal outcome"))
        val failed = withTimeout(5_000) {
            queries.conversation(conversationId).first { it.providerError != null }
        }

        assertEquals("MALFORMED_STREAM", failed.providerError?.code)
        assertEquals(
            listOf("must report a terminal outcome"),
            failed.timeline.map { (it as TimelineItemProjection.MessageItem).message.text },
        )
        assertFalse(failed.streamingReply?.inProgress ?: false)
        val attempt = checkNotNull(database).generationAttemptDao().forConversation(conversationId.value)
        assertEquals(GenerationAttemptStatus.FAILED.name, attempt?.status)
        assertEquals("MALFORMED_STREAM", attempt?.errorCode)
    }

    @Test
    fun cancellingGenerationPersistsCancelledAndNeverSavesPartialReply() = runBlocking {
        val adapter = StallingProviderAdapter()
        val component = component(adapter)
        val agents = component.agentApplicationPortHandle as AgentApplicationPort
        val conversations = component.conversationApplicationPortHandle as ConversationApplicationPort
        val queries = component.agentConversationQueryPortHandle as AgentConversationQueryPort
        val settings = component.providerSettingsPortHandle as ProviderSettingsPort
        val agentId = checkNotNull(agents.createAgent("Cancel agent"))
        val profileId = checkNotNull(settings.saveProfile(
            ProviderProfileInput(
                displayName = "Cancel API",
                vendor = ProviderVendor.CUSTOM,
                baseUrl = "https://provider.example/v1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                apiKey = "secret",
                credentialUpdate = ProviderCredentialUpdate.REPLACE,
            ),
        ).profileId)
        val modelId = checkNotNull(settings.addModel(profileId, "test-model").modelId)
        assertTrue(settings.bindAgent(agentId, profileId, modelId).success)
        val conversationId = checkNotNull(conversations.createConversation(agentId))
        val destination = withTimeout(5_000) {
            queries.conversation(conversationId).first {
                it.providerRouteState == ConversationProviderRouteState.READY
            }.messageDestination
        }

        assertTrue(conversations.sendMessage(destination, "stop this"))
        withTimeout(5_000) { adapter.started.await() }
        assertTrue(conversations.cancelReply(conversationId))
        val cancelled = withTimeout(5_000) {
            queries.conversation(conversationId).first { it.providerError?.code == "CANCELLED" }
        }

        assertEquals(1, cancelled.timeline.size)
        assertEquals(
            GenerationAttemptStatus.CANCELLED.name,
            checkNotNull(database).generationAttemptDao().forConversation(conversationId.value)?.status,
        )
        assertTrue(adapter.cancelCalled.get())
    }

    @Test
    fun recreatingProcessMarksOrphanedAttemptInterruptedWithoutAutomaticRetry() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "generation-recovery.db"
        context.deleteDatabase(databaseName)
        val firstDatabase = Room.databaseBuilder(context, CockpitDatabase::class.java, databaseName)
            .setDriver(AndroidSQLiteDriver())
            .build()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter = StallingProviderAdapter()
        var secondDatabase: CockpitDatabase? = null
        var secondScope: CoroutineScope? = null
        try {
            val firstComponent = CockpitProcessComponent(
                databaseFactory = { firstDatabase },
                mutationScope = firstScope,
                providerAdapterResolver = { adapter },
            )
            val agents = firstComponent.agentApplicationPortHandle as AgentApplicationPort
            val conversations = firstComponent.conversationApplicationPortHandle as ConversationApplicationPort
            val queries = firstComponent.agentConversationQueryPortHandle as AgentConversationQueryPort
            val settings = firstComponent.providerSettingsPortHandle as ProviderSettingsPort
            val agentId = checkNotNull(agents.createAgent("Recovery agent"))
            val profileId = checkNotNull(settings.saveProfile(
                ProviderProfileInput(
                    displayName = "Recovery API",
                    vendor = ProviderVendor.CUSTOM,
                    baseUrl = "https://provider.example/v1",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    apiKey = "secret",
                    credentialUpdate = ProviderCredentialUpdate.REPLACE,
                ),
            ).profileId)
            val modelId = checkNotNull(settings.addModel(profileId, "test-model").modelId)
            assertTrue(settings.bindAgent(agentId, profileId, modelId).success)
            val conversationId = checkNotNull(conversations.createConversation(agentId))
            val destination = withTimeout(5_000) {
                queries.conversation(conversationId).first {
                    it.providerRouteState == ConversationProviderRouteState.READY
                }.messageDestination
            }

            assertTrue(conversations.sendMessage(destination, "survive process death"))
            withTimeout(5_000) { adapter.started.await() }
            assertEquals(
                GenerationAttemptStatus.STARTED.name,
                firstDatabase.generationAttemptDao().forConversation(conversationId.value)?.status,
            )

            firstScope.cancel()
            firstDatabase.close()
            secondDatabase = Room.databaseBuilder(context, CockpitDatabase::class.java, databaseName)
                .setDriver(AndroidSQLiteDriver())
                .build()
            secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val secondComponent = CockpitProcessComponent(
                databaseFactory = { checkNotNull(secondDatabase) },
                mutationScope = secondScope,
                providerAdapterResolver = { adapter },
            )
            val recoveredQueries = secondComponent.agentConversationQueryPortHandle as AgentConversationQueryPort
            val recovered = withTimeout(5_000) {
                recoveredQueries.conversation(conversationId).first {
                    it.providerError?.code == "GENERATION_INTERRUPTED"
                }
            }

            assertEquals(1, adapter.invocationCount.get())
            assertEquals(1, recovered.timeline.size)
            assertTrue(recovered.providerError?.retryable == true)
            assertEquals(
                GenerationAttemptStatus.INTERRUPTED.name,
                checkNotNull(secondDatabase).generationAttemptDao().forConversation(conversationId.value)?.status,
            )
        } finally {
            firstScope.cancel()
            secondScope?.cancel()
            runCatching { firstDatabase.close() }
            runCatching { secondDatabase?.close() }
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun endpointChangeBlocksLockedConversationUntilExplicitMigration() = runBlocking {
        val adapter = EchoProviderAdapter()
        val component = component(adapter)
        val agents = component.agentApplicationPortHandle as AgentApplicationPort
        val conversations = component.conversationApplicationPortHandle as ConversationApplicationPort
        val queries = component.agentConversationQueryPortHandle as AgentConversationQueryPort
        val settings = component.providerSettingsPortHandle as ProviderSettingsPort
        val agentId = checkNotNull(agents.createAgent("Revision agent"))
        val profileId = checkNotNull(settings.saveProfile(
            ProviderProfileInput(
                displayName = "Revision API",
                vendor = ProviderVendor.CUSTOM,
                baseUrl = "https://old.provider.example/v1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                apiKey = "secret",
                credentialUpdate = ProviderCredentialUpdate.REPLACE,
            ),
        ).profileId)
        val modelId = checkNotNull(settings.addModel(profileId, "test-model").modelId)
        assertTrue(settings.bindAgent(agentId, profileId, modelId).success)
        val conversationId = checkNotNull(conversations.createConversation(agentId))
        val initial = withTimeout(5_000) {
            queries.conversation(conversationId).first {
                it.providerRouteState == ConversationProviderRouteState.READY
            }
        }
        assertTrue(conversations.sendMessage(initial.messageDestination, "first"))
        val afterReply = withTimeout(5_000) {
            queries.conversation(conversationId).first { it.timeline.size == 2 }
        }

        assertTrue(settings.saveProfile(
            ProviderProfileInput(
                id = profileId,
                displayName = "Revision API",
                vendor = ProviderVendor.CUSTOM,
                baseUrl = "https://new.provider.example/v1",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                credentialUpdate = ProviderCredentialUpdate.KEEP,
            ),
        ).success)
        withTimeout(5_000) {
            queries.conversation(conversationId).first {
                it.providerRouteState == ConversationProviderRouteState.REVISION_MISMATCH
            }
        }

        assertFalse(conversations.sendMessage(afterReply.messageDestination, "blocked"))
        assertEquals(1, adapter.invocationCount.get())
        assertEquals(2, queries.conversation(conversationId).first().timeline.size)
        assertTrue(conversations.migrateProviderRoute(conversationId))
        withTimeout(5_000) {
            queries.conversation(conversationId).first {
                it.providerRouteState == ConversationProviderRouteState.READY
            }
        }
        assertEquals(1, adapter.invocationCount.get())
    }

    private fun component(adapter: ProviderAdapter): CockpitProcessComponent {
        val context = RuntimeEnvironment.getApplication()
        val testDatabase = Room.inMemoryDatabaseBuilder(
            context,
            CockpitDatabase::class.java,
        ).setDriver(AndroidSQLiteDriver()).build().also { database = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            .also { processScope = it }
        return CockpitProcessComponent(
            databaseFactory = { testDatabase },
            mutationScope = scope,
            providerAdapterResolver = { adapter },
        )
    }

    private class EchoProviderAdapter(
        private val emitTerminal: Boolean = true,
    ) : ProviderAdapter {
        override val kind = ProviderKind.OPENAI_COMPATIBLE
        val lastRequest = AtomicReference<NormalizedProviderRequest?>()

        override suspend fun probe(
            profile: ProviderProfile,
            authorization: ProviderAuthorizationHandle,
        ) = ProviderProbeResult.Available(
            ProviderCapabilities(
                ProviderCapabilitySupport.SUPPORTED,
                ProviderCapabilitySupport.UNKNOWN,
                0,
                "test",
            ),
        )

        override suspend fun discoverModels(
            profile: ProviderProfile,
            authorization: ProviderAuthorizationHandle,
        ): ProviderModelDiscoveryResult = ProviderModelDiscoveryResult.Unsupported("not needed")

        override fun startInvocation(
            request: NormalizedProviderRequest,
            authorization: ProviderAuthorizationHandle,
        ): Flow<ProviderStreamEvent> = flow {
            invocationCount.incrementAndGet()
            lastRequest.set(request)
            if (emitTerminal) {
                emit(ProviderStreamEvent.TextDelta(request.invocationId, 0, "agent reply"))
                emit(ProviderStreamEvent.Completed(request.invocationId, null))
            }
        }

        override fun cancel(invocationId: dev.cockpit.provider.api.ProviderInvocationId) = Unit

        val invocationCount = AtomicInteger()
    }

    private class StallingProviderAdapter : ProviderAdapter {
        override val kind = ProviderKind.OPENAI_COMPATIBLE
        val started = CompletableDeferred<Unit>()
        val invocationCount = AtomicInteger()
        val cancelCalled = java.util.concurrent.atomic.AtomicBoolean(false)

        override suspend fun probe(
            profile: ProviderProfile,
            authorization: ProviderAuthorizationHandle,
        ) = ProviderProbeResult.Available(
            ProviderCapabilities(
                ProviderCapabilitySupport.SUPPORTED,
                ProviderCapabilitySupport.UNKNOWN,
                0,
                "test",
            ),
        )

        override suspend fun discoverModels(
            profile: ProviderProfile,
            authorization: ProviderAuthorizationHandle,
        ): ProviderModelDiscoveryResult = ProviderModelDiscoveryResult.Unsupported("not needed")

        override fun startInvocation(
            request: NormalizedProviderRequest,
            authorization: ProviderAuthorizationHandle,
        ): Flow<ProviderStreamEvent> = flow {
            invocationCount.incrementAndGet()
            emit(ProviderStreamEvent.TextDelta(request.invocationId, 0, "partial"))
            started.complete(Unit)
            awaitCancellation()
        }

        override fun cancel(invocationId: dev.cockpit.provider.api.ProviderInvocationId) {
            cancelCalled.set(true)
        }
    }
}
