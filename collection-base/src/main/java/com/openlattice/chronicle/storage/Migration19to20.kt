package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds an optional per-server mobile HMAC signing-secret override. Existing rows keep NULL
 * and continue using the APK-level BuildConfig.MOBILE_SIGNING_SECRET.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `mobileSigningSecretOverride` TEXT")
    }
}
