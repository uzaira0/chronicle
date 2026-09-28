package com.openlattice.chronicle.collection.directboot

import android.content.Context
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Encrypted device-protected counts, replayed by stable event ID after first unlock. */
internal class DirectBootDiagnosticsJournal(
    private val file: File,
    private val cipher: DirectBootRecordCipher,
) {
    constructor(context: Context) : this(
        File(directBootFilesDir(context), "$DIRECT_BOOT_BUFFER_DIR_NAME/diagnostics.bin"),
        KeystoreDirectBootRecordCipher(),
    )

    data class Owner(val study: String, val participant: String, val device: String, val epoch: String)
    data class Event(val id: String, val code: String, val count: Int, val occurredAt: String, val owner: Owner?)
    data class State(val owner: Owner? = null, val events: List<Event> = emptyList())

    fun currentOwner(): Owner? = synchronized(DIRECT_BOOT_BUFFER_LOCK) { read().owner }

    fun bind(context: Context) = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        val db = ChronicleDb.getInstance(context)
        val server = exactActiveEnrollmentServer(context, db) ?: return@synchronized
        val owner = Owner(server.studyId, server.participantId, server.sourceDeviceId, "${server.id}:${server.createdAt}")
        val state = read()
        if (state.owner != owner) write(state.copy(owner = owner))
    }

    fun record(code: String, count: Int, incidentId: String = UUID.randomUUID().toString()) =
        synchronized(DIRECT_BOOT_BUFFER_LOCK) {
            if (count <= 0) return@synchronized
            val id = uuidId(incidentId)
            val state = read()
            if (state.events.any { uuidId(it.id) == id }) return@synchronized
            write(state.copy(events = state.events + Event(
                id, code, count, OffsetDateTime.now().toString(), state.owner,
            )))
        }

    fun replay(context: Context) = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        val state = read()
        if (state.events.isEmpty()) return@synchronized
        val db = ChronicleDb.getInstance(context)
        db.runInTransaction {
            state.events.forEach { event ->
                val id = uuidId(event.id)
                val owner = event.owner
                if (owner == null) {
                    db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                        id = "direct_boot_diagnostic:$id", sourceTable = "direct_boot_diagnostic",
                        sourceId = id, studyId = null, participantId = null, deviceId = null,
                        rawData = JsonSerializer.toJson(event).toByteArray(),
                        reason = "UNKNOWN_OWNER", createdAt = event.occurredAt,
                    ))
                } else {
                    if (db.uploadDiagnosticDao().get(id) == null) {
                        db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
                        id = id, studyId = owner.study, participantId = owner.participant,
                        deviceId = owner.device, enrollmentEpoch = owner.epoch,
                        day = LocalDate.parse(event.occurredAt.substring(0, 10)).toString(),
                        moduleFamily = "SENSOR", issueCode = event.code, count = event.count,
                        firstOccurredAt = event.occurredAt, lastOccurredAt = event.occurredAt,
                        httpStatus = null, errorType = null,
                        ))
                    }
                }
            }
        }
        write(state.copy(events = emptyList()))
    }

    /** Preserve malformed ciphertext before starting a fresh journal for this owner. */
    fun quarantineCorruptJournal(): Boolean = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        if (runCatching { read() }.isSuccess) return@synchronized false
        val sources = listOf(file, File(file.parentFile, "diagnostics.tmp")).filter(File::exists)
        if (sources.isEmpty()) return@synchronized false
        sources.forEach { source ->
            val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            val id = UUID.nameUUIDFromBytes(digest).toString()
            val quarantine = File(file.parentFile, "diagnostics-corrupt-$id.bin")
            if (!quarantine.exists()) check(source.renameTo(quarantine)) { "Unable to quarantine corrupt journal" }
            else check(source.delete()) { "Unable to remove duplicate corrupt journal" }
        }
        true
    }

    /** Retry-safe incident recording after a crash between quarantine and journal replacement. */
    fun recordQuarantinedIncidents(context: Context) = synchronized(DIRECT_BOOT_BUFFER_LOCK) {
        bind(context)
        file.parentFile?.listFiles()?.filter {
            it.name.startsWith("diagnostics-corrupt-") && it.name.endsWith(".bin")
        }?.forEach { preserved ->
            val id = preserved.name.removePrefix("diagnostics-corrupt-").removeSuffix(".bin")
            record("DIRECT_BOOT_CORRUPT_RECORD", 1, id)
        }
    }

    private fun read(): State {
        val temp = File(file.parentFile, "diagnostics.tmp")
        if (temp.exists()) {
            val recovered = runCatching { parse(temp) }.getOrNull()
            if (recovered != null) {
                check(temp.renameTo(file)) { "Unable to recover direct-boot diagnostic journal" }
                return recovered
            }
        }
        return if (file.exists()) parse(file) else State()
    }

    private fun parse(source: File): State {
        val state = JsonSerializer.fromJson<State>(cipher.decrypt(source.readBytes()).toString(Charsets.UTF_8))
            ?: error("Corrupt direct-boot diagnostic journal")
        state.events.forEach { event ->
            require(event.id.isNotBlank() && event.code.isNotBlank() && event.count > 0)
            LocalDate.parse(event.occurredAt.substring(0, 10))
        }
        return state
    }

    private fun uuidId(id: String): String = runCatching { UUID.fromString(id).toString() }
        .getOrElse { UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString() }

    private fun write(state: State) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val temp = File(file.parentFile, "diagnostics.tmp")
        FileOutputStream(temp).use { out ->
            out.write(cipher.encrypt(JsonSerializer.toJson(state).toByteArray()))
            out.fd.sync()
        }
        check(temp.renameTo(file)) { "Unable to install direct-boot diagnostic journal" }
    }
}
