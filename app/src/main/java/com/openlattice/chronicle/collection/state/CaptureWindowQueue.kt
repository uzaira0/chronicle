package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.BATCH_SIZE
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.capturedMillis
import com.openlattice.chronicle.storage.updateUsageData
import java.io.IOException

// Below SQLite's default host-parameter ceiling of 999.
private const val DELETE_CHUNK = 900

/**
 * Drops queued rows captured inside a closed capture window ([windows] are on this phone's clock, from
 * ResearchErasureFence.captureWindows). An offline phone learns of a pause late; what it captured inside the pause
 * never uploads, and everything captured outside every window stays queued.
 * The caller holds the research stop, so no upload has these rows in flight.
 */
internal fun dropQueuedCapturesInside(db: ChronicleDb, windows: List<LongRange>) {
    if (windows.isEmpty()) return
    val inside = { millis: Long -> windows.any { millis in it } }
    db.runInTransaction {
        UploadQueueTable.entries.filter { it.uploaded && it != UploadQueueTable.USAGE }
            .forEach { dropSamplesInside(db, it.tableName, inside) }
        dropUsageInside(db, inside)
        // A label applies forward: one selected during a pause still labels what is captured after the resume, so
        // only the hard stop's window (the one open from the start) drops labels.
        val beforeStop = windows.filter { it.first == Long.MIN_VALUE }
        val labels = db.userQueueEntryData()
        labels.deleteEntries(labels.getUserTimestamps().filter { label -> beforeStop.any { label.writeTimestamp in it } })
    }
}

private fun dropSamplesInside(db: ChronicleDb, table: String, inside: (Long) -> Boolean) {
    val sql = db.openHelper.writableDatabase
    val ids = sql.query("SELECT id, timestamp FROM `$table`").use { rows ->
        buildList {
            while (rows.moveToNext()) {
                val capturedAt = capturedMillis(rows.getString(1)) ?: continue
                if (inside(capturedAt)) add(rows.getString(0))
            }
        }
    }
    // The queue's delete trigger drops each row's upload evidence with it.
    ids.chunked(DELETE_CHUNK).forEach { chunk ->
        sql.delete(table, "id IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray())
    }
}

private fun dropUsageInside(db: ChronicleDb, inside: (Long) -> Boolean) {
    val queue = db.queueEntryData()
    var cursor: QueueEntry? = null
    while (true) {
        val rows = queue.getEntriesAfter(cursor?.writeTimestamp ?: Long.MIN_VALUE, cursor?.id ?: Long.MIN_VALUE, BATCH_SIZE)
        if (rows.isEmpty()) break
        cursor = rows.last()
        rows.forEach { row ->
            // An unreadable row is the upload path's to quarantine; it is not judged here.
            val samples = try { JsonSerializer.deserializeQueueEntry(row.data) } catch (_: IOException) { return@forEach }
            val kept = samples.filterNot { it is ExtractedUsageEvent && inside(it.timestamp.toInstant().toEpochMilli()) }
            if (kept.size == samples.size) return@forEach
            if (kept.isEmpty()) queue.deleteEntry(row)
            else db.updateUsageData(row, JsonSerializer.serializeQueueEntry(kept))
        }
    }
}
