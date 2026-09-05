package dev.cockpit.persistence.room.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration5To6 : Migration(SchemaV5.VERSION, SchemaV6.VERSION) {
    override suspend fun migrate(connection: SQLiteConnection) {
        // Some development v5 builds already shipped this column, while the
        // v4 -> v5 migration deliberately leaves it for this migration. Keep
        // the production upgrade path safe for both database shapes.
        if (!connection.hasColumn(table = "agent_drafts", column = "providerModelId")) {
            connection.execSQL("ALTER TABLE `agent_drafts` ADD COLUMN `providerModelId` TEXT")
        }
        connection.execSQL(
            """
            INSERT OR IGNORE INTO `conversation_provider_routes` (
                `conversationId`, `providerProfileId`, `modelId`, `requestRevision`
            )
            SELECT
                `conversations`.`id`,
                COALESCE(`agent_provider_bindings`.`providerProfileId`, `provider_settings`.`defaultConnectionId`),
                CASE
                    WHEN `agent_provider_bindings`.`providerProfileId` IS NOT NULL
                        THEN COALESCE(
                            `agent_provider_bindings`.`modelId`,
                            `provider_profiles`.`preferredModelId`
                        )
                    ELSE `provider_settings`.`defaultModelId`
                END,
                `provider_profiles`.`revision`
            FROM `conversations`
            LEFT JOIN `agent_provider_bindings`
                ON `agent_provider_bindings`.`agentId` = `conversations`.`agentId`
            LEFT JOIN `provider_settings` ON `provider_settings`.`singletonId` = 1
            JOIN `provider_profiles`
                ON `provider_profiles`.`id` = COALESCE(
                    `agent_provider_bindings`.`providerProfileId`,
                    `provider_settings`.`defaultConnectionId`
                )
            JOIN `provider_model_options`
                ON `provider_model_options`.`id` = CASE
                    WHEN `agent_provider_bindings`.`providerProfileId` IS NOT NULL
                        THEN COALESCE(
                            `agent_provider_bindings`.`modelId`,
                            `provider_profiles`.`preferredModelId`
                        )
                    ELSE `provider_settings`.`defaultModelId`
                END
                AND `provider_model_options`.`connectionId` = `provider_profiles`.`id`
            """.trimIndent(),
        )
    }
}

private fun SQLiteConnection.hasColumn(table: String, column: String): Boolean =
    prepare("PRAGMA table_info(`$table`)").use { statement ->
        while (statement.step()) {
            if (statement.getText(1) == column) return@use true
        }
        false
    }
