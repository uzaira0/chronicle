package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler

class PowerSaveModeReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = PowerSaveModeReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
        val origin = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)
        // The sampler's state value, so the next connectivity/sync snapshot sees this transition as recorded.
        val event = DeviceStateSampler(context).powerSaveEvent()
        Log.i(TAG, "Battery Saver ${event.activityClass}; the sensor runtime adjusts its cadence")
        DeviceLifecycleEventRecorder.recordAsync(context, event, origin)
    }
}
