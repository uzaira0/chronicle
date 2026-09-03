package com.openlattice.chronicle.collection.device

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import kotlinx.coroutines.runBlocking

/**
 * The Health Connect grant flow for the `health_connect` module. Health Connect does NOT use the
 * normal Android runtime-permission dialog — the participant grants read access on a dedicated
 * Health Connect screen, launched via [requestContract]. Nothing in the app prompted before, so the
 * module read [getGrantedPermissions][androidx.health.connect.client.PermissionController.getGrantedPermissions]
 * and always found nothing; this is the missing prompt.
 *
 * [READ_PERMISSIONS] is exactly the data-type set [AndroidHealthMetricSource] reads. The request also
 * includes background read access when the device supports it because collection is WorkManager-driven.
 * Manifest and request parity is enforced by `ModulePermissionCoverageTest`.
 */
public object HealthConnectPermissions {
    private const val TAG = "HealthConnectPermissions"

    /** The Health Connect read permissions the module requests — must match what the source reads. */
    public val READ_PERMISSIONS: Set<String> = ModulePermissions.HEALTH_CONNECT_READ.toSet()

    /**
     * Exact active-study data-type permissions plus supported background access. An empty or
     * unreadable study scope requests nothing.
     */
    public fun permissionsToRequest(context: Context): Set<String> {
        val configured = runCatching { HealthConnectScopeStore.of(context).read() }
            .onFailure { Log.e(TAG, "Health Connect scope is unavailable; requesting nothing", it) }
            .getOrDefault(emptySet())
        return permissionsToRequest(context, configured)
    }

    /** Builds a permission request for the exact scope already disclosed to the participant. */
    public fun permissionsToRequest(
        context: Context,
        configured: Set<com.openlattice.chronicle.collection.HealthConnectRecordType>,
    ): Set<String> {
        val scopedReads = ModulePermissions.healthConnectReadPermissionsFor(configured)
        if (scopedReads.isEmpty()) return emptySet()
        return buildSet {
            addAll(scopedReads)
            if (supportsBackgroundRead(context)) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
        }
    }

    /**
     * The `ActivityResultContract` that opens the Health Connect permission screen. Register it with
     * `registerForActivityResult(HealthConnectPermissions.requestContract())` and launch the result
     * of [permissionsToRequest].
     * The result is the set of permissions the participant actually granted.
     */
    public fun requestContract(): ActivityResultContract<Set<String>, Set<String>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PermissionController.createRequestPermissionResultContract()
        } else {
            object : ActivityResultContract<Set<String>, Set<String>>() {
                override fun createIntent(context: Context, input: Set<String>): Intent = Intent()
                override fun parseResult(resultCode: Int, intent: Intent?): Set<String> = emptySet()
            }
        }

    /** Whether Health Connect is installed/available on this device (cheap, synchronous, no IO). */
    public fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        HealthConnectClient.getSdkStatus(context.applicationContext) == HealthConnectClient.SDK_AVAILABLE

    /**
     * Whether every permission in the active study scope is already granted. Suspends under the hood (Health
     * Connect's permission query is coroutine-based); call OFF the main thread. Returns `false` if
     * Health Connect is unavailable or the query fails.
     */
    public fun allGranted(context: Context): Boolean {
        if (!isAvailable(context)) return false
        val granted = runCatching {
            runBlocking {
                HealthConnectClient.getOrCreate(context.applicationContext)
                    .permissionController.getGrantedPermissions()
            }
        }.onFailure { Log.w(TAG, "Health Connect permission query failed: ${it.javaClass.simpleName}") }
            .getOrDefault(emptySet())
        return granted.containsAll(permissionsToRequest(context))
    }

    private fun supportsBackgroundRead(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context.applicationContext).features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.onFailure { Log.w(TAG, "Health Connect feature query failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }
}
