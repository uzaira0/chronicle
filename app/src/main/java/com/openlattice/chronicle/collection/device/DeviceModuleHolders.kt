package com.openlattice.chronicle.collection.device

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.sink.AppNetworkUsageSampleSink
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.collection.sink.DeviceSettingsSampleSink
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordAbandonedGateBatch
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.ChronicleDb

/**
 * App-scoped holders for the three minimal platform-API pull modules
 * (connectivity_state, device_settings, app_network_usage). Each mirrors
 * `BatteryTelemetryModuleHolder`: it builds the module lazily from the application context, wiring
 * the sink, the production source, and the enrollment+consent gate, and then holds only the module
 * (never a `Context`).
 */
private fun enrolledAndConsented(appContext: Context, moduleId: CollectionModuleId): () -> Boolean = {
    EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
        CollectionGate.collects(appContext, moduleId)
}

public object ConnectivityStateModuleHolder {
    @Volatile private var instance: ConnectivityStateCollectionModule? = null

    public fun get(context: Context): ConnectivityStateCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): ConnectivityStateCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        return ConnectivityStateCollectionModule(
            sink = ConnectivityStateSampleSink(
                db.connectivityStateSampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext, CollectionModuleId.CONNECTIVITY_STATE),
            ),
            source = AndroidConnectivityStateSource(appContext),
            enrolled = enrolledAndConsented(appContext, CollectionModuleId.CONNECTIVITY_STATE),
            ownerForSample = { ResearchPersistenceGate.captureOwner(appContext) },
            accountWriteResult = { owner, result, count ->
                recordAbandonedGateBatch(appContext, owner, LocalUploadModuleFamily.CONNECTIVITY, result, count)
            },
        )
    }
}

public object DeviceSettingsModuleHolder {
    @Volatile private var instance: DeviceSettingsCollectionModule? = null

    public fun get(context: Context): DeviceSettingsCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): DeviceSettingsCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        return DeviceSettingsCollectionModule(
            sink = DeviceSettingsSampleSink(
                db.deviceSettingsSampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext, CollectionModuleId.DEVICE_SETTINGS),
            ),
            source = AndroidDeviceSettingsSource(appContext),
            enrolled = enrolledAndConsented(appContext, CollectionModuleId.DEVICE_SETTINGS),
            ownerForSample = { ResearchPersistenceGate.captureOwner(appContext) },
            accountWriteResult = { owner, result, count ->
                recordAbandonedGateBatch(appContext, owner, LocalUploadModuleFamily.DEVICE_SETTINGS, result, count)
            },
        )
    }
}

public object AppNetworkUsageModuleHolder {
    @Volatile private var instance: AppNetworkUsageCollectionModule? = null

    public fun get(context: Context): AppNetworkUsageCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): AppNetworkUsageCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        return AppNetworkUsageCollectionModule(
            // No gate-drop count: a refused window is re-read on the next run (rejectRead), not lost.
            sink = AppNetworkUsageSampleSink(
                db.appNetworkUsageSampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext, CollectionModuleId.APP_NETWORK_USAGE),
            ),
            source = AndroidAppNetworkUsageSource(appContext),
            enrolled = enrolledAndConsented(appContext, CollectionModuleId.APP_NETWORK_USAGE),
        )
    }
}
