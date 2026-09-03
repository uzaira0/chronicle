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
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SensorSampleEntry

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
        val buffer = DirectBootSampleBuffer(applicationContext)
        if (buffer.isEmpty()) return Result.success()

        val sink = SensorSampleSink(
            ChronicleDb.getInstance(applicationContext).sensorSampleDao(),
            persistenceGuard = ResearchPersistenceGate.guard(applicationContext),
            sampleAllowedAtPersistence = { sample ->
                runCatching { AndroidSensorType.valueOf(sample.sensorType) }
                    .getOrNull()
                    ?.let { CollectionGate.collects(applicationContext, SensorCollectionModules.moduleFor(it)) } == true
            },
        )
        val result = buffer.drain { samples ->
            persistGated(samples, sink) { sensorType ->
                CollectionGate.collects(applicationContext, SensorCollectionModules.moduleFor(sensorType))
            }
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
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DirectBootDrainWorker>().build(),
            )
        }

        /**
         * Gate-filters [samples] per sensor, then writes the survivors through [sink].
         * Samples whose gate is closed — or whose persisted sensor type no longer parses —
         * are dropped (fail closed), mirroring the live runtime's gated flush.
         */
        fun persistGated(
            samples: List<SensorSampleEntry>,
            sink: SensorSampleWriter,
            log: CollectionLog = CollectionLog.LOGCAT,
            gate: (AndroidSensorType) -> Boolean,
        ): ModuleResult {
            val kept = samples.filter { entry ->
                val sensorType = try {
                    AndroidSensorType.valueOf(entry.sensorType)
                } catch (_: IllegalArgumentException) {
                    null
                }
                sensorType != null && gate(sensorType)
            }
            val dropped = samples.size - kept.size
            if (dropped > 0) {
                log.info(TAG, "Dropping $dropped buffered sample(s) whose gate is closed at drain time")
            }
            return sink.write(kept)
        }
    }
}
