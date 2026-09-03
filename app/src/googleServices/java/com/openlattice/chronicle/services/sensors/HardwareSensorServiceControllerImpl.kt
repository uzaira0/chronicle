package com.openlattice.chronicle.services.sensors

import android.content.Context
import com.openlattice.chronicle.collection.sensors.HardwareSensorServiceController

/**
 * `:app`-module implementation of [HardwareSensorServiceController].
 *
 * Forwards directly to the `HardwareSensorService` companion (`startService` /
 * `stopService`) — the same calls the collection module made before the dependency was
 * inverted. This is the only sanctioned caller of those companion methods outside the
 * service itself (design §1C.4, guardrail #5).
 */
object HardwareSensorServiceControllerImpl : HardwareSensorServiceController {

    override fun startService(context: Context) {
        HardwareSensorService.startService(context)
    }

    override fun stopService(context: Context) {
        HardwareSensorService.stopService(context)
    }
}
