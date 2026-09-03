package com.openlattice.chronicle.collection.state

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.UploadServerEntity
import java.time.OffsetDateTime

private const val TAG = "CollectionAckRetryStore"
private const val PREF_PENDING_COLLECTION_ACKS = "pending_collection_acknowledgments"

data class PendingCollectionAckRecord(
    val serverId: Long,
    val studyId: String? = null,
    val participantId: String? = null,
    val sourceDeviceId: String? = null,
    val acceptedModuleIds: List<String>,
    val declinedModuleIds: List<String>,
    val unavailableModuleIds: List<String>? = null,
    val trigger: String,
    val acknowledgedAt: String,
    val settingsVersion: Int? = null,
    val disclosureVersion: String? = null,
    val manifestDigest: String? = null,
) {
    fun stableKey(): String =
        listOf(
            serverId.toString(),
            studyId.orEmpty(),
            participantId.orEmpty(),
            sourceDeviceId.orEmpty(),
            acceptedModuleIds.sorted().joinToString(","),
            declinedModuleIds.sorted().joinToString(","),
            unavailableModuleIds.orEmpty().sorted().joinToString(","),
            trigger,
            acknowledgedAt,
            settingsVersion?.toString().orEmpty(),
            disclosureVersion.orEmpty(),
            manifestDigest.orEmpty(),
        ).joinToString("|")

    fun hasCompleteEnrollmentIdentity(): Boolean =
        listOf(studyId, participantId, sourceDeviceId, disclosureVersion, manifestDigest)
            .none { it.isNullOrBlank() }

    /**
     * Records written by the supported predecessor predate the explicit identity fields, but
     * already contain immutable enrollment-manifest and disclosure evidence. They may be rebound
     * only to the same Room row and exact current evidence; partial/corrupt records never qualify.
     */
    fun isLegacyIdentityFree(): Boolean =
        listOf(studyId, participantId, sourceDeviceId).all { it.isNullOrBlank() } &&
            !disclosureVersion.isNullOrBlank() &&
            !manifestDigest.isNullOrBlank()

    fun isLegacyBoundTo(server: UploadServerEntity): Boolean =
        isLegacyIdentityFree() &&
            serverId == server.id &&
            disclosureVersion == server.disclosureVersion &&
            manifestDigest == server.manifestDigest

    /** Prevents a retained acknowledgment from crossing a withdrawal/re-enrollment boundary. */
    fun isBoundTo(server: UploadServerEntity): Boolean =
        hasCompleteEnrollmentIdentity() &&
            studyId == server.studyId &&
            participantId == server.participantId &&
            sourceDeviceId == server.sourceDeviceId &&
            disclosureVersion == server.disclosureVersion &&
            manifestDigest == server.manifestDigest

    fun toAcknowledgmentOrNull(): CollectionAcknowledgment? {
        fun decodeExact(ids: List<String>): Set<CollectionModuleId>? {
            val decoded = ids.map { id -> CollectionModuleId.fromIdOrNull(id) ?: return null }
            return decoded.toSet()
        }
        val accepted = decodeExact(acceptedModuleIds) ?: return null
        val declined = decodeExact(declinedModuleIds) ?: return null
        val unavailable = decodeExact(unavailableModuleIds.orEmpty()) ?: return null
        if (accepted.isEmpty() && declined.isEmpty() && unavailable.isEmpty()) return null
        val parsedTrigger = runCatching { ConsentTrigger.valueOf(trigger) }.getOrNull() ?: return null
        val parsedAt = runCatching { OffsetDateTime.parse(acknowledgedAt) }.getOrNull() ?: return null
        return CollectionAcknowledgment(
            acknowledgedModules = accepted,
            acknowledgedAt = parsedAt,
            declinedModules = declined,
            unavailableModules = unavailable,
            trigger = parsedTrigger,
            appVersion = null,
            settingsVersion = settingsVersion,
            disclosureVersion = disclosureVersion?.takeIf { manifestDigest != null },
            manifestDigest = manifestDigest?.takeIf { disclosureVersion != null },
        )
    }

    companion object {
        fun from(
            server: UploadServerEntity,
            accepted: Set<CollectionModuleId>,
            declined: Set<CollectionModuleId>,
            unavailable: Set<CollectionModuleId> = emptySet(),
            trigger: ConsentTrigger,
            acknowledgedAt: OffsetDateTime,
            settingsVersion: Int? = null,
        ): PendingCollectionAckRecord = PendingCollectionAckRecord(
            serverId = server.id,
            studyId = server.studyId,
            participantId = server.participantId,
            sourceDeviceId = server.sourceDeviceId,
            acceptedModuleIds = accepted.map { it.id }.sorted(),
            declinedModuleIds = declined.map { it.id }.sorted(),
            unavailableModuleIds = unavailable.map { it.id }.sorted(),
            trigger = trigger.name,
            acknowledgedAt = acknowledgedAt.toString(),
            settingsVersion = settingsVersion,
            disclosureVersion = server.disclosureVersion,
            manifestDigest = server.manifestDigest,
        )
    }
}

data class CollectionAckRetryResult(
    val removedStableKeys: Set<String>,
    val allAttemptedSucceeded: Boolean,
)

enum class PendingCollectionAckDiscardReason {
    LEGACY_OR_INCOMPLETE_IDENTITY,
    INVALID_ENROLLMENT_IDENTITY,
    ENROLLMENT_IDENTITY_MISMATCH,
    INVALID_ACKNOWLEDGMENT,
}

interface CollectionAckRetryPersistence {
    fun load(): List<PendingCollectionAckRecord>
    fun save(records: List<PendingCollectionAckRecord>)
}

class CollectionAckRetryQueue(
    private val persistence: CollectionAckRetryPersistence,
) {
    fun load(): List<PendingCollectionAckRecord> = synchronized(mutationLock) {
        persistence.load()
    }

    fun enqueue(records: Collection<PendingCollectionAckRecord>) {
        if (records.isEmpty()) return
        synchronized(mutationLock) {
            val merged = linkedMapOf<String, PendingCollectionAckRecord>()
            persistence.load().forEach { merged[it.stableKey()] = it }
            records.forEach { merged[it.stableKey()] = it }
            persistence.save(merged.values.toList())
        }
    }

    /** Removes only records proven complete/stale from the latest durable value. */
    fun removeByStableKeys(stableKeys: Set<String>) {
        if (stableKeys.isEmpty()) return
        synchronized(mutationLock) {
            val latest = persistence.load()
            persistence.save(latest.filterNot { it.stableKey() in stableKeys })
        }
    }

    /** Idempotent terminal mutation shared by immediate and worker withdrawal cleanup. */
    fun clearForWithdrawal() {
        synchronized(mutationLock) {
            persistence.save(emptyList())
        }
    }

    companion object {
        private val mutationLock = Any()

        fun of(context: Context): CollectionAckRetryQueue =
            CollectionAckRetryQueue(EncryptedPrefsCollectionAckRetryPersistence(context.applicationContext))
    }
}

private class EncryptedPrefsCollectionAckRetryPersistence(
    context: Context,
) : CollectionAckRetryPersistence {
    private val prefs: SharedPreferences = EncryptedPrefsHelper.getEncryptedPrefs(context)
    override fun load(): List<PendingCollectionAckRecord> {
        val json = prefs.getString(PREF_PENDING_COLLECTION_ACKS, null) ?: return emptyList()
        return try {
            JsonSerializer.fromJson<List<PendingCollectionAckRecord>>(json) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read pending collection acknowledgments; dropping corrupt retry state", e)
            emptyList()
        }
    }

    override fun save(records: List<PendingCollectionAckRecord>) {
        val editor = prefs.edit()
        if (records.isEmpty()) {
            editor.remove(PREF_PENDING_COLLECTION_ACKS)
        } else {
            editor.putString(PREF_PENDING_COLLECTION_ACKS, JsonSerializer.toJson(records))
        }
        if (!editor.commit()) {
            throw IllegalStateException("Failed to persist pending collection acknowledgments")
        }
    }
}
