package com.openlattice.chronicle.collection.directboot

import android.content.Context
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.serialization.JsonSerializer
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

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
 *  - a drain first renames the live file to a draining file and checkpoints consumed batches;
 *    a crash replays only the still-buffered records;
 *  - `sensor_samples` inserts use `OnConflictStrategy.IGNORE` on the sample id, so
 *    re-persisting a partially-drained file is a DB-level no-op for the rows already in;
 *  - a truncated/corrupt tail is copied to encrypted local quarantine before checkpointing;
 *    the incident is journaled for diagnostic replay.
 *
 * The buffer is bounded by [MAX_BUFFER_BYTES]; once full, further appends are dropped (and
 * loudly logged) rather than growing device-protected storage unboundedly — the locked
 * window is normally minutes, not days.
 */
class DirectBootSampleBuffer(
    private val dir: File,
    private val cipher: DirectBootRecordCipher,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val reportLoss: (String, Int, String) -> Unit = { _, _, _ -> },
    private val ownerForAppend: () -> String? = { "test-owner" },
) {
    constructor(context: Context) : this(
        File(directBootFilesDir(context), DIRECT_BOOT_BUFFER_DIR_NAME),
        KeystoreDirectBootRecordCipher(),
        reportLoss = { code, count, id -> DirectBootDiagnosticsJournal(context).record(code, count, id) },
        ownerForAppend = { DirectBootDiagnosticsJournal(context).currentOwner()?.let(::ownerKey) },
    )

    private data class Batch(val owner: String?, val samples: List<SensorSampleEntry>)
    private data class OwnedSample(val owner: String?, val sample: SensorSampleEntry)
    private data class DecodeResult(
        val entries: List<OwnedSample>,
        val badRecords: List<Pair<Int, ByteArray>>,
        val corruptTail: ByteArray,
    )

    /** Outcome of a [drain]: what persisted, what was dropped, and whether persistence failed. */
    data class DrainResult(
        val persisted: Int,
        val corruptRecordsDropped: Int,
        val failed: Boolean,
    )

    /** IDs confirmed persisted or erased by policy; all other IDs remain buffered. */
    data class DrainTransfer(
        val transferredIds: Set<String>,
        val failed: Boolean = false,
        val discardedIds: Set<String> = emptySet(),
    )

    private val liveFile: File get() = File(dir, DIRECT_BOOT_LIVE_FILE_NAME)
    private val drainingFile: File get() = File(dir, DIRECT_BOOT_DRAINING_FILE_NAME)

    /** Whether there is nothing buffered (neither live nor crashed-drain records). */
    fun isEmpty(): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        !(liveFile.length() > 0L || drainingFile.length() > 0L)
    }

    /** Reserve room for one maximum-size record before admitting another sample. */
    fun hasAdmissionCapacity(): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        liveFile.length() + MAX_RECORD_BYTES <= MAX_BUFFER_BYTES
    }

    /**
     * Irreversibly removes both active and interrupted-drain records at an enrollment boundary.
     * A failed deletion is reported to the caller so withdrawal/recovery can retry instead of
     * allowing samples from an old study to drain into a later enrollment.
     */
    fun clear(): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        val liveCleared = !liveFile.exists() || liveFile.delete()
        val drainingCleared = !drainingFile.exists() || drainingFile.delete()
        val otherCleared = dir.listFiles()?.filter { it != liveFile && it != drainingFile }
            ?.all(File::deleteRecursively) ?: true
        if (liveCleared && drainingCleared && otherCleared) dir.delete()
        liveCleared && drainingCleared && otherCleared
    }

    /** Remove a disabled sensor from active records and retained encrypted copies. */
    fun eraseSensorType(sensorType: String, expectedOwner: String? = null,
                        beforeErase: (Set<String>) -> Unit = {}): Int =
        eraseSensorTypes(setOf(sensorType), expectedOwner, beforeErase)

    internal fun eraseSensorTypes(sensorTypes: Set<String>, expectedOwner: String?,
                                  beforeErase: (Set<String>) -> Unit = {}): Int =
        synchronized(DIRECT_BOOT_BUFFER_LOCK) {
            val removed = linkedSetOf<String>()
            val sampleFiles = dir.walkTopDown().filter {
                it.isFile && !it.name.startsWith("diagnostics") &&
                    (it.name.endsWith(".bin") || it.name.endsWith(".tmp"))
            }.toList()
            sampleFiles.filter { it.name.endsWith(".tmp") }.forEach {
                check(it.delete()) { "Unable to erase direct-boot sample checkpoint" }
            }
            sampleFiles.filter { !it.name.endsWith(".tmp") && it.length() > 0L }.forEach { file ->
                val decoded = decodeAll(file)
                val policyIds = linkedSetOf<String>()
                val keep = decoded.entries.filterNot {
                    val unknownSensor = runCatching { AndroidSensorType.valueOf(it.sample.sensorType) }.isFailure
                    // Ownerless legacy records of an erased sensor cannot prove another owner, so they go too.
                    val erase = unknownSensor || (it.sample.sensorType in sensorTypes &&
                        (expectedOwner == null || it.owner.isNullOrBlank() || it.owner == expectedOwner))
                    if (erase && removed.add(it.sample.id)) {
                        if (unknownSensor) reportLoss("DIRECT_BOOT_CORRUPT_RECORD", 1, "unknown-sensor:${it.sample.id}")
                        else policyIds += it.sample.id
                    }
                    erase
                }
                beforeErase(policyIds)
                // Undecodable sample payloads cannot be scoped to a sensor or enrollment.
                writeRecords(file, keep)
                if (file == liveFile || file == drainingFile) {
                    runCatching { preserveCorruption(decoded, retainPayloads = false) }
                        .onFailure { log.warn(TAG, "Unable to journal erased corrupt samples", it) }
                }
            }
            removed.size
        }

    /**
     * Encrypts and appends [samples] as one record. Returns [ModuleResult.Ok] on success
     * (the runtime treats it exactly like a Room flush), [ModuleResult.Failed] on an I/O or
     * crypto error (the runtime re-queues and retries). A full buffer journals the exact
     * capacity loss and returns Ok with `items = 0`; the direct-boot runtime counts it as loss.
     */
    fun append(samples: List<SensorSampleEntry>): ModuleResult {
        if (samples.isEmpty()) return ModuleResult.Ok(items = 0)
        return synchronized(DIRECT_BOOT_BUFFER_LOCK) {
            try {
                if (!dir.isDirectory && !dir.mkdirs()) {
                    return@synchronized failed(IllegalStateException("Cannot create buffer dir"))
                }
                val owner = ownerForAppend() ?: return@synchronized failed(
                    IllegalStateException("Direct-boot enrollment owner is unavailable"),
                )
                val blob = cipher.encrypt(BATCH_ADAPTER.toJson(Batch(owner, samples)).toByteArray(Charsets.UTF_8))
                require(blob.size <= MAX_RECORD_BYTES) { "Direct-boot batch exceeds record limit" }
                if (liveFile.length() + blob.size + Int.SIZE_BYTES > MAX_BUFFER_BYTES) {
                    reportLoss("DIRECT_BOOT_CAPACITY_DROPPED", samples.size, UUID.randomUUID().toString())
                    log.error(
                        TAG,
                        "Direct-boot buffer full (${liveFile.length()} bytes); dropping ${samples.size} sample(s)",
                    )
                    return@synchronized ModuleResult.Ok(items = 0)
                }
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
     * Replays only records owned by [expectedOwner] through [persist] in batches of
     * [DRAIN_BATCH]. Consumed batches are checkpointed; failures retain the remainder.
     */
    fun drain(
        expectedOwner: String = "test-owner",
        enrollmentCreatedAt: String? = null,
        persist: (List<SensorSampleEntry>) -> DrainTransfer,
    ): DrainResult = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
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

        val decoded = decodeAll(drainingFile)
        val createdAt = enrollmentCreatedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        val entries = decoded.entries.map {
            val timestamp = runCatching { OffsetDateTime.parse(it.sample.timestamp).toInstant() }.getOrNull()
            if (it.owner == null && createdAt != null && timestamp != null && timestamp >= createdAt) {
                it.copy(owner = expectedOwner)
            } else it
        }
        val corrupt = decoded.badRecords.size + if (decoded.corruptTail.isNotEmpty()) 1 else 0
        preserveCorruption(decoded)
        val unauthorized = entries.filter { it.owner != expectedOwner }
        if (unauthorized.isNotEmpty()) {
            val quarantineDir = File(dir, "quarantine")
            check(quarantineDir.isDirectory || quarantineDir.mkdirs())
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(unauthorized.joinToString("|") { "${it.owner}:${it.sample.id}" }.toByteArray())
            val quarantine = File(quarantineDir, "owner-${UUID.nameUUIDFromBytes(digest)}.bin")
            if (!quarantine.exists()) writeRecords(quarantine, unauthorized)
        }
        var remaining = entries.filter { it.owner == expectedOwner }
        // Remove the unauthorized records and corrupt tail before persisting. The encrypted
        // quarantine and undecodable bytes are durable first.
        writeRecords(drainingFile, remaining)
        var persisted = 0
        val retained = mutableListOf<OwnedSample>()
        while (remaining.isNotEmpty()) {
            val batch = remaining.take(DRAIN_BATCH)
            val result = persist(batch.map { it.sample })
            val consumed = result.transferredIds + result.discardedIds
            check(consumed.all { id -> batch.any { it.sample.id == id } })
            result.discardedIds.forEach { id -> reportLoss("DIRECT_BOOT_CORRUPT_RECORD", 1, "unknown-sensor:$id") }
            persisted += batch.count { it.sample.id in result.transferredIds }
            retained += batch.filterNot { it.sample.id in consumed }
            remaining = remaining.drop(batch.size)
            writeRecords(drainingFile, retained + remaining)
            if (result.failed) {
                log.error(TAG, "Drain persist failed after $persisted sample(s); remaining samples retained")
                return@synchronized DrainResult(persisted, corrupt, failed = true)
            }
        }
        if (retained.isEmpty()) drainingFile.delete()
        if (persisted > 0 || corrupt > 0) {
            log.info(TAG, "Drained $persisted direct-boot sample(s); $corrupt corrupt record(s) dropped")
        }
        DrainResult(persisted, corrupt, failed = retained.isNotEmpty())
    }

    private fun preserveCorruption(decoded: DecodeResult, retainPayloads: Boolean = true) {
        decoded.badRecords.forEach { (ordinal, blob) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val id = UUID.nameUUIDFromBytes(digest + ordinal.toByte()).toString()
            val preserved = File(dir, "corrupt-$id.bin")
            if (retainPayloads && !preserved.exists()) preserved.writeBytes(blob)
            reportLoss("DIRECT_BOOT_CORRUPT_RECORD", 1, id)
        }
        if (decoded.corruptTail.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(decoded.corruptTail)
            val id = UUID.nameUUIDFromBytes(digest).toString()
            val preserved = File(dir, "corrupt-$id.bin")
            if (retainPayloads && !preserved.exists()) preserved.writeBytes(decoded.corruptTail)
            reportLoss("DIRECT_BOOT_CORRUPT_RECORD", 1, id)
        }
    }

    /** A bad payload has a trustworthy length, so the next record can still be decoded. */
    private fun decodeAll(file: File): DecodeResult {
        val entries = mutableListOf<OwnedSample>()
        val badRecords = mutableListOf<Pair<Int, ByteArray>>()
        var corruptTail = byteArrayOf()
        var ordinal = 0
        RandomAccessFile(file, "r").use { input ->
            while (true) {
                if (input.filePointer == input.length()) break
                val recordStart = input.filePointer
                ordinal++
                val blob = try {
                    val length = input.readInt()
                    if (length !in 1..MAX_RECORD_BYTES) throw EOFException()
                    val blob = ByteArray(length)
                    input.readFully(blob)
                    blob
                } catch (_: EOFException) {
                    input.seek(recordStart)
                    corruptTail = ByteArray((input.length() - recordStart).toInt()).also(input::readFully)
                    break
                }
                try {
                    val json = cipher.decrypt(blob).toString(Charsets.UTF_8)
                    val batch = if (json.trimStart().startsWith("[")) {
                        Batch(null, LEGACY_ADAPTER.fromJson(json).orEmpty())
                    } else BATCH_ADAPTER.fromJson(json) ?: error("Empty direct-boot batch")
                    entries.addAll(batch.samples.map { OwnedSample(batch.owner, it) })
                } catch (e: Exception) {
                    badRecords += ordinal to blob
                    log.warn(TAG, "Quarantining corrupt direct-boot record", e)
                }
            }
        }
        return DecodeResult(entries, badRecords, corruptTail)
    }

    private fun writeRecords(destination: File, entries: List<OwnedSample>) {
        val temp = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temp).use { fos ->
            DataOutputStream(fos).use { output ->
                entries.groupBy { it.owner }.forEach { (owner, owned) ->
                    owned.chunked(DRAIN_BATCH).forEach { chunk ->
                        val blob = cipher.encrypt(BATCH_ADAPTER.toJson(
                            Batch(owner, chunk.map { it.sample }),
                        ).toByteArray(Charsets.UTF_8))
                        check(blob.size in 1..MAX_RECORD_BYTES)
                        output.writeInt(blob.size)
                        output.write(blob)
                    }
                }
                output.flush()
                fos.fd.sync()
            }
        }
        check(temp.renameTo(destination)) { "Unable to checkpoint direct-boot drain" }
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

        private val MOSHI = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        private val BATCH_ADAPTER = MOSHI.adapter(Batch::class.java)
        private val LEGACY_ADAPTER = MOSHI.adapter<List<SensorSampleEntry>>(
                Types.newParameterizedType(List::class.java, SensorSampleEntry::class.java),
            )

        internal fun ownerKey(owner: DirectBootDiagnosticsJournal.Owner): String =
            JsonSerializer.toJson(owner)
    }
}
