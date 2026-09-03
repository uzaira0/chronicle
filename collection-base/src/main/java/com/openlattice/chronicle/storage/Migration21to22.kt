package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds authoritative accessibility-node bounds and coordinate provenance to interaction rows. */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `positionSource` TEXT")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `nodeBoundsLeft` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `nodeBoundsTop` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `nodeBoundsRight` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `nodeBoundsBottom` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `displayId` INTEGER")
    }
}
