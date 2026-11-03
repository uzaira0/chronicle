package com.openlattice.chronicle.collection

import androidx.work.ExistingWorkPolicy
import androidx.annotation.CheckResult
import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.Fragment
import com.openlattice.chronicle.collection.directboot.DirectBootDrainWorker
import com.openlattice.chronicle.collection.directboot.clearCorruptDirectBootSensorBuffers
import com.openlattice.chronicle.preferences.clearDirectBootSensorSnapshot
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.services.sensors.SensorUploadWorkerDelegate
import com.openlattice.chronicle.services.sensors.SENSOR_SETTINGS_REFRESH_WORK_NAME
import com.openlattice.chronicle.services.sensors.SENSOR_SETTINGS_REFRESH_PERIODIC_WORK_NAME
import com.openlattice.chronicle.services.sensors.enqueueSensorSettingsRefresh as enqueueRestrictedSensorSettingsRefresh
import com.openlattice.chronicle.services.sensors.scheduleSensorSettingsRefreshWork as scheduleRestrictedSensorSettingsRefresh
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import com.openlattice.chronicle.utils.Utils

/** Operational restricted collectors linked only into controlled research/open builds. */
internal object DistributionRestrictedRuntime {
    val sensorSettingsWorkNames = setOf(SENSOR_SETTINGS_REFRESH_WORK_NAME, SENSOR_SETTINGS_REFRESH_PERIODIC_WORK_NAME)
    @CheckResult
    fun tryStartHardwareSensors(context: Context): Boolean = HardwareSensorService.tryStartService(context)
    fun stopHardwareSensors(context: Context) = HardwareSensorService.stopService(context)
    fun hardwareSensorsRunning(context: Context): Boolean =
        Utils.isServiceRunning(context, HardwareSensorService::class.java)

    fun enqueueSensorSettingsRefresh(context: Context, policy: ExistingWorkPolicy) = enqueueRestrictedSensorSettingsRefresh(context, policy)
    fun scheduleSensorSettingsRefresh(context: Context) = scheduleRestrictedSensorSettingsRefresh(context)

    fun drainDirectBootSamples(context: Context) = DirectBootDrainWorker.enqueue(context)

    /**
     * A discarded sensor retires the locked-boot snapshot; the next normal start rewrites it. Its
     * buffered pre-unlock samples stay with the other sensors' until the drain, which drops them by
     * the module's erasure floor.
     */
    fun retireDirectBootSensors(context: Context, sensorTypes: Set<String>) {
        if (sensorTypes.isNotEmpty()) {
            checkLocalStoreWrite(clearDirectBootSensorSnapshot(context)) { "Unable to retire direct-boot sensor snapshot" }
            checkLocalStoreWrite(clearCorruptDirectBootSensorBuffers(context)) { "Unable to erase unreadable direct-boot archives" }
        }
    }

    fun eraseActivityRegistration(context: Context, module: CollectionModuleId) =
        ActivityHealthCollectors.eraseActivityRegistration(context, module)

    fun eraseHealthSource(context: Context) = ActivityHealthCollectors.eraseHealthSource(context)

    fun uploadSensors(context: Context, db: ChronicleDb, stopped: () -> Boolean): Int =
        SensorUploadWorkerDelegate(context, db, stopped).execute()

    fun activityRecognitionAvailable(context: Context): Boolean =
        ActivityHealthCollectors.activityRecognitionAvailable(context)

    fun unregisterActivityRecognition(context: Context) =
        ActivityHealthCollectors.unregisterActivityRecognition(context)

    fun interactionAccessibilityEnabled(context: Context): Boolean =
        DistributionSpecialAccess.interactionAccessibilityEnabled(context)

    fun notificationListenerAccessGranted(context: Context): Boolean =
        DistributionSpecialAccess.notificationListenerAccessGranted(context)

    fun openInteractionAccessibilitySettings(context: Context) =
        DistributionSpecialAccess.openInteractionAccessibilitySettings(context)

    fun showInteractionAccessibilityDisclosure(fragment: Fragment) =
        DistributionSpecialAccess.showInteractionAccessibilityDisclosure(fragment)

    fun healthConnectAvailable(context: Context): Boolean = ActivityHealthCollectors.healthConnectAvailable(context)
    fun healthConnectGranted(context: Context): Boolean = ActivityHealthCollectors.healthConnectGranted(context)
    fun healthPermissionsToRequest(
        context: Context,
        configured: Set<HealthConnectRecordType>,
    ): Set<String> = ActivityHealthCollectors.healthPermissionsToRequest(context, configured)

    fun healthPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        ActivityHealthCollectors.healthPermissionContract()
}
