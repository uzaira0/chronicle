package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Per-module consent (design §4.1): generalize `collection_module_state` from a single
 * acknowledged-timestamp to an explicit tri-state participant decision plus the study's
 * last-applied `required` flag.
 *
 *  - `decision` (TEXT) — `ParticipantDecision` name; defaults `UNDECIDED`.
 *  - `requiredApplied` (INTEGER bool) — the `required` flag last applied; defaults `0`.
 *  - `acknowledgedAtEpochMillis` is **renamed** to `decidedAtEpochMillis` (same type/
 *    nullability), preserving each row's existing timestamp.
 *
 * Legacy mapping (preserves enrolled/active modules on upgrade — no wipe): a row that was
 * server-enabled AND had a non-null acknowledgment becomes `ACCEPTED`; every other row
 * stays `UNDECIDED` (so a server-enabled but unacknowledged module re-prompts, and a
 * disabled module re-prompts on re-enable — matching the new gate).
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `collection_module_state` ADD COLUMN `decision` TEXT NOT NULL DEFAULT 'UNDECIDED'",
        )
        db.execSQL(
            "ALTER TABLE `collection_module_state` ADD COLUMN `requiredApplied` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `collection_module_state` " +
                "RENAME COLUMN `acknowledgedAtEpochMillis` TO `decidedAtEpochMillis`",
        )
        db.execSQL(
            "UPDATE `collection_module_state` SET `decision` = 'ACCEPTED' " +
                "WHERE `serverEnabled` = 1 AND `decidedAtEpochMillis` IS NOT NULL",
        )
    }
}
