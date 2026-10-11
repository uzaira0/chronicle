package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.collection.CollectionUploadEvidence

@Entity(tableName = "upload_queue_evidence", primaryKeys = ["payloadType", "rowId"])
data class UploadQueueEvidence(val payloadType: String, val rowId: String, val settingsVersion: Int?, val heldVersion: Int? = null)

@Dao
interface UploadQueueEvidenceDao {
    @Query("SELECT * FROM upload_queue_evidence WHERE payloadType = :type AND rowId IN (:ids)")
    fun get(type: String, ids: List<String>): List<UploadQueueEvidence>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(rows: List<UploadQueueEvidence>)

    @Query("UPDATE upload_queue_evidence SET heldVersion = NULL WHERE heldVersion <= :settingsVersion")
    fun releaseHeldThrough(settingsVersion: Int)
}

const val QUEUE_CAPTURE_VERSION_KEY = "queue_capture_settings_version"

/** Every insert stamps the applied policy in the same SQLite transaction as its queue row. */
val UPLOAD_EVIDENCE_CALLBACK = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) = installUploadEvidenceTriggers(db)
}

private fun installUploadEvidenceTriggers(db: SupportSQLiteDatabase) {
    val queues = mapOf(EncryptedPayloadType.USAGE to "dataQueue", EncryptedPayloadType.SENSOR to "sensor_samples",
        EncryptedPayloadType.BATTERY to "battery_samples",
        EncryptedPayloadType.INTERACTION to "interaction_samples", EncryptedPayloadType.AUDIO_ACTIVITY to "audio_activity_samples",
        EncryptedPayloadType.AUDIO_CONTENT to "audio_content_samples", EncryptedPayloadType.NOTIFICATION_ACTIVITY to "notification_activity_samples",
        EncryptedPayloadType.SLEEP to "sleep_samples", EncryptedPayloadType.ACTIVITY_RECOGNITION to "activity_recognition_samples",
        EncryptedPayloadType.HEALTH_CONNECT to "health_metric_samples", EncryptedPayloadType.CONNECTIVITY_STATE to "connectivity_state_samples",
        EncryptedPayloadType.APP_NETWORK_USAGE to "app_network_usage_samples", EncryptedPayloadType.DEVICE_SETTINGS to "device_settings_samples")
    queues.forEach { (type, table) ->
        val key = if (type == EncryptedPayloadType.USAGE) "NEW.writeTimestamp || ':' || NEW.id" else "NEW.id"
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS capture_${table}_version AFTER INSERT ON `$table` BEGIN
            INSERT OR IGNORE INTO upload_queue_evidence (payloadType, rowId, settingsVersion, heldVersion)
            VALUES ('${type.name}', $key, NULLIF(COALESCE((SELECT CAST(value AS INTEGER) FROM lifecycle_state WHERE `key` = '$QUEUE_CAPTURE_VERSION_KEY'),
                (SELECT MAX(appliedVersion) FROM collection_module_state)), ${CollectionUploadEvidence.UNKNOWN_SETTINGS_VERSION}), NULL); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS erase_${table}_version AFTER DELETE ON `$table` BEGIN
            DELETE FROM upload_queue_evidence WHERE payloadType = '${type.name}' AND rowId = ${key.replace("NEW.", "OLD.")}; END""")
    }
}

val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE upload_queue_evidence (payloadType TEXT NOT NULL, rowId TEXT NOT NULL, settingsVersion INTEGER, heldVersion " +
            "INTEGER, PRIMARY KEY(payloadType, rowId))")
        db.execSQL("ALTER TABLE pending_upload_batches ADD COLUMN settingsVersion INTEGER")
        // Existing rows have no historical evidence; never stamp today's policy onto old data.
        installUploadEvidenceTriggers(db)
    }
}
