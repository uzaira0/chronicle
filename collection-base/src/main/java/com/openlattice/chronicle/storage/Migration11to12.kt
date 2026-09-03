package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Tracks battery-upload health per server so battery telemetry failures are visible in
 * the same health model as usage/lifecycle and sensor uploads.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastBatteryUploadTime` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastBatteryUploadError` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `batteryConsecutiveFailures` INTEGER NOT NULL DEFAULT 0")
    }
}
