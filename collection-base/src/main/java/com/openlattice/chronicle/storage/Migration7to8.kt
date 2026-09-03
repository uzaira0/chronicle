package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS usage_poll_checkpoints (
                sensorName TEXT NOT NULL,
                lastPollTimestamp INTEGER NOT NULL,
                PRIMARY KEY(sensorName)
            )
            """.trimIndent()
        )
        db.execSQL(
            "ALTER TABLE upload_servers ADD COLUMN lastUploadedQueueId INTEGER NOT NULL DEFAULT ${Long.MIN_VALUE}"
        )
    }
}
