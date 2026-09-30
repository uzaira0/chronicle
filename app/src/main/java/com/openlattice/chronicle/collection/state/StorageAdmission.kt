package com.openlattice.chronicle.collection.state

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    private const val EPISODE_AT = "collection_storage_pause_episode_at"
    private const val EPISODE_RECORDED = "collection_storage_pause_episode_recorded"
    // Sensor callbacks consult this per sample; free space moves slowly, so decide at most this often.
    private const val RECHECK_MS = 30_000L

    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val refreshPending = AtomicBoolean(false)
    @Volatile private var lastAllowed = false
    @Volatile private var lastCheckedAt = Long.MIN_VALUE
    private var decisionGeneration = 0L

    fun shouldPause(usableBytes: Long): Boolean = usableBytes < LOCAL_STORAGE_RESERVE_BYTES
    fun beginsPauseEpisode(wasPaused: Boolean, usableBytes: Long): Boolean =
        !wasPaused && shouldPause(usableBytes)

    private fun safeDecision(decide: () -> Boolean): Boolean = try {
        decide()
    } catch (error: Exception) {
        runCatching { Log.e("StorageAdmission", "Storage admission unavailable; refusing sensor sample", error) }
        false
    }

    fun allowed(context: Context): Boolean = safeDecision {
        val now = SystemClock.elapsedRealtime()
        if (lastCheckedAt != Long.MIN_VALUE && now - lastCheckedAt < RECHECK_MS) return@safeDecision lastAllowed
        refresh(context.applicationContext)
    }

    /** Sensor callbacks never touch storage or acquire the research persistence gate. */
    fun cachedAllowed(context: Context): Boolean = safeDecision {
        val now = SystemClock.elapsedRealtime()
        if (lastCheckedAt != Long.MIN_VALUE && now - lastCheckedAt < RECHECK_MS) return@safeDecision lastAllowed

        // Expired admission fails closed while a worker refreshes space, preferences and diagnostics.
        if (refreshPending.compareAndSet(false, true)) {
            lastAllowed = false
            refreshExecutor.execute {
                try {
                    safeDecision { refresh(context.applicationContext) }
                } finally {
                    refreshPending.set(false)
                }
            }
        }
        false
    }

    private fun refresh(context: Context): Boolean =
        allowed(context, context.filesDir.usableSpace).also {
            lastAllowed = it
            lastCheckedAt = SystemClock.elapsedRealtime()
        }

    fun allowed(context: Context, usableBytes: Long): Boolean = safeDecision {
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val paused = shouldPause(usableBytes)
        val scope: String
        val generation: Long
        var pending: Pair<String, String>? = null
        synchronized(this) {
            generation = ++decisionGeneration
            scope = "${prefs.getString(STUDY_ID, "")}:${prefs.getString(PARTICIPANT_ID, "")}"
            val wasPaused = prefs.getString(SCOPE, null) == scope && prefs.getBoolean(PAUSED, false)
            if (paused) {
                val episodeId = prefs.getString(EPISODE_ID, null)
                    .takeIf { wasPaused && !it.isNullOrBlank() }
                    ?: UUID.randomUUID().toString()
                if (!wasPaused || prefs.getString(EPISODE_ID, null) != episodeId) {
                    check(prefs.edit().putString(SCOPE, scope).putBoolean(PAUSED, true)
                        .putString(EPISODE_ID, episodeId).putString(EPISODE_AT, OffsetDateTime.now().toString())
                        .putBoolean(EPISODE_RECORDED, false).commit())
                }
            }
            // Keep the episode pending across recovery until its stable-ID diagnostic is acknowledged.
            if ((paused || wasPaused) && !prefs.getBoolean(EPISODE_RECORDED, false)) {
                runCatching {
                    val episodeId = requireNotNull(prefs.getString(EPISODE_ID, null))
                    val occurredAt = prefs.getString(EPISODE_AT, null) ?: OffsetDateTime.now().toString().also {
                        check(prefs.edit().putString(EPISODE_AT, it).commit())
                    }
                    pending = episodeId to occurredAt
                }.onFailure { Log.e("StorageAdmission", "Unable to record storage pause", it) }
            }
        }
        // Diagnostics take the persistence lease, so never record while holding this monitor.
        pending?.let { (episodeId, occurredAt) ->
            runCatching {
                LocalUploadDiagnosticsStore.of(context).recordOperationalOnce(
                    episodeId, LocalUploadModuleFamily.LOCAL_STORE,
                    LocalOperationalIssue.COLLECTION_PAUSED_STORAGE, OffsetDateTime.parse(occurredAt),
                )
                synchronized(this) {
                    if (prefs.getString(SCOPE, null) == scope && prefs.getString(EPISODE_ID, null) == episodeId &&
                        !prefs.getBoolean(EPISODE_RECORDED, false)) {
                        check(prefs.edit().putBoolean(EPISODE_RECORDED, true).commit())
                    }
                }
            }.onFailure { Log.e("StorageAdmission", "Unable to record storage pause", it) }
        }
        val resumed = synchronized(this) {
            if (decisionGeneration == generation && !paused && prefs.getString(SCOPE, null) == scope &&
                prefs.getBoolean(PAUSED, false) && prefs.getBoolean(EPISODE_RECORDED, false)) {
                check(prefs.edit().putString(SCOPE, scope).putBoolean(PAUSED, false)
                    .remove(EPISODE_ID).remove(EPISODE_AT).remove(EPISODE_RECORDED).commit())
                true
            } else false
        }
        if (resumed) {
            runCatching { DistributionRestrictedRuntime.drainDirectBootSamples(context) }
                .onFailure { Log.e("StorageAdmission", "Unable to enqueue resumed direct-boot drain", it) }
        }
        !paused
    }
}
