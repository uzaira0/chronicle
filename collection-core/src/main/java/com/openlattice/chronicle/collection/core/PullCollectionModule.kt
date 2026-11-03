package com.openlattice.chronicle.collection.core

import android.util.Log
import java.util.concurrent.CancellationException
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.storage.UploadServerEntity

private const val TAG = "PullCollectionModule"

/** Shared shape for modules that persist each pull immediately; a participant who is not enrolled is skipped. */
public abstract class PullCollectionModule(private val enrolled: () -> Boolean, private val now: () -> Long) {
    public open fun sample(): ModuleResult =
        if (enrolled()) runSample(now()) else ModuleResult.Skipped("participant not enrolled")

    protected abstract fun runSample(now: Long): ModuleResult
}

/** Owner capture and write accounting for a pull module; the defaults do neither. */
public class PullAccounting(
    public val ownerForSample: () -> UploadServerEntity? = { null },
    public val accountWriteResult: (UploadServerEntity?, ModuleResult, Int) -> Unit = { _, _, _ -> },
)

/**
 * A pull module that persists one snapshot row per pull under the admission captured before the read. A
 * source that throws surfaces as [ModuleResult.Failed] and an unavailable reading as [ModuleResult.Retry].
 */
public abstract class SnapshotCollectionModule<R : Any>(
    private val label: String,
    enrolled: () -> Boolean,
    private val accounting: PullAccounting,
    now: () -> Long,
) : PullCollectionModule(enrolled, now) {
    protected abstract fun captureAdmission(): CollectionPersistenceGuard
    protected abstract fun read(): R?
    protected abstract fun persist(reading: R, now: Long): ModuleResult

    private fun failedRead(error: Throwable): ModuleResult.Failed {
        Log.e(TAG, "The $label source threw while reading", error)
        return ModuleResult.Failed(error, redactedMessage = "$label source read failed: ${error.javaClass.simpleName}")
    }

    final override fun runSample(now: Long): ModuleResult {
        val observation = captureAdmission()
        val owner = accounting.ownerForSample()
        val captured = try {
            Result.success(read())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        }
        val reading = captured.getOrElse { return failedRead(it) }
        if (reading == null) {
            Log.w(TAG, "The $label reading is unavailable; will retry on the next poll")
            return ModuleResult.Retry("$label unavailable")
        }
        // Account after the admission transaction returns, so its rollback cannot erase the loss diagnostic.
        val written = observation.writeObservation { persist(reading, now) }
        accounting.accountWriteResult(owner, written, 1)
        if (written is ModuleResult.Failed) Log.e(TAG, "Failed to persist the $label snapshot", written.error)
        return if (written is ModuleResult.Ok) ModuleResult.Ok(1) else written
    }
}
