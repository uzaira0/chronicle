package com.openlattice.chronicle.collection.sensors

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Deferred-execution seam for the sensor duty cycle (refactor plan §9.1 step 8).
 *
 * The legacy `HardwareSensorService` drove its duty cycle directly off a
 * [ScheduledExecutorService]: `scheduler.schedule({...}, activeSeconds, SECONDS)`. That
 * is impossible to unit-test deterministically — the test would have to sleep for real
 * wall-clock seconds. [SensorRuntimeController] schedules its active/idle phases through
 * this interface so a JVM unit test can supply a [ManualSensorRuntimeScheduler] that runs
 * tasks synchronously and lets the test step the duty cycle by hand.
 *
 * Production wires [ExecutorSensorRuntimeScheduler], which preserves the exact legacy
 * behaviour: a single-thread scheduled executor, `schedule` after a delay in seconds, and
 * `shutdownNow` on stop.
 *
 */
public interface SensorRuntimeScheduler {

    /** Runs [task] on the scheduler thread after [delaySeconds]. */
    public fun schedule(delaySeconds: Long, task: () -> Unit)

    /** Runs [task] on the scheduler thread as soon as possible (the 500-buffer flush). */
    public fun execute(task: () -> Unit)

    /** Whether the scheduler has been shut down — a scheduled phase checks this before re-arming. */
    public fun isShutdown(): Boolean

    /** Stops the scheduler and abandons any pending phase. */
    public fun shutdown()
}

/**
 * Production [SensorRuntimeScheduler] backed by a single-thread [ScheduledExecutorService],
 * identical to the legacy `HardwareSensorService` scheduler.
 */
public class ExecutorSensorRuntimeScheduler : SensorRuntimeScheduler {

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    override fun schedule(delaySeconds: Long, task: () -> Unit) {
        scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS)
    }

    override fun execute(task: () -> Unit) {
        scheduler.execute(task)
    }

    override fun isShutdown(): Boolean = scheduler.isShutdown

    override fun shutdown() {
        scheduler.shutdownNow()
    }
}
