package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.core.CollectionLog

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
 * Seam over the network reads the sensor-settings refresh needs (refactor plan §9.2).
 *
 * The legacy `SensorSettingsRefreshWorker` called `ChronicleStudyApi` directly, which
 * makes it untestable on the JVM. The module path depends on this interface instead; the
 * production implementation wraps `ChronicleStudyApi`, and tests supply a fake that can
 * return a setting, throw a 404, or throw a network error.
 */
public interface SensorSettingsGateway {

    /** One enrolled upload server, reduced to the fields the refresh needs. */
    public data class Server(
        val id: Long,
        val name: String,
        val studyId: String,
        val participantId: String,
        val sourceDeviceId: String,
        val apiKey: String?,
        val mobileSigningSecretOverride: String?,
        val url: String,
        val sensorConsecutiveFailures: Int,
        val lastUploadedSensorId: String?,
    )

    /** The active study server as a zero-or-one list for the legacy test seam. */
    public fun enabledServers(): List<Server>

    /**
     * Fetches the `AndroidSensor` setting from [server].
     *
     * @throws Exception verbatim — a `code 404` + `AndroidSensor` message signals the
     *   missing-settings case; any other exception is treated as transient.
     */
    public fun fetchAndroidSensorSetting(server: Server): AndroidSensorSetting

    /**
     * Reports modeled-sensor availability to [server] for [requestedSensors].
     *
     * @return `true` if the report was acknowledged, `false` on any failure.
     */
    public fun reportAvailability(server: Server, requestedSensors: Set<AndroidSensorType>): Boolean

    /** Persists an availability-report failure onto the [server] row, for visibility. */
    public fun recordAvailabilityFailure(server: Server)
}

/**
 * Seam over the locally-persisted sensor settings ([com.openlattice.chronicle.preferences.SensorSettings]).
 * The module path reads/writes the device's sensor config through this so the refresh
 * logic is JVM-unit testable.
 */
public interface SensorSettingsStore {
    /** The currently persisted backend-configured legacy setting, or `null` if none. */
    public fun read(): AndroidSensorSetting?

    /** Whether any configured sensor is currently allowed by local tablet overrides. */
    public fun isEffectivelyEnabled(): Boolean

    /** Persists [setting] as the device's sensor configuration. */
    public fun save(setting: AndroidSensorSetting)

    /** Clears the persisted sensor configuration (disable-on-missing). */
    public fun clear()
}

/**
 * The sensor availability-reporting refresh.
 *
 * Under the per-sensor consent redesign (2026-06-11), per-sensor config and the
 * hardware-sensor service lifecycle are owned by `CollectionLoopCoordinator` (the
 * per-sensor `DataCollection` modules → `SensorSettings`). This delegate's **sole** job is
 * to report modeled-sensor availability to the one active study server.
 *
 * It deliberately does **not**:
 *  - fetch the retired device-wide `AndroidSensor` setting;
 *  - write or clear the persisted sensor config (`store.save` / `store.clear`);
 *  - start or stop the hardware-sensor service.
 *
 * Doing any of those would clobber the coordinator's per-sensor settings — the Data
 * Sharing tab's read-only Hz/duty source and the `SensorRuntimeController` config. The
 * requested-availability set is the coordinator's persisted per-sensor configured set,
 * read via [SensorSettingsStore.read].
 *
 * What is preserved **exactly**:
 *  - availability reporting to the active study server, with the upload-status update
 *    on a failed report (refactor plan §9.2 guardrail 3 — never a silent skip).
 *
 * This is a plain class holding only its seams — no Android `Context`. The seams
 * ([SensorSettingsGateway], [SensorSettingsStore]) are supplied by the worker.
 *
 */
public class SensorSettingsRefreshDelegate(
    private val gateway: SensorSettingsGateway,
    private val store: SensorSettingsStore,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) {

    /** Result of a refresh pass — the worker maps [outcome] to a WorkManager `Result`. */
    public data class SensorSettingsRefreshResult(
        val outcome: SensorSettingsRefreshOutcome,
    )

    /**
     * Runs one refresh pass: reports modeled-sensor availability for the study-configured
     * per-sensor set to the active study server.
     *
     * Per-sensor config and the hardware-sensor service lifecycle are owned by
     * `CollectionLoopCoordinator` (the per-sensor `DataCollection` modules → `SensorSettings`).
     * This refresh therefore must NOT fetch the retired device-wide `AndroidSensor` setting,
     * nor write/clear the sensor config, nor start/stop the service — doing any of those
     * clobbers the coordinator's per-sensor settings (the Data Sharing read-only Hz/duty
     * source and the runtime config). The requested-availability set is the coordinator's
     * persisted per-sensor configured set (per-sensor consent redesign, 2026-06-11).
     *
     * @return the [SensorSettingsRefreshResult] the worker maps to a WorkManager `Result`.
     */
    public fun refresh(): SensorSettingsRefreshResult {
        val servers = gateway.enabledServers()
        if (servers.isEmpty()) {
            log.info(TAG, "No enabled servers, skipping availability refresh")
            return SensorSettingsRefreshResult(SensorSettingsRefreshOutcome.SUCCESS)
        }
        val configured = store.read()?.sensors ?: emptySet()
        reportAvailabilityToAllServers(servers, configured)
        return SensorSettingsRefreshResult(SensorSettingsRefreshOutcome.SUCCESS)
    }

    /**
     * Reports modeled-sensor availability to the active study server. A failed report is
     * persisted onto the server row so it is visible (refactor plan §9.2 guardrail 3 —
     * availability failures must update visible status). A partial failure does not abort
     * the loop or fail the refresh.
     */
    private fun reportAvailabilityToAllServers(
        servers: List<SensorSettingsGateway.Server>,
        fetchedSensors: Set<AndroidSensorType>,
    ) {
        if (fetchedSensors.isEmpty()) return
        for (server in servers) {
            val ok = gateway.reportAvailability(server, fetchedSensors)
            if (!ok) {
                log.warn(TAG, "Sensor availability report failed for '${server.name}'")
                gateway.recordAvailabilityFailure(server)
            }
        }
    }
}
