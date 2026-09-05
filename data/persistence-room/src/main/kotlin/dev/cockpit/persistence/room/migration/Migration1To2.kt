package dev.cockpit.persistence.room.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration1To2 : Migration(SchemaV1.VERSION, SchemaV2.VERSION) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `provider_profiles` (
                `id` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `baseUrl` TEXT NOT NULL,
                `model` TEXT NOT NULL,
                `credentialReference` TEXT NOT NULL,
                `credentialRotation` INTEGER NOT NULL,
                `maxOutputTokens` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `streamingCapability` TEXT NOT NULL,
                `toolCapability` TEXT NOT NULL,
                `lastProbeErrorCode` TEXT,
                `lastProbeMessage` TEXT,
                `lastProbedAtEpochMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_provider_bindings` (
                `agentId` TEXT NOT NULL,
                `providerProfileId` TEXT NOT NULL,
                PRIMARY KEY(`agentId`),
                FOREIGN KEY(`agentId`) REFERENCES `agents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`providerProfileId`) REFERENCES `provider_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_provider_bindings_providerProfileId` ON `agent_provider_bindings` (`providerProfileId`)",
        )
    }
}
