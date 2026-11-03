package com.openlattice.chronicle.storage

import java.io.ByteArrayOutputStream

private const val ARCHIVE_CHUNK_BYTES = 64 * 1024

/**
 * Erases one sensor's rows from every store that holds them: the queue, its dead letters and the quarantine archive.
 * An archived row that no longer parses may be this sensor's, so erasure removes it rather than keep what it cannot
 * attribute, as the shared-queue eraser does.
 */
fun ChronicleDb.eraseSensorType(sensorType: String) = runInTransaction {
    sensorSampleDao().deleteSamplesBySensorType(sensorType)
    sensorSampleDao().deleteDeadLettersBySensorType(sensorType)
    forEachArchive(UploadQueueTable.SENSOR) { row ->
        val type = runCatching { deserializeMalformedRow(row.rawData, SensorSampleEntry::class.java)?.sensorType }.getOrNull()
        if (type == null || type == sensorType) localDataQuarantineDao().delete(row)
    }
}

/** Current admission archives indivisible oversized samples; erasure must still inspect them. */
fun ChronicleDb.forEachArchive(source: UploadQueueTable, visit: (LocalDataQuarantineEntity) -> Unit) {
    check(inTransaction()) { "Archive inspection requires one transaction" }
    val dao = localDataQuarantineDao()
    for (row in dao.archiveMetadata(source.tableName)) {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (true) {
            val chunk = dao.archiveChunk(row.id, offset + 1, ARCHIVE_CHUNK_BYTES)
            output.write(chunk)
            if (chunk.size < ARCHIVE_CHUNK_BYTES) break
            offset += chunk.size
        }
        visit(row.copy(rawData = output.toByteArray()))
    }
}
