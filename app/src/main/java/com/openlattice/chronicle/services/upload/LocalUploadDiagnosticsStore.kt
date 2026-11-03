package com.openlattice.chronicle.services.upload

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullAccounting
import com.openlattice.chronicle.collection.state.CollectionHardStop
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.generated.UploadDiagnosticConstraints.MAX_ERROR_TYPE_LENGTH
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.QuarantinedQueueRow
import com.openlattice.chronicle.storage.DiagnosticDeliveryState
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UploadSizeRejection
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import retrofit2.HttpException

private const val DIAGNOSTICS_TAG = "LocalUploadDiagnostics"
enum class LocalUploadModuleFamily {
    USAGE_LIFECYCLE, BATTERY, DEVICE_TELEMETRY, SENSOR, APP_RUNTIME,
    INTERACTION, AUDIO_ACTIVITY, AUDIO_CONTENT, NOTIFICATION, SLEEP,
    ACTIVITY_RECOGNITION, HEALTH, CONNECTIVITY, APP_NETWORK, DEVICE_SETTINGS, LOCAL_STORE;

    val uploadLabel: String
        get() = UploadQueueTable.entries.singleOrNull { it.uploaded && uploadFamily(it) == this }?.name ?: name
}

enum class UploadFailureIssue {
    HTTP_SERVER_ERROR, HTTP_CLIENT_ERROR, TIMEOUT, DNS_FAILURE,
    TLS_FAILURE, CONNECTION_FAILURE, UPLOAD_FAILURE,
}

enum class LocalOperationalIssue {
    APP_CRASH, APP_CRASH_NATIVE, APP_ANR,
    SAMPLE_QUARANTINED, LOCAL_BUFFER_OVERFLOW, LOCAL_REQUEUE_OVERFLOW,
    LOCAL_WRITE_FAILED, LOCAL_SHUTDOWN_DROPPED, COLLECTION_GATE_DROPPED,
    MODULE_POLICY_ERASED,
    DIRECT_BOOT_CAPACITY_DROPPED, DIRECT_BOOT_CORRUPT_RECORD,
    COLLECTION_PAUSED_STORAGE, COLLECTION_ACCESS_MISSING,
}

/** Count non-replayable sink refusals and failed writes. */
fun countAbandonedGateBatch(result: ModuleResult, records: Int,
                            record: (Int) -> Unit) {
    if (!result.isSuccess && records > 0) record(records)
}

fun recordForExpectedOwner(context: Context, expectedOwner: UploadServerEntity?,
                           family: LocalUploadModuleFamily, issue: LocalOperationalIssue, records: Int) {
    if (records <= 0 || expectedOwner == null) return
    runCatching {
        ResearchPersistenceGate.runIfExpectedOwner(
            context, expectedOwner,
        ) {
            LocalUploadDiagnosticsStore.of(context).recordOperational(family, issue, records)
            true
        }
    }.onFailure { Log.e(DIAGNOSTICS_TAG, "Unable to record enrollment-bound collection loss", it) }
}

fun recordAbandonedGateBatch(context: Context, expectedOwner: UploadServerEntity?, family: LocalUploadModuleFamily,
                             result: ModuleResult, records: Int) {
    countAbandonedGateBatch(result, records) { count ->
        val issue = if (result is ModuleResult.Failed)
            LocalOperationalIssue.LOCAL_WRITE_FAILED else LocalOperationalIssue.COLLECTION_GATE_DROPPED
        recordForExpectedOwner(context, expectedOwner, family, issue, count)
    }
}

/** Pull accounting that charges a dropped or failed write to the enrollment captured before the read. */
fun gateBatchAccounting(context: Context, family: LocalUploadModuleFamily): PullAccounting = PullAccounting(
    ownerForSample = { ResearchPersistenceGate.captureOwner(context) },
    accountWriteResult = { owner, result, count -> recordAbandonedGateBatch(context, owner, family, result, count) },
)

/**
 * One immutable occurrence: [count] equivalent failures at one instant. Occurrences are never merged, because a pause
 * the phone has not seen may separate them; the server filters each one by the participation window.
 */
data class LocalUploadIssueBucket(
    val day: String,
    val moduleFamily: String,
    val issue: String,
    val count: Int,
    val id: String = UUID.randomUUID().toString(),
    val firstOccurredAt: String = OffsetDateTime.now(ZoneOffset.UTC).toString(),
    val lastOccurredAt: String = firstOccurredAt,
    val httpStatus: Int? = null,
    val errorType: String? = null,
    val deliveryState: DiagnosticDeliveryState = DiagnosticDeliveryState.PENDING,
) {
    fun settled(outcome: DiagnosticDeliveryState) = copy(deliveryState = outcome.asOutcome())
}

interface LocalUploadDiagnosticsPersistence {
    fun load(): List<LocalUploadIssueBucket>
    fun save(buckets: List<LocalUploadIssueBucket>)
    fun clear() = save(emptyList())
}

class LocalUploadDiagnosticsStore(private val persistence: LocalUploadDiagnosticsPersistence) {
    fun record(moduleFamily: LocalUploadModuleFamily, issue: UploadDestinationIssue, day: LocalDate = LocalDate.now()) =
        recordRedacted(LocalUploadIssueBucket(day.toString(), moduleFamily.name, issue.name, 1))

    fun recordFailure(
        moduleFamily: LocalUploadModuleFamily,
        error: Exception,
        day: LocalDate = LocalDate.now(),
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ) {
        val status = uploadHttpStatus(error)
        val cause = uploadFailureCause(error)
        recordRedacted(LocalUploadIssueBucket(day.toString(), moduleFamily.name, classifyUploadFailure(error, status).name, 1,
            firstOccurredAt = occurredAt.toString(), lastOccurredAt = occurredAt.toString(),
            httpStatus = status, errorType = errorTypeLabel(cause.javaClass.simpleName)))
    }

    fun recordOperational(
        moduleFamily: LocalUploadModuleFamily,
        issue: LocalOperationalIssue,
        count: Int = 1,
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        day: LocalDate = occurredAt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate(),
    ) {
        if (count > 0) recordRedacted(LocalUploadIssueBucket(day.toString(), moduleFamily.name, issue.name, count,
            firstOccurredAt = occurredAt.toString(), lastOccurredAt = occurredAt.toString()))
    }

    /**
     * Stable event IDs make a retry after a process death safe before its watermark commits.
     * [ownerScope] (`studyId:participantId`) is the enrollment that observed the event; when this
     * store now belongs to a different enrollment the event is dropped, never re-attributed.
     */
    fun recordOperationalOnce(
        id: String,
        moduleFamily: LocalUploadModuleFamily,
        issue: LocalOperationalIssue,
        occurredAt: OffsetDateTime,
        ownerScope: String? = null,
    ) = withMutationLease {
        val day = occurredAt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().toString()
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.recordOnce(LocalUploadIssueBucket(day, moduleFamily.name, issue.name, 1, id = id,
                firstOccurredAt = occurredAt.toString(), lastOccurredAt = occurredAt.toString()), ownerScope)
        } else {
            val old = persistence.load()
            if (old.none { it.id == id }) persistence.save(old + LocalUploadIssueBucket(
                day, moduleFamily.name, issue.name, 1, id = id,
                firstOccurredAt = occurredAt.toString(), lastOccurredAt = occurredAt.toString(),
            ))
        }
    }

    private fun recordRedacted(occurrence: LocalUploadIssueBucket) = withMutationLease {
        if (persistence is RoomUploadDiagnosticsPersistence) persistence.record(occurrence)
        else persistence.save(persistence.load() + occurrence)
    }

    /**
     * The participant's own daily summary, never uploaded: one line per day and failure, whose [LocalUploadIssueBucket.count]
     * is the day's total and whose other fields are its first occurrence's.
     */
    fun recent(days: Long = 7, today: LocalDate = LocalDate.now()): List<LocalUploadIssueBucket> {
        require(days > 0)
        val cutoff = today.minusDays(days - 1).toString()
        val occurrences = synchronized(mutationLock) {
            if (persistence is RoomUploadDiagnosticsPersistence) {
                return@synchronized persistence.recent(cutoff, today.toString())
            }
            persistence.load().filter { it.day >= cutoff && it.day <= today.toString() }
                .sortedWith(compareByDescending<LocalUploadIssueBucket> { it.day }.thenBy { it.moduleFamily }.thenBy { it.issue }
                    .thenBy { it.firstOccurredAt })
        }
        return occurrences.groupBy { listOf(it.day, it.moduleFamily, it.issue, it.httpStatus, it.errorType) }.values
            .map { day -> day.first().copy(count = day.sumOf { it.count }) }
    }

    fun clear() = synchronized(mutationLock) { persistence.clear() }

    /** The oldest unsettled occurrences; rows are immutable, so a failed upload resends exactly these. */
    fun pending(): List<LocalUploadIssueBucket> = withMutationLease {
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.unreportedOversizedRows().forEach { row ->
                val table = UploadQueueTable.entries.singleOrNull { it.tableName == row.sourceTable }
                val family = table?.takeIf { it.uploaded }?.let(::uploadFamily) ?: LocalUploadModuleFamily.LOCAL_STORE
                recordOperationalOnce(row.id, family, LocalOperationalIssue.SAMPLE_QUARANTINED, OffsetDateTime.parse(row.createdAt))
            }
            return@withMutationLease persistence.pending()
        }
        persistence.load().filter { it.deliveryState == DiagnosticDeliveryState.PENDING }
            .sortedWith(compareBy<LocalUploadIssueBucket> { it.firstOccurredAt }.thenBy { it.id })
            .take(MAX_UPLOAD_BATCH)
    }

    /** Settles each occurrence as the server's receipt names it: stored rows DELIVERED, refused rows DISCARDED. */
    fun settle(receipt: UploadReceipt) = synchronized(mutationLock) {
        val outcomes = receipt.acceptedIds.associate { it.toString() to DiagnosticDeliveryState.DELIVERED } +
            receipt.discardedIds.associate { it.toString() to DiagnosticDeliveryState.DISCARDED }
        if (outcomes.isEmpty()) return@synchronized
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.settle(outcomes)
            return@synchronized
        }
        persistence.save(persistence.load().map { bucket ->
            outcomes[bucket.id]?.takeIf { bucket.deliveryState == DiagnosticDeliveryState.PENDING }?.let(bucket::settled) ?: bucket
        })
    }

    fun quarantineMalformed(ids: Set<String>, reason: UploadSizeRejection? = null) = synchronized(mutationLock) {
        if (ids.isEmpty()) return@synchronized
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.quarantineMalformed(ids, reason)
        } else {
            persistence.save(persistence.load().map {
                if (it.id in ids) it.settled(DiagnosticDeliveryState.QUARANTINED) else it
            })
        }
    }

    fun toWireEvents(buckets: List<LocalUploadIssueBucket>): List<AndroidUploadDiagnosticEvent> =
        buckets.mapNotNull { bucket ->
            runCatching {
                AndroidUploadDiagnosticEvent(
                    id = bucket.id, day = LocalDate.parse(bucket.day),
                    moduleFamily = bucket.moduleFamily, issueCode = bucket.issue,
                    count = bucket.count,
                    firstOccurredAt = OffsetDateTime.parse(bucket.firstOccurredAt),
                    lastOccurredAt = OffsetDateTime.parse(bucket.lastOccurredAt),
                    httpStatus = bucket.httpStatus, errorType = bucket.errorType,
                )
            }.onFailure { Log.w(DIAGNOSTICS_TAG, "Malformed upload diagnostic remains in local history ${bucket.id}", it) }.getOrNull()
        }

    private fun <T> withMutationLease(action: () -> T): T =
        ResearchPersistenceGate.withReadLease { synchronized(mutationLock, action) }

    companion object {
        private val mutationLock = Any()
        private const val MAX_UPLOAD_BATCH = 500
        internal fun errorTypeLabel(typeName: String): String = typeName.take(typeName.offsetByCodePoints(
            0, minOf(typeName.codePointCount(0, typeName.length), MAX_ERROR_TYPE_LENGTH),
        ))
        fun of(context: Context): LocalUploadDiagnosticsStore = LocalUploadDiagnosticsStore(
            RoomUploadDiagnosticsPersistence(context.applicationContext),
        )
    }
}

private fun uploadFailureCause(error: Exception): Throwable =
    generateSequence<Throwable>(error) { it.cause }.firstOrNull { it is IOException } ?: error

/** A failed or dropped connection, however the API proxy wrapped it; the run is retried. */
internal fun isTransportFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is IOException }

/** The diagnostics family each uploaded queue reports under. */
internal fun uploadFamily(table: UploadQueueTable): LocalUploadModuleFamily = when (table) {
    UploadQueueTable.USAGE -> LocalUploadModuleFamily.USAGE_LIFECYCLE
    UploadQueueTable.SENSOR -> LocalUploadModuleFamily.SENSOR
    UploadQueueTable.INTERACTION -> LocalUploadModuleFamily.INTERACTION
    UploadQueueTable.BATTERY -> LocalUploadModuleFamily.BATTERY
    UploadQueueTable.CONNECTIVITY_STATE -> LocalUploadModuleFamily.CONNECTIVITY
    UploadQueueTable.DEVICE_SETTINGS -> LocalUploadModuleFamily.DEVICE_SETTINGS
    UploadQueueTable.APP_NETWORK_USAGE -> LocalUploadModuleFamily.APP_NETWORK
    UploadQueueTable.SLEEP -> LocalUploadModuleFamily.SLEEP
    UploadQueueTable.ACTIVITY_RECOGNITION -> LocalUploadModuleFamily.ACTIVITY_RECOGNITION
    UploadQueueTable.AUDIO_ACTIVITY -> LocalUploadModuleFamily.AUDIO_ACTIVITY
    UploadQueueTable.AUDIO_CONTENT -> LocalUploadModuleFamily.AUDIO_CONTENT
    UploadQueueTable.NOTIFICATION_ACTIVITY -> LocalUploadModuleFamily.NOTIFICATION
    UploadQueueTable.HEALTH_CONNECT -> LocalUploadModuleFamily.HEALTH
    UploadQueueTable.DIAGNOSTICS, UploadQueueTable.QUARANTINE -> error("${table.name} is not uploaded as a queue")
}

internal fun classifyUploadFailure(error: Exception, httpStatus: Int? = null): UploadFailureIssue {
    val cause = uploadFailureCause(error)
    return when {
        httpStatus != null && httpStatus >= 500 -> UploadFailureIssue.HTTP_SERVER_ERROR
        httpStatus != null -> UploadFailureIssue.HTTP_CLIENT_ERROR
        cause is SocketTimeoutException -> UploadFailureIssue.TIMEOUT
        cause is UnknownHostException -> UploadFailureIssue.DNS_FAILURE
        cause is SSLException -> UploadFailureIssue.TLS_FAILURE
        cause is ConnectException -> UploadFailureIssue.CONNECTION_FAILURE
        else -> UploadFailureIssue.UPLOAD_FAILURE
    }
}

internal fun uploadHttpStatus(error: Throwable): Int? = when (error) {
    is ChronicleCallException -> error.code
    is HttpException -> error.code()
    else -> null
}

/** The `code` of the server's ApiError body, which names a refusal the phone acts on; null when there is none. */
internal fun serverErrorCode(error: Throwable): String? {
    val body = when (error) {
        is ChronicleCallException -> error.body
        is HttpException -> error.response()?.errorBody()?.string()
        else -> null
    } ?: return null
    return runCatching { JSONObject(body).optString("code").ifEmpty { null } }.getOrNull()
}

private class RoomUploadDiagnosticsPersistence(context: Context) : LocalUploadDiagnosticsPersistence {
    private val context = context.applicationContext
    private val prefs: SharedPreferences = EncryptedPrefsHelper.getEncryptedPrefs(context)
    private val db = ChronicleDb.getInstance(context)
    private val expectedServer: UploadServerEntity? = onIo {
        db.uploadServerDao().getConfiguredServer()
    }
    private val owner: Owner? = onIo {
        val server = expectedServer
        val study = prefs.getString(STUDY_ID, null)
        val participant = prefs.getString(PARTICIPANT_ID, null)
        if (server != null && server.studyId == study && server.participantId == participant &&
            server.sourceRandomDeviceId.isNotBlank()) {
            Owner(server.studyId, server.participantId, server.sourceRandomDeviceId, "${server.id}:${server.createdAt}")
        } else if (!study.isNullOrBlank() && !participant.isNullOrBlank()) {
            // Keep unattributed failures locally; never upload them as a later device.
            Owner(study, participant, "", "UNBOUND:$study:$participant")
        } else null
    }

    override fun load(): List<LocalUploadIssueBucket> = onIo {
        val scope = owner ?: return@onIo emptyList()
        db.uploadDiagnosticDao().forEnrollment(scope.study, scope.participant, scope.device, scope.epoch)
            .map { it.toBucket() }
    }

    fun recent(cutoff: String, today: String): List<LocalUploadIssueBucket> = onIo {
        val scope = owner ?: return@onIo emptyList()
        db.uploadDiagnosticDao().recent(scope.study, scope.participant, scope.device, scope.epoch, cutoff, today)
            .map { it.toBucket() }
    }

    fun record(occurrence: LocalUploadIssueBucket) = writeIfCurrentOwner {
        val scope = owner
        if (scope == null) save(listOf(occurrence)) else db.uploadDiagnosticDao().upsert(occurrence.toEntity(scope))
    }

    fun recordOnce(bucket: LocalUploadIssueBucket, ownerScope: String? = null) = writeIfCurrentOwner {
        val scope = owner ?: return@writeIfCurrentOwner
        if (ownerScope != null && ownerScope != "${scope.study}:${scope.participant}") return@writeIfCurrentOwner
        db.uploadDiagnosticDao().insertIfAbsent(bucket.toEntity(scope))
    }

    fun unreportedOversizedRows(): List<QuarantinedQueueRow> = onIo {
        val scope = owner ?: return@onIo emptyList()
        db.localDataQuarantineDao().unreportedOversizedRows(scope.study, scope.participant, scope.device,
            UploadSizeRejection.PERSISTED_ROW_EXCEEDS_PAYLOAD_BUDGET.name)
    }

    private fun writeIfCurrentOwner(action: () -> Unit) {
        // Acquire on the caller so an existing lease can re-enter ahead of a queued stop.
        ResearchPersistenceGate.withReadLease {
            onIo {
                // A hard stop cleared this queue; nothing recorded after it may wait to upload.
                if (CollectionHardStop.applies(context) ||
                    owner?.study != prefs.getString(STUDY_ID, null) ||
                    owner?.participant != prefs.getString(PARTICIPANT_ID, null)) return@onIo
                val current = db.uploadServerDao().getConfiguredServer()
                val expected = expectedServer
                if (expected == null) {
                    if (current != null) return@onIo
                } else if (current == null || current.id != expected.id || current.createdAt != expected.createdAt ||
                    current.studyId != expected.studyId || current.participantId != expected.participantId ||
                    current.sourceRandomDeviceId != expected.sourceRandomDeviceId) return@onIo
                action()
            }
        }
    }

    fun pending(): List<LocalUploadIssueBucket> = onIo {
        val scope = owner ?: return@onIo emptyList()
        db.uploadDiagnosticDao().pending(scope.study, scope.participant, scope.device, scope.epoch).map { it.toBucket() }
    }

    fun settle(outcomes: Map<String, DiagnosticDeliveryState>) = onIo {
        val scope = owner ?: return@onIo
        db.runInTransaction {
            outcomes.entries.groupBy({ it.value }, { it.key }).forEach { (outcome, ids) ->
                db.uploadDiagnosticDao().settle(ids.toSet(), outcome, scope.study, scope.participant, scope.device, scope.epoch)
            }
        }
    }

    fun quarantineMalformed(ids: Set<String>, reason: UploadSizeRejection? = null) = onIo {
        val scope = owner ?: return@onIo
        db.runInTransaction {
            db.uploadDiagnosticDao().byIds(ids).forEach { row ->
                if (row.studyId != scope.study || row.participantId != scope.participant ||
                    row.randomDeviceId != scope.device || row.enrollmentEpoch != scope.epoch) return@forEach
                val now = OffsetDateTime.now()
                val inserted = db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                    id = "upload_diagnostics:${row.id}", sourceTable = "upload_diagnostics",
                    sourceId = row.id, studyId = scope.study, participantId = scope.participant,
                    randomDeviceId = scope.device, rawData = JsonSerializer.toJson(row.toBucket()).toByteArray(),
                    reason = reason?.name ?: LocalOperationalIssue.SAMPLE_QUARANTINED.name, createdAt = now.toString(),
                ))
                db.uploadDiagnosticDao().upsert(row.settled(DiagnosticDeliveryState.QUARANTINED))
                if (inserted != -1L) {
                    db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
                        id = UUID.randomUUID().toString(), studyId = scope.study,
                        participantId = scope.participant, randomDeviceId = scope.device,
                        enrollmentEpoch = scope.epoch, day = LocalDate.now().toString(),
                        moduleFamily = LocalUploadModuleFamily.LOCAL_STORE.name,
                        issueCode = LocalOperationalIssue.SAMPLE_QUARANTINED.name,
                        count = 1, firstOccurredAt = now.toString(), lastOccurredAt = now.toString(),
                        httpStatus = null, errorType = null,
                    ))
                }
            }
        }
    }

    override fun save(buckets: List<LocalUploadIssueBucket>) = onIo {
        val scope = owner
        if (scope == null) {
            // A pre-enrollment diagnostic cannot be attributed to a participant. Retain its
            // redacted bucket in encrypted quarantine instead of silently losing it.
            buckets.forEach { bucket ->
                db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                    id = bucket.id, sourceTable = "unowned_upload_diagnostics", sourceId = bucket.id,
                    studyId = null, participantId = null, randomDeviceId = null,
                    rawData = JsonSerializer.toJson(bucket).toByteArray(), reason = "UNKNOWN_OWNER",
                    createdAt = OffsetDateTime.now().toString(),
                ))
            }
            return@onIo
        }
        db.runInTransaction {
            buckets.forEach { db.uploadDiagnosticDao().upsert(it.toEntity(scope)) }
        }
    }

    override fun clear() = onIo {
        val scope = owner
        db.runInTransaction {
            if (scope != null) {
                db.uploadDiagnosticDao().eraseEnrollment(scope.study, scope.participant)
                db.localDataQuarantineDao().eraseEnrollment(scope.study, scope.participant)
            }
        }
    }

    private inline fun <T> onIo(crossinline action: () -> T): T =
        if (db.inTransaction()) action()
        else runBlocking(Dispatchers.IO) { action() }
    private data class Owner(val study: String, val participant: String, val device: String, val epoch: String)
    private fun LocalUploadIssueBucket.toEntity(scope: Owner) = UploadDiagnosticEntity(
        id, scope.study, scope.participant, scope.device, scope.epoch, day, moduleFamily,
        issue, count, firstOccurredAt, lastOccurredAt, httpStatus, errorType, deliveryState,
    )
    private fun UploadDiagnosticEntity.toBucket() = LocalUploadIssueBucket(
        day, moduleFamily, issueCode, count, id, firstOccurredAt, lastOccurredAt,
        httpStatus, errorType, deliveryState,
    )
}
