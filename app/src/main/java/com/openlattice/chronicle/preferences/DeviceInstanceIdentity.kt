package com.openlattice.chronicle.preferences

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

private const val DEVICE_IDENTITY_PREFS_FILE = "chronicle_device_identity_prefs"
private const val DEVICE_INSTANCE_ID = "deviceInstanceId"

/**
 * App-private pseudonymous identity for one Chronicle app install on one device.
 *
 * This is not a hardware identifier and does not read Android ID, IMEI, serial,
 * MAC, or other device-provided stable IDs. Re-enrollment reuses this value while
 * app data is intact; uninstall/data wipe naturally creates a new app instance.
 */
object DeviceInstanceIdentity {
    fun getOrCreate(context: Context): String {
        val prefs = encryptedPrefs(context.applicationContext)
        prefs.getString(DEVICE_INSTANCE_ID, null)?.let { existing ->
            if (existing.isNotBlank()) return existing
        }

        val generated = UUID.randomUUID().toString()
        check(prefs.edit().putString(DEVICE_INSTANCE_ID, generated).commit()) {
            "Unable to persist Chronicle device instance identity"
        }
        return generated
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
