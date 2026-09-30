package com.openlattice.chronicle.storage

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

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
    internal fun directBootSources(context: Context): List<File> {
        val filesDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext().filesDir
        } else context.filesDir
        val dir = File(filesDir, "direct_boot_sensor_buffer")
        return directBootSourceFiles(dir)
    }

    internal fun directBootSourceFiles(dir: File): List<File> =
        if (dir.isDirectory) dir.walkTopDown().filter(File::isFile).toList() else emptyList()

    private fun scopeDigest(studyId: String, participantId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$studyId\u0000$participantId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Explicit withdrawal removes all matching bundles, including legacy bundles with no scope. */
    fun eraseForEnrollment(context: Context, studyId: String, participantId: String) {
        val root = File(context.noBackupFilesDir, "chronicle-recovery")
        val digest = scopeDigest(studyId, participantId)
        eraseForScope(root, digest)
    }

    /** Explicit pending module erasure also covers an archive made during verified recovery. */
    fun erasePendingRecoveryArtifacts(context: Context, ownerScopeDigest: String?) {
        val root = File(context.noBackupFilesDir, "chronicle-recovery")
        eraseForScope(root, ownerScopeDigest)
    }

    internal fun eraseForScope(root: File, digest: String?) {
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val manifest = File(directory, "manifest.txt")
            val recorded = manifest.takeIf(File::isFile)?.readLines()
                ?.firstOrNull { it.startsWith("owner_scope_sha256=") }
                ?.substringAfter('=')
            if (digest == null || recorded == null || recorded == "unknown" || recorded == digest) {
                val retained = try { preserveDiagnosticsBeforeErasure(directory); emptySet() }
                catch (error: Exception) {
                    android.util.Log.e("LocalStoreRecovery", "Unable to preserve separate recovery diagnostics before explicit erasure", error)
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

    private fun diagnosticFiles(directory: File): List<File> {
        val names = File(directory, "manifest.txt").takeIf(File::isFile)?.readLines().orEmpty()
            .filter { it.startsWith("diagnostic_artifact=") }.map { it.substringAfter('=') }.toSet()
        return directory.listFiles().orEmpty().filter {
            it.isFile && (it.name in names || it.name.contains("-diagnostics.") || it.name.contains("diagnostics-corrupt-"))
        }
    }

    /** An unsplit database/preferences archive may contain the only diagnostic journal. */
    private fun preserveDiagnosticsBeforeErasure(directory: File) {
        val manifest = File(directory, "manifest.txt").takeIf(File::isFile)?.readLines().orEmpty()
        val mixed = directory.listFiles().orEmpty().any {
            it.name.contains("chronicle.db") || it.name.contains("-chronicle") || it.name.contains("chronicle_encrypted_prefs")
        }
        if (mixed && "diagnostics_separated=true" !in manifest) {
            android.util.Log.w("LocalStoreRecovery", "Explicit erasure removes an unsplit recovery bundle and its mixed diagnostics")
        }
        val diagnosticNames = manifest.filter { it.startsWith("diagnostic_artifact=") }.map { it.substringAfter('=') }.toSet()
        if (mixed && "diagnostics_separated=true" in manifest &&
            (diagnosticNames.isEmpty() || diagnosticNames.any { !File(directory, it).isFile })) {
            android.util.Log.w("LocalStoreRecovery", "Separate recovery diagnostics are missing; completing explicit erasure")
        }
        val diagnostics = diagnosticFiles(directory)
        if (diagnostics.isEmpty()) return
        val preserved = File(checkNotNull(checkNotNull(directory.parentFile).parentFile), "chronicle-diagnostics-recovery/${directory.name}")
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
    }

    /** Reads only diagnostic tables; no sample or participant payload enters this artifact. */
    internal fun diagnosticSnapshot(db: ChronicleDb, legacyDiagnostics: String?): ByteArray {
        val result = org.json.JSONObject().put("legacy_upload_diagnostics", legacyDiagnostics)
        listOf("upload_diagnostics", "diagnostic_import_checkpoints").forEach { table ->
            val rows = org.json.JSONArray()
            db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = org.json.JSONObject()
                    cursor.columnNames.forEachIndexed { index, name ->
                        row.put(name, when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> org.json.JSONObject.NULL
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            android.database.Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP)
                            else -> cursor.getString(index)
                        })
                    }
                    rows.put(row)
                }
            }
            result.put(table, rows)
        }
        val quarantine = org.json.JSONArray()
        db.openHelper.readableDatabase.query("SELECT * FROM local_data_quarantine WHERE sourceTable IN ('upload_diagnostics', 'legacy_upload_diagnostics', 'unowned_upload_diagnostics', 'direct_boot_diagnostic')").use { cursor ->
            while (cursor.moveToNext()) {
                val row = org.json.JSONObject()
                cursor.columnNames.forEachIndexed { index, name ->
                    row.put(name, if (cursor.getType(index) == android.database.Cursor.FIELD_TYPE_BLOB)
                        android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP) else cursor.getString(index))
                }
                quarantine.put(row)
            }
        }
        result.put("diagnostic_quarantine", quarantine)
        return result.toString().toByteArray(Charsets.UTF_8)
    }

    fun preserveAndReset(
        context: Context,
        reason: LocalStoreRecoveryReason,
        confirmation: LocalStoreResetConfirmation,
        enrollmentOwner: Pair<String, String>? = null,
        legacyDiagnostics: String? = null,
        legacyDiagnosticsVerified: Boolean = false,
    ): LocalStoreResetResult {
        confirmation.requireExplicitApproval()
        val appContext = context.applicationContext
        val databaseSources = ChronicleDb.recoverySourceFiles(appContext)
        check(databaseSources.isNotEmpty()) { "No failed local database bundle exists to recover" }

        val sources = buildList {
            addAll(databaseSources)
            DatabaseKeyManager.keyMetadataFile(appContext).takeIf(File::exists)?.let(::add)
            // Before the one-time verified import, the encrypted preferences file is the
            // authoritative source of legacy diagnostics. Preserve it with the database.
            File(appContext.applicationInfo.dataDir, "shared_prefs/chronicle_encrypted_prefs.xml")
                .takeIf(File::exists)?.let(::add)
            addAll(directBootSources(appContext))
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
                "owner_scope_sha256=${enrollmentOwner?.let { scopeDigest(it.first, it.second) } ?: "unknown"}",
            )
            sources.forEachIndexed { index, source ->
                val artifact = File(bundleDirectory, "artifact-${index + 1}-${source.name}.enc")
                val sha256 = DatabaseKeyManager.encryptAndVerifyRecoveryArtifact(source, artifact)
                manifestLines += "artifact=${artifact.name},bytes=${source.length()},sha256=$sha256"
            }
            val diagnostics = runCatching { diagnosticSnapshot(ChronicleDb.getInstance(appContext), legacyDiagnostics) }.getOrNull()
            if (diagnostics != null && (legacyDiagnosticsVerified || sources.none { it.name == "chronicle_encrypted_prefs.xml" })) {
                val temporary = File(bundleDirectory, "diagnostic-snapshot.json")
                try {
                    FileOutputStream(temporary).use { it.write(diagnostics); it.fd.sync() }
                    val artifact = File(bundleDirectory, "diagnostic-snapshot.enc")
                    val digest = DatabaseKeyManager.encryptAndVerifyRecoveryArtifact(temporary, artifact)
                    manifestLines += "diagnostic_artifact=${artifact.name}"
                    manifestLines += "diagnostic_sha256=$digest"
                    manifestLines += "diagnostics_separated=true"
                } finally { check(!temporary.exists() || temporary.delete()) }
            }
            writeManifest(bundleDirectory, manifestLines)

            DatabaseKeyManager.clearStoredPassphrase(appContext)
            ChronicleDb.resetAfterVerifiedRecoveryBundle(appContext)
            return LocalStoreResetResult(bundleDirectory)
        } catch (error: Exception) {
            // Preserve any completed encrypted artifacts for diagnosis. A future attempt uses a
            // new timestamped directory and never overwrites the evidence from this attempt.
            throw IllegalStateException("Local store recovery reset did not complete", error)
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
