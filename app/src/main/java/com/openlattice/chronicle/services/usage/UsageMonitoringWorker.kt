package com.openlattice.chronicle.services.usage

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.hasUsageSettingPermission
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.withClientEventId
import com.openlattice.chronicle.storage.QueueEntry

internal const val TAG = "UsageMonitoringWorker"
/** Unique name of the retired split-path usage worker; scheduling cancels it on upgrade. */
const val USAGE_WORK_NAME = "usage"

private val USAGE_POLL_LOCK = Any()

/**
 * Usage collection entry for the sync worker and the enrollment monitor's last poll before a stop. One poll
 * at a time: both read from the same cursor.
 */
internal fun collectUsage(context: Context): Boolean = synchronized(USAGE_POLL_LOCK) {
    // Without Usage Access, queryEvents returns no events. Committing that empty poll moves the
    // cursor past events Android still holds, so they are never read after access returns.
    if (!hasUsageSettingPermission(context)) {
        Log.i(TAG, "Usage Access is off; usage poll skipped, cursor kept")
        return@synchronized true
    }
    UsageModuleCollectionDelegate(context).execute()
}

internal fun buildUsageQueueEntries(
    queueEntry: List<ChronicleSample>,
    firstWriteTimestamp: Long,
    nextId: () -> Long
): List<QueueEntry> {
    var writeTimestamp = firstWriteTimestamp
    return queueEntry.chunked(1000).map { chunk ->
        QueueEntry(
            writeTimestamp++,
            nextId(),
            JsonSerializer.serializeQueueEntry(ChronicleData(chunk.map(::withClientEventId)))
        )
    }
}
