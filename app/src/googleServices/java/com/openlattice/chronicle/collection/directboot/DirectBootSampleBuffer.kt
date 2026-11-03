package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Types
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.util.UUID

private const val TAG = "DirectBootSampleBuffer"

/**
 * Pre-unlock sensor samples for one enrollment: one append-only file in device-protected
 * storage, each record `[length][AES-GCM blob]` of a JSON sample batch. After unlock the drain
 * worker replays it through the consent gate and deletes it; erasure or withdrawal deletes it.
 */
class DirectBootSampleBuffer(private val file: File, private val cipher: DirectBootRecordCipher) {
    constructor(context: Context, owner: String) :
        this(File(directBootBufferDir(context), "buffer-$owner.bin"), KeystoreDirectBootRecordCipher())

    /** Samples dropped at capacity before unlock; the diagnostics store is unreadable until then. */
    private val droppedFile = File(file.parentFile, "${file.name}.dropped")

    fun isEmpty(): Boolean = file.length() == 0L

    /** Encrypts and appends one record. A full buffer drops the batch and reports `items = 0`. */
    fun append(samples: List<SensorSampleEntry>): ModuleResult = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        if (samples.isEmpty()) return ModuleResult.Ok(items = 0)
        try {
            val blob = cipher.encrypt(ADAPTER.toJson(samples).toByteArray(Charsets.UTF_8))
            if (file.length() + Int.SIZE_BYTES + blob.size > MAX_BUFFER_BYTES) {
                Log.e(TAG, "Direct-boot buffer full; dropping ${samples.size} sample(s)")
                runCatching { droppedFile.writeText((droppedCount() + samples.size).toString()) }
                    .onFailure { Log.e(TAG, "Unable to count dropped direct-boot samples", it) }
                return ModuleResult.Ok(items = 0)
            }
            val dir = checkNotNull(file.parentFile)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create the direct-boot buffer directory")
            FileOutputStream(file, true).use { out ->
                DataOutputStream(out).apply { writeInt(blob.size); write(blob); flush() }
                out.fd.sync()
            }
            ModuleResult.Ok(items = samples.size)
        } catch (error: IOException) {
            appendFailed(error)
        } catch (error: GeneralSecurityException) {
            appendFailed(error)
        } catch (error: ProviderException) {
            appendFailed(error)
        }
    }

    private fun appendFailed(error: Exception): ModuleResult {
        Log.e(TAG, "Direct-boot buffer append failed", error)
        return ModuleResult.Failed(error, redactedMessage = "direct-boot buffer append failed: ${error.javaClass.simpleName}")
    }

    /**
     * Hands each record to [persist] in order, then deletes the file. When [persist] returns
     * false the file is kept for a later retry; re-persisting is safe because sample IDs are
     * insert-ignored. The file ends at a clean end or at a torn final write. Any other unreadable
     * record keeps every byte: the file moves to `corrupt-<uuid>.bin`, which no drain opens and
     * withdrawal deletes with the directory. Samples dropped at capacity and a set-aside file are
     * handed to [reportLoss] so the study sees them in the upload diagnostics.
     */
    fun drain(
        reportLoss: (LocalOperationalIssue, Int) -> Unit,
        persist: (List<SensorSampleEntry>) -> Boolean,
    ): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        droppedCount().takeIf { it > 0 }?.let { reportLoss(LocalOperationalIssue.DIRECT_BOOT_CAPACITY_DROPPED, it) }
        droppedFile.delete()
        if (!file.exists()) return true
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                while (true) {
                    val batch = readRecord(input) ?: break
                    if (!persist(batch)) return false
                }
            }
        } catch (error: IOException) {
            return quarantine(error, reportLoss)
        } catch (error: GeneralSecurityException) {
            return quarantine(error, reportLoss)
        } catch (error: JsonDataException) {
            return quarantine(error, reportLoss)
        }
        file.delete() || !file.exists()
    }

    /** The next record, or null at the end of the file or inside a torn final write. */
    private fun readRecord(input: DataInputStream): List<SensorSampleEntry>? {
        val blob = try {
            val size = input.readInt()
            if (size !in 1..MAX_BUFFER_BYTES) throw IOException("Corrupt direct-boot record length")
            ByteArray(size).also(input::readFully)
        } catch (_: EOFException) {
            return null
        }
        return ADAPTER.fromJson(cipher.decrypt(blob).toString(Charsets.UTF_8)) ?: throw IOException("Empty direct-boot record")
    }

    private fun quarantine(error: Exception, reportLoss: (LocalOperationalIssue, Int) -> Unit): Boolean {
        Log.e(TAG, "Unreadable direct-boot record; keeping the buffer aside", error)
        if (!file.renameTo(File(file.parentFile, "corrupt-${UUID.randomUUID()}.bin"))) return false
        reportLoss(LocalOperationalIssue.DIRECT_BOOT_CORRUPT_RECORD, 1)
        return true
    }

    private fun droppedCount(): Int =
        runCatching { droppedFile.takeIf(File::exists)?.readText()?.trim()?.toInt() }.getOrNull() ?: 0

    companion object {
        /** Encrypted pre-unlock bytes kept per enrollment; later samples are dropped. */
        const val MAX_BUFFER_BYTES: Int = 8 * 1024 * 1024

        private val ADAPTER = ChronicleJson.moshi.adapter<List<SensorSampleEntry>>(
            Types.newParameterizedType(List::class.java, SensorSampleEntry::class.java))

        /** Opaque enrollment key, so a buffer never drains into a later enrollment. */
        fun ownerKey(server: UploadServerEntity): String = MessageDigest.getInstance("SHA-256")
            .digest("${server.studyId}:${server.participantId}:${server.sourceRandomDeviceId}:${server.id}:${server.createdAt}"
                .toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
    }
}
