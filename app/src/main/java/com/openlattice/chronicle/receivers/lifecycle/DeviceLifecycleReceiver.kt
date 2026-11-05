package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.openlattice.chronicle.services.lifecycle.ACTION_CONNECTIVITY_CHANGE
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate

/** Broadcast-time device-state rows while a service registers it ([deviceLifecycleIntentFilter]); Battery Saver goes to its own receiver. */
class DeviceLifecycleReceiver : BroadcastReceiver() {
    private val powerSave = PowerSaveModeReceiver()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return powerSave.onReceive(context, intent)
        val origin = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)
        val events = if (intent.action == ACTION_CONNECTIVITY_CHANGE) DeviceStateSampler(context).observe(origin)
        else listOfNotNull(DeviceLifecycleEventRecorder.eventForBroadcast(intent))
        DeviceLifecycleEventRecorder.recordAsync(context, events, origin)
    }
}
