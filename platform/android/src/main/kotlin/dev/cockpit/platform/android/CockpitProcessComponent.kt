package dev.cockpit.platform.android

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.cockpit.application.AppendConversationAgentMessage
import dev.cockpit.application.ArchiveConversation
import dev.cockpit.application.CreateAgent
import dev.cockpit.application.CreateAgentCommand
import dev.cockpit.application.CreateConversation
import dev.cockpit.application.ConversationMutationCoordinator
import dev.cockpit.application.RestoreConversation
import dev.cockpit.application.SaveConversationDraft
import dev.cockpit.application.SendConversationMessage
import dev.cockpit.application.SendConversationMessageResult
import dev.cockpit.application.api.AgentApplicationPort
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.room.CockpitDatabase
import dev.cockpit.persistence.room.RoomConversationRepository
import dev.cockpit.projection.ConversationProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID

class CockpitProcessComponent internal constructor(
    private val responder: ConversationTextResponder?,
    databaseFactory: () -> CockpitDatabase,
    mutationScope: CoroutineScope,
) {
    constructor(responder: ConversationTextResponder? = null) : this(
        responder = responder,
        databaseFactory = { CockpitDatabase.open("cockpit-local.db") },
        mutationScope = newConversationMutationScope(),
    )

    constructor(context: Context, responder: ConversationTextResponder? = null) : this(
        responder = responder,
        databaseFactory = {
            Room.databaseBuilder(
                context.applicationContext,
                CockpitDatabase::class.java,
                "cockpit-local.db",
            ).setDriver(BundledSQLiteDriver()).build()
        },
        mutationScope = newConversationMutationScope(),
    )

    val shellAppName: String = "Cockpit"

    private val database by lazy(databaseFactory)
    private val repository by lazy { RoomConversationRepository(database) }
    private val ids = object : IdGenerator {
        override fun nextId(): String = UUID.randomUUID().toString()
    }
    private val projector by lazy { ConversationProjector(repository) }
    private val createAgentUseCase by lazy { CreateAgent(repository, ids) }
    private val createConversationUseCase by lazy { CreateConversation(repository, repository, ids) }
    private val archiveConversationUseCase by lazy { ArchiveConversation(repository) }
    private val restoreConversationUseCase by lazy { RestoreConversation(repository) }
    private val saveDraftUseCase by lazy { SaveConversationDraft(repository) }
    private val sendMessageUseCase by lazy { SendConversationMessage(repository, ids) }
    private val appendAgentMessageUseCase by lazy { AppendConversationAgentMessage(repository, ids) }
    private val conversationMutations = ConversationMutationCoordinator(mutationScope)

    private val agents: AgentApplicationPort = object : AgentApplicationPort {
        override suspend fun createAgent(identity: String): AgentId? = withContext(Dispatchers.IO) {
            val trimmed = identity.trim()
            if (trimmed.isEmpty()) null else createAgentUseCase(
                CreateAgentCommand(
                    persona = Persona(
                        identity = trimmed,
                        presentation = "Local Agent",
                        voice = "Clear",
                        behavioralTendency = "Helpful",
                        promptStyle = "Conversation",
                    ),
                    capabilities = AgentCapabilities("Local conversation only"),
                ),
            ).id
        }
    }

    private val conversations: ConversationApplicationPort = object : ConversationApplicationPort {
        override suspend fun createConversation(agentId: AgentId): ConversationId? =
            withContext(Dispatchers.IO) { createConversationUseCase(agentId)?.id }

        override suspend fun archiveConversation(id: ConversationId): Boolean =
            conversationMutations.submit(id) { archiveConversationUseCase(id) }

        override suspend fun restoreConversation(id: ConversationId): Boolean =
            conversationMutations.submit(id) { restoreConversationUseCase(id) }

        override suspend fun saveDraft(destination: ConversationMessageDestination, text: String): Boolean =
            conversationMutations.submit(destination.conversationId) {
                saveDraftUseCase(destination, text)
            }

        override suspend fun sendMessage(destination: ConversationMessageDestination, text: String): Boolean =
            conversationMutations.submit(destination.conversationId) {
                if (sendMessageUseCase(destination, text) != SendConversationMessageResult.Sent) return@submit false
                responder?.replyTo(text)?.let { appendAgentMessageUseCase(destination, it) }
                true
            }

        override suspend fun configureModelProvider() = Unit
    }

    private val queries: AgentConversationQueryPort = object : AgentConversationQueryPort {
        override fun home() = projector.home().flowOn(Dispatchers.IO)
        override fun agent(id: AgentId) = projector.agent(id).flowOn(Dispatchers.IO)
        override fun conversation(id: ConversationId) = projector.conversation(id).flowOn(Dispatchers.IO)
    }

    // Keep inner API modules off :app's compile classpath while entry glue forwards narrow handles.
    val agentApplicationPortHandle: Any get() = agents
    val conversationApplicationPortHandle: Any get() = conversations
    val agentConversationQueryPortHandle: Any get() = queries
}

private fun newConversationMutationScope() =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)
