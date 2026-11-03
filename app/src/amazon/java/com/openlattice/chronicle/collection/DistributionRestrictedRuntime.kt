package com.openlattice.chronicle.collection

import androidx.work.ExistingWorkPolicy
import androidx.annotation.CheckResult
import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.Fragment
import com.openlattice.chronicle.storage.ChronicleDb

/** No-op implementation for functionality deliberately absent from Play and Amazon. */
internal object DistributionRestrictedRuntime {
    val sensorSettingsWorkNames: Set<String> = emptySet()
    @CheckResult
    fun tryStartHardwareSensors(context: Context): Boolean = false
    fun stopHardwareSensors(context: Context): Unit = Unit
    fun hardwareSensorsRunning(context: Context): Boolean = false
    fun enqueueSensorSettingsRefresh(context: Context, policy: ExistingWorkPolicy): Unit = Unit
    fun scheduleSensorSettingsRefresh(context: Context): Unit = Unit
    fun drainDirectBootSamples(context: Context): Unit = Unit
    fun retireDirectBootSensors(context: Context, sensorTypes: Set<String>): Unit = Unit
    fun eraseActivityRegistration(context: Context, module: CollectionModuleId) =
        UnlinkedActivityHealth.eraseActivityRegistration(context, module)

    fun eraseHealthSource(context: Context) = UnlinkedActivityHealth.eraseHealthSource(context)

    fun uploadSensors(context: Context, db: ChronicleDb, stopped: () -> Boolean): Int = 0

    fun activityRecognitionAvailable(context: Context): Boolean = false
    fun unregisterActivityRecognition(context: Context): Unit = Unit
    fun interactionAccessibilityEnabled(context: Context): Boolean = false
    fun notificationListenerAccessGranted(context: Context): Boolean = false
    fun openInteractionAccessibilitySettings(context: Context): Unit = Unit
    fun showInteractionAccessibilityDisclosure(fragment: Fragment): Unit = Unit
    fun healthConnectAvailable(context: Context): Boolean = false
    fun healthConnectGranted(context: Context): Boolean = false
    fun healthPermissionsToRequest(
        context: Context,
        configured: Set<HealthConnectRecordType>,
    ): Set<String> = emptySet()

    fun healthPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        UnlinkedActivityHealth.permissionContract
}
