package com.openlattice.chronicle.collection.device

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.healthMetricSampleDao

/** Research/Open-only holder for the optional Health Connect implementation. */
public object HealthMetricModuleHolder {
    @Volatile private var instance: HealthMetricCollectionModule? = null

    public fun get(context: Context): HealthMetricCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): HealthMetricCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        return HealthMetricCollectionModule(
            sink = HealthMetricSampleSink(
                db.healthMetricSampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(
                    appContext,
                    CollectionModuleId.HEALTH_CONNECT,
                ),
            ),
            source = AndroidHealthMetricSource(appContext),
            enrolled = {
                EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
                    CollectionGate.collects(appContext, CollectionModuleId.HEALTH_CONNECT)
            },
        )
    }
}
