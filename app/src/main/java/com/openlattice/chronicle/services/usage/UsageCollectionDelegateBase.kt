package com.openlattice.chronicle.services.usage

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.common.base.Stopwatch
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.StorageQueue
import com.openlattice.chronicle.storage.UserStorageQueue
import com.openlattice.chronicle.telemetry.LocalTelemetry
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/** Shared setup, failure reporting and post-write bookkeeping for both usage collectors. */
abstract class UsageCollectionDelegateBase(protected val context: Context) {
    protected val sw = Stopwatch.createStarted()
    protected val rand = SecureRandom()
    protected val serviceId = rand.nextLong()

    protected lateinit var chronicleDb: ChronicleDb
    protected lateinit var storageQueue: StorageQueue
    protected lateinit var userStorageQueue: UserStorageQueue
    protected lateinit var settings: EnrollmentSettings

    fun execute(): Boolean {
        try {
            settings = EnrollmentSettings(context)
            chronicleDb = ChronicleDb.getInstance(context)
            storageQueue = chronicleDb.queueEntryData()
            userStorageQueue = chronicleDb.userQueueEntryData()
            val collected = monitorUsage()
            if (!collected) {
                return false
            }
        } catch (e: Exception) {
            LocalTelemetry.recordException(e)
            if (::settings.isInitialized) {
                Log.i(TAG, "usage monitoring worker failed for enrolled device", e)
            }
            Log.i(TAG, "usage monitoring worker failed with an exception", e)
            LocalTelemetry.logEvent(TelemetryEvents.USAGE_FAILURE, null)
            throw e
        }
        return true
    }

    protected abstract fun monitorUsage(): Boolean

    protected fun finishUsageCollection(
        queueEntry: List<ChronicleSample>,
        w: Stopwatch,
        users: TreeMap<Long, String>,
        currentPollTimestamp: Long,
        sink: UsageEventSink,
    ): Boolean {
        queueEntry.asSequence().chunked(1000).forEach { chunk ->
            Log.d(
                javaClass.name,
                "Persisting ${chunk.size} usage information elements took ${w.elapsed(TimeUnit.MILLISECONDS)} millis."
            )
            LocalTelemetry.logEvent(TelemetryEvents.USAGE_SUCCESS, Bundle().apply {
                putInt("size", chunk.size)
            })
        }

        val lowestTimestamp = users.lowerEntry(currentPollTimestamp)?.key
        lowestTimestamp?.let {
            sink.withAdmission { userStorageQueue.deleteEntriesWithLowerTimestamp(currentPollTimestamp) }
        }
        users.clear() //Release references for GC

        return true
    }

}
