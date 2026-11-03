package com.openlattice.chronicle.services.sensors

import android.content.Context
import com.openlattice.chronicle.collection.sensors.SensorSettingsGateway
import com.openlattice.chronicle.collection.state.enrollmentIdentity
import com.openlattice.chronicle.storage.ChronicleDb
import java.time.OffsetDateTime

/**
 * Production [SensorSettingsGateway] for the module-path sensor-settings refresh.
 *
 * Wraps `SensorAvailabilityReporter.checkAndReport` and the `updateSensorUploadStatus` write that records an availability-report failure on
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
                identity = entity.enrollmentIdentity(),
                apiKey = entity.apiKey,
                url = entity.url,
                sensorConsecutiveFailures = entity.sensorConsecutiveFailures,
                lastUploadedSensorId = entity.lastUploadedSensorId,
            )
        }

    override fun reportAvailability(server: SensorSettingsGateway.Server): Boolean =
        SensorAvailabilityReporter.checkAndReport(context, server.identity)

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
