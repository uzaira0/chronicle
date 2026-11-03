package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.services.sensors.HardwareSensorService

/**
 * Starts sensor collection between reboot and first unlock, which otherwise records nothing until the
 * participant unlocks the device. Only a usable [DirectBootSensorSnapshot] starts it, so an absent, stale or empty
 * snapshot collects nothing. Usage events need no bridge: the OS keeps the UsageStats timeline.
 */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) return
        if (context == null) return
        if (intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val started = try {
            val userManager = context.getSystemService(UserManager::class.java)
            // Unlocked (or no lock credential): StartOnBoot runs the normal path.
            if (userManager?.isUserUnlocked != false) return

            val snapshot = DirectBootSensorSnapshot(context)
            if (!snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS)) {
                Log.i(TAG, "No usable direct-boot snapshot; not collecting before first unlock")
                return
            }

            Log.i(TAG, "Locked boot: starting ${snapshot.collectableSensors().size} sensor(s) before unlock")
            HardwareSensorService.tryStartService(context)
        } catch (_: Exception) {
            Log.e(TAG, "Direct-boot sensor initialization unavailable; retrying after unlock")
            false
        }
        if (!started) Log.w(TAG, "Direct-boot sensor start refused; snapshot retained for unlock recovery")
    }

    companion object {
        private val TAG = LockedBootReceiver::class.java.simpleName
    }
}
