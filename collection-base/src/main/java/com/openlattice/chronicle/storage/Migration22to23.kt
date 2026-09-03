package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persists the exact collection policy covered by each participant decision. */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `collection_module_state` " +
                "ADD COLUMN `appliedPolicySnapshot` TEXT",
        )
    }
}
