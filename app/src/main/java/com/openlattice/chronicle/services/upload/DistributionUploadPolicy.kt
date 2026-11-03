package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.state.dedicatedModuleTables
import com.openlattice.chronicle.collection.state.SharedUsageDisposition
import com.openlattice.chronicle.collection.state.sharedUsageDisposition
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.storage.UploadQueueTable

/** A module this distribution does not support keeps its queue; it is never sent. */
internal object DistributionUploadPolicy {
    val held: Set<CollectionModuleId> get() = CollectionModuleId.entries.filterNot(DistributionModulePolicy::supports).toSet()
    val sharedHeld: Set<CollectionModuleId> get() = held.filterTo(linkedSetOf()) { it in setOf(CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.DEVICE_LIFECYCLE, CollectionModuleId.IN_APP_ACTIVITY_CLASS, CollectionModuleId.USER_IDENTIFICATION) }
    val heldSensorTypes: List<String> get() = held.mapNotNull { SensorCollectionModules.sensorTypeOf(it)?.name }

    fun allows(module: CollectionModuleId): Boolean = module !in held
    fun holds(event: ExtractedUsageEvent): Boolean = sharedHeld.any {
        sharedUsageDisposition(it, event) != SharedUsageDisposition.KEEP
    }
    fun allows(table: UploadQueueTable): Boolean =
        dedicatedModuleTables.entries.firstOrNull { it.value == table }?.let { allows(it.key) } ?: true
}
