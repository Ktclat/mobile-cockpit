package dev.cockpit.persistence.room

import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.cockpit.persistence.room.migration.Migration1To2
import dev.cockpit.persistence.room.migration.Migration2To3
import dev.cockpit.persistence.room.migration.Migration3To4
import dev.cockpit.persistence.room.migration.Migration4To5
import dev.cockpit.persistence.room.migration.Migration5To6
import dev.cockpit.persistence.room.migration.SchemaV6

@Database(
    entities = [
        PersonaEntity::class,
        AgentEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        DraftEntity::class,
        ProviderProfileEntity::class,
        ProviderModelOptionEntity::class,
        ProviderSettingsEntity::class,
        AgentProviderBindingEntity::class,
        ConversationProviderRouteEntity::class,
        AgentDefinitionRevisionEntity::class,
        AgentImportSourceEntity::class,
        AgentDraftEntity::class,
    ],
    version = SchemaV6.VERSION,
    exportSchema = true,
)
abstract class CockpitDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun agentDao(): AgentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun draftDao(): DraftDao
    abstract fun providerProfileDao(): ProviderProfileDao
    abstract fun providerModelOptionDao(): ProviderModelOptionDao
    abstract fun providerSettingsDao(): ProviderSettingsDao
    abstract fun agentProviderBindingDao(): AgentProviderBindingDao
    abstract fun conversationProviderRouteDao(): ConversationProviderRouteDao
    abstract fun agentDefinitionRevisionDao(): AgentDefinitionRevisionDao
    abstract fun agentImportSourceDao(): AgentImportSourceDao
    abstract fun agentDraftDao(): AgentDraftDao

    companion object {
        fun open(path: String): CockpitDatabase = Room.databaseBuilder<CockpitDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(
                Migration1To2,
                Migration2To3,
                Migration3To4,
                Migration4To5,
                Migration5To6,
            )
            .build()
    }
}
