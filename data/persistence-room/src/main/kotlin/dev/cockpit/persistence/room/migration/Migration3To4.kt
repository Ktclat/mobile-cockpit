package dev.cockpit.persistence.room.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration3To4 : Migration(SchemaV3.VERSION, SchemaV4.VERSION) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `personas` ADD COLUMN `definitionJson` TEXT NOT NULL DEFAULT '{}'")
        connection.execSQL("ALTER TABLE `conversations` ADD COLUMN `agentRevision` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `conversations` ADD COLUMN `personaSnapshotJson` TEXT NOT NULL DEFAULT ''")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_definition_revisions` (
                `agentId` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `definitionJson` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`agentId`, `revision`),
                FOREIGN KEY(`agentId`) REFERENCES `agents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_definition_revisions_agentId` ON `agent_definition_revisions` (`agentId`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_import_sources` (
                `agentId` TEXT NOT NULL,
                `sourceFileName` TEXT,
                `payloadDigest` TEXT NOT NULL,
                `detectedContainer` TEXT NOT NULL,
                `detectedSpec` TEXT NOT NULL,
                `originalJson` TEXT NOT NULL,
                `warningsJson` TEXT NOT NULL,
                `preservedFieldCount` INTEGER NOT NULL,
                `downgraded` INTEGER NOT NULL,
                PRIMARY KEY(`agentId`),
                FOREIGN KEY(`agentId`) REFERENCES `agents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_import_sources_payloadDigest` ON `agent_import_sources` (`payloadDigest`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_drafts` (
                `id` TEXT NOT NULL,
                `definitionJson` TEXT NOT NULL,
                `providerProfileId` TEXT,
                `capabilitySummary` TEXT NOT NULL,
                `sourceFileName` TEXT,
                `payloadDigest` TEXT,
                `detectedContainer` TEXT,
                `detectedSpec` TEXT,
                `originalJson` TEXT,
                `warningsJson` TEXT,
                `preservedFieldCount` INTEGER NOT NULL,
                `downgraded` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}
