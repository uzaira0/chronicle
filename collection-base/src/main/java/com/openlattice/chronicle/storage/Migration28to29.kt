package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `upload_diagnostics` (`id` TEXT NOT NULL, `studyId` TEXT, `participantId` TEXT, `deviceId` TEXT, `enrollmentEpoch` TEXT, `day` TEXT NOT NULL, `moduleFamily` TEXT NOT NULL, `issueCode` TEXT NOT NULL, `count` INTEGER NOT NULL, `firstOccurredAt` TEXT NOT NULL, `lastOccurredAt` TEXT NOT NULL, `httpStatus` INTEGER, `errorType` TEXT, `deliveryState` TEXT NOT NULL, `sealedForUpload` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_diagnostics_studyId_participantId_deviceId_enrollmentEpoch_deliveryState_day` ON `upload_diagnostics` (`studyId`, `participantId`, `deviceId`, `enrollmentEpoch`, `deliveryState`, `day`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_diagnostics_day_moduleFamily_issueCode` ON `upload_diagnostics` (`day`, `moduleFamily`, `issueCode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_diagnostics_studyId_participantId_deviceId_enrollmentEpoch_day_moduleFamily_issueCode_deliveryState_sealedForUpload` ON `upload_diagnostics` (`studyId`, `participantId`, `deviceId`, `enrollmentEpoch`, `day`, `moduleFamily`, `issueCode`, `deliveryState`, `sealedForUpload`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `local_data_quarantine` (`id` TEXT NOT NULL, `sourceTable` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `studyId` TEXT, `participantId` TEXT, `deviceId` TEXT, `rawData` BLOB NOT NULL, `reason` TEXT NOT NULL, `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_data_quarantine_sourceTable_sourceId` ON `local_data_quarantine` (`sourceTable`, `sourceId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `diagnostic_import_checkpoints` (`digest` TEXT NOT NULL, `importedCount` INTEGER NOT NULL, `quarantinedCount` INTEGER NOT NULL, PRIMARY KEY(`digest`))")
        // Statistics are retained independently of destination deletion.
        db.execSQL("CREATE TABLE IF NOT EXISTS `upload_stats_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serverId` INTEGER NOT NULL, `date` TEXT NOT NULL, `studyId` TEXT, `participantId` TEXT, `deviceId` TEXT, `enrollmentEpoch` TEXT, `usageEventsUploaded` INTEGER NOT NULL, `sensorSamplesUploaded` INTEGER NOT NULL, `batterySamplesUploaded` INTEGER NOT NULL, `usageUploadFailures` INTEGER NOT NULL, `sensorUploadFailures` INTEGER NOT NULL, `batteryUploadFailures` INTEGER NOT NULL)")
        db.execSQL("INSERT INTO `upload_stats_new` (`id`, `serverId`, `date`, `studyId`, `participantId`, `deviceId`, `enrollmentEpoch`, `usageEventsUploaded`, `sensorSamplesUploaded`, `batterySamplesUploaded`, `usageUploadFailures`, `sensorUploadFailures`, `batteryUploadFailures`) SELECT u.`id`, u.`serverId`, u.`date`, s.`studyId`, s.`participantId`, s.`sourceDeviceId`, s.`id` || ':' || s.`createdAt`, u.`usageEventsUploaded`, u.`sensorSamplesUploaded`, u.`batterySamplesUploaded`, u.`usageUploadFailures`, u.`sensorUploadFailures`, u.`batteryUploadFailures` FROM `upload_stats` u LEFT JOIN `upload_servers` s ON s.`id` = u.`serverId`")
        db.execSQL("DROP TABLE `upload_stats`")
        db.execSQL("ALTER TABLE `upload_stats_new` RENAME TO `upload_stats`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_stats_serverId_date` ON `upload_stats` (`serverId`, `date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_stats_serverId` ON `upload_stats` (`serverId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_stats_studyId_participantId_deviceId_enrollmentEpoch_date` ON `upload_stats` (`studyId`, `participantId`, `deviceId`, `enrollmentEpoch`, `date`)")
    }
}
