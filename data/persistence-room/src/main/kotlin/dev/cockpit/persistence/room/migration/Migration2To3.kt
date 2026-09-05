package dev.cockpit.persistence.room.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration2To3 : Migration(SchemaV2.VERSION, SchemaV3.VERSION) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `provider_profiles` ADD COLUMN `vendor` TEXT NOT NULL DEFAULT 'CUSTOM'",
        )
        connection.execSQL(
            "UPDATE `provider_profiles` SET `vendor` = 'OPENAI' WHERE `kind` = 'OPENAI_RESPONSES'",
        )
        connection.execSQL(
            "UPDATE `provider_profiles` SET `vendor` = 'ANTHROPIC' WHERE `kind` = 'ANTHROPIC'",
        )
        connection.execSQL(
            """
            UPDATE `provider_profiles` SET `vendor` = 'DEEPSEEK'
            WHERE `baseUrl` = 'https://api.deepseek.com'
                OR `baseUrl` LIKE 'https://api.deepseek.com/%'
            """.trimIndent(),
        )
        connection.execSQL(
            """
            UPDATE `provider_profiles` SET `vendor` = 'GEMINI'
            WHERE `baseUrl` = 'https://generativelanguage.googleapis.com'
                OR `baseUrl` LIKE 'https://generativelanguage.googleapis.com/%'
            """.trimIndent(),
        )
        connection.execSQL(
            """
            UPDATE `provider_profiles` SET `vendor` = 'GLM'
            WHERE `baseUrl` = 'https://open.bigmodel.cn/api/paas/v4'
                OR `baseUrl` LIKE 'https://open.bigmodel.cn/api/paas/v4/%'
            """.trimIndent(),
        )
    }
}
