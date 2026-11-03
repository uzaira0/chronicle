package com.openlattice.chronicle.collection.sensors

import android.util.Log
import com.openlattice.chronicle.collection.state.EnrollmentIdentity

private const val TAG = "SensorSettingsRefreshDelegate"

/**
 * Outcome of a [SensorSettingsRefreshDelegate.refresh] pass — drives the worker's
 * `Result` and is asserted on by the parity tests.
 */
public enum class SensorSettingsRefreshOutcome {
    /** Availability reported (or nothing to report); refresh succeeded. */
    SUCCESS,

    /** A transient error (network etc.) — the worker should retry. */
    RETRY,
}

/**
 * Seam over the network reads the sensor availability refresh needs, so the refresh logic is JVM-unit testable.
 * The production implementation wraps `SensorAvailabilityReporter`; tests supply a fake.
 */
public interface SensorSettingsGateway {

    /** One enrolled upload server, reduced to the fields the refresh needs. */
    public data class Server(
        val id: Long,
        val name: String,
        val identity: EnrollmentIdentity,
        val apiKey: String?,
        val url: String,
        val sensorConsecutiveFailures: Int,
        val lastUploadedSensorId: String?,
    )

    /** The active study server as a zero-or-one list for the legacy test seam. */
    public fun enabledServers(): List<Server>

    /**
     * Reports which modeled sensors the device has (every `AndroidSensorType`, whether or not a sensor module is
     * enabled) to [server].
     *
     * @return `true` if the report was acknowledged or intentionally skipped, `false` on any failure.
     */
    public fun reportAvailability(server: Server): Boolean

    /** Persists an availability-report failure onto the [server] row, for visibility. */
    public fun recordAvailabilityFailure(server: Server)
}

/**
 * The sensor availability-reporting refresh. Its **sole** job is to report which modeled sensors the device has to
 * the one active study server. It never writes the sensor config nor starts or stops the hardware-sensor service:
 * those belong to `CollectionLoopCoordinator` (the per-sensor `DataCollection` modules).
 *
 * A failed report updates the server row's upload status (never a silent skip) and retries through WorkManager.
 */
public class SensorSettingsRefreshDelegate(private val gateway: SensorSettingsGateway) {

    /** Result of a refresh pass — the worker maps [outcome] to a WorkManager `Result`. */
    public data class SensorSettingsRefreshResult(
        val outcome: SensorSettingsRefreshOutcome,
    )

    /** Runs one refresh pass; the worker maps the result to a WorkManager `Result`. */
    public fun refresh(): SensorSettingsRefreshResult {
        val servers = gateway.enabledServers()
        if (servers.isEmpty()) {
            Log.i(TAG, "No enabled servers, skipping availability refresh")
            return SensorSettingsRefreshResult(SensorSettingsRefreshOutcome.SUCCESS)
        }
        var outcome = SensorSettingsRefreshOutcome.SUCCESS
        for (server in servers) {
            if (!gateway.reportAvailability(server)) {
                Log.w(TAG, "Sensor availability report failed for '${server.name}'")
                gateway.recordAvailabilityFailure(server)
                outcome = SensorSettingsRefreshOutcome.RETRY
            }
        }
        return SensorSettingsRefreshResult(outcome)
    }
}
