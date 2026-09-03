package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.services.sensors.HardwareSensorService

/**
 * Starts sensor collection in the direct-boot window — after a reboot but before the user's
 * first unlock, when `BOOT_COMPLETED` (and all credential-encrypted storage) is still held
 * back. Without this, a rebooted-but-locked device collects nothing until someone unlocks
 * it (observed live 2026-07-15: a locked Pixel sat silent for an hour post-reboot).
 *
 * Declared `directBootAware` on `LOCKED_BOOT_COMPLETED`. The decision to collect comes
 * exclusively from the device-protected [DirectBootSensorSnapshot] — written from live
 * consent-gate reads while unlocked, cleared on withdrawal, staleness-bounded — so this
 * path fails closed whenever the snapshot is absent, stale, or empty. `HardwareSensorService`
 * detects the locked user state itself and runs against the direct-boot buffer; its unlock
 * transition (and `StartOnBoot`, which fires at first unlock) hands over to the normal
 * credential-encrypted pipeline.
 *
 * Usage events need no equivalent: the OS records the UsageStats timeline itself, and the
 * post-unlock collector reads the locked window back retroactively.
 */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) return
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked != false) {
            // No lock credential (or already unlocked): BOOT_COMPLETED → StartOnBoot runs
            // the normal startup path momentarily; nothing to bridge.
            return
        }

        val snapshot = DirectBootSensorSnapshot(context)
        if (!snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS)) {
            Log.i(TAG, "No usable direct-boot snapshot; not collecting before first unlock")
            return
        }

        Log.i(
            TAG,
            "Locked boot: starting sensor collection for ${snapshot.collectableSensors().size} sensor(s) pre-unlock",
        )
        HardwareSensorService.tryStartService(context)
    }

    companion object {
        private val TAG = LockedBootReceiver::class.java.simpleName
    }
}
