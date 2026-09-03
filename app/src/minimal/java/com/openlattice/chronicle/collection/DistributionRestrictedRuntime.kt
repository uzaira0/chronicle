package com.openlattice.chronicle.collection

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.Fragment
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.UUID

/** No-op implementation for functionality deliberately absent from Play and Amazon. */
internal object DistributionRestrictedRuntime {
    fun startHardwareSensors(context: Context): Unit = Unit
    fun tryStartHardwareSensors(context: Context): Boolean = false
    fun stopHardwareSensors(context: Context): Unit = Unit
    fun hardwareSensorsRunning(context: Context): Boolean = false
    fun enqueueSensorSettingsRefresh(context: Context): Unit = Unit
    fun scheduleSensorSettingsRefresh(context: Context): Unit = Unit
    fun reinitializeDirectBootProcess(context: Context): Unit = Unit
    fun drainDirectBootSamples(context: Context): Unit = Unit
    fun uploadSensors(context: Context, db: ChronicleDb): Int = 0

    fun reportSensorAvailability(
        context: Context,
        studyId: UUID,
        participantId: String,
        deviceId: String,
        apiKey: String?,
        requestedSensors: Set<AndroidSensorType>,
        serverUrl: String,
        mobileSigningSecretOverride: String?,
    ): Boolean = false

    fun activityRecognitionAvailable(context: Context): Boolean = false
    fun unregisterActivityRecognition(context: Context): Unit = Unit
    fun interactionAccessibilityEnabled(context: Context): Boolean = false
    fun openInteractionAccessibilitySettings(context: Context): Unit = Unit
    fun showInteractionAccessibilityDisclosure(fragment: Fragment): Unit = Unit
    fun healthConnectAvailable(context: Context): Boolean = false
    fun healthConnectGranted(context: Context): Boolean = false
    fun healthPermissionsToRequest(
        context: Context,
        configured: Set<HealthConnectRecordType>,
    ): Set<String> = emptySet()

    fun healthPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        object : ActivityResultContract<Set<String>, Set<String>>() {
            override fun createIntent(context: Context, input: Set<String>): Intent = Intent()
            override fun parseResult(resultCode: Int, intent: Intent?): Set<String> = emptySet()
        }
}
