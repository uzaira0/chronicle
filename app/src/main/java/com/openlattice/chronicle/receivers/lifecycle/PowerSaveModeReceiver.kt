package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.INTERACTION_POWER_SAVE_MODE_OFF
import com.openlattice.chronicle.services.lifecycle.INTERACTION_POWER_SAVE_MODE_ON

class PowerSaveModeReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = PowerSaveModeReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
        try {
            handlePowerSave(context)
        } catch (error: Exception) {
            Log.e(TAG, "Power-save collection initialization unavailable", error)
            DistributionRestrictedRuntime.stopHardwareSensors(context)
        }
    }

    private fun handlePowerSave(context: Context) {
        val origin = com.openlattice.chronicle.collection.state.ResearchPersistenceGate.captureObservation(
            context, com.openlattice.chronicle.collection.CollectionModuleId.DEVICE_LIFECYCLE)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {
            Log.i(TAG, "Power save mode ON - stopping sensor collection")
            DeviceLifecycleEventRecorder.recordAsync(
                context,
                DeviceLifecycleEventRecorder.buildEvent(
                    "android.os.action.POWER_SAVE_MODE_CHANGED:on",
                    INTERACTION_POWER_SAVE_MODE_ON,
                    System.currentTimeMillis()
                ),
                origin,
            )
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS && SensorSettings(context).isEnabled()) {
                DistributionRestrictedRuntime.stopHardwareSensors(context)
            }
        } else {
            Log.i(TAG, "Power save mode OFF - restarting sensor collection")
            DeviceLifecycleEventRecorder.recordAsync(
                context,
                DeviceLifecycleEventRecorder.buildEvent(
                    "android.os.action.POWER_SAVE_MODE_CHANGED:off",
                    INTERACTION_POWER_SAVE_MODE_OFF,
                    System.currentTimeMillis()
                ),
                origin,
            )
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS && SensorSettings(context).isEnabled()) {
                DistributionRestrictedRuntime.startHardwareSensors(context)
            }
        }
    }
}
