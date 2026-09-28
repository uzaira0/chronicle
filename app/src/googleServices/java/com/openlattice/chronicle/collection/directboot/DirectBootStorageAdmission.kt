package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import java.util.UUID

/** Pause pre-unlock collection before the bounded encrypted buffer reaches its limit. */
internal object DirectBootStorageAdmission {
    private const val PREFS = "direct_boot_storage_admission"
    private const val PAUSED = "paused"
    private const val EPISODE_ID = "pause_episode_id"
    private const val RECHECK_MS = 30_000L
    @Volatile private var lastAllowed = true
    @Volatile private var lastCheckedAt = Long.MIN_VALUE

    internal fun cachedDecision(checkedAt: Long, now: Long, allowed: Boolean): Boolean? =
        allowed.takeIf { checkedAt != Long.MIN_VALUE && now - checkedAt in 0 until RECHECK_MS }

    internal fun safeDecision(decide: () -> Boolean): Boolean = try {
        decide()
    } catch (error: Exception) {
        runCatching { Log.e("DirectBootStorageAdmission", "Storage admission unavailable; refusing sensor sample", error) }
        false
    }

    fun allowed(context: Context, buffer: DirectBootSampleBuffer): Boolean {
        val now = SystemClock.elapsedRealtime()
        cachedDecision(lastCheckedAt, now, lastAllowed)?.let { return it }
        return synchronized(this) {
            cachedDecision(lastCheckedAt, now, lastAllowed)?.let { return@synchronized it }
            safeDecision { evaluate(context, buffer) }.also {
                lastAllowed = it
                lastCheckedAt = now
            }
        }
    }

    internal fun evaluate(
        context: Context,
        buffer: DirectBootSampleBuffer,
        usableBytes: Long = directBootFilesDir(context).usableSpace,
        journal: DirectBootDiagnosticsJournal = DirectBootDiagnosticsJournal(context),
    ): Boolean {
        val protectedContext = context.createDeviceProtectedStorageContext()
        val prefs = protectedContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paused = !buffer.hasAdmissionCapacity() || usableBytes < LOCAL_STORAGE_RESERVE_BYTES
        val wasPaused = prefs.getBoolean(PAUSED, false)
        if (paused) {
            val episodeId = prefs.getString(EPISODE_ID, null)?.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString()
            if (!wasPaused || prefs.getString(EPISODE_ID, null) != episodeId) {
                check(prefs.edit().putBoolean(PAUSED, true).putString(EPISODE_ID, episodeId).commit())
            }
            journal.record("COLLECTION_PAUSED_STORAGE", 1, episodeId)
        } else if (wasPaused) {
            check(prefs.edit().putBoolean(PAUSED, false).remove(EPISODE_ID).commit())
            if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
                runCatching { DirectBootDrainWorker.enqueue(context) }
                    .onFailure { Log.e("DirectBootStorageAdmission", "Unable to enqueue resumed drain", it) }
            }
        }
        return !paused
    }
}
