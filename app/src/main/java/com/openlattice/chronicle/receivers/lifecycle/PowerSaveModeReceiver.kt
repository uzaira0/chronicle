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
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {
            Log.i(TAG, "Power save mode ON - stopping sensor collection")
            DeviceLifecycleEventRecorder.recordAsync(
                context,
                DeviceLifecycleEventRecorder.buildEvent(
                    "android.os.action.POWER_SAVE_MODE_CHANGED:on",
                    INTERACTION_POWER_SAVE_MODE_ON,
                    System.currentTimeMillis()
                )
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
                )
            )
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS && SensorSettings(context).isEnabled()) {
                DistributionRestrictedRuntime.startHardwareSensors(context)
            }
        }
    }
}
