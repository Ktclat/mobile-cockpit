package dev.cockpit.persistence.room.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration4To5 : Migration(SchemaV4.VERSION, SchemaV5.VERSION) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `note` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `authenticationType` TEXT NOT NULL DEFAULT 'BEARER'")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `anthropicVersion` TEXT NOT NULL DEFAULT '2023-06-01'")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `organizationId` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `projectId` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `workspaceId` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `preferredModelId` TEXT")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `credentialHint` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `createdAtEpochMillis` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `updatedAtEpochMillis` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `lastTestModelId` TEXT")
        connection.execSQL("ALTER TABLE `provider_profiles` ADD COLUMN `lastTestElapsedMillis` INTEGER")
        connection.execSQL("UPDATE `provider_profiles` SET `authenticationType` = 'X_API_KEY' WHERE `kind` = 'ANTHROPIC'")

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `provider_model_options` (
                `id` TEXT NOT NULL,
                `connectionId` TEXT NOT NULL,
                `remoteModelId` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `discoveredAtEpochMillis` INTEGER,
                `discoveryState` TEXT NOT NULL,
                `textCapability` TEXT NOT NULL DEFAULT 'UNKNOWN',
                `visionCapability` TEXT NOT NULL DEFAULT 'UNKNOWN',
                `toolCapability` TEXT NOT NULL DEFAULT 'UNKNOWN',
                `reasoningCapability` TEXT NOT NULL DEFAULT 'UNKNOWN',
                `capabilitySource` TEXT NOT NULL DEFAULT 'UNKNOWN',
                PRIMARY KEY(`id`),
                FOREIGN KEY(`connectionId`) REFERENCES `provider_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_provider_model_options_connectionId` ON `provider_model_options` (`connectionId`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_provider_model_options_connectionId_remoteModelId` ON `provider_model_options` (`connectionId`, `remoteModelId`)",
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `provider_model_options` (
                `id`, `connectionId`, `remoteModelId`, `displayName`, `enabled`, `source`,
                `discoveredAtEpochMillis`, `discoveryState`
            )
            SELECT `id` || ':migrated', `id`, `model`, `model`, 1, 'MIGRATED', NULL, 'STALE'
            FROM `provider_profiles` WHERE TRIM(`model`) <> ''
            """.trimIndent(),
        )
        connection.execSQL(
            "UPDATE `provider_profiles` SET `preferredModelId` = `id` || ':migrated' WHERE TRIM(`model`) <> ''",
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `provider_settings` (
                `singletonId` INTEGER NOT NULL,
                `defaultConnectionId` TEXT,
                `defaultModelId` TEXT,
                PRIMARY KEY(`singletonId`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `provider_settings` (`singletonId`, `defaultConnectionId`, `defaultModelId`)
            SELECT 1, `id`, `id` || ':migrated'
            FROM `provider_profiles`
            WHERE TRIM(`model`) <> ''
            ORDER BY `displayName`, `id`
            LIMIT 1
            """.trimIndent(),
        )

        connection.execSQL("ALTER TABLE `agent_provider_bindings` ADD COLUMN `modelId` TEXT")
        connection.execSQL(
            """
            UPDATE `agent_provider_bindings`
            SET `modelId` = `providerProfileId` || ':migrated'
            WHERE EXISTS (
                SELECT 1 FROM `provider_model_options`
                WHERE `provider_model_options`.`id` = `agent_provider_bindings`.`providerProfileId` || ':migrated'
            )
            """.trimIndent(),
        )

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_provider_routes` (
                `conversationId` TEXT NOT NULL,
                `providerProfileId` TEXT NOT NULL,
                `modelId` TEXT NOT NULL,
                `requestRevision` INTEGER NOT NULL,
                PRIMARY KEY(`conversationId`),
                FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_provider_routes_conversationId` ON `conversation_provider_routes` (`conversationId`)",
        )
    }
}
