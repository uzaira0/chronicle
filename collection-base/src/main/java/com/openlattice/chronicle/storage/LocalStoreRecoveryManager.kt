package com.openlattice.chronicle.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream

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
    fun preserveAndReset(
        context: Context,
        reason: LocalStoreRecoveryReason,
        confirmation: LocalStoreResetConfirmation,
    ): LocalStoreResetResult {
        confirmation.requireExplicitApproval()
        val appContext = context.applicationContext
        val databaseSources = ChronicleDb.recoverySourceFiles(appContext)
        check(databaseSources.isNotEmpty()) { "No failed local database bundle exists to recover" }

        val sources = buildList {
            addAll(databaseSources)
            DatabaseKeyManager.keyMetadataFile(appContext).takeIf(File::exists)?.let(::add)
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
