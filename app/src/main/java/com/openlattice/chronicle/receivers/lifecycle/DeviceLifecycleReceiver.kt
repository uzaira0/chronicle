package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openlattice.chronicle.services.lifecycle.ACTION_CONNECTIVITY_CHANGE
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler

class DeviceLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeviceLifecycleEventRecorder.recordObserved(context) {
            if (intent.action == ACTION_CONNECTIVITY_CHANGE) DeviceStateSampler(context).poll()
            else listOfNotNull(DeviceLifecycleEventRecorder.eventForBroadcast(intent))
        }
    }
}
