package com.openlattice.chronicle.collection.sensors

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult

private const val TAG = "HardwareSensorsCollectionModule"

/**
 * The hardware sensors data collection module (design §1A.2 `hardware_sensors`,
 * privacy class `PHYSICAL_TELEMETRY`, refactor plan §9).
 *
 * Phase 6A wraps the hardware-sensor runtime behind the [DataCollectionModule] boundary.
 * Hardware sensors is a **push module** — it runs a foreground [HardwareSensorService]:
 *
 *  - [start] starts the foreground service ([HardwareSensorServiceController.startService]);
 *  - [stop] stops it ([HardwareSensorServiceController.stopService]);
 *  - [poll] is a no-op — there is no pull step ([ModuleResult.Skipped]);
 *  - [flush] is a no-op at the module level — the service's [SensorRuntimeController]
 *    owns the buffer and flushes it on the duty cycle and on destroy. The module does
 *    not hold the controller (the controller lives inside the service process), so it
 *    cannot flush it from here.
 *
 * The actual sensor runtime (duty cycle, buffer, power-save degraded mode,
 * critical-battery stop, sensor registration, max-report-latency) lives in
 * [SensorRuntimeController], driven by the service shell. This module is the registry-
 * facing handle: it declares the stable [id] / [privacyClass] and exposes lifecycle
 * control plus diagnostics.
 *
 * **`HardwareSensorService.startService`/`stopService` must be called only through this
 * module / the module manager** (design §1C.4, guardrail #5). The service start/stop is
 * reached through the injected [HardwareSensorServiceController] so this module carries
 * no dependency on the `:app`-module `HardwareSensorService`. Phase 6 routes the module
 * here; the legacy direct callers in `SensorSettingsRefreshWorker` are migrated behind
 * the Phase 6B switch.
 *
 * `PHYSICAL_TELEMETRY` is never enabled implicitly (design §1A.4) — whether this module
 * is started is decided by [com.openlattice.chronicle.collection.settings.CollectionSettingsResolver],
 * not by this class. The module simply executes the start/stop it is told to.
 *
 * This is a plain class holding only a clock, a logger and the service controller — no
 * Android `Context`; the `Context` is passed per call.
 *
 */
public class HardwareSensorsCollectionModule(
    private val serviceController: HardwareSensorServiceController,
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.HARDWARE_SENSORS

    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    init {
        require(privacyClass == id.privacyClass) {
            "HardwareSensorsCollectionModule.privacyClass must equal id.privacyClass"
        }
    }

    // ----- module diagnostics state (design §1B.3 — redaction-safe operational telemetry) -----
    @Volatile private var lastRunEpochMs: Long? = null
    @Volatile private var lastResult: ModuleResult = ModuleResult.Skipped("not yet run")
    @Volatile private var lastError: String? = null
    @Volatile private var running: Boolean = false

    /**
     * Starts the hardware sensor foreground service. The service builds its own
     * [SensorRuntimeController] in `onCreate`; this only triggers the start.
     */
    override fun start(context: Context): ModuleResult {
        lastRunEpochMs = clock.nowEpochMs()
        return try {
            serviceController.startService(context)
            running = true
            lastError = null
            lastResult = ModuleResult.Ok(0)
            log.info(TAG, "Hardware sensor service start requested")
            lastResult
        } catch (e: Exception) {
            lastError = "sensor service start failed: ${e.javaClass.simpleName}"
            lastResult = ModuleResult.Failed(e, redactedMessage = lastError!!)
            log.error(TAG, "Failed to start hardware sensor service", e)
            lastResult
        }
    }

    /** Stops the hardware sensor foreground service. */
    override fun stop(context: Context): ModuleResult {
        lastRunEpochMs = clock.nowEpochMs()
        return try {
            serviceController.stopService(context)
            running = false
            lastError = null
            lastResult = ModuleResult.Ok(0)
            log.info(TAG, "Hardware sensor service stop requested")
            lastResult
        } catch (e: Exception) {
            lastError = "sensor service stop failed: ${e.javaClass.simpleName}"
            lastResult = ModuleResult.Failed(e, redactedMessage = lastError!!)
            log.error(TAG, "Failed to stop hardware sensor service", e)
            lastResult
        }
    }

    /** No-op: hardware sensors is a push module, not a pull module. */
    override fun poll(context: Context, window: CollectionWindow): ModuleResult =
        ModuleResult.Skipped("hardware_sensors is a push module")

    /**
     * No-op at the module level: the in-process [SensorRuntimeController] owns the buffer
     * and flushes it on the duty cycle and on service destroy. The module cannot reach
     * the controller from here.
     */
    override fun flush(context: Context): ModuleResult =
        ModuleResult.Skipped("hardware_sensors buffer is flushed by the in-service runtime controller")

    override fun status(): CollectionModuleStatus = when (lastResult) {
        is ModuleResult.Failed -> CollectionModuleStatus.FAILED
        is ModuleResult.Ok -> if (running) CollectionModuleStatus.ACTIVE else CollectionModuleStatus.IDLE
        is ModuleResult.Retry -> CollectionModuleStatus.DEGRADED
        is ModuleResult.Skipped -> CollectionModuleStatus.IDLE
    }

    override fun diagnostics(): CollectionModuleDiagnostics = CollectionModuleDiagnostics(
        moduleId = id,
        privacyClass = privacyClass,
        lastRunEpochMs = lastRunEpochMs,
        lastResult = lastResult.label,
        itemsCollected = 0,
        queueDepth = 0,
        lastError = lastError,
        redactedParticipantRef = null,
        // The buffer depth, flush counts and destroy-flush status live inside the
        // in-service SensorRuntimeController and are not reachable from this handle;
        // surface the service running state until cross-process diagnostics exist.
        notTracked = setOf(
            "serviceRunning=$running",
            "bufferDepth",
            "samplesFlushed",
            "destroyFlushStatus",
        ),
    )
}
