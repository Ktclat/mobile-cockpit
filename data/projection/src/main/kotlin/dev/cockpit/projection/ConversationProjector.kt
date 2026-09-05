package dev.cockpit.projection

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.conversation.ConversationMessageDestination
import dev.cockpit.domain.agent.editableDefinition
import dev.cockpit.persistence.api.AgentConversationReadRepository
import dev.cockpit.persistence.api.ArchiveState
import dev.cockpit.persistence.api.ConversationSnapshot
import dev.cockpit.projection.model.AgentDetailProjection
import dev.cockpit.projection.model.AgentSummaryProjection
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.ConversationProjection
import dev.cockpit.projection.model.ConversationSummaryProjection
import dev.cockpit.projection.model.DraftProjection
import dev.cockpit.projection.model.HomeProjection
import dev.cockpit.projection.model.MessageProjection
import dev.cockpit.projection.model.MessageRoleProjection
import dev.cockpit.projection.model.MessageSourceProjection
import dev.cockpit.projection.model.MessageStatusProjection
import dev.cockpit.projection.model.TimelineItemProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationProjector(private val facts: AgentConversationReadRepository) {
    fun home(): Flow<HomeProjection> = facts.observeAgentFacts().map { agents ->
        HomeProjection(agents.filter { it.agent.archiveState == ArchiveState.ACTIVE }.map { fact ->
            val definition = fact.agent.agent.persona.editableDefinition()
            AgentSummaryProjection(
                id = fact.agent.agent.id,
                name = definition.name,
                revision = fact.agent.revision,
                summary = definition.summary,
                avatarRef = definition.avatarRef,
                mode = definition.mode,
            )
        })
    }

    fun agent(id: AgentId): Flow<AgentDetailProjection> = facts.observeAgentDetail(id).map { detail ->
        val authoritative = requireNotNull(detail) { "Agent not found: ${id.value}" }.agent
        val snapshots = detail.conversations
        AgentDetailProjection(
            id = authoritative.agent.agent.id,
            name = authoritative.agent.agent.persona.identity,
            revision = authoritative.agent.revision,
            archiveState = authoritative.agent.archiveState.toProjection(),
            conversations = snapshots.map(::conversationSummary),
            definition = authoritative.agent.agent.persona.editableDefinition(),
            importSource = authoritative.agent.importSource,
        )
    }

    fun conversation(id: ConversationId): Flow<ConversationProjection> = facts.observeConversation(id).map { snapshot ->
        requireNotNull(snapshot) { "Conversation not found: ${id.value}" }.toProjection()
    }

    private fun conversationSummary(snapshot: ConversationSnapshot) = ConversationSummaryProjection(snapshot.conversation.conversation.id, snapshot.conversation.conversation.revision, snapshot.conversation.archiveState.toProjection())
    private fun ConversationSnapshot.toProjection() = ConversationProjection(
        id = conversation.conversation.id,
        agentId = conversation.conversation.agentId,
        revision = conversation.conversation.revision,
        messageDestination = ConversationMessageDestination(conversation.conversation.id, conversation.conversation.revision),
        archiveState = conversation.archiveState.toProjection(),
        drafts = drafts.map { DraftProjection(it.destination, it.text) },
        timeline = messages.sortedBy { it.ordinal }.map { fact -> TimelineItemProjection.MessageItem(MessageProjection(fact.id, fact.message.text, fact.ordinal, MessageRoleProjection.valueOf(fact.role.name), MessageSourceProjection.valueOf(fact.source.name), MessageStatusProjection.valueOf(fact.status.name))) },
    )

    private fun ArchiveState.toProjection() = ArchiveProjectionState.valueOf(name)
}
