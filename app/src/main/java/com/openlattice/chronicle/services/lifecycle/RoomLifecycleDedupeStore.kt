package com.openlattice.chronicle.services.lifecycle

import com.openlattice.chronicle.collection.lifecycle.LifecycleDedupeStore
import com.openlattice.chronicle.collection.lifecycle.LIFECYCLE_DEDUPE_KEY_PREFIX
import com.openlattice.chronicle.collection.lifecycle.lifecycleDedupeKey
import com.openlattice.chronicle.collection.lifecycle.isDuplicateLifecycleEvent
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_DISCHARGING
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.QueueEntry

internal class RoomLifecycleDedupeStore(private val db: ChronicleDb) : LifecycleDedupeStore {
    override fun shouldPersist(event: ExtractedUsageEvent, now: Long): Boolean {
        val state = sampledState(event)
        if (state != null && db.lifecycleStateDao().get(state.first)?.value == state.second) return false
        return !isDuplicateLifecycleEvent(db.lifecycleStateDao().get(lifecycleDedupeKey(event))?.observedAt,
            event.timestamp.toInstant().toEpochMilli())
    }
}

private const val BATTERY_STATE_KEY = "battery_state"

private val SAMPLED_STATE_KEYS = mapOf(
    "network" to "network_state",
    "power-save" to "power_save_state",
)

/**
 * The (key, value) of the sampled state [event] reports, or null. Charging state has one owner: plug broadcasts
 * and the battery sampler both report it as the Charging/Discharging type, so a sample that agrees with the last plug
 * row, or only crosses a level bucket, records nothing.
 */
internal fun sampledState(event: ExtractedUsageEvent): Pair<String, String>? = when (event.interactionType) {
    INTERACTION_BATTERY_CHARGING, INTERACTION_BATTERY_DISCHARGING -> BATTERY_STATE_KEY to event.interactionType
    else -> SAMPLED_STATE_KEYS[event.activityClass?.substringBefore(':')]?.let { it to event.activityClass.orEmpty() }
}

internal fun eraseLifecycleObservations(db: ChronicleDb) =
    db.lifecycleStateDao().clearObservations(LIFECYCLE_DEDUPE_KEY_PREFIX, SAMPLED_STATE_KEYS.values + BATTERY_STATE_KEY)

internal fun persistLifecycleEntries(db: ChronicleDb, entries: List<QueueEntry>) = db.runInTransaction {
    val events = entries.flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()
    db.queueEntryData().insertEntries(entries)
    for (event in events) {
        val observedAt = event.timestamp.toInstant().toEpochMilli()
        db.lifecycleStateDao().upsert(LifecycleStateEntity(lifecycleDedupeKey(event), "", observedAt))
        sampledState(event)?.let { (key, value) -> db.lifecycleStateDao().upsert(LifecycleStateEntity(key, value, observedAt)) }
    }
}
