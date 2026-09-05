package dev.cockpit.application.api

import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.AgentMode
import dev.cockpit.domain.agent.LorebookEntry
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.HomeProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface AgentConversationQueryPort {
    fun home(): Flow<HomeProjection>
    fun agent(id: AgentId): Flow<AgentDetailProjection>
    fun conversation(id: ConversationId): Flow<ConversationProjection>
}

data class AgentProfileInput(
    val identity: String,
    val mode: AgentMode = AgentMode.ASSISTANT,
    val summary: String = "",
    val avatarRef: String? = null,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val tags: List<String> = emptyList(),
    val nickname: String = "",
    val creator: String = "",
    val characterVersion: String = "",
    val creatorNotes: String = "",
    val lorebookEntries: List<LorebookEntry> = emptyList(),
    val lorebookScanDepth: Int = 8,
    val lorebookTokenBudget: Int = 1_024,
    val providerProfileId: String? = null,
    val providerModelId: String? = null,
    val importSource: AgentImportSource? = null,
    val capabilitySummary: String = "Local conversation only",
)

data class AgentDraftView(
    val profile: AgentProfileInput,
    val updatedAtEpochMillis: Long,
)

enum class AgentTestRole { USER, AGENT }

data class AgentTestMessage(
    val role: AgentTestRole,
    val text: String,
)

data class AgentTestResult(
    val success: Boolean,
    val response: String = "",
    val message: String = "",
)

data class AgentExportDocument(
    val fileName: String,
    val json: String,
    val warning: String,
)

interface AgentApplicationPort {
    suspend fun createAgent(input: AgentProfileInput): AgentId?

    suspend fun createAgent(identity: String): AgentId? =
        createAgent(AgentProfileInput(identity = identity))

    suspend fun updateAgent(id: AgentId, input: AgentProfileInput): Boolean = false

    fun observeCreationDraft(): Flow<AgentDraftView?> = flowOf(null)

    suspend fun saveCreationDraft(input: AgentProfileInput): Boolean = false

    suspend fun discardCreationDraft(): Boolean = false

    suspend fun findAgentByImportDigest(payloadDigest: String): AgentId? = null

    suspend fun exportAgent(id: AgentId): AgentExportDocument? = null

    suspend fun testAgent(
        input: AgentProfileInput,
        messages: List<AgentTestMessage>,
    ): AgentTestResult = AgentTestResult(false, message = "Agent preview is unavailable.")

    suspend fun cancelAgentTest(): Boolean = false
}

interface ConversationApplicationPort {
    suspend fun createConversation(agentId: AgentId): ConversationId?
    suspend fun archiveConversation(id: ConversationId): Boolean
    suspend fun restoreConversation(id: ConversationId): Boolean
    suspend fun saveDraft(destination: ConversationMessageDestination, text: String): Boolean
    suspend fun sendMessage(destination: ConversationMessageDestination, text: String): Boolean
    suspend fun migrateProviderRoute(id: ConversationId): Boolean = false
    suspend fun cancelReply(id: ConversationId): Boolean = false
    suspend fun retryReply(id: ConversationId): Boolean = false
}
