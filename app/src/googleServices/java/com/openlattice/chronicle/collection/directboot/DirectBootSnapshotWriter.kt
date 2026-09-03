package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.SensorSettings

private val TAG = DirectBootSnapshotWriter::class.java.simpleName

/**
 * Rewrites the [DirectBootSensorSnapshot] from live state: the study-configured sensors
 * ([SensorSettings]) filtered through each sensor's [CollectionGate] (server-enabled AND
 * participant-acknowledged), with each survivor's rate/duty. Runs only in unlocked mode —
 * both sources live in credential-encrypted storage.
 *
 * Called from `HardwareSensorService` on every normal-mode start/reconcile — the same
 * moments consent toggles and settings syncs already re-issue `startService` — so the
 * snapshot tracks gate state without a call site per consent/settings writer.
 */
object DirectBootSnapshotWriter {

    fun refresh(context: Context) {
        try {
            val sensorSettings = SensorSettings(context)
            val collectable = sensorSettings.getConfiguredSensors()
                .filter { sensor ->
                    CollectionGate.collects(context, SensorCollectionModules.moduleFor(sensor))
                }
                .associateWith { sensor ->
                    DirectBootSensorSnapshot.SensorConfig(
                        samplingRateHz = sensorSettings.getSamplingRateHz(sensor),
                        dutyCycleActiveSeconds = sensorSettings.getDutyCycleActiveSeconds(sensor),
                        dutyCyclePeriodSeconds = sensorSettings.getDutyCyclePeriodSeconds(sensor),
                    )
                }
            if (!DirectBootSensorSnapshot(context).write(collectable)) {
                Log.e(TAG, "Direct-boot snapshot commit failed")
                return
            }
            // Make sure the buffer key exists before the first locked boot needs it.
            KeystoreDirectBootRecordCipher.ensureKey()
            Log.i(TAG, "Direct-boot snapshot updated: ${collectable.size} collectable sensor(s)")
        } catch (e: Exception) {
            // Snapshot maintenance must never take down the sensor service; a stale/absent
            // snapshot only means the next locked boot fails closed (no pre-unlock collection).
            Log.e(TAG, "Direct-boot snapshot refresh failed", e)
        }
    }
}
