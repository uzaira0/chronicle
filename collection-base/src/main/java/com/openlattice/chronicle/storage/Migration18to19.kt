package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * device_settings expansion — fold audio settings (no microphone) + screen brightness into the
 * `device_settings` snapshot: screen brightness (+ adaptive flag), the media/ring/notification/alarm
 * stream volumes (each with its device max) and the ringer mode.
 *
 * Additive only — eleven new nullable columns on the existing `device_settings_samples` table; every
 * buffered row is preserved. Each `ADD COLUMN` must match its Room entity field exactly: nullable
 * `Int`/`Long` → nullable `INTEGER`, nullable `Boolean` → nullable `INTEGER` (Room stores 0/1),
 * nullable `String` → nullable `TEXT` — or Room's post-migration schema validation fails.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `screenBrightness` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `screenBrightnessAuto` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `mediaVolume` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `mediaVolumeMax` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `ringVolume` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `ringVolumeMax` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `notificationVolume` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `notificationVolumeMax` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `alarmVolume` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `alarmVolumeMax` INTEGER")
        db.execSQL("ALTER TABLE `device_settings_samples` ADD COLUMN `ringerMode` TEXT")
    }
}
