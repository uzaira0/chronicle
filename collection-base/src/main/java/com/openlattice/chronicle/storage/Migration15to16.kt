package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sensing expansion — interaction salience (`docs/SENSING-EXPANSION-DESIGN.md` §6): add the
 * legacy node-center position representation to the `interaction_samples` buffer.
 *
 * Historically added a nullable bundle that was incorrectly named "exact position": `rawX` /
 * `rawY` were derived accessibility-element centers, not pointer coordinates, while
 * `normalizedX` / `normalizedY` were further derivations. These four legacy columns are no longer
 * populated by new collection. `screenWidth` / `screenHeight` remain useful display context for
 * the authoritative node bounds added by the later provenance migration. Additive only (touches
 * nothing existing), so it preserves all buffered rows; pre-migration rows keep NULL positions. The column
 * definitions must match the [InteractionSampleEntry] Room schema exactly: a nullable `Int?`
 * maps to a SQLite `INTEGER` column and a nullable `Double?` to a `REAL` column, both with no
 * NOT NULL constraint, or Room's post-migration validation fails.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `rawX` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `rawY` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `screenWidth` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `screenHeight` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `normalizedX` REAL")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `normalizedY` REAL")
        // Salience kinematics + context. A nullable Long?/Int? maps to a SQLite INTEGER column,
        // a Double? to REAL, and a Boolean? to INTEGER (Room stores booleans as 0/1) — all with
        // no NOT NULL constraint, matching the [InteractionSampleEntry] schema exactly.
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `eventTimeMillis` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `episodeId` TEXT")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `dwellMillisSincePrev` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `orientation` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `screenDensityDpi` INTEGER")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `scrollVelocityX` REAL")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `scrollVelocityY` REAL")
        db.execSQL("ALTER TABLE `interaction_samples` ADD COLUMN `scrollReversed` INTEGER")
    }
}
