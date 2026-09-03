package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.services.sensors.HardwareSensorServiceControllerImpl

/**
 * Holds the single app-scoped [HardwareSensorsCollectionModule] instance (mirrors
 * `DeviceLifecycleModuleHolder`, refactor plan §9).
 *
 * [HardwareSensorsCollectionModule] carries mutable diagnostics state (the last
 * start/stop result, the service running flag) that must accumulate across calls — a
 * fresh module per call would reset it. The module holds no Android `Context`, so a
 * plain process-wide singleton is safe here; unlike the lifecycle holder there is no
 * `Context` to keep out of a singleton field.
 *
 * The holder lives in `:app` because it wires the `:app`-side
 * [HardwareSensorServiceControllerImpl] into the module — the module itself depends only
 * on the [HardwareSensorServiceController] interface.
 *
 */
public object HardwareSensorsModuleHolder {

    /** The shared [HardwareSensorsCollectionModule]. */
    public val module: HardwareSensorsCollectionModule by lazy {
        HardwareSensorsCollectionModule(serviceController = HardwareSensorServiceControllerImpl)
    }
}
