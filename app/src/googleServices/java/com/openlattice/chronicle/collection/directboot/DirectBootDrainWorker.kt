package com.openlattice.chronicle.collection.directboot

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.security.LeaseBoundWorker
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.ChronicleDb
import java.time.OffsetDateTime

/** After first unlock, moves the current enrollment's pre-unlock samples into `sensor_samples`. */
class DirectBootDrainWorker(context: Context, params: WorkerParameters) : LeaseBoundWorker(context, params) {
    override fun runWork(): Result = if (drain(applicationContext)) Result.success() else Result.retry()

    companion object {
        /**
         * Drains through the consent gate: declined modules' observations and samples before
         * the erasure floor are dropped. A temporary stop keeps pending samples for a retry. Lease before buffer lock,
         * the order module erasure takes them in.
         */
        fun drain(context: Context): Boolean = ResearchPersistenceGate.withReadLease {
            val owner = ResearchPersistenceGate.captureOwner(context) ?: return@withReadLease true
            val dao = ChronicleDb.getInstance(context).sensorSampleDao()
            val diagnostics = LocalUploadDiagnosticsStore.of(context)
            DirectBootSampleBuffer(context, DirectBootSampleBuffer.ownerKey(owner)).drain(
                reportLoss = { issue, count -> diagnostics.recordOperational(LocalUploadModuleFamily.SENSOR, issue, count) },
            ) { samples ->
                samples.groupBy { it.sensorType }.all { (name, group) ->
                    val module = AndroidSensorType.entries.firstOrNull { it.name == name }
                        ?.let(SensorCollectionModules::moduleFor) ?: return@all false
                    val floor = ResearchErasureFence(context).floor(module)
                    val store = CollectionLoopStore.of(context)
                    val state = store.loadAll()[module]
                    val retained = group.filter { OffsetDateTime.parse(it.timestamp).toInstant().toEpochMilli() >= floor }
                    retained.isEmpty() || state?.decision == ParticipantDecision.DECLINED ||
                        (store.collects(module) && SensorSampleSink(dao,
                            persistenceGuard = ResearchPersistenceGate.guard(context, module)).write(retained) is ModuleResult.Ok)
                }
            }
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "direct_boot_sample_drain",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DirectBootDrainWorker>().build(),
            )
        }
    }
}
