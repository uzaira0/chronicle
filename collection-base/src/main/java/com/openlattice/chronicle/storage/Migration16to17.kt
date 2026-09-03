package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sensing expansion — app audio + notification activity (`docs/SENSING-EXPANSION-DESIGN.md` §4):
 * add the `audio_activity_samples`, `audio_content_samples`, and `notification_activity_samples`
 * buffers for the new `audio_activity` / `audio_content` / `notification_activity` modules.
 *
 * Additive only (creates three new tables, touches nothing existing), so every buffered row in the
 * other sample tables is preserved. Each `CREATE TABLE` must match its Room entity schema
 * ([AudioActivitySampleEntry] / [AudioContentSampleEntry] / [NotificationActivitySampleEntry])
 * exactly — non-null Kotlin types map to `NOT NULL` columns, nullable types to nullable columns,
 * a `Boolean` to a SQLite `INTEGER` (Room stores booleans as 0/1), and `id` is the primary key —
 * or Room's post-migration validation fails.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `audio_activity_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`eventType` TEXT NOT NULL, " +
                "`audioActive` INTEGER NOT NULL, " +
                "`audioPackage` TEXT, " +
                "`contentType` TEXT, " +
                "`playbackState` TEXT, " +
                "`outputRoute` TEXT, " +
                "`routeConnected` INTEGER, " +
                "`mediaVolume` INTEGER, " +
                "`maxMediaVolume` INTEGER, " +
                "`ringerMode` TEXT, " +
                "`dndActive` INTEGER, " +
                "`callActive` INTEGER, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `audio_content_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`audioPackage` TEXT NOT NULL, " +
                "`title` TEXT, " +
                "`artist` TEXT, " +
                "`album` TEXT, " +
                "`durationMillis` INTEGER, " +
                "`positionMillis` INTEGER, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_activity_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`eventType` TEXT NOT NULL, " +
                "`packageName` TEXT NOT NULL, " +
                "`category` TEXT, " +
                "`ongoing` INTEGER, " +
                "`importance` INTEGER, " +
                "PRIMARY KEY(`id`))"
        )
    }
}
