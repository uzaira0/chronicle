package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openlattice.chronicle.collection.CollectionUploadEvidence

/**
 * What a queued row was captured under: the settings revision and the phone's latest decision sequence. The server
 * authorizes the row by both, never by its capture time.
 */
@Entity(tableName = "upload_queue_evidence", primaryKeys = ["payloadType", "rowId"])
data class UploadQueueEvidence(val payloadType: String, val rowId: String, val settingsVersion: Int?, val decisionSeq: Long? = null)

@Dao
interface UploadQueueEvidenceDao {
    @Query("SELECT * FROM upload_queue_evidence WHERE payloadType = :type AND rowId IN (:ids)")
    fun get(type: String, ids: List<String>): List<UploadQueueEvidence>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(rows: List<UploadQueueEvidence>)
}

const val QUEUE_CAPTURE_VERSION_KEY = "queue_capture_settings_version"

/** The sequence number of the phone's latest participant decision; it also identifies that decision's acknowledgment. */
const val DECISION_SEQ_KEY = "decision_seq"

/** Numbers the next decision. Call inside the transaction that queues its acknowledgment. */
fun ChronicleDb.nextDecisionSeq(): Long {
    val dao = lifecycleStateDao()
    val next = Math.addExact(dao.get(DECISION_SEQ_KEY)?.value?.toLong() ?: 0L, 1L)
    dao.upsert(LifecycleStateEntity(DECISION_SEQ_KEY, next.toString(), System.currentTimeMillis()))
    return next
}

/** Every insert stamps the applied policy in the same SQLite transaction as its queue row. */
val UPLOAD_EVIDENCE_CALLBACK = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) = installUploadEvidenceTriggers(db)
}

private fun installUploadEvidenceTriggers(db: SupportSQLiteDatabase) {
    UploadQueueTable.entries.filter { it.uploaded }.forEach { type ->
        val table = type.tableName
        val key = if (type == UploadQueueTable.USAGE) "NEW.writeTimestamp || ':' || NEW.id" else "NEW.id"
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS capture_${table}_version AFTER INSERT ON `$table` BEGIN
            INSERT OR IGNORE INTO upload_queue_evidence (payloadType, rowId, settingsVersion, decisionSeq)
            VALUES ('${type.name}', $key, NULLIF(COALESCE((SELECT CAST(value AS INTEGER) FROM lifecycle_state WHERE `key` = '$QUEUE_CAPTURE_VERSION_KEY'),
                (SELECT MAX(appliedVersion) FROM collection_module_state)), ${CollectionUploadEvidence.UNKNOWN_SETTINGS_VERSION}),
                NULLIF((SELECT CAST(value AS INTEGER) FROM lifecycle_state WHERE `key` = '$DECISION_SEQ_KEY'),
                ${CollectionUploadEvidence.UNKNOWN_DECISION_SEQ})); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS erase_${table}_version AFTER DELETE ON `$table` BEGIN
            DELETE FROM upload_queue_evidence WHERE payloadType = '${type.name}' AND rowId = ${key.replace("NEW.", "OLD.")}; END""")
    }
}

/**
 * Version 2: rows carry their decision sequence instead of a server hold, and module state
 * drops the policy snapshot and disable disposition. Rows already queued keep their revision and upload without a
 * sequence. The capture triggers name the old column, so they go first; [UPLOAD_EVIDENCE_CALLBACK] reinstalls them.
 */
internal val DECISION_SEQ_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        UploadQueueTable.entries.filter { it.uploaded }.forEach { type ->
            db.execSQL("DROP TRIGGER IF EXISTS capture_${type.tableName}_version")
            db.execSQL("DROP TRIGGER IF EXISTS erase_${type.tableName}_version")
        }
        rebuild(db, "upload_queue_evidence", "`payloadType` TEXT NOT NULL, `rowId` TEXT NOT NULL, `settingsVersion` INTEGER, " +
            "`decisionSeq` INTEGER, PRIMARY KEY(`payloadType`, `rowId`)", "payloadType, rowId, settingsVersion")
        rebuild(db, "collection_module_state", "`moduleId` TEXT NOT NULL, `serverEnabled` INTEGER NOT NULL, " +
            "`decision` TEXT NOT NULL, `decidedAtEpochMillis` INTEGER, `requiredApplied` INTEGER NOT NULL, " +
            "`appliedVersion` INTEGER NOT NULL, PRIMARY KEY(`moduleId`)",
            "moduleId, serverEnabled, decision, decidedAtEpochMillis, requiredApplied, appliedVersion")
    }

    private fun rebuild(db: SupportSQLiteDatabase, table: String, columns: String, kept: String) {
        db.execSQL("CREATE TABLE `${table}_new` ($columns)")
        db.execSQL("INSERT INTO `${table}_new` ($kept) SELECT $kept FROM `$table`")
        db.execSQL("DROP TABLE `$table`")
        db.execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
    }
}
