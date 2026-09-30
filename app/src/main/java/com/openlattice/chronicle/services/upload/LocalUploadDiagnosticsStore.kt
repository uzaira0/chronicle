package com.openlattice.chronicle.services.upload

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.DiagnosticImportCheckpointEntity
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import retrofit2.HttpException

private const val DIAGNOSTICS_TAG = "LocalUploadDiagnostics"
private const val PREF_LOCAL_UPLOAD_ISSUES = "local_upload_issue_history"
private const val PREF_PARKED_PROBE_OFFSET = "local_upload_parked_probe_offset"
private const val PREF_DELIVERED_REPLAY_OFFSET = "local_upload_delivered_replay_offset"
private const val PREF_DELIVERED_REPLAY_DAY = "local_upload_delivered_replay_day"
private val LEGACY_IMPORT_LOCK = Any()

internal data class DeliveredReplaySelection(val day: String, val nextOffset: Int, val ids: Set<String>)

internal fun acknowledgeDeliveredReplaySelection(
    prefs: SharedPreferences,
    selection: DeliveredReplaySelection,
    submitted: Set<String>,
    acknowledged: Set<String>,
): Boolean {
    if (submitted != selection.ids || !acknowledged.containsAll(submitted)) return false
    check(prefs.edit().putInt(PREF_DELIVERED_REPLAY_OFFSET, selection.nextOffset)
        .putString(PREF_DELIVERED_REPLAY_DAY, selection.day).commit())
    return true
}

internal data class ParsedLegacyDiagnostics(
    val buckets: List<LocalUploadIssueBucket>,
    val quarantined: List<Pair<String, String>>,
)

/** Parses every legacy element independently; age, count, and unknown codes are not filters. */
internal fun parseLegacyDiagnosticJson(json: String, digest: String, assignOwner: Boolean): ParsedLegacyDiagnostics {
    val parsed = mutableListOf<LocalUploadIssueBucket>()
    val quarantined = mutableListOf<Pair<String, String>>()
    val elements = runCatching { JSONArray(json) }.getOrNull()
    if (elements == null) return ParsedLegacyDiagnostics(emptyList(), listOf(digest to json))
    for (index in 0 until elements.length()) {
        val raw = elements.get(index).toString()
        val bucket = runCatching { JsonSerializer.fromJson<LocalUploadIssueBucket>(raw) }.getOrNull()
        if (bucket == null || bucket.count <= 0 || bucket.id.isBlank() || !assignOwner) {
            quarantined += "$digest:$index" to raw
        } else {
            parsed += bucket
        }
    }
    return ParsedLegacyDiagnostics(parsed, quarantined)
}

// These names are the shared server catalog. Existing historical codes remain readable.
enum class LocalUploadModuleFamily {
    USAGE_LIFECYCLE, BATTERY, DEVICE_TELEMETRY, SENSOR, APP_RUNTIME,
    INTERACTION, AUDIO_ACTIVITY, AUDIO_CONTENT, NOTIFICATION, SLEEP,
    ACTIVITY_RECOGNITION, HEALTH, CONNECTIVITY, APP_NETWORK, DEVICE_SETTINGS, LOCAL_STORE,
}

enum class LocalOperationalIssue {
    SENSOR_SAMPLE_QUARANTINED, SENSOR_DEAD_LETTER_DROPPED,
    APP_CRASH, APP_CRASH_NATIVE, APP_ANR,
    SENSOR_AGE_EXPIRED, SENSOR_CAPACITY_DROPPED, USAGE_QUEUE_EVICTED,
    SAMPLE_QUARANTINED, LOCAL_BUFFER_OVERFLOW, LOCAL_REQUEUE_OVERFLOW,
    LOCAL_WRITE_FAILED, LOCAL_SHUTDOWN_DROPPED, COLLECTION_GATE_DROPPED,
    MODULE_POLICY_ERASED, DISTRIBUTION_POLICY_ERASED,
    DIRECT_BOOT_CAPACITY_DROPPED, DIRECT_BOOT_CORRUPT_RECORD,
    COLLECTION_PAUSED_STORAGE,
}

/** Count non-replayable sink refusals and failed writes. */
fun countAbandonedGateBatch(result: com.openlattice.chronicle.collection.core.ModuleResult, records: Int,
                            record: (Int) -> Unit) {
    if ((result is com.openlattice.chronicle.collection.core.ModuleResult.Skipped ||
        result is com.openlattice.chronicle.collection.core.ModuleResult.Retry ||
        result is com.openlattice.chronicle.collection.core.ModuleResult.Failed) && records > 0) record(records)
}

fun recordForExpectedOwner(context: Context, expectedOwner: UploadServerEntity?,
                           family: LocalUploadModuleFamily, issue: LocalOperationalIssue, records: Int) {
    if (records <= 0 || expectedOwner == null) return
    runCatching {
        com.openlattice.chronicle.collection.state.ResearchPersistenceGate.runIfExpectedOwner(
            context, expectedOwner,
        ) {
            LocalUploadDiagnosticsStore.of(context).recordOperational(family, issue, records)
            true
        }
    }.onFailure { Log.e(DIAGNOSTICS_TAG, "Unable to record enrollment-bound collection loss", it) }
}

fun recordAbandonedGateBatch(context: Context, expectedOwner: UploadServerEntity?, family: LocalUploadModuleFamily,
                             result: com.openlattice.chronicle.collection.core.ModuleResult, records: Int) {
    countAbandonedGateBatch(result, records) { count ->
        val issue = if (result is com.openlattice.chronicle.collection.core.ModuleResult.Failed)
            LocalOperationalIssue.LOCAL_WRITE_FAILED else LocalOperationalIssue.COLLECTION_GATE_DROPPED
        recordForExpectedOwner(context, expectedOwner, family, issue, count)
    }
}

internal val V104_SERVER_ISSUE_CODES = setOf(
    "SENSOR_SAMPLE_QUARANTINED", "SENSOR_DEAD_LETTER_DROPPED",
    "APP_CRASH", "APP_CRASH_NATIVE", "APP_ANR",
)
internal val LEGACY_SERVER_MODULE_FAMILIES = setOf("USAGE_LIFECYCLE", "BATTERY", "DEVICE_TELEMETRY")
private val V104_SERVER_MODULE_FAMILIES = LEGACY_SERVER_MODULE_FAMILIES + setOf("SENSOR", "APP_RUNTIME")
private val V106_SERVER_ISSUE_CODES = setOf("SENSOR_AGE_EXPIRED", "SENSOR_CAPACITY_DROPPED", "USAGE_QUEUE_EVICTED")

/** Oldest server migration that accepts this bucket: 0 = V99, 1 = V104, 2 = V106, 3 = V107. */
internal fun serverTier(bucket: LocalUploadIssueBucket): Int = when {
    bucket.moduleFamily in LEGACY_SERVER_MODULE_FAMILIES && bucket.issue in LEGACY_SERVER_ISSUE_CODES -> 0
    bucket.moduleFamily !in V104_SERVER_MODULE_FAMILIES -> 3
    bucket.issue in LEGACY_SERVER_ISSUE_CODES || bucket.issue in V104_SERVER_ISSUE_CODES -> 1
    bucket.issue in V106_SERVER_ISSUE_CODES -> 2
    else -> 3
}
internal val LEGACY_SERVER_ISSUE_CODES: Set<String> =
    UploadDestinationIssue.entries.mapTo(mutableSetOf()) { it.name } + setOf(
        "HTTP_SERVER_ERROR", "HTTP_CLIENT_ERROR", "TIMEOUT", "DNS_FAILURE",
        "TLS_FAILURE", "CONNECTION_FAILURE", "UPLOAD_FAILURE",
    )

/** One immutable outbound bucket after [pending] seals it. */
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
    val deliveryState: String = "PENDING",
    val sealedForUpload: Boolean = false,
)

interface LocalUploadDiagnosticsPersistence {
    fun load(): List<LocalUploadIssueBucket>
    fun save(buckets: List<LocalUploadIssueBucket>)
    fun clear() = save(emptyList())
}

class LocalUploadDiagnosticsStore(private val persistence: LocalUploadDiagnosticsPersistence) {
    fun record(moduleFamily: LocalUploadModuleFamily, issue: UploadDestinationIssue, day: LocalDate = LocalDate.now()) =
        recordRedacted(moduleFamily, issue.name, day = day)

    fun recordFailure(
        moduleFamily: LocalUploadModuleFamily,
        error: Exception,
        day: LocalDate = LocalDate.now(),
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ) {
        val status = uploadHttpStatus(error)
        recordRedacted(moduleFamily, classifyUploadFailure(error, status), day, occurredAt,
            status, error.javaClass.simpleName.take(MAX_ERROR_TYPE_LENGTH))
    }

    fun recordOperational(
        moduleFamily: LocalUploadModuleFamily,
        issue: LocalOperationalIssue,
        count: Int = 1,
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        day: LocalDate = occurredAt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate(),
    ) {
        if (count > 0) recordRedacted(moduleFamily, issue.name, day, occurredAt, count = count)
    }

    /** Stable event IDs make a retry after a process death safe before its watermark commits. */
    fun recordOperationalOnce(
        id: String,
        moduleFamily: LocalUploadModuleFamily,
        issue: LocalOperationalIssue,
        occurredAt: OffsetDateTime,
    ) = withMutationLease {
        val day = occurredAt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.recordOnce(id, moduleFamily.name, issue.name, day, occurredAt.toString())
        } else {
            val old = persistence.load()
            if (old.none { it.id == id }) persistence.save(old + LocalUploadIssueBucket(
                day, moduleFamily.name, issue.name, 1, id = id,
                firstOccurredAt = occurredAt.toString(), lastOccurredAt = occurredAt.toString(),
            ))
        }
    }

    private fun recordRedacted(
        moduleFamily: LocalUploadModuleFamily,
        issueCode: String,
        day: LocalDate,
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        httpStatus: Int? = null,
        errorType: String? = null,
        count: Int = 1,
    ) = withMutationLease {
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.record(moduleFamily.name, issueCode, day.toString(), occurredAt.toString(),
                httpStatus, errorType, count)
            return@withMutationLease
        }
        var remaining = count.toLong()
        val loaded = persistence.load().toMutableList()
        val key = stableKey(day.toString(), moduleFamily.name, issueCode, httpStatus, errorType)
        while (remaining > 0) {
            val index = loaded.indexOfLast { !it.sealedForUpload && it.deliveryState == "PENDING" && it.stableKey() == key && it.count < Int.MAX_VALUE }
            val old = loaded.getOrNull(index)
            val room = Int.MAX_VALUE.toLong() - (old?.count ?: 0)
            val addition = minOf(room, remaining).toInt()
            val updated = LocalUploadIssueBucket(
                day = day.toString(), moduleFamily = moduleFamily.name, issue = issueCode,
                count = (old?.count ?: 0) + addition,
                id = old?.id ?: UUID.randomUUID().toString(),
                firstOccurredAt = old?.firstOccurredAt?.takeIf {
                    runCatching { OffsetDateTime.parse(it).isBefore(occurredAt) }.getOrDefault(false)
                } ?: occurredAt.toString(),
                lastOccurredAt = old?.lastOccurredAt?.takeIf {
                    runCatching { OffsetDateTime.parse(it).isAfter(occurredAt) }.getOrDefault(false)
                } ?: occurredAt.toString(),
                httpStatus = httpStatus, errorType = errorType,
            )
            if (index >= 0) loaded[index] = updated else loaded += updated
            remaining -= addition
        }
        persistence.save(loaded)
    }

    fun recent(days: Long = 7, today: LocalDate = LocalDate.now()): List<LocalUploadIssueBucket> {
        require(days > 0)
        val cutoff = today.minusDays(days - 1).toString()
        return synchronized(mutationLock) {
            if (persistence is RoomUploadDiagnosticsPersistence) {
                return@synchronized persistence.recent(cutoff, today.toString())
            }
            persistence.load().filter { it.day >= cutoff && it.day <= today.toString() }
                .sortedWith(compareByDescending<LocalUploadIssueBucket> { it.day }.thenBy { it.moduleFamily }.thenBy { it.issue })
        }
    }

    fun clear() = synchronized(mutationLock) { persistence.clear() }

    /** Calling this seals the exact rows offered to the server; subsequent increments use new IDs. */
    @Suppress("UNUSED_PARAMETER")
    fun pending(today: LocalDate = LocalDate.now()): List<LocalUploadIssueBucket> = synchronized(mutationLock) {
        if (persistence is RoomUploadDiagnosticsPersistence) return@synchronized persistence.pending()
        val loaded = persistence.load()
        val selected = loaded.filter { it.deliveryState == "PENDING" }
            .sortedWith(compareBy<LocalUploadIssueBucket> { it.firstOccurredAt }.thenBy { it.id })
            .take(MAX_UPLOAD_BATCH)
        if (selected.any { !it.sealedForUpload }) {
            val ids = selected.mapTo(hashSetOf()) { it.id }
            persistence.save(loaded.map { if (it.id in ids) it.copy(sealedForUpload = true) else it })
        }
        selected.map { it.copy(sealedForUpload = true) }
    }

    /** Parked rows are probed again on every upload cycle, so an upgraded server can accept them. */
    fun parked(): List<LocalUploadIssueBucket> = synchronized(mutationLock) {
        if (persistence is RoomUploadDiagnosticsPersistence) return@synchronized persistence.parked()
        persistence.load().filter { it.deliveryState == "PARKED" }.take(MAX_UPLOAD_BATCH)
    }

    /** Rotating replay of retained history repairs records expired by pre-upgrade servers. */
    fun deliveredReplay(): List<LocalUploadIssueBucket> = synchronized(mutationLock) {
        if (persistence is RoomUploadDiagnosticsPersistence) return@synchronized persistence.deliveredReplay()
        persistence.load().filter { it.deliveryState == "DELIVERED" }.take(MAX_UPLOAD_BATCH)
    }

    fun parkUnsupportedByLegacyServer(ids: Set<String>) = synchronized(mutationLock) {
        if (ids.isEmpty()) return@synchronized
        val loaded = if (persistence is RoomUploadDiagnosticsPersistence) persistence.byIds(ids) else persistence.load()
        val batch = loaded.filter { it.id in ids }
        // Park only the newest tier in the rejected batch; older tiers go through on the next run.
        val newestTier = batch.maxOfOrNull { serverTier(it) } ?: 0
        val park = if (newestTier == 0) emptySet() else batch.filter { serverTier(it) == newestTier }.mapTo(hashSetOf()) { it.id }
        persistence.save(loaded.map { if (it.id in park) it.copy(deliveryState = "PARKED", sealedForUpload = true) else it })
    }

    fun acknowledge(ids: Set<String>) = synchronized(mutationLock) {
        if (ids.isEmpty()) return@synchronized
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.acknowledge(ids)
            return@synchronized
        }
        persistence.save(persistence.load().map {
            if (it.id in ids) it.copy(deliveryState = "DELIVERED", sealedForUpload = true) else it
        })
    }

    fun acknowledgeDeliveredReplay(submitted: Set<String>, acknowledged: Set<String>) = synchronized(mutationLock) {
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.acknowledgeDeliveredReplay(submitted, acknowledged)
        }
    }

    fun quarantineMalformed(ids: Set<String>) = synchronized(mutationLock) {
        if (ids.isEmpty()) return@synchronized
        if (persistence is RoomUploadDiagnosticsPersistence) {
            persistence.quarantineMalformed(ids)
        } else {
            persistence.save(persistence.load().map {
                if (it.id in ids) it.copy(deliveryState = "QUARANTINED", sealedForUpload = true) else it
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

    private fun LocalUploadIssueBucket.stableKey() = stableKey(day, moduleFamily, issue, httpStatus, errorType)
    private fun stableKey(day: String, family: String, issue: String, status: Int?, error: String?) =
        "$day|$family|$issue|$status|$error"

    companion object {
        private val mutationLock = Any()
        private const val MAX_UPLOAD_BATCH = 500
        private const val MAX_ERROR_TYPE_LENGTH = 128
        fun of(context: Context): LocalUploadDiagnosticsStore = LocalUploadDiagnosticsStore(
            RoomUploadDiagnosticsPersistence(context.applicationContext),
        )
    }
}

internal fun classifyUploadFailure(error: Exception, httpStatus: Int? = null): String = when {
    httpStatus != null && httpStatus >= 500 -> "HTTP_SERVER_ERROR"
    httpStatus != null -> "HTTP_CLIENT_ERROR"
    error is SocketTimeoutException -> "TIMEOUT"
    error is UnknownHostException -> "DNS_FAILURE"
    error is SSLException -> "TLS_FAILURE"
    error is ConnectException -> "CONNECTION_FAILURE"
    else -> "UPLOAD_FAILURE"
}

internal fun uploadHttpStatus(error: Exception): Int? = when (error) {
    is ChronicleCallException -> error.code
    is HttpException -> error.code()
    else -> null
}

private class RoomUploadDiagnosticsPersistence(context: Context) : LocalUploadDiagnosticsPersistence {
    private val context = context.applicationContext
    private val prefs: SharedPreferences = EncryptedPrefsHelper.getEncryptedPrefs(context)
    private val db = ChronicleDb.getInstance(context)
    private var replaySelection: DeliveredReplaySelection? = null
    private val expectedServer: UploadServerEntity? = runBlocking(Dispatchers.IO) {
        db.uploadServerDao().getConfiguredServer()
    }
    private val owner: Owner? = runBlocking(Dispatchers.IO) {
        val server = expectedServer
        val study = prefs.getString(STUDY_ID, null)
        val participant = prefs.getString(PARTICIPANT_ID, null)
        if (server != null && server.studyId == study && server.participantId == participant &&
            server.sourceDeviceId.isNotBlank()) {
            Owner(server.studyId, server.participantId, server.sourceDeviceId, "${server.id}:${server.createdAt}")
        } else if (!study.isNullOrBlank() && !participant.isNullOrBlank()) {
            // Keep unattributed failures locally; never upload them as a later device.
            Owner(study, participant, "", "UNBOUND:$study:$participant")
        } else null
    }

    init {
        ResearchPersistenceGate.withReadLease {
            if (WithdrawalStateStore(context).stateOrThrow() == WithdrawalState.NONE) importLegacy()
        }
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

    fun record(family: String, issue: String, day: String, occurredAt: String,
               status: Int?, errorType: String?, count: Int) = writeIfCurrentOwner {
        val scope = owner
        if (scope == null) {
            save(listOf(LocalUploadIssueBucket(day, family, issue, count,
                firstOccurredAt = occurredAt, lastOccurredAt = occurredAt,
                httpStatus = status, errorType = errorType)))
        } else {
            db.runInTransaction {
                var remaining = count.toLong()
                while (remaining > 0) {
                    val old = db.uploadDiagnosticDao().findOpen(scope.study, scope.participant,
                        scope.device, scope.epoch, day, family, issue, status, errorType)
                    val addition = minOf(remaining, Int.MAX_VALUE.toLong() - (old?.count ?: 0)).toInt()
                    val instant = OffsetDateTime.parse(occurredAt)
                    db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
                        id = old?.id ?: UUID.randomUUID().toString(),
                        studyId = scope.study, participantId = scope.participant,
                        deviceId = scope.device, enrollmentEpoch = scope.epoch,
                        day = day, moduleFamily = family, issueCode = issue,
                        count = (old?.count ?: 0) + addition,
                        firstOccurredAt = old?.firstOccurredAt?.takeIf {
                            runCatching { OffsetDateTime.parse(it).isBefore(instant) }.getOrDefault(false)
                        } ?: occurredAt,
                        lastOccurredAt = old?.lastOccurredAt?.takeIf {
                            runCatching { OffsetDateTime.parse(it).isAfter(instant) }.getOrDefault(false)
                        } ?: occurredAt,
                        httpStatus = status, errorType = errorType,
                    ))
                    remaining -= addition
                }
            }
        }
    }

    fun recordOnce(id: String, family: String, issue: String, day: String, occurredAt: String) = writeIfCurrentOwner {
        val scope = owner ?: return@writeIfCurrentOwner
        db.uploadDiagnosticDao().insertIfAbsent(UploadDiagnosticEntity(
            id = id, studyId = scope.study, participantId = scope.participant,
            deviceId = scope.device, enrollmentEpoch = scope.epoch,
            day = day, moduleFamily = family, issueCode = issue, count = 1,
            firstOccurredAt = occurredAt, lastOccurredAt = occurredAt,
            httpStatus = null, errorType = null,
        ))
    }

    private fun writeIfCurrentOwner(action: () -> Unit) {
        // Acquire on the caller so an existing lease can re-enter ahead of a queued stop.
        ResearchPersistenceGate.withReadLease {
            onIo {
                if (WithdrawalStateStore(context).stateOrThrow() != WithdrawalState.NONE ||
                    owner?.study != prefs.getString(STUDY_ID, null) ||
                    owner?.participant != prefs.getString(PARTICIPANT_ID, null)) return@onIo
                val current = db.uploadServerDao().getConfiguredServer()
                val expected = expectedServer
                if (expected == null) {
                    if (current != null) return@onIo
                } else if (current == null || current.id != expected.id || current.createdAt != expected.createdAt ||
                    current.studyId != expected.studyId || current.participantId != expected.participantId ||
                    current.sourceDeviceId != expected.sourceDeviceId) return@onIo
                action()
            }
        }
    }

    fun pending(): List<LocalUploadIssueBucket> = onIo {
        val scope = owner ?: return@onIo emptyList()
        db.runInTransaction<List<LocalUploadIssueBucket>> {
            val selected = db.uploadDiagnosticDao().pending(scope.study, scope.participant, scope.device, scope.epoch)
            selected.forEach { if (!it.sealedForUpload) db.uploadDiagnosticDao().upsert(it.copy(sealedForUpload = true)) }
            selected.map { it.copy(sealedForUpload = true).toBucket() }
        }
    }

    fun parked(): List<LocalUploadIssueBucket> = onIo {
        val scope = owner ?: return@onIo emptyList()
        val dao = db.uploadDiagnosticDao()
        val count = dao.parkedCount(scope.study, scope.participant, scope.device, scope.epoch)
        if (count == 0) return@onIo emptyList()
        val offset = prefs.getInt(PREF_PARKED_PROBE_OFFSET, 0).coerceAtLeast(0) % count
        val selected = dao.parked(scope.study, scope.participant, scope.device, scope.epoch, offset)
        val next = if (offset + selected.size >= count) 0 else offset + selected.size
        check(prefs.edit().putInt(PREF_PARKED_PROBE_OFFSET, next).commit())
        selected.map { it.toBucket() }
    }

    fun deliveredReplay(): List<LocalUploadIssueBucket> = onIo {
        val scope = owner ?: return@onIo emptyList()
        val dao = db.uploadDiagnosticDao()
        // Re-sending delivered rows only repairs rows an older server purged; once a day is enough.
        val today = LocalDate.now().toString()
        if (prefs.getString(PREF_DELIVERED_REPLAY_DAY, null) == today) return@onIo emptyList()
        val count = dao.deliveredCount(scope.study, scope.participant, scope.device, scope.epoch)
        if (count == 0) return@onIo emptyList()
        val offset = prefs.getInt(PREF_DELIVERED_REPLAY_OFFSET, 0).coerceAtLeast(0) % count
        val selected = dao.delivered(scope.study, scope.participant, scope.device, scope.epoch, offset)
        val next = if (offset + selected.size >= count) 0 else offset + selected.size
        replaySelection = DeliveredReplaySelection(today, next, selected.mapTo(hashSetOf()) { it.id })
        selected.map { it.toBucket() }
    }

    fun acknowledgeDeliveredReplay(submitted: Set<String>, acknowledged: Set<String>) = onIo {
        replaySelection?.let { selection ->
            if (acknowledgeDeliveredReplaySelection(prefs, selection, submitted, acknowledged)) replaySelection = null
        }
    }

    fun byIds(ids: Set<String>): List<LocalUploadIssueBucket> = onIo {
        db.uploadDiagnosticDao().byIds(ids).map { it.toBucket() }
    }

    fun acknowledge(ids: Set<String>) = onIo {
        val scope = owner ?: return@onIo
        db.uploadDiagnosticDao().acknowledge(ids, scope.study, scope.participant, scope.device, scope.epoch)
    }

    fun quarantineMalformed(ids: Set<String>) = onIo {
        val scope = owner ?: return@onIo
        db.runInTransaction {
            db.uploadDiagnosticDao().byIds(ids).forEach { row ->
                if (row.studyId != scope.study || row.participantId != scope.participant ||
                    row.deviceId != scope.device || row.enrollmentEpoch != scope.epoch) return@forEach
                val now = OffsetDateTime.now()
                val inserted = db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                    id = "upload_diagnostics:${row.id}", sourceTable = "upload_diagnostics",
                    sourceId = row.id, studyId = scope.study, participantId = scope.participant,
                    deviceId = scope.device, rawData = JsonSerializer.toJson(row.toBucket()).toByteArray(),
                    reason = "SAMPLE_QUARANTINED", createdAt = now.toString(),
                ))
                db.uploadDiagnosticDao().upsert(row.copy(deliveryState = "QUARANTINED", sealedForUpload = true))
                if (inserted != -1L) {
                    db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
                        id = UUID.randomUUID().toString(), studyId = scope.study,
                        participantId = scope.participant, deviceId = scope.device,
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
                    studyId = null, participantId = null, deviceId = null,
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
        check(prefs.edit().remove(PREF_LOCAL_UPLOAD_ISSUES).remove(PREF_PARKED_PROBE_OFFSET)
            .remove(PREF_DELIVERED_REPLAY_OFFSET).remove(PREF_DELIVERED_REPLAY_DAY).commit())
    }

    private fun importLegacy(): Unit = synchronized(LEGACY_IMPORT_LOCK) {
        val json = prefs.getString(PREF_LOCAL_UPLOAD_ISSUES, null) ?: return
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        onIo {
            if (db.uploadDiagnosticDao().checkpoint(digest) != null) {
                check(prefs.edit().remove(PREF_LOCAL_UPLOAD_ISSUES).commit())
                return@onIo
            }
            val parsed = parseLegacyDiagnosticJson(json, digest, owner?.device?.isNotBlank() == true)
            val usedIds = hashSetOf<String>()
            val imported = parsed.buckets.mapIndexed { index, bucket ->
                var id = bucket.id
                var collision = 0
                while (id in usedIds || db.uploadDiagnosticDao().get(id) != null) {
                    id = UUID.nameUUIDFromBytes("legacy:$digest:$index:${bucket.id}:$collision".toByteArray()).toString()
                    collision++
                }
                usedIds += id
                bucket.copy(id = id)
            }
            val quarantined = parsed.quarantined
            db.runInTransaction {
                db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                    id = "legacy-upload-json:$digest", sourceTable = "legacy_upload_json_source",
                    sourceId = digest, studyId = owner?.takeIf { it.device.isNotBlank() }?.study,
                    participantId = owner?.takeIf { it.device.isNotBlank() }?.participant,
                    deviceId = owner?.takeIf { it.device.isNotBlank() }?.device,
                    rawData = json.toByteArray(), reason = "VERIFIED_IMPORT_SOURCE",
                    createdAt = OffsetDateTime.now().toString(),
                ))
                imported.forEach { db.uploadDiagnosticDao().upsert(it.toEntity(requireNotNull(owner))) }
                quarantined.forEach { (id, raw) ->
                    db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                        id = id, sourceTable = "legacy_upload_diagnostics", sourceId = id,
                        studyId = owner?.takeIf { it.device.isNotBlank() }?.study,
                        participantId = owner?.takeIf { it.device.isNotBlank() }?.participant,
                        deviceId = owner?.takeIf { it.device.isNotBlank() }?.device,
                        rawData = raw.toByteArray(), reason = "LEGACY_IMPORT", createdAt = OffsetDateTime.now().toString(),
                    ))
                }
                db.uploadDiagnosticDao().insertCheckpoint(DiagnosticImportCheckpointEntity(digest, imported.size, quarantined.size + 1))
            }
            check(db.uploadDiagnosticDao().checkpoint(digest) == DiagnosticImportCheckpointEntity(digest, imported.size, quarantined.size + 1))
            check(imported.all { db.uploadDiagnosticDao().get(it.id)?.toBucket() == it })
            check(db.localDataQuarantineDao().get("legacy_upload_json_source", digest)
                ?.rawData?.contentEquals(json.toByteArray()) == true)
            check(quarantined.all { (id, raw) ->
                db.localDataQuarantineDao().get("legacy_upload_diagnostics", id)
                    ?.rawData?.contentEquals(raw.toByteArray()) == true
            })
            check(prefs.edit().remove(PREF_LOCAL_UPLOAD_ISSUES).commit())
        }
    }

    private inline fun <T> onIo(crossinline action: () -> T): T = runBlocking(Dispatchers.IO) { action() }
    private data class Owner(val study: String, val participant: String, val device: String, val epoch: String)
    private fun LocalUploadIssueBucket.toEntity(scope: Owner) = UploadDiagnosticEntity(
        id, scope.study, scope.participant, scope.device, scope.epoch, day, moduleFamily,
        issue, count, firstOccurredAt, lastOccurredAt, httpStatus, errorType, deliveryState, sealedForUpload,
    )
    private fun UploadDiagnosticEntity.toBucket() = LocalUploadIssueBucket(
        day, moduleFamily, issueCode, count, id, firstOccurredAt, lastOccurredAt,
        httpStatus, errorType, deliveryState, sealedForUpload,
    )
}
