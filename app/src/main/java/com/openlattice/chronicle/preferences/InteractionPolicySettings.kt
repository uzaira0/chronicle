package com.openlattice.chronicle.preferences

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.serialization.JsonSerializer
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal const val INTERACTION_POLICY_SNAPSHOT_KEY = "interaction_policy_snapshot_v1"
private const val INTERACTION_POLICY_SNAPSHOT_VERSION = 1
private const val TAG = "InteractionPolicySettings"

/**
 * One atomic, study-bound copy of the interaction runtime policy.
 *
 * [settingsVersion] identifies the fetched DataCollection schema generation for diagnostics. The
 * enrolled [studyId] is the security boundary: a snapshot is never published to the hot path when
 * it belongs to another study, the enrollment is inactive, or the module is disabled.
 */
internal data class InteractionPolicySnapshot(
    val snapshotVersion: Int = INTERACTION_POLICY_SNAPSHOT_VERSION,
    val studyId: String,
    val settingsVersion: Int,
    val enabled: Boolean,
    val policy: InteractionPolicy,
) {
    init {
        require(snapshotVersion == INTERACTION_POLICY_SNAPSHOT_VERSION) {
            "Unsupported interaction-policy snapshot version: $snapshotVersion"
        }
        UUID.fromString(studyId)
    }

    fun activePolicyFor(currentStudyId: String?, enrolled: Boolean): InteractionPolicy? =
        policy.takeIf { enabled && enrolled && studyId == currentStudyId }
}

internal fun encodeInteractionPolicySnapshot(snapshot: InteractionPolicySnapshot): String =
    JsonSerializer.toJson(snapshot)

internal fun decodeInteractionPolicySnapshot(json: String): InteractionPolicySnapshot =
    requireNotNull(JsonSerializer.fromJson<InteractionPolicySnapshot>(json)) {
        "Interaction-policy snapshot decoded as null"
    }

/**
 * Durable cache of the enrolled study's interaction-event policy.
 *
 * The durable representation is one JSON value, rather than independently-read preference keys,
 * so an event can never observe a mixture of two policy generations. A process-wide atomic cache
 * is the Accessibility service's hot-path source; encrypted preferences are read only when this
 * store is first opened, saved, or cleared.
 *
 * Missing, corrupt, disabled, inactive-enrollment, and cross-study snapshots all fail closed to
 * `null`. There is deliberately no runtime fallback to [InteractionPolicy.DEFAULT]: the default is
 * resolved and persisted by the collection-settings coordinator before it opens collection gates.
 */
class InteractionPolicySettings(context: Context) {
    private val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)

    init {
        refreshCacheFromDisk()
    }

    /** Returns the already-validated in-memory policy, or `null` when collection must stay closed. */
    fun currentPolicy(): InteractionPolicy? = cachedSnapshot.get()?.policy

    /** Captures the exact published generation for a hot-path event. */
    internal fun currentSnapshot(): InteractionPolicySnapshot? = cachedSnapshot.get()

    /** True only while [snapshot] is still the exact generation published to the hot path. */
    internal fun isCurrent(snapshot: InteractionPolicySnapshot): Boolean = cachedSnapshot.get() === snapshot

    /**
     * Replaces the prior generation with [policy] for [studyId].
     *
     * The old generation is invalidated in memory before persistence. A failed write therefore
     * stops interaction collection for this process instead of continuing with stale policy.
     */
    fun save(
        studyId: UUID,
        settingsVersion: Int,
        enabled: Boolean,
        policy: InteractionPolicy,
    ): Boolean = synchronized(cacheLock) {
        val currentStudyId = prefs.getString(STUDY_ID, null)
        val enrolled = prefs.getString(PARTICIPATION_STATUS, null) == ParticipationStatus.ENROLLED.name
        if (!enrolled || currentStudyId != studyId.toString()) {
            cachedSnapshot.set(null)
            Log.w(TAG, "Refusing interaction policy for a non-current or inactive study")
            return@synchronized false
        }

        val snapshot = InteractionPolicySnapshot(
            studyId = studyId.toString(),
            settingsVersion = settingsVersion,
            enabled = enabled,
            policy = policy,
        )
        val encoded = encodeInteractionPolicySnapshot(snapshot)

        try {
            val existing = prefs.getString(INTERACTION_POLICY_SNAPSHOT_KEY, null)
            if (existing == encoded) {
                cachedSnapshot.set(snapshot.takeIf { it.enabled })
                return@synchronized true
            }

            // Remove the prior generation first. If publishing the replacement fails, a process
            // restart must still fail closed instead of reloading the stale durable snapshot.
            cachedSnapshot.set(null)
            if (!prefs.edit().remove(INTERACTION_POLICY_SNAPSHOT_KEY).commit()) {
                Log.e(TAG, "Failed to invalidate the prior interaction-policy snapshot")
                return@synchronized false
            }
            if (!prefs.edit().putString(INTERACTION_POLICY_SNAPSHOT_KEY, encoded).commit()) {
                cachedSnapshot.set(null)
                Log.e(TAG, "Failed to persist interaction-policy snapshot; collection remains closed")
                return@synchronized false
            }
            cachedSnapshot.set(snapshot.takeIf { it.enabled })
            true
        } catch (error: RuntimeException) {
            cachedSnapshot.set(null)
            Log.e(TAG, "Failed to persist interaction-policy snapshot; collection remains closed", error)
            false
        }
    }

    /** Clears both the durable generation and the process cache. */
    fun clear(): Boolean = synchronized(cacheLock) {
        cachedSnapshot.set(null)
        try {
            prefs.edit().remove(INTERACTION_POLICY_SNAPSHOT_KEY).commit().also { cleared ->
                if (!cleared) Log.e(TAG, "Failed to clear persisted interaction-policy snapshot")
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to clear persisted interaction-policy snapshot", error)
            false
        }
    }

    private fun refreshCacheFromDisk() = synchronized(cacheLock) {
        val currentStudyId = prefs.getString(STUDY_ID, null)
        val enrolled = prefs.getString(PARTICIPATION_STATUS, null) == ParticipationStatus.ENROLLED.name
        val snapshot = try {
            prefs.getString(INTERACTION_POLICY_SNAPSHOT_KEY, null)
                ?.let(::decodeInteractionPolicySnapshot)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Invalid interaction-policy snapshot; collection remains closed", error)
            null
        }
        cachedSnapshot.set(
            snapshot?.takeIf {
                it.activePolicyFor(currentStudyId = currentStudyId, enrolled = enrolled) != null
            },
        )
    }

    companion object {
        private val cacheLock = Any()
        private val cachedSnapshot = AtomicReference<InteractionPolicySnapshot?>(null)

        /** Immediately closes the process hot path before an enrollment identity transition. */
        internal fun invalidateMemoryCache() {
            cachedSnapshot.set(null)
        }
    }
}
