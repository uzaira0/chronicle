package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServer

private val TAG = DirectBootDrainWorker::class.java.simpleName
private const val UNIQUE_WORK_NAME = "direct_boot_sample_drain"
private const val MAX_RETRY_ATTEMPTS = 5

/**
 * Replays the [DirectBootSampleBuffer] into the normal `sensor_samples` queue after first
 * unlock. Enqueued from `StartOnBoot` (covers a direct-boot service that died before
 * unlock) and from `HardwareSensorService`'s unlock transition / normal-mode start (covers
 * leftovers); the buffer's own single-flight lock plus the sample-id PK make overlapping
 * drains harmless.
 *
 * Each buffered sample re-checks its sensor's [CollectionGate] before persisting — consent
 * or study settings may have changed between the locked-window collection and this drain,
 * and the persistence chokepoint stays gated exactly like the live runtime's flush.
 */
class DirectBootDrainWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val journal = DirectBootDiagnosticsJournal(applicationContext)
        try {
            journal.replay(applicationContext)
        } catch (error: Exception) {
            Log.e(TAG, "Direct-boot diagnostics replay failed; quarantining journal", error)
            try {
                if (!journal.quarantineCorruptJournal()) return Result.retry()
            } catch (quarantineError: Exception) {
                Log.e(TAG, "Unable to quarantine corrupt direct-boot journal", quarantineError)
                return Result.retry()
            }
        }
        try {
            journal.recordQuarantinedIncidents(applicationContext)
        } catch (error: Exception) {
            Log.e(TAG, "Direct-boot journal quarantine recording will retry after sample drain", error)
        }
        val buffer = DirectBootSampleBuffer(applicationContext)
        val db = ChronicleDb.getInstance(applicationContext)
        val server = exactActiveEnrollmentServer(applicationContext, db)
        val expectedOwner = server?.let(::ownerKey)
        // Barrier before buffer lock, the same order as ResearchPersistenceGate.stop{} erasures;
        // taking the buffer lock first and the barrier inside persist deadlocked a sensor discard.
        val result = drainBuffer(applicationContext, buffer, expectedOwner, server?.createdAt) { samples ->
            persistGated(samples, sinkFor = { sensorType ->
                SensorSampleSink(
                    db.sensorSampleDao(),
                    persistenceGuard = ResearchPersistenceGate.guardForExpectedOwner(
                        applicationContext, requireNotNull(expectedOwner),
                        ownerNow = {
                            if (!CollectionGate.collects(applicationContext, SensorCollectionModules.moduleFor(sensorType))) null
                            else exactActiveEnrollmentServer(applicationContext, db)?.let(::ownerKey)
                        },
                    ),
                )
            })
        }
        try {
            DirectBootDiagnosticsJournal(applicationContext).replay(applicationContext)
        } catch (error: Exception) {
            Log.e(TAG, "Direct-boot diagnostics replay failed; journal retained", error)
            return Result.retry()
        }
        Log.i(
            TAG,
            "Direct-boot drain: persisted=${result.persisted} corruptDropped=${result.corruptRecordsDropped} failed=${result.failed}",
        )
        return when {
            !result.failed -> Result.success()
            runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
            else -> {
                // Records stay in the draining file; the next enqueue (next boot / next
                // sensor-service start) retries. Never silently discarded.
                Log.e(TAG, "Direct-boot drain giving up after $runAttemptCount attempts; buffer retained")
                Result.failure()
            }
        }
    }

    companion object {
        internal fun drainBuffer(
            context: Context,
            buffer: DirectBootSampleBuffer,
            expectedOwner: String?,
            enrollmentCreatedAt: String?,
            persist: (List<SensorSampleEntry>) -> DirectBootSampleBuffer.DrainTransfer,
        ): DirectBootSampleBuffer.DrainResult = ResearchPersistenceGate.withReadLease {
            try {
                DistributionRestrictedRuntime.pendingDirectBootSensorErasures(context).forEach { sensorType ->
                    DistributionRestrictedRuntime.eraseDirectBootSensorSamples(context, sensorType, buffer, enqueueDrain = {})
                }
            } catch (error: Exception) {
                Log.e(TAG, "Pending direct-boot sensor erasure will retry before transfer", error)
                return@withReadLease DirectBootSampleBuffer.DrainResult(0, 0, failed = true)
            }
            if (expectedOwner == null) DirectBootSampleBuffer.DrainResult(0, 0, failed = !buffer.isEmpty())
            else buffer.drain(expectedOwner, enrollmentCreatedAt, persist)
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DirectBootDrainWorker>().build(),
            )
        }

        private fun ownerKey(server: UploadServerEntity): String =
            DirectBootSampleBuffer.ownerKey(DirectBootDiagnosticsJournal.Owner(
                server.studyId, server.participantId, server.sourceDeviceId, "${server.id}:${server.createdAt}",
            ))

        /** Write each sensor group under its own gate lease and report exact transferred IDs. */
        fun persistGated(
            samples: List<SensorSampleEntry>,
            sinkFor: (AndroidSensorType) -> SensorSampleWriter,
            log: CollectionLog = CollectionLog.LOGCAT,
        ): DirectBootSampleBuffer.DrainTransfer {
            val transferred = linkedSetOf<String>()
            val discarded = linkedSetOf<String>()
            for ((typeName, group) in samples.groupBy { it.sensorType }) {
                val type = runCatching { AndroidSensorType.valueOf(typeName) }.getOrNull()
                if (type == null) {
                    discarded += group.map { it.id }
                    continue
                }
                when (val result = sinkFor(type).write(group)) {
                    is ModuleResult.Ok -> {
                        // Production sinks accept a whole group or none. A partial result cannot
                        // identify individual rows, so replay the group by its idempotent IDs.
                        if (result.items == group.size) transferred += group.map { it.id }
                    }
                    is ModuleResult.Skipped -> log.info(TAG, "Retaining ${group.size} buffered sample(s) for a later drain")
                    else -> return DirectBootSampleBuffer.DrainTransfer(transferred, failed = true, discardedIds = discarded)
                }
            }
            return DirectBootSampleBuffer.DrainTransfer(transferred, discardedIds = discarded)
        }
    }
}
