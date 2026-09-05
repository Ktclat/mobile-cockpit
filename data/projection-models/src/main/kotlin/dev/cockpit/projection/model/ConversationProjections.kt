package dev.cockpit.projection.model

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.AgentMode
import dev.cockpit.domain.conversation.ConversationMessageDestination

data class HomeProjection(val agents: List<AgentSummaryProjection>)
data class AgentSummaryProjection(
    val id: AgentId,
    val name: String,
    val revision: Long,
    val providerName: String? = null,
    val summary: String = "",
    val avatarRef: String? = null,
    val mode: AgentMode = AgentMode.ASSISTANT,
)
enum class ArchiveProjectionState { ACTIVE, ARCHIVED }
enum class MessageRoleProjection { USER, AGENT, SYSTEM }
enum class MessageSourceProjection { USER, DEBUG, RUNTIME }
enum class MessageStatusProjection { ACCEPTED, DELIVERED, FAILED }
data class AgentDetailProjection(
    val id: AgentId,
    val name: String,
    val revision: Long,
    val archiveState: ArchiveProjectionState,
    val conversations: List<ConversationSummaryProjection>,
    val provider: BoundProviderProjection? = null,
    val definition: AgentDefinition? = null,
    val importSource: AgentImportSource? = null,
)
data class ConversationSummaryProjection(val id: ConversationId, val revision: ConversationRevision, val archiveState: ArchiveProjectionState)
data class ConversationProjection(
    val id: ConversationId,
    val agentId: AgentId,
    val revision: ConversationRevision,
    val messageDestination: ConversationMessageDestination,
    val archiveState: ArchiveProjectionState,
    val drafts: List<DraftProjection>,
    val timeline: List<TimelineItemProjection>,
    val provider: BoundProviderProjection? = null,
    val streamingReply: StreamingReplyProjection? = null,
    val providerError: ProviderReplyErrorProjection? = null,
)
data class BoundProviderProjection(
    val id: String,
    val displayName: String,
    val model: String,
    val available: Boolean,
    val usesDefault: Boolean = false,
    val modelId: String? = null,
)
data class StreamingReplyProjection(
    val invocationId: String,
    val providerName: String,
    val text: String,
    val inProgress: Boolean,
)
data class ProviderReplyErrorProjection(
    val code: String,
    val message: String,
    val retryable: Boolean,
)
data class DraftProjection(val destination: ConversationMessageDestination, val text: String)
data class MessageProjection(val id: String, val text: String, val ordinal: Long, val role: MessageRoleProjection, val source: MessageSourceProjection, val status: MessageStatusProjection)
sealed interface TimelineItemProjection {
    data class MessageItem(val message: MessageProjection) : TimelineItemProjection
}
