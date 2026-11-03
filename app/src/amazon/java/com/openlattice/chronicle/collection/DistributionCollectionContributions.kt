package com.openlattice.chronicle.collection

import android.content.Context
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.services.upload.RestrictedPendingUploadCounts
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.collection.state.CollectionConsentCopy

/**
 * Amazon contribution. Restricted research, activity-recognition, audio, hardware-sensor, and
 * Health Connect implementations are not source inputs to the Amazon build.
 */
internal object DistributionCollectionContributions {
    /** Amazon carries no restricted-module consent copy. */
    val consentResources: Map<CollectionModuleId, CollectionConsentCopy.Resources> = emptyMap()

    fun collectAdditionalSamples(
        context: Context,
        schedule: ExpansionPullSchedule?,
        nowMs: Long,
    ): Boolean = true

    fun uploadAdditionalStreams(
        delegate: ExpansionUploadWorkerDelegate,
        servers: List<UploadServerEntity>,
    ): Map<UploadQueueTable, () -> Int> = emptyMap()

    /** Restricted queues do not exist as an operational surface in public artifacts. */
    fun pendingUploadCounts(db: ChronicleDb): RestrictedPendingUploadCounts? = null
}
