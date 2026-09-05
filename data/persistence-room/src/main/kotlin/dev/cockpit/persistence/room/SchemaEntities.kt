package dev.cockpit.persistence.room

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val identity: String,
    val presentation: String,
    val voice: String,
    val behavioralTendency: String,
    val promptStyle: String,
    @ColumnInfo(defaultValue = "'{}'") val definitionJson: String = "{}",
)

@Entity(tableName = "agents", foreignKeys = [ForeignKey(entity = PersonaEntity::class, parentColumns = ["id"], childColumns = ["personaId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["personaId"], unique = true)])
data class AgentEntity(@PrimaryKey val id: String, val personaId: String, val capabilitySummary: String, val revision: Long, val archiveState: String)

@Entity(tableName = "conversations", foreignKeys = [ForeignKey(entity = AgentEntity::class, parentColumns = ["id"], childColumns = ["agentId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["agentId"])])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val revision: Long,
    val archiveState: String,
    @ColumnInfo(defaultValue = "0") val agentRevision: Long = 0,
    @ColumnInfo(defaultValue = "''") val personaSnapshotJson: String = "",
)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["conversationId", "ordinal"], unique = true)])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val text: String, val ordinal: Long, val role: String, val source: String, val status: String)

@Entity(tableName = "drafts", primaryKeys = ["conversationId", "expectedConversationRevision"], foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["conversationId"])])
data class DraftEntity(val conversationId: String, val expectedConversationRevision: Long, val text: String)

@Entity(tableName = "provider_profiles")
data class ProviderProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    @ColumnInfo(defaultValue = "'CUSTOM'") val vendor: String,
    val kind: String,
    val baseUrl: String,
    val model: String,
    val credentialReference: String,
    val credentialRotation: Long,
    val maxOutputTokens: Int,
    val revision: Long,
    val streamingCapability: String,
    val toolCapability: String,
    val lastProbeErrorCode: String?,
    val lastProbeMessage: String?,
    val lastProbedAtEpochMillis: Long?,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "'BEARER'") val authenticationType: String = "BEARER",
    @ColumnInfo(defaultValue = "'2023-06-01'") val anthropicVersion: String = "2023-06-01",
    @ColumnInfo(defaultValue = "''") val organizationId: String = "",
    @ColumnInfo(defaultValue = "''") val projectId: String = "",
    @ColumnInfo(defaultValue = "''") val workspaceId: String = "",
    val preferredModelId: String? = null,
    @ColumnInfo(defaultValue = "''") val credentialHint: String = "",
    @ColumnInfo(defaultValue = "0") val createdAtEpochMillis: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAtEpochMillis: Long = 0L,
    val lastTestModelId: String? = null,
    val lastTestElapsedMillis: Long? = null,
)

@Entity(
    tableName = "provider_model_options",
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["connectionId"]),
        Index(value = ["connectionId", "remoteModelId"], unique = true),
    ],
)
data class ProviderModelOptionEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val remoteModelId: String,
    val displayName: String,
    val enabled: Boolean,
    val source: String,
    val discoveredAtEpochMillis: Long?,
    val discoveryState: String,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val textCapability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val visionCapability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val toolCapability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val reasoningCapability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val capabilitySource: String = "UNKNOWN",
)

@Entity(tableName = "provider_settings")
data class ProviderSettingsEntity(
    @PrimaryKey val singletonId: Int = 1,
    val defaultConnectionId: String?,
    val defaultModelId: String?,
)

@Entity(
    tableName = "agent_provider_bindings",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerProfileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["providerProfileId"])],
)
data class AgentProviderBindingEntity(
    @PrimaryKey val agentId: String,
    val providerProfileId: String,
    val modelId: String? = null,
)

@Entity(
    tableName = "conversation_provider_routes",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId"], unique = true)],
)
data class ConversationProviderRouteEntity(
    @PrimaryKey val conversationId: String,
    val providerProfileId: String,
    val modelId: String,
    val requestRevision: Long,
)

@Entity(
    tableName = "agent_definition_revisions",
    primaryKeys = ["agentId", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["agentId"])],
)
data class AgentDefinitionRevisionEntity(
    val agentId: String,
    val revision: Long,
    val definitionJson: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "agent_import_sources",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["payloadDigest"])],
)
data class AgentImportSourceEntity(
    @PrimaryKey val agentId: String,
    val sourceFileName: String?,
    val payloadDigest: String,
    val detectedContainer: String,
    val detectedSpec: String,
    val originalJson: String,
    val warningsJson: String,
    val preservedFieldCount: Int,
    val downgraded: Boolean,
)

@Entity(tableName = "agent_drafts")
data class AgentDraftEntity(
    @PrimaryKey val id: String,
    val definitionJson: String,
    val providerProfileId: String?,
    val providerModelId: String? = null,
    val capabilitySummary: String,
    val sourceFileName: String?,
    val payloadDigest: String?,
    val detectedContainer: String?,
    val detectedSpec: String?,
    val originalJson: String?,
    val warningsJson: String?,
    val preservedFieldCount: Int,
    val downgraded: Boolean,
    val updatedAtEpochMillis: Long,
)
