package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS upload_servers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                studyId TEXT NOT NULL,
                participantId TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                lastUploadTime TEXT,
                lastUploadError TEXT,
                consecutiveFailures INTEGER NOT NULL DEFAULT 0,
                lastSensorUploadTime TEXT,
                lastSensorUploadError TEXT,
                sensorConsecutiveFailures INTEGER NOT NULL DEFAULT 0,
                lastUploadedTimestamp INTEGER NOT NULL DEFAULT 0,
                lastUploadedSensorId TEXT,
                createdAt TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
    }
}
