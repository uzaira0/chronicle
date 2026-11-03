package com.openlattice.chronicle.storage

/**
 * The `dataQueue` `writeTimestamp` orders the queue: the usage uploader reads rows past an in-memory
 * `(writeTimestamp, id)` cursor and deletes each row by its key once the server answers for it. A
 * device clock that steps BACKWARD (NTP correction, user change, timezone-daemon glitch) would put a
 * new row behind rows still queued: out of write order, and skipped until the next run when the
 * cursor is already past it. Every writer therefore takes its value from here instead of
 * `System.currentTimeMillis()` directly, so queued rows stay in write order. Wall time survives inside
 * the sample payloads, which carry their own event timestamps.
 */
fun monotonicQueueWriteTimestamp(wallClockMillis: Long, highWaterMark: Long?): Long =
    if (highWaterMark == null) wallClockMillis else maxOf(wallClockMillis, highWaterMark + 1)

/**
 * The next `dataQueue` write cursor: wall clock, advanced past anything already queued. Reads one
 * indexed MAX() query; call it once per collection run, not per row.
 */
fun ChronicleDb.nextQueueWriteTimestamp(wallClockMillis: Long = System.currentTimeMillis()): Long =
    queueEntryData().nextWriteTimestamp(wallClockMillis)
