package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Separates upload attempts, successful uploads, and failed attempts so the UI does
 * not present a 401 attempt as a successful upload.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastUsageUploadAttemptTime` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastUsageUploadSuccessTime` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `usageUploadSuccessCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `usageUploadFailureCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastSensorUploadAttemptTime` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastSensorUploadSuccessTime` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `sensorUploadSuccessCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `sensorUploadFailureCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastBatteryUploadAttemptTime` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `lastBatteryUploadSuccessTime` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `batteryUploadSuccessCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `batteryUploadFailureCount` INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE `upload_stats` ADD COLUMN `batterySamplesUploaded` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_stats` ADD COLUMN `usageUploadFailures` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_stats` ADD COLUMN `sensorUploadFailures` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `upload_stats` ADD COLUMN `batteryUploadFailures` INTEGER NOT NULL DEFAULT 0")

        db.execSQL("""
            UPDATE upload_servers
            SET lastUsageUploadAttemptTime = lastUploadTime,
                lastUsageUploadSuccessTime = CASE WHEN lastUploadError IS NULL THEN lastUploadTime ELSE NULL END,
                lastSensorUploadAttemptTime = lastSensorUploadTime,
                lastSensorUploadSuccessTime = CASE WHEN lastSensorUploadError IS NULL THEN lastSensorUploadTime ELSE NULL END,
                lastBatteryUploadAttemptTime = lastBatteryUploadTime,
                lastBatteryUploadSuccessTime = CASE WHEN lastBatteryUploadError IS NULL THEN lastBatteryUploadTime ELSE NULL END
        """.trimIndent())
    }
}
