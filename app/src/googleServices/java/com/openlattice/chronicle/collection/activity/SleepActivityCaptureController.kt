package com.openlattice.chronicle.collection.activity

import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.SleepSegmentRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.CaptureRegistrationStatus

/**
 * Drives Play Services registration for the push modules `sleep` and `activity_recognition`. A
 * single shared PendingIntent feeds [SleepActivityReceiver]. [ensureRegistration] is idempotent and
 * is called each collection-worker run: it requests updates for a module when the participant has
 * consented to it and removes them when not. A device without Play Services (e.g. Fire OS) simply
 * sees the requests fail — logged, never thrown.
 */
public object SleepActivityCaptureController {

    private const val TAG = "SleepActivityCapture"
    private const val REQUEST_CODE = 0xC04E

    /** Activity classes worth transition updates for screen-time research. */
    private val TRACKED_ACTIVITIES = intArrayOf(
        DetectedActivity.STILL,
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_FOOT,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.IN_VEHICLE,
    )

    /**
     * Whether the ACTIVITY_RECOGNITION runtime permission (required by the GMS Activity Transition
     * and Sleep request APIs) is currently granted. Before API 29, check the install-time GMS permission.
     */
    private fun hasActivityRecognitionPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context,
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ModulePermissions.LEGACY_ACTIVITY_RECOGNITION
            else ModulePermissions.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    public fun isAvailable(context: Context): Boolean =
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    @android.annotation.SuppressLint("MissingPermission")
    public fun ensureRegistration(context: Context) = ensureRegistration(context, ::isAvailable) { module, intent ->
        val client = ActivityRecognition.getClient(context.applicationContext)
        when (module) {
            CollectionModuleId.ACTIVITY_RECOGNITION -> client.requestActivityTransitionUpdates(transitionRequest(), intent)
            CollectionModuleId.SLEEP -> client.requestSleepSegmentUpdates(intent, SleepSegmentRequest.getDefaultSleepSegmentRequest())
            else -> error("Unsupported activity registration module")
        }
    }

    internal fun ensureRegistration(
        context: Context,
        available: (Context) -> Boolean,
        request: (CollectionModuleId, PendingIntent) -> com.google.android.gms.tasks.Task<Void>,
    ) = ResearchPersistenceGate.withReadLease {
        val appContext = context.applicationContext
        if (!available(appContext)) {
            val owner = ResearchPersistenceGate.captureOwner(appContext)
            for (module in listOf(CollectionModuleId.ACTIVITY_RECOGNITION, CollectionModuleId.SLEEP)) {
                if (CollectionGate.collects(appContext, module)) {
                    CaptureRegistrationStatus.recordOutcome(appContext, owner, module, false)
                }
            }
            Log.e(TAG, "Google Play Services unavailable; sleep/activity registration skipped")
            return@withReadLease
        }
        val hasPermission = hasActivityRecognitionPermission(appContext)
        for (module in listOf(CollectionModuleId.ACTIVITY_RECOGNITION, CollectionModuleId.SLEEP)) {
            if (CollectionGate.collects(appContext, module) && hasPermission) {
                if (!register(appContext, module, request)) return@withReadLease
            } else eraseRegistration(appContext, module)
        }
    }

    private fun register(
        context: Context,
        module: CollectionModuleId,
        request: (CollectionModuleId, PendingIntent) -> com.google.android.gms.tasks.Task<Void>,
    ): Boolean {
        val pendingIntent = registrationIntent(context, module) ?: return false
        val origin = ResearchPersistenceGate.captureObservation(context, module)
        val owner = ResearchPersistenceGate.captureOwner(context)
        val activity = module == CollectionModuleId.ACTIVITY_RECOGNITION
        val requestName = if (activity) "requestActivityTransitionUpdates" else "requestSleepSegmentUpdates"
        val label = if (activity) "activity transition" else "sleep"
        fun completed(success: Boolean) = ResearchPersistenceGate.executeAsync {
            if (origin.isCurrent()) runCatching {
                CaptureRegistrationStatus.recordOutcome(context, owner, module, success)
            }.onFailure { Log.w(TAG, "Capture status unavailable", it) }
        }
        try {
            request(module, pendingIntent)
                .addOnFailureListener { Log.e(TAG, "$requestName failed: ${it.javaClass.simpleName}"); completed(false) }
                .addOnSuccessListener { completed(true) }
        } catch (e: SecurityException) {
            completed(false)
            Log.e(TAG, "$label registration denied (ACTIVITY_RECOGNITION not granted): ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            completed(false)
            Log.e(TAG, "$label registration threw: ${e.javaClass.simpleName}")
        }
        return true
    }

    /** Removes both registrations (used on withdrawal / disable). */
    public fun unregisterAll(context: Context) {
        val appContext = context.applicationContext
        eraseRegistration(appContext, CollectionModuleId.SLEEP)
        eraseRegistration(appContext, CollectionModuleId.ACTIVITY_RECOGNITION)
        // Retire the old shared registration as well when upgrading a pre-epoch installation.
        val legacy = PendingIntent.getBroadcast(appContext, REQUEST_CODE,
            Intent(appContext, SleepActivityReceiver::class.java).setAction(SleepActivityReceiver.ACTION_SLEEP_ACTIVITY),
            pendingFlags(PendingIntent.FLAG_NO_CREATE))
        if (legacy != null) {
            val client = ActivityRecognition.getClient(appContext)
            removeActivityTransitionUpdatesSafely(appContext, client, legacy)
            removeSleepUpdatesSafely(appContext, client, legacy)
            legacy.cancel()
        }
    }

    public fun eraseRegistration(context: Context, module: CollectionModuleId) {
        val prefs = context.getSharedPreferences(ACTIVITY_REGISTRATION_PREFS, Context.MODE_PRIVATE)
        val scope = prefs.getString(module.id, null) ?: return
        val pending = pendingIntent(context, module, scope, PendingIntent.FLAG_NO_CREATE)
        if (pending != null) {
            val client = ActivityRecognition.getClient(context)
            if (module == CollectionModuleId.SLEEP) removeSleepUpdatesSafely(context, client, pending)
            else removeActivityTransitionUpdatesSafely(context, client, pending)
            pending.cancel()
        }
        checkLocalStoreWrite(prefs.edit().remove(module.id).commit())
    }

    private fun registrationIntent(context: Context, module: CollectionModuleId): PendingIntent? {
        val origin = ResearchPersistenceGate.captureObservation(context, module)
        if (!origin.isCurrent()) return null
        val scope = ResearchPersistenceGate.observationScope(context, module)?.first ?: return null
        val prefs = context.getSharedPreferences(ACTIVITY_REGISTRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(module.id, null) != scope) {
            eraseRegistration(context, module)
            checkLocalStoreWrite(prefs.edit().putString(module.id, scope).commit())
        }
        return pendingIntent(context, module, scope, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Transition removal also requires activity access. The caller still cancels the callback
     * PendingIntent on withdrawal; a missing grant is recorded as an incomplete provider removal.
     */
    private fun removeActivityTransitionUpdatesSafely(
        context: Context,
        client: com.google.android.gms.location.ActivityRecognitionClient,
        pendingIntent: PendingIntent,
    ) {
        val owner = ResearchPersistenceGate.captureOwner(context)
        if (!hasActivityRecognitionPermission(context)) {
            recordRemovalFailure(context, owner, CollectionModuleId.ACTIVITY_RECOGNITION)
            return
        }
        try {
            client.removeActivityTransitionUpdates(pendingIntent).addOnFailureListener {
                recordRemovalFailure(context, owner, CollectionModuleId.ACTIVITY_RECOGNITION)
            }
        } catch (e: SecurityException) {
            recordRemovalFailure(context, owner, CollectionModuleId.ACTIVITY_RECOGNITION)
            Log.w(TAG, "removeActivityTransitionUpdates suppressed (ACTIVITY_RECOGNITION not granted): ${e.javaClass.simpleName}")
        }
    }

    private fun removeSleepUpdatesSafely(
        context: Context,
        client: com.google.android.gms.location.ActivityRecognitionClient,
        pendingIntent: PendingIntent,
    ) {
        val owner = ResearchPersistenceGate.captureOwner(context)
        runCatching { client.removeSleepSegmentUpdates(pendingIntent).addOnFailureListener {
            recordRemovalFailure(context, owner, CollectionModuleId.SLEEP)
        } }.onFailure {
            recordRemovalFailure(context, owner, CollectionModuleId.SLEEP)
            Log.w(TAG, "removeSleepSegmentUpdates failed: ${it.javaClass.simpleName}")
        }
    }

    private fun recordRemovalFailure(context: Context, owner: com.openlattice.chronicle.storage.UploadServerEntity?,
                                     module: CollectionModuleId) = ResearchPersistenceGate.executeAsync {
        com.openlattice.chronicle.services.upload.recordForExpectedOwner(context, owner,
            if (module == CollectionModuleId.SLEEP) com.openlattice.chronicle.services.upload.LocalUploadModuleFamily.SLEEP
            else com.openlattice.chronicle.services.upload.LocalUploadModuleFamily.ACTIVITY_RECOGNITION,
            com.openlattice.chronicle.services.upload.LocalOperationalIssue.COLLECTION_ACCESS_MISSING, 1)
    }

    private fun transitionRequest(): ActivityTransitionRequest {
        val transitions = TRACKED_ACTIVITIES.flatMap { activity ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(activity)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
            )
        }
        return ActivityTransitionRequest(transitions)
    }

    private fun pendingFlags(base: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base or PendingIntent.FLAG_MUTABLE else base

    private fun pendingIntent(context: Context, module: CollectionModuleId, scope: String, flags: Int): PendingIntent? {
        val intent = Intent(context, SleepActivityReceiver::class.java)
            .setAction(SleepActivityReceiver.ACTION_SLEEP_ACTIVITY)
            .setData(Uri.parse("chronicle://activity-registration/${module.id}/${Uri.encode(scope)}"))
            .putExtra(ACTIVITY_REGISTRATION_SCOPE_EXTRA, scope)
            .putExtra(ACTIVITY_REGISTRATION_MODULE_EXTRA, module.id)
        return PendingIntent.getBroadcast(context, REQUEST_CODE + module.ordinal, intent, pendingFlags(flags))
    }
}
