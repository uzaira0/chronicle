package com.openlattice.chronicle.storage

import com.squareup.moshi.JsonReader
import okio.Buffer

internal fun BoundedQueueWriter.boundedUsageRows(entries: List<QueueEntry>, settingsVersion: Int? = null): List<QueueEntry> = buildList {
    for (entry in entries) {
        if (entry.data.size <= MAX_PERSISTED_QUEUE_PAYLOAD_BYTES) { add(entry); continue }
        val chunks = runCatching { splitUsagePayload(entry.data) }.getOrNull()
        if (chunks == null) preserveOversizedRow(UploadQueueTable.USAGE, entry.queueKey, entry.data, settingsVersion)
        else chunks.forEachIndexed { index, bytes ->
            if (bytes.size > MAX_PERSISTED_QUEUE_PAYLOAD_BYTES) {
                preserveOversizedRow(UploadQueueTable.USAGE, "${entry.queueKey}:$index", bytes, settingsVersion)
            } else add(QueueEntry(entry.writeTimestamp, entry.id + index, bytes))
        }
    }
}

private fun splitUsagePayload(raw: ByteArray): List<ByteArray> {
    val reader = JsonReader.of(Buffer().write(raw))
    val chunks = mutableListOf<ByteArray>()
    var current = Buffer().writeByte('['.code)
    reader.beginArray()
    while (reader.hasNext()) {
        val sample = reader.nextSource().use { it.readByteArray() }
        if (current.size > 1 && current.size + sample.size + 2 > MAX_PERSISTED_QUEUE_PAYLOAD_BYTES) {
            chunks += current.writeByte(']'.code).readByteArray()
            current = Buffer().writeByte('['.code)
        }
        if (current.size > 1) current.writeByte(','.code)
        current.write(sample)
    }
    reader.endArray()
    check(reader.peek() == JsonReader.Token.END_DOCUMENT)
    if (current.size > 1) chunks += current.writeByte(']'.code).readByteArray()
    return chunks
}
