package dev.cockpit.application.api

import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.HomeProjection
import kotlinx.coroutines.flow.Flow

interface AgentConversationQueryPort {
    fun home(): Flow<HomeProjection>
    fun agent(id: AgentId): Flow<AgentDetailProjection>
    fun conversation(id: ConversationId): Flow<ConversationProjection>
}

interface AgentApplicationPort {
    suspend fun createAgent(identity: String): AgentId?
}

interface ConversationApplicationPort {
    suspend fun createConversation(agentId: AgentId): ConversationId?
    suspend fun archiveConversation(id: ConversationId): Boolean
    suspend fun restoreConversation(id: ConversationId): Boolean
    suspend fun saveDraft(destination: ConversationMessageDestination, text: String): Boolean
    suspend fun sendMessage(destination: ConversationMessageDestination, text: String): Boolean
    suspend fun configureModelProvider()
}
