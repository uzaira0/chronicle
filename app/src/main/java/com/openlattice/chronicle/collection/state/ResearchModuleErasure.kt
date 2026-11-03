package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.preferences.CURRENT_USER
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.device.AndroidAppNetworkUsageSource
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.sensors.LAST_USAGE_QUERY_TIMESTAMP
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.services.upload.PROCESS_EXIT_PREFS
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.services.notifications.eraseSurveyArtifacts
import com.openlattice.chronicle.services.lifecycle.eraseLifecycleObservations
import com.openlattice.chronicle.services.upload.clearAccessLossEpisodes

/** Non-table state is part of the same replayable erasure as the module's Room queue. */
internal fun eraseModuleAuxiliaryState(context: Context, module: CollectionModuleId) {
    when (module) {
        CollectionModuleId.USAGE_EVENTS -> {
            ChronicleDb.getInstance(context).openHelper.writableDatabase.execSQL("DELETE FROM usage_poll_checkpoints")
            checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().remove(LAST_USAGE_QUERY_TIMESTAMP).commit())
        }
        CollectionModuleId.USER_IDENTIFICATION -> {
            checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().remove(CURRENT_USER).commit())
            val db = ChronicleDb.getInstance(context)
            db.runInTransaction {
                eraseSharedQueueModule(db, module)
                eraseSharedQueueModuleQuarantine(db, module)
            }
        }
        CollectionModuleId.DEVICE_LIFECYCLE -> {
            eraseLifecycleObservations(ChronicleDb.getInstance(context))
            listOf("chronicle_device_state", "chronicle_lifecycle_recorder").forEach {
                checkLocalStoreWrite(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
            }
        }
        CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION -> DistributionRestrictedRuntime.eraseActivityRegistration(context, module)
        CollectionModuleId.QUESTIONNAIRE -> eraseSurveyArtifacts(context)
        CollectionModuleId.APP_NETWORK_USAGE -> AndroidAppNetworkUsageSource.clearCheckpoint(context)
        CollectionModuleId.HEALTH_CONNECT -> DistributionRestrictedRuntime.eraseHealthSource(context)
        else -> Unit
    }
}

/** Runs on acknowledged withdrawal or distribution purge, after the shared epoch has rotated. */
internal fun eraseResearchSourceState(context: Context) {
    clearAccessLossEpisodes(context)
    CaptureRegistrationStatus.clear(context)
    ChronicleDb.getInstance(context).lifecycleStateDao().clear()
    SensorCollectionModules.sensorModuleIds.forEach { module ->
        SensorCollectionModules.sensorTypeOf(module)?.let { SensorSampleWriter.discardSensorSamples(
            it.name) }
    }
    AndroidAppNetworkUsageSource.clearCheckpoint(context)
    DistributionRestrictedRuntime.eraseHealthSource(context)
    listOf(
        "chronicle_device_state",
        "chronicle_lifecycle_recorder",
        PROCESS_EXIT_PREFS,
    ).forEach {
        checkLocalStoreWrite(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
        checkLocalStoreWrite(context.createDeviceProtectedStorageContext()
            .getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
    }
    checkLocalStoreWrite(ExpansionPullSchedule.erase(context))
    checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().remove(LAST_USAGE_QUERY_TIMESTAMP).commit())
    eraseSurveyArtifacts(context)
}
