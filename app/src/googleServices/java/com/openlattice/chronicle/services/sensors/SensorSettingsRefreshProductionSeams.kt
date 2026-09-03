package com.openlattice.chronicle.services.sensors

import android.content.Context
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.sensors.SensorSettingsGateway
import com.openlattice.chronicle.collection.sensors.SensorSettingsStore
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Production [SensorSettingsGateway] for the Phase 6B module-path sensor-settings refresh.
 *
 * Wraps the exact network reads the legacy `SensorSettingsRefreshWorker` performed:
 * `ChronicleStudyApi.getAndroidSensorSettings`, `SensorAvailabilityReporter.checkAndReport`,
 * and the `updateSensorUploadStatus` write that records an availability-report failure on
 * the server row. The gateway exposes the one enabled enrollment as a list to the legacy seam.
 *
 * Behaviour is unchanged from the legacy worker; this class only moves the calls behind
 * the [SensorSettingsGateway] seam so the refresh logic is JVM-unit testable.
 *
 */
public class ChronicleSensorSettingsGateway(
    private val context: Context,
    private val chronicleDb: ChronicleDb,
) : SensorSettingsGateway {

    override fun enabledServers(): List<SensorSettingsGateway.Server> =
        listOfNotNull(chronicleDb.uploadServerDao().getEnabledServer()).map { entity ->
            SensorSettingsGateway.Server(
                id = entity.id,
                name = entity.name,
                studyId = entity.studyId,
                participantId = entity.participantId,
                sourceDeviceId = entity.sourceDeviceId,
                apiKey = entity.apiKey,
                mobileSigningSecretOverride = entity.mobileSigningSecretOverride,
                url = entity.url,
                sensorConsecutiveFailures = entity.sensorConsecutiveFailures,
                lastUploadedSensorId = entity.lastUploadedSensorId,
            )
        }

    override fun fetchAndroidSensorSetting(server: SensorSettingsGateway.Server): AndroidSensorSetting {
        val api = UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
        return api.getAndroidSensorSettings(UUID.fromString(server.studyId))
    }

    override fun reportAvailability(
        server: SensorSettingsGateway.Server,
        requestedSensors: Set<AndroidSensorType>,
    ): Boolean = SensorAvailabilityReporter.checkAndReport(
        context,
        UUID.fromString(server.studyId),
        server.participantId,
        server.sourceDeviceId,
        server.apiKey,
        requestedSensors,
        server.url,
        server.mobileSigningSecretOverride,
    )

    override fun recordAvailabilityFailure(server: SensorSettingsGateway.Server) {
        chronicleDb.uploadServerDao().updateSensorUploadStatus(
            server.id,
            OffsetDateTime.now().toString(),
            "availability report failed",
            server.sensorConsecutiveFailures + 1,
            server.lastUploadedSensorId,
        )
    }
}

/**
 * Production [SensorSettingsStore] backed by the encrypted-prefs [SensorSettings].
 *
 * Reconstructs the legacy [AndroidSensorSetting] from the four `SensorSettings` getters
 * and writes it back through `save` / `clear`, exactly as the legacy worker did.
 *
 */
public class SensorSettingsPrefsStore(context: Context) : SensorSettingsStore {

    private val sensorSettings = SensorSettings(context.applicationContext)

    override fun read(): AndroidSensorSetting? {
        val configured = sensorSettings.getConfiguredSensors()
        if (configured.isEmpty()) return null
        return AndroidSensorSetting(
            sensors = configured,
            samplingRateHz = sensorSettings.getSamplingRateHz(),
            dutyCycleActiveSeconds = sensorSettings.getDutyCycleActiveSeconds(),
            dutyCyclePeriodSeconds = sensorSettings.getDutyCyclePeriodSeconds(),
        )
    }

    override fun isEffectivelyEnabled(): Boolean = sensorSettings.isEnabled()

    override fun save(setting: AndroidSensorSetting) {
        sensorSettings.save(setting)
    }

    override fun clear() {
        sensorSettings.clear()
    }
}
