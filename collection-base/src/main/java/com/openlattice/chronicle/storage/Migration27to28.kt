package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Replaces legacy free-form upload exception text with a closed, non-sensitive status code. */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE `upload_servers`
            SET `lastUploadError` = CASE
                    WHEN `lastUploadError` IS NULL THEN NULL ELSE 'UPLOAD_FAILURE' END,
                `lastSensorUploadError` = CASE
                    WHEN `lastSensorUploadError` IS NULL THEN NULL ELSE 'UPLOAD_FAILURE' END,
                `lastBatteryUploadError` = CASE
                    WHEN `lastBatteryUploadError` IS NULL THEN NULL ELSE 'UPLOAD_FAILURE' END
            """.trimIndent(),
        )
    }
}
