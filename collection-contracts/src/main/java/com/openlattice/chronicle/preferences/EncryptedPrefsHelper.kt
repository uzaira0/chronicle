@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "EncryptedPrefsHelper"
private const val ENCRYPTED_PREFS_FILE = "chronicle_encrypted_prefs"
private const val MIGRATION_DONE_KEY = "encrypted_prefs_migration_done"

object EncryptedPrefsHelper {

    @Volatile
    private var instance: SharedPreferences? = null

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createEncryptedPrefs(context).also { instance = it }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val encryptedPrefs = try {
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
            throw IllegalStateException("Secure preference storage is unavailable", e)
        }

        // Migration is separate — a migration failure must not cause encryption fallback
        try {
            migrateFromUnencrypted(context, encryptedPrefs)
        } catch (e: Exception) {
            Log.e(TAG, "Migration from unencrypted prefs failed, encrypted prefs still active", e)
        }

        return encryptedPrefs
    }

    private fun migrateFromUnencrypted(context: Context, encryptedPrefs: SharedPreferences) {
        if (encryptedPrefs.getBoolean(MIGRATION_DONE_KEY, false)) return

        val oldPrefs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        val allOld = oldPrefs.all
        if (allOld.isEmpty()) {
            encryptedPrefs.edit().putBoolean(MIGRATION_DONE_KEY, true).commit()
            return
        }

        Log.i(TAG, "Migrating ${allOld.size} preferences to encrypted storage")
        try {
            val editor = encryptedPrefs.edit()
            for ((key, value) in allOld) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    }
                }
            }
            editor.putBoolean(MIGRATION_DONE_KEY, true)
            val success = editor.commit()
            if (!success) {
                Log.e(TAG, "Failed to write encrypted preferences, preserving old prefs")
                return
            }
            oldPrefs.edit().clear().commit()
            Log.i(TAG, "Migration complete, old preferences cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed, preserving old prefs", e)
        }
    }
}
