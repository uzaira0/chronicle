package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sensing expansion — interaction salience (`docs/SENSING-EXPANSION-DESIGN.md` §6): add the
 * `interaction_samples` table for the `interaction_events` collection module's on-device buffer.
 *
 * Additive only (creates a new table; touches nothing existing), so it preserves all prior
 * data. The column definitions must match the [InteractionSampleEntry] Room schema exactly
 * (types, nullability, primary key) or Room's post-migration validation fails: non-null `Int`
 * fields are `INTEGER NOT NULL`, the nullable `scrollDeltaX`/`scrollDeltaY` are `INTEGER`, and
 * `id` is the `TEXT` primary key.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `interaction_samples` (" +
                "`id` TEXT NOT NULL, " +
                "`timestamp` TEXT NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`eventType` TEXT NOT NULL, " +
                "`gridRows` INTEGER NOT NULL, " +
                "`gridCols` INTEGER NOT NULL, " +
                "`gridRow` INTEGER NOT NULL, " +
                "`gridCol` INTEGER NOT NULL, " +
                "`elementRole` TEXT NOT NULL, " +
                "`foregroundPackage` TEXT NOT NULL, " +
                "`scrollDeltaX` INTEGER, " +
                "`scrollDeltaY` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
    }
}
