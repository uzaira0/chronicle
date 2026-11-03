package com.openlattice.chronicle.collection.state

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.PendingCollectionAckEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.nextDecisionSeq
import com.squareup.moshi.JsonDataException
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID

private const val TAG = "CollectionAckRetryStore"

data class PendingCollectionAckRecord(
    val serverId: Long,
    val studyId: String? = null,
    val participantId: String? = null,
    val sourceRandomDeviceId: String? = null,
    val acceptedModuleIds: List<String>,
    val declinedModuleIds: List<String>,
    val unavailableModuleIds: List<String>? = null,
    val trigger: String,
    val acknowledgedAt: String,
    val settingsVersion: Int? = null,
    val disclosureVersion: String? = null,
    val manifestDigest: String? = null,
    /** App version the ack was first sent with; replayed unchanged so the server receipt matches. */
    val appVersion: String? = null,
    /** B8: the decision's sequence number; a retry after a lost response is recognized by it. */
    val decisionSeq: Long? = null,
) {
    fun stableKey(): String =
        listOf(
            serverId.toString(),
            studyId.orEmpty(),
            participantId.orEmpty(),
            sourceRandomDeviceId.orEmpty(),
            acceptedModuleIds.sorted().joinToString(","),
            declinedModuleIds.sorted().joinToString(","),
            unavailableModuleIds.orEmpty().sorted().joinToString(","),
            trigger,
            acknowledgedAt,
            settingsVersion?.toString().orEmpty(),
            disclosureVersion.orEmpty(),
            manifestDigest.orEmpty(),
            decisionSeq?.toString().orEmpty(),
        ).joinToString("|")

    fun discardReason(acknowledgment: CollectionAcknowledgment?): PendingCollectionAckDiscardReason? = when {
        acknowledgment == null -> PendingCollectionAckDiscardReason.INVALID_ACKNOWLEDGMENT
        !hasCompleteEnrollmentIdentity() ->
            PendingCollectionAckDiscardReason.INCOMPLETE_IDENTITY
        runCatching { UUID.fromString(studyId) }.isFailure ->
            PendingCollectionAckDiscardReason.INVALID_ENROLLMENT_IDENTITY
        else -> null
    }

    fun hasCompleteEnrollmentIdentity(): Boolean =
        listOf(studyId, participantId, sourceRandomDeviceId, disclosureVersion, manifestDigest)
            .none { it.isNullOrBlank() }

    /** Prevents a retained acknowledgment from crossing a withdrawal/re-enrollment boundary. */
    fun isBoundTo(server: UploadServerEntity): Boolean =
        hasCompleteEnrollmentIdentity() &&
            studyId == server.studyId &&
            participantId == server.participantId &&
            sourceRandomDeviceId == server.sourceRandomDeviceId &&
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
            appVersion = appVersion,
            settingsVersion = settingsVersion,
            disclosureVersion = disclosureVersion?.takeIf { manifestDigest != null },
            manifestDigest = manifestDigest?.takeIf { disclosureVersion != null },
            decisionSeq = decisionSeq,
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
            appVersion: String? = currentAppVersion(),
        ): PendingCollectionAckRecord = PendingCollectionAckRecord(
            serverId = server.id,
            studyId = server.studyId,
            participantId = server.participantId,
            sourceRandomDeviceId = server.sourceRandomDeviceId,
            acceptedModuleIds = accepted.map { it.id }.sorted(),
            declinedModuleIds = declined.map { it.id }.sorted(),
            unavailableModuleIds = unavailable.map { it.id }.sorted(),
            trigger = trigger.name,
            acknowledgedAt = acknowledgedAt.toString(),
            settingsVersion = settingsVersion,
            disclosureVersion = server.disclosureVersion,
            manifestDigest = server.manifestDigest,
            appVersion = appVersion,
        )
    }
}

/** The installed build, sent with every collection acknowledgment so operators see app versions. */
internal fun currentAppVersion(): String =
    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

data class CollectionAckRetryResult(
    val removedStableKeys: Set<String>,
    val allAttemptedSucceeded: Boolean,
)

enum class PendingCollectionAckDiscardReason {
    INCOMPLETE_IDENTITY,
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

        /** Setup recovery replays the exact enrollment evidence, including its original app version. */
        internal fun enqueueEnrollmentOnce(context: Context, server: UploadServerEntity,
                                           record: PendingCollectionAckRecord) = synchronized(mutationLock) {
            val db = ChronicleDb.getInstance(context)
            val key = enrollmentAckKey(server)
            db.runInTransaction {
                val receipt = db.lifecycleStateDao().get(key)
                if (db.lifecycleStateDao().get(enrollmentAckDeliveryKey(server))?.value == key) return@runInTransaction
                val saved = receipt?.value?.let {
                    checkNotNull(JsonSerializer.fromJson<PendingCollectionAckRecord>(it))
                } ?: record.copy(decisionSeq = db.nextDecisionSeq()).also {
                    db.lifecycleStateDao().upsert(LifecycleStateEntity(key, JsonSerializer.toJson(it), System.currentTimeMillis()))
                }
                of(context).enqueue(listOf(saved))
            }
        }

        private fun enrollmentAckKey(server: UploadServerEntity): String =
            "enrollment_ack:" + ResearchErasureFence.enrollmentKey(server)

        private fun enrollmentAckDeliveryKey(server: UploadServerEntity): String = enrollmentAckKey(server) + ":delivered"

        internal fun markEnrollmentDelivered(context: Context, server: UploadServerEntity) {
            val dao = ChronicleDb.getInstance(context).lifecycleStateDao()
            val receipt = dao.get(enrollmentAckKey(server)) ?: return
            dao.upsert(LifecycleStateEntity(enrollmentAckDeliveryKey(server), receipt.key, System.currentTimeMillis()))
        }

        /**
         * B7: the lowest decision sequence the server has not received. A row captured at or after it waits, so the
         * server judges it with the decision that covers it.
         */
        internal fun pendingDecisionFloor(db: ChronicleDb): Long? =
            CollectionAckRetryQueue(RoomCollectionAckRetryPersistence(db)).load().mapNotNull { it.decisionSeq }.minOrNull()

        fun of(context: Context): CollectionAckRetryQueue =
            CollectionAckRetryQueue(RoomCollectionAckRetryPersistence(ChronicleDb.getInstance(context)))
    }
}

private class RoomCollectionAckRetryPersistence(private val db: ChronicleDb) : CollectionAckRetryPersistence {
    override fun load(): List<PendingCollectionAckRecord> = db.pendingCollectionAckDao().getAll().map { row ->
        try {
            JsonSerializer.fromJson<PendingCollectionAckRecord>(row.record)
        } catch (e: IOException) {
            throw unreadable(e)
        } catch (e: JsonDataException) {
            throw unreadable(e)
        } ?: throw unreadable(null)
    }

    private fun unreadable(cause: Exception?): LocalStoreRecoveryRequiredException {
        Log.e(TAG, "Pending collection acknowledgments are unreadable; retained for recovery")
        return LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.INVALID_PERSISTED_STATE, cause)
    }

    override fun save(records: List<PendingCollectionAckRecord>) = db.runInTransaction {
        db.pendingCollectionAckDao().clear()
        db.pendingCollectionAckDao().insertAll(records.map {
            PendingCollectionAckEntity(stableKey = it.stableKey(), record = JsonSerializer.toJson(it))
        })
    }
}
