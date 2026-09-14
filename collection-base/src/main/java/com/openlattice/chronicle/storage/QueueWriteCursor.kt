package com.openlattice.chronicle.storage

/**
 * The `dataQueue` `writeTimestamp` is not a timestamp to the upload path — it is the ordering
 * cursor. `StorageQueue.getEntriesAfter` only ever selects rows past
 * `UploadServerEntity.lastUploadedTimestamp`, and `deleteEntriesBeforeOrAt` deletes everything at
 * or below it. A device clock that steps BACKWARD (NTP correction, user change, timezone-daemon
 * glitch) therefore writes new rows *behind* the cursor: they are never uploaded and the next
 * successful upload prunes them as already delivered — silent data loss.
 *
 * Every writer takes its cursor from here instead of `System.currentTimeMillis()` directly, so the
 * sequence is monotonic regardless of the clock. Wall time survives inside the sample payloads,
 * which carry their own event timestamps.
 */
fun monotonicQueueWriteTimestamp(wallClockMillis: Long, highWaterMark: Long?): Long =
    if (highWaterMark == null) wallClockMillis else maxOf(wallClockMillis, highWaterMark + 1)

/**
 * The next `dataQueue` write cursor: wall clock, advanced past anything already queued or already
 * uploaded. Reads two indexed MAX() queries; call it once per collection run, not per row.
 */
fun ChronicleDb.nextQueueWriteTimestamp(wallClockMillis: Long = System.currentTimeMillis()): Long =
    monotonicQueueWriteTimestamp(
        wallClockMillis,
        listOfNotNull(
            queueEntryData().maxWriteTimestamp(),
            uploadServerDao().maxUploadedQueueTimestamp(),
        ).maxOrNull(),
    )
