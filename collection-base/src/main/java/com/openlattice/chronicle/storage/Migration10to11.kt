package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `collection_module_state` table for the collection-loop-closure feature —
 * the device's per-module server-enabled + acknowledgment state (design §5.5).
 *
 * The DDL matches Room's generated schema for [CollectionModuleStateEntity] exactly
 * (column order = declaration order; nullable columns omit `NOT NULL`; trailing
 * `PRIMARY KEY`) so Room's post-migration schema validation passes.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `collection_module_state` (" +
                "`moduleId` TEXT NOT NULL, " +
                "`serverEnabled` INTEGER NOT NULL, " +
                "`acknowledgedAtEpochMillis` INTEGER, " +
                "`appliedVersion` INTEGER NOT NULL, " +
                "`lastDisposition` TEXT, " +
                "PRIMARY KEY(`moduleId`))"
        )
    }
}
