package dev.cockpit.persistence.room.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object Migration6To7 : Migration(SchemaV6.VERSION, SchemaV7.VERSION) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `generation_attempts` (
                `conversationId` TEXT NOT NULL,
                `attemptId` TEXT NOT NULL,
                `providerProfileId` TEXT NOT NULL,
                `modelId` TEXT NOT NULL,
                `providerRevision` INTEGER NOT NULL,
                `acceptedUserRevision` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `errorCode` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`conversationId`),
                FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_generation_attempts_attemptId` ON `generation_attempts` (`attemptId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_generation_attempts_status` ON `generation_attempts` (`status`)",
        )
    }
}
