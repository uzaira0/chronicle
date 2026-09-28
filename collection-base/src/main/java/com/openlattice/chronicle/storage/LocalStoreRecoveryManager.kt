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

    internal fun eraseForScope(root: File, digest: String) {
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val manifest = File(directory, "manifest.txt")
            val recorded = manifest.takeIf(File::isFile)?.readLines()
                ?.firstOrNull { it.startsWith("owner_scope_sha256=") }
                ?.substringAfter('=')
            if (recorded == null || recorded == "unknown" || recorded == digest) {
                check(directory.deleteRecursively()) { "Unable to erase withdrawn enrollment recovery bundle" }
            }
        }
    }

    fun preserveAndReset(
        context: Context,
        reason: LocalStoreRecoveryReason,
        confirmation: LocalStoreResetConfirmation,
        enrollmentOwner: Pair<String, String>? = null,
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
