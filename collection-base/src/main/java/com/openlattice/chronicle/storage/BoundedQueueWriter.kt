package com.openlattice.chronicle.storage

import android.util.Log
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.OffsetDateTime
import java.util.UUID

/** Shared admission at the DAO boundary also covers direct service writers. */
interface BoundedQueueWriter {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOversizedQuarantine(row: LocalDataQuarantineEntity): Long

    @Query("SELECT studyId, participantId, sourceRandomDeviceId AS randomDeviceId FROM upload_servers LIMIT 1")
    fun queueRowOwner(): QueueRowOwner?

    fun <T : Any> boundedRows(table: UploadQueueTable, rows: List<T>, sizeOf: (T) -> Long, idOf: (T) -> String): List<T> = rows.filter { row ->
        if (sizeOf(row) <= MAX_PERSISTED_QUEUE_PAYLOAD_BYTES) true else {
            preserveOversizedRow(table, idOf(row), serializeMalformedRow(row))
            false
        }
    }

    fun preserveOversizedRow(table: UploadQueueTable, id: String, raw: ByteArray, settingsVersion: Int? = null) {
        QueueRowArchive(table, id, raw, queueRowOwner(), settingsVersion).preserve(::insertOversizedQuarantine)
    }
}

data class QueueRowOwner(val studyId: String, val participantId: String, val randomDeviceId: String)

internal data class QueueRowArchive(
    val table: UploadQueueTable,
    val sourceId: String,
    val rawData: ByteArray,
    val owner: QueueRowOwner?,
    val settingsVersion: Int? = null,
) {
    fun preserve(insert: (LocalDataQuarantineEntity) -> Long) {
        check(insert(toEntity()) != -1L) { "Oversized row quarantine identity collision" }
        Log.w("BoundedQueue", "Quarantined oversized row: table=${table.tableName} bytes=${rawData.size}")
    }

    private fun toEntity(): LocalDataQuarantineEntity {
        val identity = UUID.randomUUID().toString()
        return LocalDataQuarantineEntity(
            id = identity, sourceTable = table.tableName, sourceId = "$sourceId:$identity",
            studyId = owner?.studyId, participantId = owner?.participantId, randomDeviceId = owner?.randomDeviceId,
            rawData = rawData, reason = UploadSizeRejection.PERSISTED_ROW_EXCEEDS_PAYLOAD_BUDGET.name,
            createdAt = OffsetDateTime.now().toString(), settingsVersion = settingsVersion,
        )
    }
}
