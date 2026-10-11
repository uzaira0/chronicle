package com.openlattice.chronicle.services.usage

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.common.base.Stopwatch
import com.google.common.collect.ImmutableMap
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.sensors.PROPERTY_TYPE_IDS
import com.openlattice.chronicle.sensors.PROPERTY_TYPES
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.StorageQueue
import com.openlattice.chronicle.storage.UserStorageQueue
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.utils.Utils
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.security.SecureRandom
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Shared setup, failure reporting and post-write bookkeeping for both usage collectors. */
abstract class UsageCollectionDelegateBase(protected val context: Context) {
    protected val sw = Stopwatch.createStarted()
    protected val rand = SecureRandom()
    protected val serviceId = rand.nextLong()

    private lateinit var chronicleApi: ChronicleStudyApi

    protected lateinit var propertyTypeIds: Map<FullQualifiedName, UUID>
    protected lateinit var chronicleDb: ChronicleDb
    protected lateinit var storageQueue: StorageQueue
    protected lateinit var userStorageQueue: UserStorageQueue
    protected lateinit var settings: EnrollmentSettings

    fun execute(): Boolean {
        try {
            settings = EnrollmentSettings(context)
            chronicleDb = ChronicleDb.getInstance(context)
            chronicleDb.uploadServerDao().getEnabledServer()
                ?.takeIf { it.studyId == settings.getStudyId().toString() }
                ?.let { primary ->
                    chronicleApi = UploadWorker.getChronicleStudyApi(
                        primary.url,
                        primary.mobileSigningSecretOverride
                    )
                }
            propertyTypeIds = loadPropertyTypeIds()
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
        queueEntry: List<com.openlattice.chronicle.android.ChronicleSample>,
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

        // Update "items remaining" only after successfully persisting collected data.
        Utils.updateUploadQueueSize(context, storageQueue.getSize())

        val lowestTimestamp = users.lowerEntry(currentPollTimestamp)?.key
        lowestTimestamp?.let {
            sink.withAdmission { userStorageQueue.deleteEntriesWithLowerTimestamp(currentPollTimestamp) }
        }
        users.clear() //Release references for GC

        return true
    }

    private fun loadPropertyTypeIds(): Map<FullQualifiedName, UUID> {
        var propertyTypeIds = settings.getPropertyTypeIds()
        if (propertyTypeIds.size != PROPERTY_TYPES.size) {
            Log.i(javaClass.name, "Loading BCM property types cache")
            propertyTypeIds = PROPERTY_TYPE_IDS
                .filterKeys { it in PROPERTY_TYPES }
                .ifEmpty {
                    if (::chronicleApi.isInitialized) {
                        chronicleApi.getPropertyTypeIds(PROPERTY_TYPES) ?: ImmutableMap.of()
                    } else {
                        ImmutableMap.of()
                    }
                }
            settings.setPropertyTypeIds(propertyTypeIds)
        }
        return propertyTypeIds
    }
}
