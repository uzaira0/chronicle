package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Enforces the Play product contract: one logical study/server per app installation.
 *
 * A legacy database with multiple destinations is ambiguous because its queues were not tagged by
 * study. Fail closed by removing every pending research record and enrollment rather than guessing
 * which researcher should receive the bytes. The participant must then enroll again explicitly.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val ambiguousEnrollment = "(SELECT COUNT(*) FROM `upload_servers`) > 1"
        listOf(
            "dataQueue",
            "userQueue",
            "sensor_samples",
            "battery_samples",
            "interaction_samples",
            "audio_activity_samples",
            "audio_content_samples",
            "notification_activity_samples",
            "sleep_samples",
            "activity_recognition_samples",
            "health_metric_samples",
            "connectivity_state_samples",
            "app_network_usage_samples",
            "device_settings_samples",
            "sensor_sample_dead_letters",
            "usage_poll_checkpoints",
            "collection_module_state",
        ).forEach { table ->
            db.execSQL("DELETE FROM `$table` WHERE $ambiguousEnrollment")
        }
        db.execSQL("DELETE FROM `upload_servers` WHERE $ambiguousEnrollment")
        db.execSQL(
            "ALTER TABLE `upload_servers` " +
                "ADD COLUMN `singletonKey` INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `studyDisclosureJson` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `disclosureVersion` TEXT")
        db.execSQL("ALTER TABLE `upload_servers` ADD COLUMN `manifestDigest` TEXT")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_servers_singletonKey` " +
                "ON `upload_servers` (`singletonKey`)",
        )
    }
}
