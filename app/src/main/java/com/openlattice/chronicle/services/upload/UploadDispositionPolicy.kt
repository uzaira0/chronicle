package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.state.SharedUsageDisposition
import com.openlattice.chronicle.collection.state.sharedUsageDisposition
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.storage.ChronicleDb

/** Snapshot read under the uploader's privacy lease; a held queue is never acknowledged. */
internal class UploadDispositionPolicy(db: ChronicleDb) {
    val held = db.collectionModuleStateDao().getAll().mapNotNullTo(linkedSetOf()) { state ->
        CollectionModuleId.fromIdOrNull(state.moduleId)?.takeIf {
            state.lastDisposition == CollectionDataDisposition.HOLD_PENDING.id &&
                (!state.serverEnabled || state.decision != "ACCEPTED")
        }
    }
    val sharedHeld = held.filterTo(linkedSetOf()) { it in setOf(CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.DEVICE_LIFECYCLE, CollectionModuleId.IN_APP_ACTIVITY_CLASS, CollectionModuleId.USER_IDENTIFICATION) }
    val heldSensorTypes = held.mapNotNull { SensorCollectionModules.sensorTypeOf(it)?.name }

    fun allows(module: CollectionModuleId): Boolean = module !in held
    fun holds(event: ExtractedUsageEvent): Boolean = sharedHeld.any {
        sharedUsageDisposition(it, event) != SharedUsageDisposition.KEEP
    }
    fun allows(payload: EncryptedPayloadType): Boolean = when (payload) {
        EncryptedPayloadType.BATTERY -> allows(CollectionModuleId.BATTERY_TELEMETRY)
        EncryptedPayloadType.CONNECTIVITY_STATE -> allows(CollectionModuleId.CONNECTIVITY_STATE)
        EncryptedPayloadType.DEVICE_SETTINGS -> allows(CollectionModuleId.DEVICE_SETTINGS)
        EncryptedPayloadType.APP_NETWORK_USAGE -> allows(CollectionModuleId.APP_NETWORK_USAGE)
        EncryptedPayloadType.HEALTH_CONNECT -> allows(CollectionModuleId.HEALTH_CONNECT)
        EncryptedPayloadType.SLEEP -> allows(CollectionModuleId.SLEEP)
        EncryptedPayloadType.ACTIVITY_RECOGNITION -> allows(CollectionModuleId.ACTIVITY_RECOGNITION)
        EncryptedPayloadType.AUDIO_ACTIVITY -> allows(CollectionModuleId.AUDIO_ACTIVITY)
        EncryptedPayloadType.AUDIO_CONTENT -> allows(CollectionModuleId.AUDIO_CONTENT)
        EncryptedPayloadType.NOTIFICATION_ACTIVITY -> allows(CollectionModuleId.NOTIFICATION_ACTIVITY)
        EncryptedPayloadType.INTERACTION -> allows(CollectionModuleId.INTERACTION_EVENTS)
        else -> true
    }
}
