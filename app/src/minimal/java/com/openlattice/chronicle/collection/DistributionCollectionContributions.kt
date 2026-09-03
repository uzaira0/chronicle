package com.openlattice.chronicle.collection

import android.content.Context
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.services.upload.RestrictedPendingUploadCounts
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity

/**
 * Minimal public-distribution contribution. Restricted research, activity-recognition, audio,
 * hardware-sensor, and Health Connect implementations are not source inputs to Play or Amazon.
 */
internal object DistributionCollectionContributions {
    val moduleSuppliers: Map<CollectionModuleId, (Context) -> DataCollectionModule> = emptyMap()

    fun collectAdditionalSamples(
        context: Context,
        schedule: ExpansionPullSchedule?,
        nowMs: Long,
    ) = Unit

    fun uploadAdditionalStreams(
        delegate: ExpansionUploadWorkerDelegate,
        servers: List<UploadServerEntity>,
    ): Int = 0

    fun purgeAdditionalSamples(db: ChronicleDb, cutoff: String): Int = 0

    /** Restricted queues do not exist as an operational surface in public artifacts. */
    fun pendingUploadCounts(db: ChronicleDb): RestrictedPendingUploadCounts? = null
}
