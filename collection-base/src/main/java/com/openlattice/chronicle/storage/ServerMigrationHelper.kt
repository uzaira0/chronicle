package com.openlattice.chronicle.storage

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper

private const val TAG = "ServerMigrationHelper"
private const val LEGACY_SERVER_DEVICE_UUID = "serverDeviceUUID"
private const val PREF_MIGRATION_DONE = "server_migration_v6_done"

/**
 * One-shot migration from the pre-source-device schema.
 *
 * v5-schema installs that already had `upload_servers` rows but no
 *      `sourceDeviceId`. Backfill from the legacy global SERVER_DEVICE_UUID.
 *
 * A missing Room row is never reconstructed from preference identity: doing so would invent an
 * operator destination after the one-study migration deliberately discarded ambiguous routing.
 * Such installs must explicitly re-enroll from a current study invitation.
 *
 * Runs once per install (gated by [PREF_MIGRATION_DONE]). Not on the upload
 * hot path — call from app startup or before the first upload.
 */
object ServerMigrationHelper {
    fun migrateIfNeeded(context: Context, db: ChronicleDb) {
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        if (prefs.getBoolean(PREF_MIGRATION_DONE, false)) return

        try {
            val dao = db.uploadServerDao()
            val legacyDeviceId = prefs.getString(LEGACY_SERVER_DEVICE_UUID, "") ?: ""

            // Backfill an existing, explicit server row only. Never invent a destination.
            if (legacyDeviceId.isNotBlank()) {
                val backfilled = dao.backfillEmptySourceDeviceId(legacyDeviceId)
                if (backfilled > 0) {
                    Log.i(TAG, "Backfilled sourceDeviceId on $backfilled server row(s) from legacy global UUID")
                }
            }

            prefs.edit().putBoolean(PREF_MIGRATION_DONE, true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "v6 migration failed; will retry on next launch", e)
        }
    }
}
