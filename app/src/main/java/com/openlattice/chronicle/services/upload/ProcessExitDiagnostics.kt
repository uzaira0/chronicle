package com.openlattice.chronicle.services.upload

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val PROCESS_EXIT_TAG = "ProcessExitDiagnostics"
internal const val PROCESS_EXIT_PREFS = "chronicle_process_exit_watermark"
private const val KEY_WATERMARK = "last_reported_exit_ms"
internal const val LEGACY_CRASH_TIMESTAMP = "last_uncaught_crash_ms"

internal fun installLegacyCrashHandler(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous is CrashTimestampHandler) return
    val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PROCESS_EXIT_PREFS, Context.MODE_PRIVATE)
    Thread.setDefaultUncaughtExceptionHandler(CrashTimestampHandler(prefs, previous))
}

internal class CrashTimestampHandler(
    private val prefs: android.content.SharedPreferences,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            prefs.edit().putLong(LEGACY_CRASH_TIMESTAMP, System.currentTimeMillis()).commit()
        } finally {
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}

/** Only the reason and time of a past exit; message, stack, and trace are never read. */
internal data class ProcessExit(val reason: Int, val timestampMillis: Long, val pid: Int = 0)

/**
 * Counts crash, native-crash, and ANR exits newer than [watermarkMillis] as redacted
 * APP_RUNTIME diagnostics and returns the new watermark. The app version reaches the server with
 * every collection acknowledgment.
 */
internal fun recordProcessExits(
    store: LocalUploadDiagnosticsStore,
    exits: List<ProcessExit>,
    watermarkMillis: Long,
    admissionFloor: Long = Long.MIN_VALUE,
): Long {
    var watermark = watermarkMillis
    exits.filter { it.timestampMillis > watermarkMillis && it.timestampMillis >= admissionFloor }
        .sortedBy { it.timestampMillis }
        .forEach { exit ->
            watermark = maxOf(watermark, exit.timestampMillis)
            val issue = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH -> LocalOperationalIssue.APP_CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> LocalOperationalIssue.APP_CRASH_NATIVE
                ApplicationExitInfo.REASON_ANR -> LocalOperationalIssue.APP_ANR
                else -> return@forEach
            }
            // nosemgrep: chronicle-uuid-from-string -- PID, timestamp and exit reason identify one process-exit diagnostic across watermark retries.
            val id = UUID.nameUUIDFromBytes(

                "process-exit:${exit.pid}:${exit.timestampMillis}:${exit.reason}".toByteArray(Charsets.UTF_8),
            ).toString()
            store.recordOperationalOnce(
                id,
                LocalUploadModuleFamily.APP_RUNTIME,
                issue,
                occurredAt = Instant.ofEpochMilli(exit.timestampMillis).atOffset(ZoneOffset.UTC),
            )
        }
    return watermark
}

/** Reports timestamp-only pre-R crashes or platform process exits through redacted diagnostics. */
fun recordRecentProcessExits(context: Context) {
    try {
        val gate = com.openlattice.chronicle.collection.state.ResearchPersistenceGate
        val owner = gate.captureOwner(context) ?: return
        gate.runIfExpectedOwner(context, owner) {
        val scope = gate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.UPLOAD_TELEMETRY)
            ?: return@runIfExpectedOwner false
        val floor = maxOf(scope.second, owner.enrollmentIssuedAtEpochMillis ?: 0, java.time.OffsetDateTime.parse(owner.createdAt).toInstant(
            ).toEpochMilli())
        val prefs = context.getSharedPreferences(PROCESS_EXIT_PREFS, Context.MODE_PRIVATE)
        val exits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return@runIfExpectedOwner false
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .map { ProcessExit(it.reason, it.timestamp, it.pid) }
        } else {
            val crash = context.createDeviceProtectedStorageContext().getSharedPreferences(PROCESS_EXIT_PREFS, Context.MODE_PRIVATE)
                .getLong(LEGACY_CRASH_TIMESTAMP, 0)
            if (crash == 0L) emptyList() else listOf(ProcessExit(ApplicationExitInfo.REASON_CRASH, crash))
        }
        val previous = prefs.getLong("$KEY_WATERMARK:${scope.first}", 0L)
        val next = recordProcessExits(LocalUploadDiagnosticsStore.of(context), exits, previous, floor)
        if (next != previous) check(prefs.edit().putLong("$KEY_WATERMARK:${scope.first}", next).commit())
        true
        }
    } catch (e: Exception) {
        Log.w(PROCESS_EXIT_TAG, "Could not record recent process exits", e)
    }
}
