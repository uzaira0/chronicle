package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `battery_samples` table for the `battery_telemetry` collection module
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §5).
 *
 * The DDL matches Room's generated schema for [BatterySampleEntry] exactly — column
 * order = declaration order, every column `NOT NULL`, and a trailing `PRIMARY KEY`
 * constraint — so Room's post-migration schema validation passes.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `battery_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`levelPercent` INTEGER NOT NULL, " +
                "`chargingState` TEXT NOT NULL, " +
                "`plugType` TEXT NOT NULL, " +
                "`temperatureDeciC` INTEGER NOT NULL, " +
                "`voltageMillivolts` INTEGER NOT NULL, " +
                "`health` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}
