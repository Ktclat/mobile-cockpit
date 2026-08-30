package dev.cockpit.persistence.room

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert

@Dao interface AgentDao { @Upsert suspend fun upsert(entity: AgentEntity); @Query("SELECT * FROM agents WHERE id = :id") suspend fun find(id: String): AgentEntity? }
@Dao interface ConversationDao { @Upsert suspend fun upsert(entity: ConversationEntity); @Query("SELECT * FROM conversations WHERE id = :id") suspend fun find(id: String): ConversationEntity? }
@Dao interface MessageDao { @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entity: MessageEntity); @Query("DELETE FROM messages WHERE conversationId = :conversationId") suspend fun deleteForConversation(conversationId: String); @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY ordinal") suspend fun forConversation(conversationId: String): List<MessageEntity> }
@Dao interface DraftDao { @Upsert suspend fun upsert(entity: DraftEntity); @Query("DELETE FROM drafts WHERE conversationId = :conversationId") suspend fun deleteForConversation(conversationId: String); @Query("SELECT * FROM drafts WHERE conversationId = :conversationId ORDER BY expectedConversationRevision") suspend fun forConversation(conversationId: String): List<DraftEntity> }
@Dao interface PersonaDao { @Upsert suspend fun upsert(entity: PersonaEntity); @Query("SELECT * FROM personas WHERE id = :id") suspend fun find(id: String): PersonaEntity? }
