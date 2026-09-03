package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds per-destination sensor acknowledgements, enrollment generations, and a bounded
 * malformed-sample quarantine table.
 *
 * Existing pending samples intentionally start without acknowledgements and are replayed. The
 * server's sample-id uniqueness makes that conservative migration safe, and avoids trusting the
 * old timestamp cursor for an exact delivery claim it could not prove. The dead-letter table
 * also starts empty; only rows that fail current wire mapping are moved into it.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Supports oldest-first upload reads and bounded retention cleanup without repeatedly
        // sorting a multi-million-row sensor queue.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sensor_samples_timestamp_id` " +
                "ON `sensor_samples` (`timestamp`, `id`)",
        )
        db.execSQL(
            "ALTER TABLE `upload_servers` ADD COLUMN `sensorDeliveryGeneration` " +
                "INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sensor_sample_deliveries` (
                `sampleId` TEXT NOT NULL,
                `serverId` INTEGER NOT NULL,
                `serverGeneration` INTEGER NOT NULL,
                `deliveredAt` TEXT NOT NULL,
                PRIMARY KEY(`sampleId`, `serverId`),
                FOREIGN KEY(`sampleId`) REFERENCES `sensor_samples`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`serverId`) REFERENCES `upload_servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sensor_sample_deliveries_serverId` " +
                "ON `sensor_sample_deliveries` (`serverId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sensor_sample_dead_letters` (
                `sampleId` TEXT NOT NULL,
                `sensorType` TEXT NOT NULL,
                `timestamp` TEXT NOT NULL,
                `timezone` TEXT NOT NULL,
                `x` REAL,
                `y` REAL,
                `z` REAL,
                `w` REAL,
                `accuracy` INTEGER,
                `valuesJson` TEXT,
                `quarantinedAt` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                PRIMARY KEY(`sampleId`)
            )
            """.trimIndent(),
        )
    }
}
