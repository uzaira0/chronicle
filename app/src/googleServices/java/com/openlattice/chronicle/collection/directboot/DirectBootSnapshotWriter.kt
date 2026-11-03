package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.SensorSettings

private val TAG = DirectBootSnapshotWriter::class.java.simpleName

/**
 * Rewrites the [DirectBootSensorSnapshot] from live consent: the configured sensors whose
 * [CollectionGate] is open, with their rate and duty cycle, and the enrollment's buffer key.
 * Runs only unlocked, on every normal-mode sensor service start or reconcile.
 */
object DirectBootSnapshotWriter {
    fun refresh(context: Context) {
        try {
            ResearchPersistenceGate.runIfActive(context) {
                val owner = ResearchPersistenceGate.captureOwner(context) ?: return@runIfActive false
                val settings = SensorSettings(context)
                val collectable = settings.getConfiguredSensors()
                    .filter { CollectionGate.collects(context, SensorCollectionModules.moduleFor(it)) }
                    .associateWith {
                        DirectBootSensorSnapshot.SensorConfig(settings.getSamplingRateHz(it),
                            settings.getDutyCycleActiveSeconds(it), settings.getDutyCyclePeriodSeconds(it))
                    }
                // The key must exist before the first locked boot needs it.
                KeystoreDirectBootRecordCipher.ensureKey()
                DirectBootSensorSnapshot(context).write(collectable, DirectBootSampleBuffer.ownerKey(owner))
                    .also { if (!it) Log.e(TAG, "Direct-boot snapshot commit failed") }
            }
        } catch (e: Exception) {
            // A stale or absent snapshot only means the next locked boot collects nothing.
            Log.e(TAG, "Direct-boot snapshot refresh failed", e)
        }
    }
}
