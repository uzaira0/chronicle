package com.openlattice.chronicle.collection.directboot

import android.content.Context
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "DirectBootSampleBuffer"
/**
 * The device-protected-storage sample buffer for the direct-boot window.
 *
 * Between a reboot and the user's first unlock, the SQLCipher Room DB (credential-encrypted
 * storage) cannot be opened, so the sensor runtime writes its flushes here instead: an
 * append-only file of length-prefixed records, each record an AES/GCM-encrypted JSON batch
 * of [SensorSampleEntry] (see [KeystoreDirectBootRecordCipher]). After unlock,
 * [drain] replays the buffered entries into the normal `sensor_samples` queue — the caller
 * supplies the persist function so the drain can re-check each sample's collection gate at
 * drain time (consent may have been revoked while the device was off; the gate is the
 * persistence chokepoint, mirroring the runtime's flush semantics).
 *
 * Durability/idempotency:
 *  - a drain first renames the live file to a draining file, so a crash mid-drain never
 *    loses records — the next drain re-processes the draining file first;
 *  - `sensor_samples` inserts use `OnConflictStrategy.IGNORE` on the sample id, so
 *    re-persisting a partially-drained file is a DB-level no-op for the rows already in;
 *  - a truncated/corrupt tail (crash mid-append) drops only the tail: every record that
 *    decodes cleanly before it is kept.
 *
 * The buffer is bounded by [MAX_BUFFER_BYTES]; once full, further appends are dropped (and
 * loudly logged) rather than growing device-protected storage unboundedly — the locked
 * window is normally minutes, not days.
 */
class DirectBootSampleBuffer(
    private val dir: File,
    private val cipher: DirectBootRecordCipher,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) {
    constructor(context: Context) : this(
        File(directBootFilesDir(context), DIRECT_BOOT_BUFFER_DIR_NAME),
        KeystoreDirectBootRecordCipher(),
    )

    /** Outcome of a [drain]: what persisted, what was dropped, and whether persistence failed. */
    data class DrainResult(
        val persisted: Int,
        val corruptRecordsDropped: Int,
        val failed: Boolean,
    )

    private val liveFile: File get() = File(dir, DIRECT_BOOT_LIVE_FILE_NAME)
    private val drainingFile: File get() = File(dir, DIRECT_BOOT_DRAINING_FILE_NAME)

    /** Whether there is nothing buffered (neither live nor crashed-drain records). */
    fun isEmpty(): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        !(liveFile.length() > 0L || drainingFile.length() > 0L)
    }

    /**
     * Irreversibly removes both active and interrupted-drain records at an enrollment boundary.
     * A failed deletion is reported to the caller so withdrawal/recovery can retry instead of
     * allowing samples from an old study to drain into a later enrollment.
     */
    fun clear(): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        val liveCleared = !liveFile.exists() || liveFile.delete()
        val drainingCleared = !drainingFile.exists() || drainingFile.delete()
        if (liveCleared && drainingCleared) dir.delete()
        liveCleared && drainingCleared
    }

    /**
     * Encrypts and appends [samples] as one record. Returns [ModuleResult.Ok] on success
     * (the runtime treats it exactly like a Room flush), [ModuleResult.Failed] on an I/O or
     * crypto error (the runtime re-queues and retries). A full buffer drops the batch —
     * reported as Ok with `items = 0` so the runtime does not retry what can never fit,
     * with the drop logged as an error.
     */
    fun append(samples: List<SensorSampleEntry>): ModuleResult {
        if (samples.isEmpty()) return ModuleResult.Ok(items = 0)
        return synchronized(DIRECT_BOOT_BUFFER_LOCK) {
            try {
                if (!dir.isDirectory && !dir.mkdirs()) {
                    return@synchronized failed(IllegalStateException("Cannot create buffer dir"))
                }
                if (liveFile.length() >= MAX_BUFFER_BYTES) {
                    log.error(
                        TAG,
                        "Direct-boot buffer full (${liveFile.length()} bytes); dropping ${samples.size} sample(s)",
                    )
                    return@synchronized ModuleResult.Ok(items = 0)
                }
                val blob = cipher.encrypt(BATCH_ADAPTER.toJson(samples).toByteArray(Charsets.UTF_8))
                FileOutputStream(liveFile, true).use { fos ->
                    DataOutputStream(fos).apply {
                        writeInt(blob.size)
                        write(blob)
                        flush()
                    }
                    fos.fd.sync()
                }
                ModuleResult.Ok(items = samples.size)
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /**
     * Replays every buffered record through [persist] in batches of [DRAIN_BATCH]. On full
     * success the files are deleted; if any persist call fails, the records are kept for the
     * next drain (re-persisting already-inserted rows is deduplicated by the sample-id PK).
     */
    fun drain(persist: (List<SensorSampleEntry>) -> ModuleResult): DrainResult = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        // A crashed prior drain leaves a draining file; fold the live file into it so one
        // pass covers both (order preserved: crashed-drain records precede newer live ones).
        if (drainingFile.length() > 0L && liveFile.length() > 0L) {
            FileOutputStream(drainingFile, true).use { out ->
                FileInputStream(liveFile).use { it.copyTo(out) }
                out.fd.sync()
            }
            liveFile.delete()
        } else if (liveFile.length() > 0L) {
            if (!liveFile.renameTo(drainingFile)) {
                return@synchronized DrainResult(0, 0, failed = true)
            }
        }
        if (drainingFile.length() == 0L) {
            drainingFile.delete()
            return@synchronized DrainResult(0, 0, failed = false)
        }

        val (entries, corrupt) = decodeAll(drainingFile)
        var persisted = 0
        entries.chunked(DRAIN_BATCH).forEach { batch ->
            when (val result = persist(batch)) {
                is ModuleResult.Ok -> persisted += batch.size
                else -> {
                    // Failed / Retry / Skipped all mean "not durably persisted": keep the
                    // file so the next drain retries (sample-id PK dedups the overlap).
                    val detail = (result as? ModuleResult.Failed)?.redactedMessage
                        ?: result.javaClass.simpleName
                    log.error(TAG, "Drain persist failed after $persisted sample(s): $detail")
                    return@synchronized DrainResult(persisted, corrupt, failed = true)
                }
            }
        }
        drainingFile.delete()
        if (persisted > 0 || corrupt > 0) {
            log.info(TAG, "Drained $persisted direct-boot sample(s); $corrupt corrupt record(s) dropped")
        }
        DrainResult(persisted, corrupt, failed = false)
    }

    /** Decodes records until EOF or the first corrupt/truncated record (tail dropped). */
    private fun decodeAll(file: File): Pair<List<SensorSampleEntry>, Int> {
        val entries = mutableListOf<SensorSampleEntry>()
        var corrupt = 0
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            while (true) {
                val length = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                try {
                    require(length in 1..MAX_RECORD_BYTES) { "Bad record length $length" }
                    val blob = ByteArray(length)
                    input.readFully(blob)
                    val json = cipher.decrypt(blob).toString(Charsets.UTF_8)
                    entries.addAll(BATCH_ADAPTER.fromJson(json).orEmpty())
                } catch (e: Exception) {
                    corrupt++
                    log.warn(TAG, "Dropping corrupt direct-boot record tail", e)
                    break
                }
            }
        }
        return entries to corrupt
    }

    private fun failed(e: Exception): ModuleResult.Failed {
        log.error(TAG, "Direct-boot buffer write failed", e)
        return ModuleResult.Failed(e, redactedMessage = "direct-boot buffer append failed: ${e.javaClass.simpleName}")
    }

    companion object {
        /** Hard bound on the live buffer file; appends beyond it are dropped, not grown. */
        const val MAX_BUFFER_BYTES: Long = 8L * 1024 * 1024

        /** Sanity bound on a single record (a runtime flush is ≤500 samples). */
        const val MAX_RECORD_BYTES: Int = 4 * 1024 * 1024

        /** Samples per persist call during a drain — the runtime's flush-threshold batch. */
        const val DRAIN_BATCH: Int = 500

        private val BATCH_ADAPTER = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter<List<SensorSampleEntry>>(
                Types.newParameterizedType(List::class.java, SensorSampleEntry::class.java),
            )
    }
}
