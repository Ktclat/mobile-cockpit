package dev.cockpit.persistence.room

import androidx.room3.withWriteTransaction
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
import dev.cockpit.persistence.api.*

class RoomConversationRepository(private val database: CockpitDatabase) : ConversationRepository {
    override suspend fun save(snapshot: ConversationSnapshot) = database.withWriteTransaction {
        database.personaDao().upsert(snapshot.persona.toEntity())
        database.agentDao().upsert(snapshot.agent.toEntity())
        database.conversationDao().upsert(snapshot.conversation.toEntity())
        database.messageDao().deleteForConversation(snapshot.conversation.conversation.id.value)
        database.draftDao().deleteForConversation(snapshot.conversation.conversation.id.value)
        snapshot.messages.forEach { database.messageDao().insert(it.toEntity()) }
        snapshot.drafts.forEach { database.draftDao().upsert(it.toEntity()) }
    }
    override suspend fun load(conversationId: ConversationId): ConversationSnapshot? {
        val conversation = database.conversationDao().find(conversationId.value) ?: return null
        val agent = database.agentDao().find(conversation.agentId) ?: return null
        val persona = database.personaDao().find(agent.personaId) ?: return null
        return ConversationSnapshot(persona.toState(), agent.toState(persona), conversation.toState(), database.messageDao().forConversation(conversationId.value).map { it.toState() }, database.draftDao().forConversation(conversationId.value).map { Draft(ConversationMessageDestination(ConversationId(it.conversationId), ConversationRevision(it.expectedConversationRevision)), it.text) })
    }
}
private fun PersonaPersistenceState.toEntity() = PersonaEntity(id, persona.identity, persona.presentation, persona.voice, persona.behavioralTendency, persona.promptStyle)
private fun AgentPersistenceState.toEntity() = AgentEntity(agent.id.value, personaId, agent.capabilities.summary, revision, archiveState.name)
private fun ConversationPersistenceState.toEntity() = ConversationEntity(conversation.id.value, conversation.agentId.value, conversation.revision.value, archiveState.name)
private fun MessagePersistenceState.toEntity() = MessageEntity(id, message.conversationId.value, message.text, ordinal, role.name, source.name, status.name)
private fun Draft.toEntity() = DraftEntity(destination.conversationId.value, destination.expectedConversationRevision.value, text)
private fun PersonaEntity.toState() = PersonaPersistenceState(id, Persona(identity, presentation, voice, behavioralTendency, promptStyle))
private fun AgentEntity.toState(persona: PersonaEntity) = AgentPersistenceState(Agent(AgentId(id), persona.toState().persona, AgentCapabilities(capabilitySummary)), personaId, revision, ArchiveState.valueOf(archiveState))
private fun ConversationEntity.toState() = ConversationPersistenceState(Conversation(ConversationId(id), AgentId(agentId), ConversationRevision(revision)), ArchiveState.valueOf(archiveState))
private fun MessageEntity.toState() = MessagePersistenceState(id, Message(ConversationId(conversationId), text), ordinal, MessageRole.valueOf(role), MessageSource.valueOf(source), MessageStatus.valueOf(status))
