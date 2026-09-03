package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds [UploadServerEntity.authMode] and [UploadServerEntity.apiKey].
 * Existing rows default to `deviceId` mode (the legacy upstream Chronicle flow).
 * New BCM-server enrollments populate `apiKey` and switch the row to `apiKey` mode.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE upload_servers ADD COLUMN authMode TEXT NOT NULL DEFAULT 'deviceId'")
        db.execSQL("ALTER TABLE upload_servers ADD COLUMN apiKey TEXT")
    }
}
