package dev.cockpit.persistence.room

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(@PrimaryKey val id: String, val identity: String, val presentation: String, val voice: String, val behavioralTendency: String, val promptStyle: String)

@Entity(tableName = "agents", foreignKeys = [ForeignKey(entity = PersonaEntity::class, parentColumns = ["id"], childColumns = ["personaId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["personaId"], unique = true)])
data class AgentEntity(@PrimaryKey val id: String, val personaId: String, val capabilitySummary: String, val revision: Long, val archiveState: String)

@Entity(tableName = "conversations", foreignKeys = [ForeignKey(entity = AgentEntity::class, parentColumns = ["id"], childColumns = ["agentId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["agentId"])])
data class ConversationEntity(@PrimaryKey val id: String, val agentId: String, val revision: Long, val archiveState: String)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["conversationId", "ordinal"], unique = true)])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val text: String, val ordinal: Long, val role: String, val source: String, val status: String)

@Entity(tableName = "drafts", primaryKeys = ["conversationId", "expectedConversationRevision"], foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["conversationId"])])
data class DraftEntity(val conversationId: String, val expectedConversationRevision: Long, val text: String)
