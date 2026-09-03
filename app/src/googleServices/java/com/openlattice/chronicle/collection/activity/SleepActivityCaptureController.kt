package com.openlattice.chronicle.collection.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    public fun ensureRegistration(context: Context) {
        val appContext = context.applicationContext
        if (!isAvailable(appContext)) {
            Log.i(TAG, "Google Play Services unavailable; sleep/activity registration skipped")
            return
        }
        val pendingIntent = pendingIntent(appContext)
        val client = ActivityRecognition.getClient(appContext)
        val hasPermission = hasActivityRecognitionPermission(appContext)

        // activity_recognition — only request updates when consented AND the runtime permission is
        // granted; without ACTIVITY_RECOGNITION the request fails anyway. The SecurityException is
        // also handled explicitly so lint's permission contract for the gated GMS call is satisfied
        // (lint does not follow the hoisted permission check through a helper).
        if (CollectionGate.collects(appContext, CollectionModuleId.ACTIVITY_RECOGNITION) && hasPermission) {
            try {
                client.requestActivityTransitionUpdates(transitionRequest(), pendingIntent)
                    .addOnFailureListener { Log.w(TAG, "requestActivityTransitionUpdates failed: ${it.javaClass.simpleName}") }
            } catch (e: SecurityException) {
                Log.w(TAG, "activity transition registration denied (ACTIVITY_RECOGNITION not granted): ${e.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "activity transition registration threw: ${e.javaClass.simpleName}")
            }
        } else {
            removeActivityTransitionUpdatesSafely(client, pendingIntent)
        }

        // sleep
        if (CollectionGate.collects(appContext, CollectionModuleId.SLEEP) && hasPermission) {
            try {
                client.requestSleepSegmentUpdates(pendingIntent, SleepSegmentRequest.getDefaultSleepSegmentRequest())
                    .addOnFailureListener { Log.w(TAG, "requestSleepSegmentUpdates failed: ${it.javaClass.simpleName}") }
            } catch (e: SecurityException) {
                Log.w(TAG, "sleep registration denied (ACTIVITY_RECOGNITION not granted): ${e.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "sleep registration threw: ${e.javaClass.simpleName}")
            }
        } else {
            removeSleepUpdatesSafely(client, pendingIntent)
        }
    }

    /** Removes both registrations (used on withdrawal / disable). */
    public fun unregisterAll(context: Context) {
        val appContext = context.applicationContext
        val pendingIntent = pendingIntent(appContext)
        val client = ActivityRecognition.getClient(appContext)
        removeActivityTransitionUpdatesSafely(client, pendingIntent)
        removeSleepUpdatesSafely(client, pendingIntent)
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

    private fun pendingIntent(appContext: Context): PendingIntent {
        val intent = Intent(appContext, SleepActivityReceiver::class.java)
            .setAction(SleepActivityReceiver.ACTION_SLEEP_ACTIVITY)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
    }
}
