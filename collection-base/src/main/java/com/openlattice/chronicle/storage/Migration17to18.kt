package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sensing expansion — six new Android collection modules (sleep, activity_recognition,
 * health_connect, connectivity_state, app_network_usage, device_settings): add the buffer
 * tables each module persists to before upload.
 *
 * Additive only (creates six new tables, touches nothing existing), so every buffered row in
 * the other sample tables is preserved. Each `CREATE TABLE` must match its Room entity schema
 * exactly — non-null Kotlin types map to `NOT NULL` columns, nullable types to nullable
 * columns, a `Boolean` to a SQLite `INTEGER` (Room stores booleans as 0/1), a `Double`/`Float`
 * to `REAL`, and `id` is the primary key — or Room's post-migration validation fails.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sleep_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`eventType` TEXT NOT NULL, " +
                "`segmentStartMillis` INTEGER, " +
                "`segmentEndMillis` INTEGER, " +
                "`segmentStatus` TEXT, " +
                "`confidence` INTEGER, " +
                "`light` INTEGER, " +
                "`motion` INTEGER, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_recognition_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`activityType` TEXT NOT NULL, " +
                "`confidence` INTEGER NOT NULL, " +
                "`transitionType` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_metric_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`metricType` TEXT NOT NULL, " +
                "`value` REAL NOT NULL, " +
                "`unit` TEXT NOT NULL, " +
                "`startMillis` INTEGER NOT NULL, " +
                "`endMillis` INTEGER NOT NULL, " +
                "`sourcePackage` TEXT, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `connectivity_state_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`eventType` TEXT NOT NULL, " +
                "`transport` TEXT NOT NULL, " +
                "`connected` INTEGER NOT NULL, " +
                "`metered` INTEGER, " +
                "`validated` INTEGER, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_network_usage_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`packageName` TEXT NOT NULL, " +
                "`networkType` TEXT NOT NULL, " +
                "`rxBytes` INTEGER NOT NULL, " +
                "`txBytes` INTEGER NOT NULL, " +
                "`bucketStartMillis` INTEGER NOT NULL, " +
                "`bucketEndMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `device_settings_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`darkMode` INTEGER, " +
                "`fontScale` REAL, " +
                "`accessibilityEnabled` INTEGER, " +
                "`dndActive` INTEGER, " +
                "`batterySaver` INTEGER, " +
                "`thermalStatus` TEXT, " +
                "`autoRotate` INTEGER, " +
                "`locationServicesEnabled` INTEGER, " +
                "`storageFreeBytes` INTEGER, " +
                "`storageTotalBytes` INTEGER, " +
                "PRIMARY KEY(`id`))"
        )
    }
}
