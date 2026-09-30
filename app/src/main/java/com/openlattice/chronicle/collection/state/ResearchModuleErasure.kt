package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.content.Context
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.device.AndroidAppNetworkUsageSource
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.sensors.LAST_USAGE_QUERY_TIMESTAMP
import com.openlattice.chronicle.services.crypto.PayloadSealer
import com.openlattice.chronicle.storage.ChronicleDb

/** Non-table state is part of the same replayable erasure as the module's Room queue. */
internal fun eraseModuleAuxiliaryState(context: Context, module: CollectionModuleId) {
    when (module) {
        CollectionModuleId.USAGE_EVENTS -> {
            ChronicleDb.getInstance(context).openHelper.writableDatabase.execSQL("DELETE FROM usage_poll_checkpoints")
            checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().remove(LAST_USAGE_QUERY_TIMESTAMP).commit())
        }
        CollectionModuleId.USER_IDENTIFICATION -> {
            checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().remove(context.getString(R.string.current_user)).commit())
            val db = ChronicleDb.getInstance(context)
            db.runInTransaction {
                eraseSharedQueueModule(db, module)
                eraseSharedQueueModuleQuarantine(db, module)
            }
        }
        CollectionModuleId.DEVICE_LIFECYCLE -> listOf("chronicle_device_state", "chronicle_lifecycle_recorder").forEach {
            checkLocalStoreWrite(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
        }
        CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION -> DistributionRestrictedRuntime.eraseActivityRegistration(context, module)
        CollectionModuleId.QUESTIONNAIRE -> com.openlattice.chronicle.services.notifications.eraseSurveyArtifacts(context)
        CollectionModuleId.APP_NETWORK_USAGE -> AndroidAppNetworkUsageSource.clearCheckpoint(context)
        CollectionModuleId.HEALTH_CONNECT -> DistributionRestrictedRuntime.eraseHealthSource(context)
        else -> Unit
    }
    val types = when {
        SensorCollectionModules.isSensorModule(module) -> setOf(EncryptedPayloadType.SENSOR)
        module in setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.IN_APP_ACTIVITY_CLASS,
            CollectionModuleId.USER_IDENTIFICATION, CollectionModuleId.DEVICE_LIFECYCLE) -> setOf(EncryptedPayloadType.USAGE)
        module == CollectionModuleId.INTERACTION_EVENTS -> setOf(EncryptedPayloadType.INTERACTION)
        module == CollectionModuleId.AUDIO_ACTIVITY -> setOf(EncryptedPayloadType.AUDIO_ACTIVITY)
        module == CollectionModuleId.AUDIO_CONTENT -> setOf(EncryptedPayloadType.AUDIO_CONTENT)
        module == CollectionModuleId.NOTIFICATION_ACTIVITY -> setOf(EncryptedPayloadType.NOTIFICATION_ACTIVITY)
        module == CollectionModuleId.BATTERY_TELEMETRY -> setOf(EncryptedPayloadType.BATTERY)
        module == CollectionModuleId.SLEEP -> setOf(EncryptedPayloadType.SLEEP)
        module == CollectionModuleId.ACTIVITY_RECOGNITION -> setOf(EncryptedPayloadType.ACTIVITY_RECOGNITION)
        module == CollectionModuleId.HEALTH_CONNECT -> setOf(EncryptedPayloadType.HEALTH_CONNECT)
        module == CollectionModuleId.CONNECTIVITY_STATE -> setOf(EncryptedPayloadType.CONNECTIVITY_STATE)
        module == CollectionModuleId.APP_NETWORK_USAGE -> setOf(EncryptedPayloadType.APP_NETWORK_USAGE)
        module == CollectionModuleId.DEVICE_SETTINGS -> setOf(EncryptedPayloadType.DEVICE_SETTINGS)
        else -> emptySet()
    }
    if (types.isNotEmpty()) PayloadSealer.eraseSealedEnvelopes(types)
}

/** Runs on acknowledged withdrawal or distribution purge, after the shared epoch has rotated. */
internal fun eraseResearchSourceState(context: Context) {
    SensorCollectionModules.sensorModuleIds.forEach { module ->
        SensorCollectionModules.sensorTypeOf(module)?.let { com.openlattice.chronicle.collection.sink.SensorSampleWriter.discardSensorSamples(it.name) }
    }
    AndroidAppNetworkUsageSource.clearCheckpoint(context)
    DistributionRestrictedRuntime.eraseHealthSource(context)
    listOf("chronicle_device_state", "chronicle_lifecycle_recorder", "chronicle_process_exit_watermark").forEach {
        checkLocalStoreWrite(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
    }
    checkLocalStoreWrite(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().remove(LAST_USAGE_QUERY_TIMESTAMP).commit())
    com.openlattice.chronicle.services.notifications.eraseSurveyArtifacts(context)
}
