package com.openlattice.chronicle.collection

import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.activity.ActivityRecognitionIntegration
import com.openlattice.chronicle.collection.directboot.DirectBootDrainWorker
import com.openlattice.chronicle.collection.directboot.DIRECT_BOOT_BUFFER_LOCK
import com.openlattice.chronicle.collection.directboot.DirectBootDiagnosticsJournal
import com.openlattice.chronicle.collection.directboot.DirectBootSampleBuffer
import com.openlattice.chronicle.collection.directboot.DirectBootProcessInit
import com.openlattice.chronicle.collection.directboot.DirectBootStorageAdmission
import com.openlattice.chronicle.collection.device.HealthConnectPermissions
import com.openlattice.chronicle.collection.interaction.InteractionAccessibilityOnboarding
import com.openlattice.chronicle.collection.sensors.SensorUploadMigration
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.services.sensors.SensorAvailabilityReporter
import com.openlattice.chronicle.services.sensors.SensorUploadWorkerDelegate
import com.openlattice.chronicle.services.sensors.enqueueSensorSettingsRefresh as enqueueRestrictedSensorSettingsRefresh
import com.openlattice.chronicle.services.sensors.scheduleSensorSettingsRefreshWork as scheduleRestrictedSensorSettingsRefresh
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.util.UUID

/** Operational restricted collectors linked only into controlled research/open builds. */
internal object DistributionRestrictedRuntime {
    fun startHardwareSensors(context: Context) = HardwareSensorService.startService(context)
    fun tryStartHardwareSensors(context: Context): Boolean = HardwareSensorService.tryStartService(context)
    fun stopHardwareSensors(context: Context) = HardwareSensorService.stopService(context)
    fun hardwareSensorsRunning(context: Context): Boolean =
        Utils.isServiceRunning(context, HardwareSensorService::class.java)

    fun enqueueSensorSettingsRefresh(context: Context) = enqueueRestrictedSensorSettingsRefresh(context)
    fun scheduleSensorSettingsRefresh(context: Context) = scheduleRestrictedSensorSettingsRefresh(context)
    fun reinitializeDirectBootProcess(context: Context) =
        DirectBootProcessInit.reinitializeAfterUnlock(context)

    fun drainDirectBootSamples(context: Context) = DirectBootDrainWorker.enqueue(context)

    private fun erasurePrefs(context: Context) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context)
            .getSharedPreferences("direct_boot_pending_sensor_erasures", Context.MODE_PRIVATE)

    fun pendingDirectBootSensorErasures(context: Context): Set<String> =
        synchronized(DIRECT_BOOT_BUFFER_LOCK) {
            erasurePrefs(context).getStringSet("sensor_types", emptySet()).orEmpty().toSet()
        }

    fun markDirectBootSensorErasures(context: Context, sensorTypes: Set<String>) {
        if (sensorTypes.isEmpty()) return
        synchronized(DIRECT_BOOT_BUFFER_LOCK) {
            check(erasurePrefs(context).edit().putStringSet("sensor_types",
                pendingDirectBootSensorErasures(context) + sensorTypes).commit()) {
                "Unable to persist direct-boot sensor erasure intent"
            }
        }
    }

    fun eraseDirectBootStorageAdmission(context: Context) = DirectBootStorageAdmission.clear(context)

    fun eraseDirectBootSensorSamples(
        context: Context,
        sensorType: String,
        buffer: DirectBootSampleBuffer = DirectBootSampleBuffer(context),
        journal: DirectBootDiagnosticsJournal = DirectBootDiagnosticsJournal(context),
        enqueueDrain: (Context) -> Unit = DirectBootDrainWorker::enqueue,
    ) {
        eraseDirectBootStorageAdmission(context)
        val owner = DirectBootDiagnosticsJournal.configuredOwner(context)
        journal.quarantineCorruptJournal()
        journal.recordQuarantinedIncidents(context)
        val erased = buffer.eraseSensorType(
            sensorType, owner?.let { DirectBootSampleBuffer.ownerKey(it) },
            // One journal event per file rewrite (a per-sample event rewrites the journal per sample). The
            // id comes from the erased ids, so a rewrite retried after a crash is not counted twice.
            beforeErase = { ids ->
                if (ids.isNotEmpty()) journal.record("MODULE_POLICY_ERASED", ids.size, "module-policy-erased:" +
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(ids.sorted().joinToString(",").toByteArray()).joinToString("") { "%02x".format(it) })
            },
        )
        synchronized(DIRECT_BOOT_BUFFER_LOCK) {
            check(erasurePrefs(context).edit().putStringSet("sensor_types",
                pendingDirectBootSensorErasures(context) - sensorType).commit()) {
                "Unable to complete direct-boot sensor erasure intent"
            }
        }
        if (erased > 0) enqueueDrain(context)
    }

    fun eraseActivityRegistration(context: Context, module: CollectionModuleId) { com.openlattice.chronicle.collection.activity.SleepActivityCaptureController.eraseRegistration(context, module) }

    fun eraseHealthSource(context: Context) { com.openlattice.chronicle.collection.device.AndroidHealthMetricSource.clearCheckpoint(context) }

    fun uploadSensors(context: Context, db: ChronicleDb): Int {
        val worker = SensorUploadWorkerDelegate(context, db)
        return if (SensorUploadMigration.USE_MODULE_MANAGER_SENSOR_UPLOAD_PATH) {
            worker.asModule().upload().serverFailureCount
        } else {
            worker.execute()
        }
    }

    fun reportSensorAvailability(
        context: Context,
        studyId: UUID,
        participantId: String,
        deviceId: String,
        apiKey: String?,
        requestedSensors: Set<AndroidSensorType>,
        serverUrl: String,
        mobileSigningSecretOverride: String?,
    ): Boolean = SensorAvailabilityReporter.checkAndReport(
        context,
        studyId,
        participantId,
        deviceId,
        apiKey,
        requestedSensors,
        serverUrl,
        mobileSigningSecretOverride,
    )

    fun activityRecognitionAvailable(context: Context): Boolean =
        ActivityRecognitionIntegration.isAvailable(context)

    fun unregisterActivityRecognition(context: Context) =
        ActivityRecognitionIntegration.unregisterAll(context)

    fun interactionAccessibilityEnabled(context: Context): Boolean =
        InteractionAccessibilityOnboarding.isServiceEnabled(context)

    fun openInteractionAccessibilitySettings(context: Context) =
        InteractionAccessibilityOnboarding.openAccessibilitySettings(context)

    fun showInteractionAccessibilityDisclosure(fragment: Fragment) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.accessibility_disclosure_title)
            .setMessage(R.string.accessibility_disclosure_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                openInteractionAccessibilitySettings(fragment.requireContext())
            }
            .show()
    }

    fun healthConnectAvailable(context: Context): Boolean = HealthConnectPermissions.isAvailable(context)
    fun healthConnectGranted(context: Context): Boolean = HealthConnectPermissions.allGranted(context)
    fun healthPermissionsToRequest(
        context: Context,
        configured: Set<HealthConnectRecordType>,
    ): Set<String> = HealthConnectPermissions.permissionsToRequest(context, configured)

    fun healthPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        HealthConnectPermissions.requestContract()
}
