package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import java.time.OffsetDateTime
import java.util.UUID

/** Pause pre-unlock collection before the bounded encrypted buffer reaches its limit. */
internal object DirectBootStorageAdmission {
    private const val PREFS = "direct_boot_storage_admission"
    private const val PAUSED = "paused"
    private const val EPISODE_ID = "pause_episode_id"
    private const val EPISODE_AT = "pause_episode_at"
    private const val EPISODE_RECORDED = "pause_episode_recorded"
    private const val EPISODE_OWNER = "pause_episode_owner"
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

    @Volatile private var refreshSource: Pair<Context, DirectBootSampleBuffer>? = null
    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "direct-boot-admission").apply { isDaemon = true }
    }

    fun cachedAllowed(): Boolean {
        cachedDecision(lastCheckedAt, SystemClock.elapsedRealtime(), lastAllowed)?.let { return it }
        val source = refreshSource
        if (source != null && refreshQueued.compareAndSet(false, true)) refreshWorker.execute {
            try { allowed(source.first, source.second) }
            finally { refreshQueued.set(false) }
        }
        return false
    }

    fun allowed(context: Context, buffer: DirectBootSampleBuffer): Boolean {
        refreshSource = context.applicationContext to buffer
        val now = SystemClock.elapsedRealtime()
        cachedDecision(lastCheckedAt, now, lastAllowed)?.let { return it }
        return synchronized(this) {
            synchronized(DIRECT_BOOT_BUFFER_LOCK) {
                cachedDecision(lastCheckedAt, now, lastAllowed) ?: safeDecision { evaluate(context, buffer) }.also {
                    lastAllowed = it
                    lastCheckedAt = now
                }
            }
        }
    }

    fun clear(context: Context) = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        val protectedContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
        check(protectedContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()) { "Unable to erase direct-boot storage admission episode" }
        refreshSource = null
        lastCheckedAt = Long.MIN_VALUE
        lastAllowed = true
    }

    internal fun evaluate(
        context: Context,
        buffer: DirectBootSampleBuffer,
        usableBytes: Long = directBootFilesDir(context).usableSpace,
        journal: DirectBootDiagnosticsJournal = DirectBootDiagnosticsJournal(context),
    ): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        val protectedContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
        val prefs = protectedContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paused = !buffer.hasAdmissionCapacity() || usableBytes < LOCAL_STORAGE_RESERVE_BYTES
        val owner = journal.currentOwner()?.let(DirectBootSampleBuffer::ownerKey).orEmpty()
        var wasPaused = prefs.getBoolean(PAUSED, false)
        if (wasPaused && (!prefs.contains(EPISODE_OWNER) || prefs.getString(EPISODE_OWNER, null) != owner)) {
            check(prefs.edit().clear().commit())
            wasPaused = false
        }
        if (paused) {
            val episodeId = prefs.getString(EPISODE_ID, null)?.takeIf { wasPaused && it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            if (!wasPaused || prefs.getString(EPISODE_ID, null) != episodeId) {
                check(prefs.edit().putBoolean(PAUSED, true).putString(EPISODE_ID, episodeId)
                    .putString(EPISODE_AT, OffsetDateTime.now().toString())
                    .putString(EPISODE_OWNER, owner)
                    .putBoolean(EPISODE_RECORDED, false).commit())
            }
        }
        if ((paused || wasPaused) && !prefs.getBoolean(EPISODE_RECORDED, false)) {
            val episodeId = requireNotNull(prefs.getString(EPISODE_ID, null))
            val occurredAt = prefs.getString(EPISODE_AT, null) ?: OffsetDateTime.now().toString().also {
                check(prefs.edit().putString(EPISODE_AT, it).commit())
            }
            journal.record("COLLECTION_PAUSED_STORAGE", 1, episodeId, OffsetDateTime.parse(occurredAt))
            check(prefs.edit().putBoolean(EPISODE_RECORDED, true).commit())
        }
        if (!paused && wasPaused) {
            check(prefs.edit().putBoolean(PAUSED, false).remove(EPISODE_ID)
                .remove(EPISODE_AT).remove(EPISODE_RECORDED).remove(EPISODE_OWNER).commit())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                context.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
                runCatching { DirectBootDrainWorker.enqueue(context) }
                    .onFailure { Log.e("DirectBootStorageAdmission", "Unable to enqueue resumed drain", it) }
            }
        }
        !paused
    }
}
