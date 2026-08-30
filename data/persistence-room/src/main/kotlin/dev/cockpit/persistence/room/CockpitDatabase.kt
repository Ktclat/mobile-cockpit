package dev.cockpit.persistence.room

import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.cockpit.persistence.room.migration.SchemaV1

@Database(entities = [PersonaEntity::class, AgentEntity::class, ConversationEntity::class, MessageEntity::class, DraftEntity::class], version = SchemaV1.VERSION, exportSchema = true)
abstract class CockpitDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun agentDao(): AgentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun draftDao(): DraftDao

    companion object {
        fun open(path: String): CockpitDatabase = Room.databaseBuilder<CockpitDatabase>(name = path).setDriver(BundledSQLiteDriver()).build()
    }
}
