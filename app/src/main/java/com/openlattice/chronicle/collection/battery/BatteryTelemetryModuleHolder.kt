package com.openlattice.chronicle.collection.battery

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.ChronicleDb

/**
 * Holds the single app-scoped [BatteryTelemetryCollectionModule] instance and constructs
 * it with its production seams (see `docs/SENSING-EXPANSION-DESIGN.md` §5).
 *
 * **Why a holder, not an `object` field of `Context`.** Design §1C / refactor plan §6.1
 * guardrail 2 forbid storing an Android `Context` in a singleton field, and the module
 * carries diagnostics that should accumulate across poll ticks. This holder resolves
 * both: it builds the module **lazily on first use** from the application `Context`,
 * wires its seams — each of which keeps only an application-`Context` handle, never the
 * module — and then holds only the module.
 *
 * The seams wired here:
 *  - persistence → [BatterySampleSink] over `ChronicleDb.batterySampleDao()`;
 *  - battery reading → [AndroidBatterySampleSource] over the sticky
 *    `ACTION_BATTERY_CHANGED` broadcast;
 *  - enrollment check → `EnrollmentSettings.getParticipationStatus() == ENROLLED`,
 *    mirroring [com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleModuleHolder].
 *
 */
public object BatteryTelemetryModuleHolder {

    @Volatile private var instance: BatteryTelemetryCollectionModule? = null

    /**
     * Returns the shared [BatteryTelemetryCollectionModule], building it on first use
     * from the application context of [context]. Subsequent calls return the same
     * instance so its diagnostics accumulate across poll ticks.
     */
    public fun get(context: Context): BatteryTelemetryCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): BatteryTelemetryCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        return BatteryTelemetryCollectionModule(
            sink = BatterySampleSink(
                db.batterySampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext, CollectionModuleId.BATTERY_TELEMETRY),
            ),
            source = AndroidBatterySampleSource(appContext),
            enrolled = {
                EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
                    CollectionGate.collects(appContext, CollectionModuleId.BATTERY_TELEMETRY)
            },
        )
    }
}
