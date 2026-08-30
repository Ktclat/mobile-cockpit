package dev.cockpit.application.api

import dev.cockpit.domain.ConversationId
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.HomeProjection
import kotlinx.coroutines.flow.Flow

interface AgentConversationQueryPort {
    fun home(): Flow<HomeProjection>
    fun conversation(id: ConversationId): Flow<ConversationProjection>
}
