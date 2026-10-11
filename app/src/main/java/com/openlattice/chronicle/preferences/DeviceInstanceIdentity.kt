@file:Suppress("DEPRECATION") // security-crypto 1.1.0 is deprecated upstream; Keystore migration tracked separately.

package com.openlattice.chronicle.preferences

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

internal const val DEVICE_IDENTITY_PREFS_FILE = "chronicle_device_identity_prefs"
private const val DEVICE_INSTANCE_ID = "deviceInstanceId"

/**
 * App-private pseudonymous identity for one Chronicle app install on one device.
 *
 * This is not a hardware identifier and does not read Android ID, IMEI, serial,
 * MAC, or other device-provided stable IDs. Acknowledged withdrawal, uninstall or
 * data wipe retires this identity before another enrollment.
 */
object DeviceInstanceIdentity {
    fun getOrCreate(context: Context): String = getOrCreate(encryptedPrefs(context.applicationContext))

    internal fun getOrCreate(prefs: android.content.SharedPreferences): String {
        prefs.getString(DEVICE_INSTANCE_ID, null)?.let { existing ->
            if (existing.isNotBlank()) return existing
        }

        val generated = UUID.randomUUID().toString()
        check(prefs.edit().putString(DEVICE_INSTANCE_ID, generated).commit()) {
            "Unable to persist Chronicle device instance identity"
        }
        return generated
    }

    fun reset(context: Context) {
        check(context.deleteSharedPreferences(DEVICE_IDENTITY_PREFS_FILE)) {
            "Unable to retire Chronicle device instance identity"
        }
    }

    private fun encryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            DEVICE_IDENTITY_PREFS_FILE,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
