package com.openlattice.chronicle.collection.state

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import java.time.OffsetDateTime
import java.util.UUID

/** Persistent pause episode state; existing rows are never evicted to make room. */
internal object StorageAdmission {
    private const val PAUSED = "collection_storage_paused"
    private const val SCOPE = "collection_storage_enrollment_scope"
    private const val EPISODE_ID = "collection_storage_pause_episode_id"
    // Sensor callbacks consult this per sample; free space moves slowly, so decide at most this often.
    private const val RECHECK_MS = 30_000L

    @Volatile private var lastAllowed = true
    @Volatile private var lastCheckedAt = Long.MIN_VALUE

    fun shouldPause(usableBytes: Long): Boolean = usableBytes < LOCAL_STORAGE_RESERVE_BYTES
    fun beginsPauseEpisode(wasPaused: Boolean, usableBytes: Long): Boolean =
        !wasPaused && shouldPause(usableBytes)

    fun allowed(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastCheckedAt != Long.MIN_VALUE && now - lastCheckedAt < RECHECK_MS) return lastAllowed
        return allowed(context, context.filesDir.usableSpace).also {
            lastAllowed = it
            lastCheckedAt = now
        }
    }

    fun allowed(context: Context, usableBytes: Long): Boolean = synchronized(this) {
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val scope = "${prefs.getString(STUDY_ID, "")}:${prefs.getString(PARTICIPANT_ID, "")}"
        val paused = shouldPause(usableBytes)
        val wasPaused = prefs.getString(SCOPE, null) == scope && prefs.getBoolean(PAUSED, false)
        if (paused) {
            val episodeId = prefs.getString(EPISODE_ID, null)
                .takeIf { wasPaused && !it.isNullOrBlank() }
                ?: UUID.randomUUID().toString()
            if (!wasPaused || prefs.getString(EPISODE_ID, null) != episodeId) {
                check(prefs.edit().putString(SCOPE, scope).putBoolean(PAUSED, true)
                    .putString(EPISODE_ID, episodeId).commit())
            }
            runCatching {
                LocalUploadDiagnosticsStore.of(context).recordOperationalOnce(
                    episodeId, LocalUploadModuleFamily.LOCAL_STORE,
                    LocalOperationalIssue.COLLECTION_PAUSED_STORAGE,
                    OffsetDateTime.now(),
                )
            }.onFailure { Log.e("StorageAdmission", "Unable to record storage pause", it) }
        } else if (wasPaused) {
            check(prefs.edit().putString(SCOPE, scope).putBoolean(PAUSED, false)
                .remove(EPISODE_ID).commit())
            runCatching { DistributionRestrictedRuntime.drainDirectBootSamples(context) }
                .onFailure { Log.e("StorageAdmission", "Unable to enqueue resumed direct-boot drain", it) }
        }
        !paused
    }
}
