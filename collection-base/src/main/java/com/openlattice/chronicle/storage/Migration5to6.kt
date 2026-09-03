package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds [UploadServerEntity.sourceDeviceId]. Existing rows get an empty string;
 * [ServerMigrationHelper] backfills from the legacy global SERVER_DEVICE_UUID
 * on first launch (Room migrations have no Context, so prefs aren't readable
 * here). Empty rows uploads will fail until the backfill runs — that's a
 * one-launch window and idempotent.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE upload_servers ADD COLUMN sourceDeviceId TEXT NOT NULL DEFAULT ''")
    }
}
