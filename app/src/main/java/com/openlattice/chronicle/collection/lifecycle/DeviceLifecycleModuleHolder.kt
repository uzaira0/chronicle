package com.openlattice.chronicle.collection.lifecycle

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils

/**
 * Holds the single app-scoped [DeviceLifecycleCollectionModule] instance and constructs
 * it with its production seams (refactor plan §8.2 step 1–11).
 *
 * **Why a holder, not an `object` field of `Context`.** The module must carry mutable
 * diagnostics that accumulate across broadcasts — the dropped-duplicate counter and the
 * last-event marker. A fresh module per `recordAsync` call would reset that counter
 * every broadcast, so exactly one shared instance is needed. At the same time, design
 * §1C / refactor plan §6.1 guardrail 2 forbid storing an Android `Context` in a
 * singleton field. This holder resolves the tension: it builds the module **lazily on
 * first use** from the application `Context`, wires its seams (each of which resolves
 * the `Context` it needs at construction and keeps only a `Context`-free handle), and
 * then holds only the module — never a `Context`.
 *
 * The seams wired here mirror the legacy `DeviceLifecycleEventRecorder.recordNow`
 * exactly:
 *  - persistence → [LifecycleEventSink] over `ChronicleDb.queueEntryData()`;
 *  - dedupe → [PrefsLifecycleDedupeStore] over the `chronicle_lifecycle_recorder` prefs;
 *  - enrollment check → `EnrollmentSettings.getParticipationStatus() == ENROLLED`;
 *  - queue-size update → `Utils.updateUploadQueueSize`.
 *
 */
public object DeviceLifecycleModuleHolder {

    @Volatile private var instance: DeviceLifecycleCollectionModule? = null

    /**
     * Returns the shared [DeviceLifecycleCollectionModule], building it on first use from
     * the application context of [context]. Subsequent calls return the same instance so
     * its dropped-duplicate / last-event diagnostics accumulate across broadcasts.
     */
    public fun get(context: Context): DeviceLifecycleCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): DeviceLifecycleCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        return DeviceLifecycleCollectionModule(
            sink = LifecycleEventSink(
                db.queueEntryData(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext, CollectionModuleId.DEVICE_LIFECYCLE),
            ),
            dedupeStore = PrefsLifecycleDedupeStore(appContext),
            enrolled = {
                EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
                    CollectionGate.collects(appContext, CollectionModuleId.DEVICE_LIFECYCLE)
            },
            updateQueueSize = { depth -> Utils.updateUploadQueueSize(appContext, depth) },
            serializeQueueEntry = { data -> JsonSerializer.serializeQueueEntry(data) },
        )
    }
}
