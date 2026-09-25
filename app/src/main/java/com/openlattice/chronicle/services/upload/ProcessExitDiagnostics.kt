package com.openlattice.chronicle.services.upload

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.time.Instant
import java.time.ZoneOffset

private const val PROCESS_EXIT_TAG = "ProcessExitDiagnostics"
private const val PREFS = "chronicle_process_exit_watermark"
private const val KEY_WATERMARK = "last_reported_exit_ms"

/** Only the reason and time of a past exit; message, stack, and trace are never read. */
internal data class ProcessExit(val reason: Int, val timestampMillis: Long)

/**
 * Counts crash, native-crash, and ANR exits newer than [watermarkMillis] as redacted
 * APP_RUNTIME diagnostics and returns the new watermark. The app version reaches the server with
 * every collection acknowledgment.
 */
internal fun recordProcessExits(
    store: LocalUploadDiagnosticsStore,
    exits: List<ProcessExit>,
    watermarkMillis: Long,
): Long {
    var watermark = watermarkMillis
    exits.filter { it.timestampMillis > watermarkMillis }
        .sortedBy { it.timestampMillis }
        .forEach { exit ->
            watermark = maxOf(watermark, exit.timestampMillis)
            val issue = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH -> LocalOperationalIssue.APP_CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> LocalOperationalIssue.APP_CRASH_NATIVE
                ApplicationExitInfo.REASON_ANR -> LocalOperationalIssue.APP_ANR
                else -> return@forEach
            }
            store.recordOperational(
                LocalUploadModuleFamily.APP_RUNTIME,
                issue,
                occurredAt = Instant.ofEpochMilli(exit.timestampMillis).atOffset(ZoneOffset.UTC),
            )
        }
    return watermark
}

/** Reads this package's recent process exits (API 30+) into the upload diagnostics. Never throws. */
fun recordRecentProcessExits(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .map { ProcessExit(it.reason, it.timestamp) }
        val previous = prefs.getLong(KEY_WATERMARK, 0L)
        val next = recordProcessExits(LocalUploadDiagnosticsStore.of(context), exits, previous)
        if (next != previous) prefs.edit().putLong(KEY_WATERMARK, next).apply()
    } catch (e: Exception) {
        Log.w(PROCESS_EXIT_TAG, "Could not record recent process exits", e)
    }
}
