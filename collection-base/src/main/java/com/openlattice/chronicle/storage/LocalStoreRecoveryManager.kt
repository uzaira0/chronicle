package com.openlattice.chronicle.storage

import android.database.Cursor
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import org.json.JSONArray
import org.json.JSONObject

import android.content.Context
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

data class LocalStoreResetConfirmation(
    val preserveEncryptedRecoveryBundle: Boolean,
    val understandsReenrollmentRequired: Boolean,
) {
    fun requireExplicitApproval() {
        require(preserveEncryptedRecoveryBundle && understandsReenrollmentRequired) {
            "Local store reset requires both explicit confirmations"
        }
    }
}

data class LocalStoreResetResult(val recoveryBundleDirectory: File)

/**
 * The sole destructive recovery seam for the Android local store.
 *
 * This operation is intentionally impossible to trigger implicitly: callers must present two
 * explicit confirmations. Every database component (including a legacy plaintext database, when
 * a migration failed) and the wrapped passphrase metadata are encrypted with a device-bound
 * recovery key and decrypted again for hash verification before live files are removed.
 */
object LocalStoreRecoveryManager {
    private const val DIAGNOSTIC_RECOVERY_DIRECTORY = "chronicle-diagnostics-recovery"
    internal fun directBootSources(context: Context): List<File> {
        val dir = File(context.createDeviceProtectedStorageContext().filesDir, "direct_boot_sensor_buffer")
        return directBootSourceFiles(dir)
    }

    internal fun directBootSourceFiles(dir: File): List<File> =
        if (dir.isDirectory) dir.walkTopDown().filter(File::isFile).toList() else emptyList()

    /** The enrollment owner as recorded in a recovery manifest; [preserveAndReset] takes it as `ownerScope`. */
    fun scopeDigest(studyId: String, participantId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$studyId\u0000$participantId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Explicit withdrawal removes all matching bundles, including legacy bundles with no scope. */
    fun eraseForEnrollment(context: Context, studyId: String, participantId: String) {
        val root = File(context.noBackupFilesDir, "chronicle-recovery")
        val digest = scopeDigest(studyId, participantId)
        eraseForScope(root, digest, preserveDiagnostics = false)
        eraseForScope(File(context.noBackupFilesDir, DIAGNOSTIC_RECOVERY_DIRECTORY), digest, preserveDiagnostics = false)
    }

    /** Explicit pending module erasure also covers an archive made during verified recovery. */
    fun erasePendingRecoveryArtifacts(context: Context, ownerScopeDigest: String?) {
        val root = File(context.noBackupFilesDir, "chronicle-recovery")
        eraseForScope(root, ownerScopeDigest)
    }

    internal fun eraseForScope(root: File, digest: String?, preserveDiagnostics: Boolean = true) {
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val manifest = File(directory, "manifest.txt")
            val recorded = manifest.takeIf(File::isFile)?.readLines()
                ?.firstOrNull { it.startsWith("owner_scope_sha256=") }
                ?.substringAfter('=')
            if (digest == null || recorded == null || recorded == "unknown" || recorded == digest) {
                val retained = try {
                    if (preserveDiagnostics) preserveDiagnosticsBeforeErasure(directory)
                    emptySet()
                }
                catch (error: Exception) {
                    Log.e("LocalStoreRecovery", "Unable to preserve separate recovery diagnostics before explicit erasure", error)
                    diagnosticFiles(directory).toSet()
                }
                if (retained.isEmpty()) {
                    checkLocalStoreWrite(directory.deleteRecursively()) { "Unable to erase withdrawn enrollment recovery bundle" }
                } else {
                    // Research artifacts go now; separate diagnostics stay in place for the next attempt.
                    directory.listFiles().orEmpty().filter { it !in retained && it.name != "manifest.txt" }.forEach {
                        checkLocalStoreWrite(it.deleteRecursively()) { "Unable to erase withdrawn enrollment recovery bundle" }
                    }
                }
            }
        }
    }

    /** The bundle manifest is the only record of which files are separated diagnostics. */
    private fun manifestLines(directory: File): List<String>? = File(directory, "manifest.txt").takeIf(File::isFile)?.readLines()

    private fun diagnosticFiles(directory: File): List<File> {
        val names = manifestLines(directory).orEmpty()
            .filter { it.startsWith("diagnostic_artifact=") }.map { it.substringAfter('=') }.toSet()
        return directory.listFiles().orEmpty().filter { it.isFile && it.name in names }
    }

    private fun preserveDiagnosticsBeforeErasure(directory: File) {
        // Without a manifest nothing in the bundle is known to be diagnostics: explicit erasure removes it all.
        val manifest = manifestLines(directory) ?: return
        val declared = manifest.count { it.startsWith("diagnostic_artifact=") }
        val diagnostics = diagnosticFiles(directory)
        if (diagnostics.size < declared) {
            Log.w("LocalStoreRecovery", "Separate recovery diagnostics are missing; completing explicit erasure")
        }
        if (diagnostics.isEmpty()) return
        val preserved = File(checkNotNull(checkNotNull(directory.parentFile).parentFile), "$DIAGNOSTIC_RECOVERY_DIRECTORY/${directory.name}")
        check(preserved.exists() || preserved.mkdirs())
        diagnostics.forEach { source ->
            val destination = File(preserved, source.name)
            if (!destination.exists()) {
                FileOutputStream(destination).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            }
            if (!MessageDigest.getInstance("SHA-256").digest(source.readBytes()).contentEquals(
                    MessageDigest.getInstance("SHA-256").digest(destination.readBytes()))) {
                destination.delete() // a partial copy must not block the next attempt
                error("Preserved diagnostic artifact verification failed")
            }
        }
        writeManifest(preserved, manifest)
    }

    /** Reads only diagnostic tables; no sample or participant payload enters this artifact. */
    internal fun diagnosticSnapshot(db: ChronicleDb): ByteArray {
        val result = JSONObject()
        listOf("upload_diagnostics").forEach { table ->
            db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                result.put(table, cursorRows(cursor, typedValues = true))
            }
        }
        db.openHelper.readableDatabase.query(
            "SELECT * FROM local_data_quarantine WHERE sourceTable IN ('upload_diagnostics', " +
            "'unowned_upload_diagnostics', 'direct_boot_diagnostic')").use { cursor ->
            result.put("diagnostic_quarantine", cursorRows(cursor, typedValues = false))
        }
        return result.toString().toByteArray(Charsets.UTF_8)
    }

    private fun cursorRows(cursor: Cursor, typedValues: Boolean): JSONArray {
        val rows = JSONArray()
        while (cursor.moveToNext()) {
            val row = JSONObject()
            cursor.columnNames.forEachIndexed { index, name ->
                val type = cursor.getType(index)
                row.put(name, when {
                    type == Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index),
                        Base64.NO_WRAP)
                    !typedValues -> cursor.getString(index)
                    type == Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    type == Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    type == Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    else -> cursor.getString(index)
                })
            }
            rows.put(row)
        }
        return rows
    }

    fun preserveAndReset(
        context: Context,
        reason: LocalStoreRecoveryReason,
        confirmation: LocalStoreResetConfirmation,
        ownerScope: String? = null,
    ): LocalStoreResetResult {
        confirmation.requireExplicitApproval()
        val appContext = context.applicationContext
        val databaseSources = ChronicleDb.recoverySourceFiles(appContext)
        check(databaseSources.isNotEmpty()) { "No failed local database bundle exists to recover" }

        val unreadablePreferences = EncryptedPrefsHelper.unreadableStores(appContext)
        val sources = buildList {
            addAll(databaseSources)
            DatabaseKeyManager.keyMetadataFile(appContext).takeIf(File::exists)?.let(::add)
            addAll(directBootSources(appContext))
            addAll(unreadablePreferences)
        }
        val bundleDirectory = File(
            appContext.noBackupFilesDir,
            "chronicle-recovery/${System.currentTimeMillis()}",
        )
        check(bundleDirectory.mkdirs()) { "Unable to create the encrypted recovery directory" }

        try {
            val manifestLines = mutableListOf(
                "format=chronicle-android-recovery-v1",
                "reason=${reason.name}",
                "created_at_epoch_ms=${System.currentTimeMillis()}",
                "owner_scope_sha256=${ownerScope ?: "unknown"}",
            )
            sources.forEachIndexed { index, source ->
                val artifact = File(bundleDirectory, "artifact-${index + 1}-${source.name}.enc")
                val sha256 = DatabaseKeyManager.encryptAndVerifyRecoveryArtifact(FileInputStream(source), artifact)
                manifestLines += "artifact=${artifact.name},bytes=${source.length()},sha256=$sha256"
            }
            preserveDiagnosticSnapshot(appContext, bundleDirectory, manifestLines)
            writeManifest(bundleDirectory, manifestLines)

            finishRecoveryBundle(bundleDirectory) {
                DatabaseKeyManager.clearStoredPassphrase(appContext)
                ChronicleDb.resetAfterVerifiedRecoveryBundle(appContext)
                if (unreadablePreferences.isNotEmpty()) {
                    checkLocalStoreWrite(EncryptedPrefsHelper.replaceUnreadableStore(appContext)) {
                        "Failed to replace the unreadable secure preferences after preserving them"
                    }
                }
            }
            return LocalStoreResetResult(bundleDirectory)
        } catch (error: Exception) {
            // Preserve any completed encrypted artifacts for diagnosis. A future attempt uses a
            // new timestamped directory and never overwrites the evidence from this attempt.
            throw IllegalStateException("Local store recovery reset did not complete", error)
        }
    }

    internal fun finishRecoveryBundle(
        directory: File,
        sync: (File) -> Unit = { file ->
            FileChannel.open(file.toPath(), if (file.isDirectory) StandardOpenOption.READ else StandardOpenOption.WRITE)
                .use { it.force(true) }
        },
        reset: () -> Unit,
    ) {
        checkNotNull(directory.listFiles()) { "Recovery bundle is unreadable" }.forEach(sync)
        sync(directory)
        directory.parentFile?.let(sync)
        directory.parentFile?.parentFile?.let(sync)
        reset()
    }

    private fun preserveDiagnosticSnapshot(context: Context, bundleDirectory: File, manifestLines: MutableList<String>) {
        val diagnostics = runCatching { diagnosticSnapshot(ChronicleDb.getInstance(context)) }.getOrNull()
        if (diagnostics != null) {
            // Participant-linked rows are encrypted from memory; no plaintext copy ever reaches disk.
            val artifact = File(bundleDirectory, "diagnostic-snapshot.enc")
            val digest = DatabaseKeyManager.encryptAndVerifyRecoveryArtifact(ByteArrayInputStream(diagnostics), artifact)
            manifestLines += "diagnostic_artifact=${artifact.name}"
            manifestLines += "diagnostic_sha256=$digest"
            manifestLines += "diagnostics_separated=true"
        }
    }

    private fun writeManifest(directory: File, lines: List<String>) {
        val manifest = File(directory, "manifest.txt")
        FileOutputStream(manifest).use { output ->
            output.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }
}
