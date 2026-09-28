package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_DISCHARGING
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_OKAY
import com.openlattice.chronicle.constants.INTERACTION_LOW_MEMORY
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordPolicyErasureCountInTransaction
import com.openlattice.chronicle.services.lifecycle.INTERACTION_NETWORK_CONNECTED
import com.openlattice.chronicle.services.lifecycle.INTERACTION_NETWORK_DISCONNECTED
import com.openlattice.chronicle.services.lifecycle.INTERACTION_POWER_SAVE_MODE_ON
import com.openlattice.chronicle.services.lifecycle.INTERACTION_POWER_SAVE_MODE_OFF
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.QueueEntry
import java.time.OffsetDateTime

private val LIFECYCLE_INTERACTIONS = setOf(
    INTERACTION_BATTERY_CHARGING, INTERACTION_BATTERY_DISCHARGING,
    INTERACTION_BATTERY_LOW, INTERACTION_BATTERY_OKAY, INTERACTION_LOW_MEMORY,
    INTERACTION_NETWORK_CONNECTED, INTERACTION_NETWORK_DISCONNECTED,
    INTERACTION_POWER_SAVE_MODE_ON, INTERACTION_POWER_SAVE_MODE_OFF,
)

internal enum class SharedUsageDisposition { KEEP, ERASE, REDACT_ACTIVITY_CLASS }

internal fun sharedUsageDisposition(
    moduleId: CollectionModuleId,
    event: ExtractedUsageEvent,
): SharedUsageDisposition {
    val lifecycle = event.appPackageName == ANDROID_SYSTEM_PACKAGE &&
        event.interactionType in LIFECYCLE_INTERACTIONS
    return when (moduleId) {
        CollectionModuleId.USAGE_EVENTS ->
            if (lifecycle) SharedUsageDisposition.KEEP else SharedUsageDisposition.ERASE
        CollectionModuleId.DEVICE_LIFECYCLE ->
            if (lifecycle) SharedUsageDisposition.ERASE else SharedUsageDisposition.KEEP
        CollectionModuleId.IN_APP_ACTIVITY_CLASS ->
            if (!lifecycle && event.activityClass != null) SharedUsageDisposition.REDACT_ACTIVITY_CLASS
            else SharedUsageDisposition.KEEP
        else -> SharedUsageDisposition.KEEP
    }
}

/** Runs under the caller's exclusive queue mutation and Room transaction. */
internal fun eraseSharedQueueModule(db: ChronicleDb, moduleId: CollectionModuleId) {
    val queue = db.queueEntryData()
    var cursorTimestamp = Long.MIN_VALUE
    var cursorId = Long.MIN_VALUE
    while (true) {
        val page = queue.getEntriesAfter(cursorTimestamp, cursorId, 500)
        if (page.isEmpty()) break
        page.forEach { entry ->
            val mapped = runCatching { JsonSerializer.deserializeQueueEntry(entry.data) }.getOrNull()
            val events = mapped?.map { it as? ExtractedUsageEvent }
            if (events == null || events.any { it == null }) {
                quarantineAmbiguousSharedRow(db, entry)
            } else {
                val originals = events.filterNotNull()
                val erased = mutableListOf<ExtractedUsageEvent>()
                val kept = mutableListOf<ExtractedUsageEvent>()
                originals.forEach { event ->
                    when (sharedUsageDisposition(moduleId, event)) {
                        SharedUsageDisposition.KEEP -> kept += event
                        SharedUsageDisposition.ERASE -> erased += event
                        SharedUsageDisposition.REDACT_ACTIVITY_CLASS -> {
                            erased += event
                            kept += event.copy(activityClass = null)
                        }
                    }
                }
                if (erased.isNotEmpty()) {
                    recordPolicyErasureCountInTransaction(
                        db, erased.size.toLong(), LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        LocalOperationalIssue.MODULE_POLICY_ERASED,
                    )
                    queue.deleteEntry(entry)
                    if (kept.isNotEmpty()) {
                        queue.insertEntry(QueueEntry(entry.writeTimestamp, entry.id,
                            JsonSerializer.serializeQueueEntry(ChronicleData(kept))))
                    }
                }
            }
        }
        val last = page.last()
        cursorTimestamp = last.writeTimestamp
        cursorId = last.id
    }
}

private fun quarantineAmbiguousSharedRow(db: ChronicleDb, entry: QueueEntry) {
    val sourceId = "${entry.writeTimestamp}:${entry.id}"
    val server = db.uploadServerDao().getConfiguredServer()
    val inserted = db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
        id = "dataQueue:$sourceId", sourceTable = "dataQueue", sourceId = sourceId,
        studyId = server?.studyId, participantId = server?.participantId,
        deviceId = server?.sourceDeviceId, rawData = entry.data,
        reason = "SAMPLE_QUARANTINED", createdAt = OffsetDateTime.now().toString(),
    ))
    queueDelete(db, entry)
    if (inserted != -1L) {
        recordPolicyErasureCountInTransaction(
            db, 1L, LocalUploadModuleFamily.USAGE_LIFECYCLE,
            LocalOperationalIssue.SAMPLE_QUARANTINED,
        )
    }
}

private fun queueDelete(db: ChronicleDb, entry: QueueEntry) {
    db.queueEntryData().deleteEntry(entry)
}
