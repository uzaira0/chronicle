package com.openlattice.chronicle.services.upload

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.JsonSerializer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.net.ssl.SSLException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.HttpException

private const val LOCAL_UPLOAD_DIAGNOSTICS_TAG = "LocalUploadDiagnostics"
private const val PREF_LOCAL_UPLOAD_ISSUES = "local_upload_issue_history"
private const val RETENTION_DAYS = 30L

enum class LocalUploadModuleFamily {
    USAGE_LIFECYCLE,
    BATTERY,
    DEVICE_TELEMETRY,
}

/** Bounded local aggregate awaiting authenticated delivery to the enrolled study server. */
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
)

interface LocalUploadDiagnosticsPersistence {
    fun load(): List<LocalUploadIssueBucket>
    fun save(buckets: List<LocalUploadIssueBucket>)
}

class LocalUploadDiagnosticsStore(
    private val persistence: LocalUploadDiagnosticsPersistence,
) {
    fun record(
        moduleFamily: LocalUploadModuleFamily,
        issue: UploadDestinationIssue,
        day: LocalDate = LocalDate.now(),
    ) = recordRedacted(moduleFamily, issue.name, day = day)

    fun recordFailure(
        moduleFamily: LocalUploadModuleFamily,
        error: Exception,
        day: LocalDate = LocalDate.now(),
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ) {
        val httpStatus = (error as? HttpException)?.code()
        recordRedacted(
            moduleFamily = moduleFamily,
            issueCode = classifyUploadFailure(error, httpStatus),
            day = day,
            occurredAt = occurredAt,
            httpStatus = httpStatus,
            errorType = error.javaClass.simpleName.take(MAX_ERROR_TYPE_LENGTH),
        )
    }

    private fun recordRedacted(
        moduleFamily: LocalUploadModuleFamily,
        issueCode: String,
        day: LocalDate = LocalDate.now(),
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        httpStatus: Int? = null,
        errorType: String? = null,
    ) {
        synchronized(mutationLock) {
            val cutoff = day.minusDays(RETENTION_DAYS - 1)
            val merged = linkedMapOf<String, LocalUploadIssueBucket>()
            validBuckets(persistence.load(), cutoff, day).forEach { bucket ->
                merged[bucket.stableKey()] = bucket
            }
            val key = stableKey(
                day.toString(), moduleFamily.name, issueCode, httpStatus, errorType,
            )
            val prior = merged[key]?.count ?: 0
            merged[key] = LocalUploadIssueBucket(
                day = day.toString(),
                moduleFamily = moduleFamily.name,
                issue = issueCode,
                count = if (prior >= Int.MAX_VALUE) Int.MAX_VALUE else prior + 1,
                id = merged[key]?.id ?: UUID.randomUUID().toString(),
                firstOccurredAt = merged[key]?.firstOccurredAt ?: occurredAt.toString(),
                lastOccurredAt = occurredAt.toString(),
                httpStatus = httpStatus,
                errorType = errorType,
            )
            persistence.save(
                merged.values
                    .sortedByDescending(LocalUploadIssueBucket::lastOccurredAt)
                    .take(MAX_LOCAL_BUCKETS),
            )
        }
    }

    fun recent(days: Long = 7, today: LocalDate = LocalDate.now()): List<LocalUploadIssueBucket> {
        require(days > 0)
        val cutoff = today.minusDays(days - 1)
        return synchronized(mutationLock) {
            val retained = loadValidAndPrune(today)
            validBuckets(retained, cutoff, today)
                .sortedWith(
                    compareByDescending<LocalUploadIssueBucket> { it.day }
                        .thenBy { it.moduleFamily }
                        .thenBy { it.issue },
                )
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            persistence.save(emptyList())
        }
    }

    fun pending(today: LocalDate = LocalDate.now()): List<LocalUploadIssueBucket> =
        synchronized(mutationLock) {
            loadValidAndPrune(today)
                .sortedWith(compareBy<LocalUploadIssueBucket> { it.firstOccurredAt }.thenBy { it.id })
                .take(MAX_UPLOAD_BATCH)
        }

    /** Physically removes expired or malformed buckets whenever the history is read. */
    private fun loadValidAndPrune(today: LocalDate): List<LocalUploadIssueBucket> {
        val loaded = persistence.load()
        val retained = validBuckets(loaded, today.minusDays(RETENTION_DAYS - 1), today)
            .sortedByDescending(LocalUploadIssueBucket::lastOccurredAt)
            .take(MAX_LOCAL_BUCKETS)
        if (retained != loaded) persistence.save(retained)
        return retained
    }

    fun acknowledge(ids: Set<String>) {
        if (ids.isEmpty()) return
        synchronized(mutationLock) {
            persistence.save(persistence.load().filterNot { it.id in ids })
        }
    }

    fun toWireEvents(buckets: List<LocalUploadIssueBucket>): List<AndroidUploadDiagnosticEvent> =
        buckets.mapNotNull { bucket ->
        runCatching {
            AndroidUploadDiagnosticEvent(
                id = bucket.id,
                day = LocalDate.parse(bucket.day),
                moduleFamily = bucket.moduleFamily,
                issueCode = bucket.issue,
                count = bucket.count,
                firstOccurredAt = OffsetDateTime.parse(bucket.firstOccurredAt),
                lastOccurredAt = OffsetDateTime.parse(bucket.lastOccurredAt),
                httpStatus = bucket.httpStatus,
                errorType = bucket.errorType,
            )
        }.onFailure {
            Log.w(LOCAL_UPLOAD_DIAGNOSTICS_TAG, "Dropping malformed upload diagnostic ${bucket.id}", it)
        }.getOrNull()
    }

    private fun validBuckets(
        buckets: List<LocalUploadIssueBucket>,
        cutoff: LocalDate,
        latest: LocalDate,
    ): List<LocalUploadIssueBucket> = buckets.filter { bucket ->
        val parsedDay = runCatching { LocalDate.parse(bucket.day) }.getOrNull()
        parsedDay != null && !parsedDay.isBefore(cutoff) && !parsedDay.isAfter(latest) &&
            bucket.count > 0 &&
            LocalUploadModuleFamily.entries.any { it.name == bucket.moduleFamily } &&
            bucket.issue in ALLOWED_ISSUE_CODES &&
            runCatching { UUID.fromString(bucket.id) }.isSuccess &&
            validOccurrenceWindow(bucket) &&
            (bucket.httpStatus == null || bucket.httpStatus in 100..599) &&
            (bucket.errorType == null || bucket.errorType.length <= MAX_ERROR_TYPE_LENGTH)
    }

    private fun validOccurrenceWindow(bucket: LocalUploadIssueBucket): Boolean {
        val first = runCatching { OffsetDateTime.parse(bucket.firstOccurredAt) }.getOrNull() ?: return false
        val last = runCatching { OffsetDateTime.parse(bucket.lastOccurredAt) }.getOrNull() ?: return false
        return !last.isBefore(first)
    }

    private fun LocalUploadIssueBucket.stableKey(): String =
        stableKey(day, moduleFamily, issue, httpStatus, errorType)

    private fun stableKey(
        day: String,
        moduleFamily: String,
        issue: String,
        httpStatus: Int?,
        errorType: String?,
    ): String = "$day|$moduleFamily|$issue|$httpStatus|$errorType"

    companion object {
        private val mutationLock = Any()
        private val ALLOWED_ISSUE_CODES = UploadDestinationIssue.entries.mapTo(mutableSetOf()) { it.name }.apply {
            addAll(
                setOf(
                    "HTTP_SERVER_ERROR",
                    "HTTP_CLIENT_ERROR",
                    "TIMEOUT",
                    "DNS_FAILURE",
                    "TLS_FAILURE",
                    "CONNECTION_FAILURE",
                    "UPLOAD_FAILURE",
                ),
            )
        }
        private const val MAX_UPLOAD_BATCH = 500
        private const val MAX_LOCAL_BUCKETS = 500
        private const val MAX_ERROR_TYPE_LENGTH = 128

        fun of(context: Context): LocalUploadDiagnosticsStore = LocalUploadDiagnosticsStore(
            EncryptedPrefsLocalUploadDiagnosticsPersistence(context.applicationContext),
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

private class EncryptedPrefsLocalUploadDiagnosticsPersistence(
    context: Context,
) : LocalUploadDiagnosticsPersistence {
    private val prefs: SharedPreferences = EncryptedPrefsHelper.getEncryptedPrefs(context)

    override fun load(): List<LocalUploadIssueBucket> {
        val json = prefs.getString(PREF_LOCAL_UPLOAD_ISSUES, null) ?: return emptyList()
        return try {
            JsonSerializer.fromJson<List<LocalUploadIssueBucket>>(json) ?: emptyList()
        } catch (error: Exception) {
            Log.w(LOCAL_UPLOAD_DIAGNOSTICS_TAG, "Dropping corrupt local upload issue history", error)
            emptyList()
        }
    }

    override fun save(buckets: List<LocalUploadIssueBucket>) {
        val editor = prefs.edit()
        if (buckets.isEmpty()) {
            editor.remove(PREF_LOCAL_UPLOAD_ISSUES)
        } else {
            editor.putString(PREF_LOCAL_UPLOAD_ISSUES, JsonSerializer.toJson(buckets))
        }
        check(editor.commit()) { "Failed to persist local upload issue history" }
    }
}
