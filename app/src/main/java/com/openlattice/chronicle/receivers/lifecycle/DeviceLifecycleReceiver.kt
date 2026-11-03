package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openlattice.chronicle.services.lifecycle.ACTION_CONNECTIVITY_CHANGE
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate

class DeviceLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val origin = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)
        val events = if (intent.action == ACTION_CONNECTIVITY_CHANGE) DeviceStateSampler(context).observe(origin)
        else listOfNotNull(DeviceLifecycleEventRecorder.eventForBroadcast(intent))
        DeviceLifecycleEventRecorder.recordAsync(context, events, origin)
    }
}
