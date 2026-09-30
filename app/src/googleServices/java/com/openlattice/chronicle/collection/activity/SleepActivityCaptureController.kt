package com.openlattice.chronicle.collection.activity

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
     * and Sleep request APIs) is currently granted. On API < 29 the permission is install-time, so
     * `checkSelfPermission` reports it granted there too.
     */
    private fun hasActivityRecognitionPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, ModulePermissions.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    public fun isAvailable(context: Context): Boolean =
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS

    public fun ensureRegistration(context: Context) = ResearchPersistenceGate.withReadLease {
        ensureRegistrationAdmitted(context)
    }

    private fun ensureRegistrationAdmitted(context: Context) {
        val appContext = context.applicationContext
        if (!isAvailable(appContext)) {
            Log.i(TAG, "Google Play Services unavailable; sleep/activity registration skipped")
            return
        }
        val client = ActivityRecognition.getClient(appContext)
        val hasPermission = hasActivityRecognitionPermission(appContext)

        // activity_recognition — only request updates when consented AND the runtime permission is
        // granted; without ACTIVITY_RECOGNITION the request fails anyway. The SecurityException is
        // also handled explicitly so lint's permission contract for the gated GMS call is satisfied
        // (lint does not follow the hoisted permission check through a helper).
        if (CollectionGate.collects(appContext, CollectionModuleId.ACTIVITY_RECOGNITION) && hasPermission) {
            val pendingIntent = registrationIntent(appContext, CollectionModuleId.ACTIVITY_RECOGNITION) ?: return
            try {
                client.requestActivityTransitionUpdates(transitionRequest(), pendingIntent)
                    .addOnFailureListener { Log.w(TAG, "requestActivityTransitionUpdates failed: ${it.javaClass.simpleName}") }
            } catch (e: SecurityException) {
                Log.w(TAG, "activity transition registration denied (ACTIVITY_RECOGNITION not granted): ${e.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "activity transition registration threw: ${e.javaClass.simpleName}")
            }
        } else {
            eraseRegistration(appContext, CollectionModuleId.ACTIVITY_RECOGNITION)
        }

        // sleep
        if (CollectionGate.collects(appContext, CollectionModuleId.SLEEP) && hasPermission) {
            val pendingIntent = registrationIntent(appContext, CollectionModuleId.SLEEP) ?: return
            try {
                client.requestSleepSegmentUpdates(pendingIntent, SleepSegmentRequest.getDefaultSleepSegmentRequest())
                    .addOnFailureListener { Log.w(TAG, "requestSleepSegmentUpdates failed: ${it.javaClass.simpleName}") }
            } catch (e: SecurityException) {
                Log.w(TAG, "sleep registration denied (ACTIVITY_RECOGNITION not granted): ${e.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "sleep registration threw: ${e.javaClass.simpleName}")
            }
        } else {
            eraseRegistration(appContext, CollectionModuleId.SLEEP)
        }
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
            removeActivityTransitionUpdatesSafely(client, legacy)
            removeSleepUpdatesSafely(client, legacy)
            legacy.cancel()
        }
    }

    public fun eraseRegistration(context: Context, module: CollectionModuleId) {
        val prefs = context.getSharedPreferences("activity_registration_scopes", Context.MODE_PRIVATE)
        val scope = prefs.getString(module.id, null) ?: return
        val pending = pendingIntent(context, module, scope, PendingIntent.FLAG_NO_CREATE)
        if (pending != null) {
            val client = ActivityRecognition.getClient(context)
            if (module == CollectionModuleId.SLEEP) removeSleepUpdatesSafely(client, pending)
            else removeActivityTransitionUpdatesSafely(client, pending)
            pending.cancel()
        }
        check(prefs.edit().remove(module.id).commit())
    }

    private fun registrationIntent(context: Context, module: CollectionModuleId): PendingIntent? {
        val origin = ResearchPersistenceGate.captureObservation(context, module)
        if (!origin.isCurrent()) return null
        val scope = ResearchPersistenceGate.observationScope(context, module)?.first ?: return null
        val prefs = context.getSharedPreferences("activity_registration_scopes", Context.MODE_PRIVATE)
        if (prefs.getString(module.id, null) != scope) {
            eraseRegistration(context, module)
            check(prefs.edit().putString(module.id, scope).commit())
        }
        return pendingIntent(context, module, scope, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Removal is always safe to attempt (used on disable/withdrawal even after the permission was
     * revoked). `removeActivityTransitionUpdates` is annotated ACTIVITY_RECOGNITION-gated, so the
     * SecurityException is handled explicitly to satisfy the permission contract without changing
     * behavior — a revoked permission simply means there is nothing left to remove.
     */
    private fun removeActivityTransitionUpdatesSafely(
        client: com.google.android.gms.location.ActivityRecognitionClient,
        pendingIntent: PendingIntent,
    ) {
        try {
            client.removeActivityTransitionUpdates(pendingIntent)
        } catch (e: SecurityException) {
            Log.w(TAG, "removeActivityTransitionUpdates suppressed (ACTIVITY_RECOGNITION not granted): ${e.javaClass.simpleName}")
        }
    }

    private fun removeSleepUpdatesSafely(
        client: com.google.android.gms.location.ActivityRecognitionClient,
        pendingIntent: PendingIntent,
    ) {
        runCatching { client.removeSleepSegmentUpdates(pendingIntent) }
            .onFailure { Log.w(TAG, "removeSleepSegmentUpdates failed: ${it.javaClass.simpleName}") }
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
            .putExtra("registration_scope", scope)
            .putExtra("registration_module", module.id)
        return PendingIntent.getBroadcast(context, REQUEST_CODE + module.ordinal, intent, pendingFlags(flags))
    }
}
