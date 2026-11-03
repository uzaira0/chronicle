@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

private const val TAG = "EncryptedPrefsHelper"
private const val ENCRYPTED_PREFS_FILE = "chronicle_encrypted_prefs"

object EncryptedPrefsHelper {

    @Volatile
    private var instance: SharedPreferences? = null

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createEncryptedPrefs(context).also { instance = it }
        }
    }

    /** Preserve every available file when opening or reading the store fails. */
    fun unreadableStores(context: Context): List<File> {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$ENCRYPTED_PREFS_FILE.xml")
        return if (runCatching { getEncryptedPrefs(context).all }.isFailure) {
            listOf(file, File(file.path + ".bak")).filter(File::exists)
        } else emptyList()
    }

    /** After recovery preserved [unreadableStores]: removes them so the next open starts an empty store. */
    fun replaceUnreadableStore(context: Context): Boolean = synchronized(this) {
        instance = null
        context.deleteSharedPreferences(ENCRYPTED_PREFS_FILE)
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable; refusing plaintext fallback", e)
            throw SecurePreferencesUnavailableException(e)
        }

    }


}
