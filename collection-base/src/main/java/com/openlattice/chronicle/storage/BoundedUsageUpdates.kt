package com.openlattice.chronicle.storage

/** Rewrites and splits held rows atomically without assigning a newer capture policy. */
fun ChronicleDb.updateUsageData(row: QueueEntry, data: ByteArray) = runInTransaction {
    val queue = queueEntryData()
    check(queue.getByKeys(listOf(row.queueKey)).singleOrNull() == row) { "Usage queue row changed before rewrite" }
    val evidence = uploadQueueEvidenceDao()
    val type = UploadQueueTable.USAGE.name
    val captured = evidence.get(type, listOf(row.queueKey)).singleOrNull()
    val chunks = queue.boundedUsageRows(listOf(QueueEntry(row.writeTimestamp, row.id, data)), captured?.settingsVersion)
    val first = chunks.firstOrNull()
    if (first == null) queue.deleteEntry(row)
    else {
        queue.updateBoundedData(row.writeTimestamp, row.id, first.data)
        if (chunks.size > 1) {
            val timestamp = queue.nextWriteTimestamp(row.writeTimestamp)
            val extra = chunks.drop(1).mapIndexed { index, chunk ->
                QueueEntry(Math.addExact(timestamp, index.toLong()), chunk.id, chunk.data)
            }
            queue.insertAllocatedEntries(extra)
            evidence.put(extra.map { chunk ->
                captured?.copy(rowId = chunk.queueKey) ?: UploadQueueEvidence(type, chunk.queueKey, null)
            })
        }
    }
}
