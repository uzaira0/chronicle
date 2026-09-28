package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.os.Build
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordPolicyErasureCountInTransaction
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

internal const val DIRECT_BOOT_BUFFER_DIR_NAME = "direct_boot_sensor_buffer"
internal const val DIRECT_BOOT_LIVE_FILE_NAME = "buffer.bin"
internal const val DIRECT_BOOT_DRAINING_FILE_NAME = "buffer.draining.bin"
internal val DIRECT_BOOT_BUFFER_LOCK = Any()

data class DirectBootErasureInventory(
    val sampleCountsByOwner: Map<String?, Int>,
    val corruptIncidentIds: Set<String>,
    val digest: String?,
)

internal fun directBootFilesDir(context: Context): File =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext().filesDir
    } else {
        context.filesDir
    }

/** Distinct sample counts by original enrollment for the Play/Amazon boundary. */
fun inspectDirectBootSamplesForErasure(context: Context): DirectBootErasureInventory = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
    val dir = File(directBootFilesDir(context), DIRECT_BOOT_BUFFER_DIR_NAME)
    val files = sampleBearingFiles(dir)
    val idsByOwner = linkedMapOf<String?, MutableSet<String>>()
    val corrupt = linkedSetOf<String>()
    files.forEach { file ->
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val incidentId = UUID.nameUUIDFromBytes(digest).toString()
        try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                while (input.available() > 0) {
                    val size = input.readInt()
                    require(size in 1..(4 * 1024 * 1024))
                    val blob = ByteArray(size)
                    input.readFully(blob)
                    require(blob.size > 13 && blob[0] == 1.toByte())
                    val plaintext = decryptDirectBootBlob(blob)
                    val legacy = plaintext.trimStart().startsWith("[")
                    val record = if (legacy) null else JSONObject(plaintext)
                    val owner = record?.optString("owner")?.takeIf(String::isNotBlank)
                    val samples = if (legacy) JSONArray(plaintext) else record!!.getJSONArray("samples")
                    for (index in 0 until samples.length()) {
                        val id = samples.getJSONObject(index).getString("id")
                        require(id.isNotBlank())
                        idsByOwner.getOrPut(owner) { hashSetOf() } += id
                    }
                }
            }
        } catch (_: Exception) {
            corrupt += incidentId
        }
    }
    dir.listFiles()?.filter { it.name.startsWith("corrupt-") && it.name.endsWith(".bin") }
        ?.forEach { file ->
            val suffix = file.name.removePrefix("corrupt-").removeSuffix(".bin")
            corrupt += runCatching { UUID.fromString(suffix).toString() }
                .getOrElse { UUID.nameUUIDFromBytes("corrupt:$suffix".toByteArray()).toString() }
        }
    DirectBootErasureInventory(idsByOwner.mapValues { it.value.size }, corrupt, directBootBufferDigest(context))
}

private fun decryptDirectBootBlob(blob: ByteArray): String {
    require(blob.size > 13 && blob[0] == 1.toByte())
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = keyStore.getKey("chronicle_direct_boot_buffer", null) as SecretKey
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(1, 13)))
    return cipher.doFinal(blob, 13, blob.size - 13).toString(Charsets.UTF_8)
}

fun recordDirectBootDistributionErasure(
    db: ChronicleDb,
    countsByOwner: Map<String?, Int>,
    digest: String,
) {
    val now = OffsetDateTime.now().toString()
    countsByOwner.forEach { (ownerKey, count) ->
        if (count <= 0) return@forEach
        val owner = ownerKey?.let { runCatching { JSONObject(it) }.getOrNull() }
        val id = UUID.nameUUIDFromBytes("directboot-boundary:$digest:$ownerKey".toByteArray()).toString()
        if (db.uploadDiagnosticDao().get(id) != null) return@forEach
        db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
            id = id, studyId = owner?.optString("study")?.takeIf(String::isNotBlank),
            participantId = owner?.optString("participant")?.takeIf(String::isNotBlank),
            deviceId = owner?.optString("device")?.takeIf(String::isNotBlank),
            enrollmentEpoch = owner?.optString("epoch")?.takeIf(String::isNotBlank),
            day = LocalDate.now().toString(), moduleFamily = "SENSOR",
            issueCode = LocalOperationalIssue.DISTRIBUTION_POLICY_ERASED.name,
            count = count, firstOccurredAt = now, lastOccurredAt = now,
            httpStatus = null, errorType = null,
        ))
    }
}

/** Transfer journal events to Room before the distribution boundary removes device storage. */
fun replayDirectBootJournalForErasure(context: Context, db: ChronicleDb) = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
    val dir = File(directBootFilesDir(context), DIRECT_BOOT_BUFFER_DIR_NAME)
    fun accountQuarantined() = inventoryQuarantinedDirectBootJournals(dir) { incidentId ->
        db.runInTransaction {
            if (db.uploadDiagnosticDao().get(incidentId) == null) {
                recordPolicyErasureCountInTransaction(db, 1, LocalUploadModuleFamily.SENSOR,
                    LocalOperationalIssue.DIRECT_BOOT_CORRUPT_RECORD, incidentId)
            }
        }
    }
    // A prior attempt may have crashed after quarantine and before the Room transaction.
    accountQuarantined()
    listOf("diagnostics.bin", "diagnostics.tmp").map { File(dir, it) }
        .filter(File::exists).forEach { file ->
            val bytes = file.readBytes()
            val incidentId = UUID.nameUUIDFromBytes(MessageDigest.getInstance("SHA-256").digest(bytes)).toString()
            val state = runCatching {
                JSONObject(decryptDirectBootBlob(bytes)).also { parsed ->
                    val events = parsed.getJSONArray("events")
                    for (index in 0 until events.length()) {
                        val event = events.getJSONObject(index)
                        require(event.getString("id").isNotBlank())
                        require(event.getString("code").isNotBlank())
                        require(event.getInt("count") > 0)
                        LocalDate.parse(event.getString("occurredAt").substring(0, 10))
                        event.optJSONObject("owner")?.let { owner ->
                            listOf("study", "participant", "device", "epoch")
                                .forEach { key -> require(owner.getString(key).isNotBlank()) }
                        }
                    }
                }
            }.getOrNull()
            if (state == null) {
                val preserved = File(dir, "diagnostics-corrupt-$incidentId.bin")
                if (!preserved.exists()) check(file.renameTo(preserved))
                else {
                    check(preserved.readBytes().contentEquals(bytes)) { "Corrupt journal quarantine collision" }
                    check(file.delete())
                }
                accountQuarantined()
                return@forEach
            }
            val events = state.getJSONArray("events")
            db.runInTransaction {
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    val rawId = event.getString("id")
                    val id = runCatching { UUID.fromString(rawId).toString() }
                        .getOrElse { UUID.nameUUIDFromBytes(rawId.toByteArray()).toString() }
                    if (db.uploadDiagnosticDao().get(id) != null) continue
                    val owner = event.optJSONObject("owner")
                    val occurredAt = event.getString("occurredAt")
                    if (owner == null) {
                        db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                            id = "direct_boot_diagnostic:$id", sourceTable = "direct_boot_diagnostic",
                            sourceId = id, studyId = null, participantId = null, deviceId = null,
                            rawData = event.toString().toByteArray(), reason = "UNKNOWN_OWNER",
                            createdAt = occurredAt,
                        ))
                    } else {
                        db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
                            id = id, studyId = owner.getString("study"),
                            participantId = owner.getString("participant"),
                            deviceId = owner.getString("device"), enrollmentEpoch = owner.getString("epoch"),
                            day = LocalDate.parse(occurredAt.substring(0, 10)).toString(),
                            moduleFamily = "SENSOR", issueCode = event.getString("code"),
                            count = event.getInt("count"), firstOccurredAt = occurredAt,
                            lastOccurredAt = occurredAt, httpStatus = null, errorType = null,
                        ))
                    }
                }
            }
        }
}

/** Recompute IDs from preserved ciphertext so retries never trust a filename alone. */
internal fun inventoryQuarantinedDirectBootJournals(dir: File, account: (String) -> Unit) {
    val files = if (dir.exists()) checkNotNull(dir.listFiles()) { "Unable to inventory direct-boot journals" }
        else emptyArray()
    files.filter { it.isFile && it.name.startsWith("diagnostics-corrupt-") && it.name.endsWith(".bin") }
        .forEach { file ->
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            account(UUID.nameUUIDFromBytes(digest).toString())
        }
}

fun directBootBufferDigest(context: Context): String? = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
    val dir = File(directBootFilesDir(context), DIRECT_BOOT_BUFFER_DIR_NAME)
    val files = sampleBearingFiles(dir)
    if (files.isEmpty()) return@synchronized null
    val digest = MessageDigest.getInstance("SHA-256")
    files.forEach { file -> file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    } }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun sampleBearingFiles(dir: File): List<File> =
    listOf(DIRECT_BOOT_DRAINING_FILE_NAME, DIRECT_BOOT_LIVE_FILE_NAME)
        .map { File(dir, it) }.filter { it.length() > 0L } +
        File(dir, "quarantine").listFiles().orEmpty().filter { it.isFile && it.length() > 0L }
            .sortedBy { it.name }

/**
 * Erases the legacy pre-unlock sensor buffer without packaging its capture, encryption, or
 * replay implementation. Minimal Play and Amazon builds call this during update, withdrawal,
 * and recovery so data written by an older research build cannot survive an enrollment boundary.
 */
fun clearDirectBootSensorBuffer(context: Context): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
    val dir = File(directBootFilesDir(context), DIRECT_BOOT_BUFFER_DIR_NAME)
    val liveFile = File(dir, DIRECT_BOOT_LIVE_FILE_NAME)
    val drainingFile = File(dir, DIRECT_BOOT_DRAINING_FILE_NAME)
    val liveCleared = !liveFile.exists() || liveFile.delete()
    val drainingCleared = !drainingFile.exists() || drainingFile.delete()
    val otherCleared = dir.listFiles()?.filter { it != liveFile && it != drainingFile }
        ?.all(File::deleteRecursively) ?: true
    if (liveCleared && drainingCleared && otherCleared) dir.delete()
    liveCleared && drainingCleared && otherCleared
}
