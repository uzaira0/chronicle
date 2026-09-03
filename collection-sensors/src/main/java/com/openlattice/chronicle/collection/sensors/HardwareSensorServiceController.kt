package com.openlattice.chronicle.collection.sensors

import android.content.Context

/**
 * Abstraction over starting and stopping the hardware-sensor foreground service.
 *
 * `HardwareSensorService` is an `:app`-module Android `Service` — a collection library
 * module cannot depend on it without creating a `collection -> :app` edge. This interface
 * inverts that dependency: it is declared in the collection package, and `:app` provides
 * the concrete implementation that drives the real `HardwareSensorService` companion
 * (`startService` / `stopService`).
 *
 * [HardwareSensorsCollectionModule] takes this interface; the `:app`-side
 * `HardwareSensorsModuleHolder` wires the real implementation. Behaviour is unchanged —
 * the calls forwarded are exactly the historical `HardwareSensorService.startService` /
 * `HardwareSensorService.stopService`.
 */
interface HardwareSensorServiceController {

    /** Starts the hardware-sensor foreground service. */
    fun startService(context: Context)

    /** Stops the hardware-sensor foreground service. */
    fun stopService(context: Context)
}
