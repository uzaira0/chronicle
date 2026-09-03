package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds encrypted, short-lived ownership and process-death recovery state for enrollment. */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `reservationNonce` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `reservationExpiresAtEpochMillis` INTEGER")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `enrollmentIssuedAtEpochMillis` INTEGER")
        db.execSQL(
            "ALTER TABLE `upload_servers` " +
                "ADD COLUMN `enrollmentSetupComplete` INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `pendingAcceptedModuleIds` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `pendingDeclinedModuleIds` TEXT")
    }
}
