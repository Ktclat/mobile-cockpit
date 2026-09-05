package dev.cockpit.persistence.room

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert

@Dao interface AgentDao { @Upsert suspend fun upsert(entity: AgentEntity); @Query("SELECT * FROM agents WHERE id = :id") suspend fun find(id: String): AgentEntity?; @Query("SELECT * FROM agents ORDER BY id") suspend fun all(): List<AgentEntity> }
@Dao interface ConversationDao { @Upsert suspend fun upsert(entity: ConversationEntity); @Query("SELECT * FROM conversations WHERE id = :id") suspend fun find(id: String): ConversationEntity?; @Query("SELECT * FROM conversations WHERE agentId = :agentId ORDER BY id") suspend fun forAgent(agentId: String): List<ConversationEntity> }
@Dao interface MessageDao { @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: MessageEntity); @Query("DELETE FROM messages WHERE conversationId = :conversationId") suspend fun deleteForConversation(conversationId: String); @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY ordinal") suspend fun forConversation(conversationId: String): List<MessageEntity> }
@Dao interface DraftDao { @Upsert suspend fun upsert(entity: DraftEntity); @Query("DELETE FROM drafts WHERE conversationId = :conversationId") suspend fun deleteForConversation(conversationId: String); @Query("SELECT * FROM drafts WHERE conversationId = :conversationId ORDER BY expectedConversationRevision") suspend fun forConversation(conversationId: String): List<DraftEntity> }
@Dao interface PersonaDao { @Upsert suspend fun upsert(entity: PersonaEntity); @Query("SELECT * FROM personas WHERE id = :id") suspend fun find(id: String): PersonaEntity? }

@Dao
interface ProviderProfileDao {
    @Upsert suspend fun upsert(entity: ProviderProfileEntity)
    @Query("SELECT * FROM provider_profiles WHERE id = :id") suspend fun find(id: String): ProviderProfileEntity?
    @Query("SELECT * FROM provider_profiles ORDER BY displayName, id") suspend fun all(): List<ProviderProfileEntity>
    @Query("DELETE FROM provider_profiles WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ProviderModelOptionDao {
    @Upsert suspend fun upsert(entity: ProviderModelOptionEntity)
    @Upsert suspend fun upsertAll(entities: List<ProviderModelOptionEntity>)
    @Query("SELECT * FROM provider_model_options WHERE id = :id")
    suspend fun find(id: String): ProviderModelOptionEntity?
    @Query("SELECT * FROM provider_model_options WHERE connectionId = :connectionId ORDER BY enabled DESC, displayName, remoteModelId")
    suspend fun forConnection(connectionId: String): List<ProviderModelOptionEntity>
    @Query("SELECT * FROM provider_model_options ORDER BY connectionId, enabled DESC, displayName, remoteModelId")
    suspend fun all(): List<ProviderModelOptionEntity>
}

@Dao
interface ProviderSettingsDao {
    @Upsert suspend fun upsert(entity: ProviderSettingsEntity)
    @Query("SELECT * FROM provider_settings WHERE singletonId = 1")
    suspend fun get(): ProviderSettingsEntity?
    @Query("DELETE FROM provider_settings") suspend fun clear()
}

@Dao
interface AgentProviderBindingDao {
    @Upsert suspend fun upsert(entity: AgentProviderBindingEntity)
    @Query("SELECT * FROM agent_provider_bindings WHERE agentId = :agentId") suspend fun forAgent(agentId: String): AgentProviderBindingEntity?
    @Query("SELECT * FROM agent_provider_bindings ORDER BY agentId") suspend fun all(): List<AgentProviderBindingEntity>
    @Query("DELETE FROM agent_provider_bindings WHERE agentId = :agentId") suspend fun deleteForAgent(agentId: String)
}

@Dao
interface ConversationProviderRouteDao {
    @Upsert suspend fun upsert(entity: ConversationProviderRouteEntity)
    @Query("SELECT * FROM conversation_provider_routes WHERE conversationId = :conversationId")
    suspend fun forConversation(conversationId: String): ConversationProviderRouteEntity?
    @Query("SELECT * FROM conversation_provider_routes ORDER BY conversationId")
    suspend fun all(): List<ConversationProviderRouteEntity>
}

@Dao
interface GenerationAttemptDao {
    @Upsert suspend fun upsert(entity: GenerationAttemptEntity)
    @Query("SELECT * FROM generation_attempts WHERE conversationId = :conversationId")
    suspend fun forConversation(conversationId: String): GenerationAttemptEntity?
    @Query(
        """
        UPDATE generation_attempts
        SET status = :status, errorCode = :errorCode, updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE conversationId = :conversationId AND attemptId = :attemptId AND status = 'STARTED'
        """,
    )
    suspend fun finishStarted(
        conversationId: String,
        attemptId: String,
        status: String,
        errorCode: String?,
        updatedAtEpochMillis: Long,
    ): Int
    @Query(
        """
        UPDATE generation_attempts
        SET status = 'INTERRUPTED', errorCode = 'GENERATION_INTERRUPTED', updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE status = 'STARTED'
        """,
    )
    suspend fun interruptAllStarted(updatedAtEpochMillis: Long): Int
}

@Dao
interface AgentDefinitionRevisionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AgentDefinitionRevisionEntity)
}

@Dao
interface AgentImportSourceDao {
    @Upsert suspend fun upsert(entity: AgentImportSourceEntity)
    @Query("SELECT * FROM agent_import_sources WHERE agentId = :agentId")
    suspend fun forAgent(agentId: String): AgentImportSourceEntity?
    @Query("SELECT agentId FROM agent_import_sources WHERE payloadDigest = :payloadDigest ORDER BY agentId LIMIT 1")
    suspend fun findAgentId(payloadDigest: String): String?
}

@Dao
interface AgentDraftDao {
    @Upsert suspend fun upsert(entity: AgentDraftEntity)
    @Query("SELECT * FROM agent_drafts WHERE id = :id") suspend fun find(id: String): AgentDraftEntity?
    @Query("DELETE FROM agent_drafts WHERE id = :id") suspend fun delete(id: String)
}
