package com.openlattice.chronicle.services.lifecycle

import com.openlattice.chronicle.collection.lifecycle.LifecycleDedupeStore
import com.openlattice.chronicle.collection.lifecycle.isDuplicateLifecycleEvent
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.QueueEntry

internal class RoomLifecycleDedupeStore(private val db: ChronicleDb) : LifecycleDedupeStore {
    override fun shouldPersist(event: ExtractedUsageEvent, now: Long): Boolean =
        !isDuplicateLifecycleEvent(db.lifecycleStateDao().get(dedupeKey(event))?.observedAt, now)
}

private fun dedupeKey(event: ExtractedUsageEvent) = "last:${event.interactionType}:${event.activityClass.orEmpty()}"

internal fun sampledStateKey(activityClass: String?): String? = when (activityClass?.substringBefore(':')) {
    "battery" -> "battery_state"
    "network" -> "network_state"
    "power-save" -> "power_save_state"
    else -> null
}

internal fun persistLifecycleEntries(db: ChronicleDb, entries: List<QueueEntry>) = db.runInTransaction {
    val events = entries.flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()
    db.queueEntryData().insertEntries(entries)
    val now = System.currentTimeMillis()
    for (event in events) {
        db.lifecycleStateDao().upsert(LifecycleStateEntity(dedupeKey(event), "", now))
        sampledStateKey(event.activityClass)?.let { key ->
            db.lifecycleStateDao().upsert(LifecycleStateEntity(key, event.activityClass.orEmpty(), now))
        }
    }
}
