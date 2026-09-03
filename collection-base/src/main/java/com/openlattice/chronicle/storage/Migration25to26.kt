package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds SQLCipher-only state required to replay one enrollment attempt after process death. */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `pendingEnrollmentAttemptId` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `pendingEnrollmentAccessCode` TEXT")
        db.execSQL(
            "ALTER TABLE `upload_servers` " +
                "ADD COLUMN `pendingEnrollmentInviteExpiresAtEpochMillis` INTEGER",
        )
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `pendingProposedApiKey` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `pendingEnrollmentSourceDeviceJson` TEXT")
        db.execSQL(
            "ALTER TABLE `upload_servers` " +
                "ADD COLUMN `pendingEnrollmentFirstRequestAtEpochMillis` INTEGER",
        )
        db.execSQL(
            "ALTER TABLE `upload_servers` " +
                "ADD COLUMN `pendingEnrollmentReplayDeadlineEpochMillis` INTEGER",
        )
    }
}
