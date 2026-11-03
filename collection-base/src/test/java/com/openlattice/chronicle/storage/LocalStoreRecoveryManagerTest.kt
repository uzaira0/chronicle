package com.openlattice.chronicle.storage

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalStoreRecoveryManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun completedCiphertextAndDirectoriesAreSyncedBeforeReset() {
        val root = tmp.newFolder("no-backup")
        val bundle = File(root, "chronicle-recovery/bundle").apply { mkdirs() }
        File(bundle, "artifact.enc").writeText("completed ciphertext including tag")
        File(bundle, "manifest.txt").writeText("verified")
        val calls = mutableListOf<String>()
        LocalStoreRecoveryManager.finishRecoveryBundle(bundle, sync = { calls += it.name }) { calls += "reset" }
        assertEquals(setOf("artifact.enc", "manifest.txt"), calls.take(2).toSet())
        assertEquals(listOf("bundle", "chronicle-recovery", "no-backup", "reset"), calls.drop(2))
        var reset = false
        assertThrows(java.io.IOException::class.java) {
            LocalStoreRecoveryManager.finishRecoveryBundle(bundle, sync = { throw java.io.IOException("fsync failed") }) { reset = true }
        }
        assertFalse("failed durability must preserve keys and originals", reset)
    }

    @Test
    fun directBootRecoveryIncludesJournalAndQuarantinedOriginals() {
        val dir = tmp.newFolder("direct_boot_sensor_buffer")
        listOf("buffer.bin", "buffer.draining.bin", "diagnostics.bin", "corrupt-a.bin")
            .forEach { File(dir, it).writeText(it) }
        val quarantine = File(dir, "quarantine").apply { mkdir() }
        File(quarantine, "owner-a.bin").writeText("encrypted")
        assertEquals(5, LocalStoreRecoveryManager.directBootSourceFiles(dir).size)
    }

    @Test
    fun withdrawalErasesMatchingAndUnscopedBundlesButKeepsOtherScopes() {
        val root = tmp.newFolder("chronicle-recovery")
        val matching = File(root, "matching").apply { mkdir(); File(this, "manifest.txt").writeText("owner_scope_sha256=A") }
        val other = File(root, "other").apply { mkdir(); File(this, "manifest.txt").writeText("owner_scope_sha256=B") }
        val unscoped = File(root, "unscoped").apply { mkdir() }
        LocalStoreRecoveryManager.eraseForScope(root, "A")
        assertFalse(matching.exists())
        assertFalse(unscoped.exists())
        assertTrue(other.exists())
    }
    @Test
    fun onlyManifestListedDiagnosticsSurviveExplicitErasure() {
        val noBackup = tmp.newFolder("no_backup")
        val root = File(noBackup, "chronicle-recovery")
        File(root, "bundle").apply {
            mkdirs()
            File(this, "manifest.txt").writeText("owner_scope_sha256=A\ndiagnostic_artifact=diagnostic-snapshot.enc\n")
            File(this, "diagnostic-snapshot.enc").writeText("listed")
            File(this, "artifact-1-upload-diagnostics.db.enc").writeText("research named like diagnostics")
        }
        File(root, "unlisted").apply {
            mkdirs()
            File(this, "diagnostics-corrupt-1.bin").writeText("no manifest")
        }
        LocalStoreRecoveryManager.eraseForScope(root, "A")
        val preserved = File(noBackup, "chronicle-diagnostics-recovery")
        assertEquals(listOf("bundle"), preserved.list().orEmpty().sorted())
        assertEquals(listOf("diagnostic-snapshot.enc", "manifest.txt"), File(preserved, "bundle").list().orEmpty().sorted())
        assertFalse(root.listFiles().orEmpty().any())
    }

    @Test
    fun withdrawalErasesUnknownOwnerRecoveryBundle() {
        val root = tmp.newFolder("unknown-recovery")
        val unknown = File(root, "unresolved").apply {
            mkdir()
            File(this, "manifest.txt").writeText("owner_scope_sha256=unknown")
        }
        LocalStoreRecoveryManager.eraseForScope(root, "withdrawn-scope")
        assertFalse(unknown.exists())
    }
    @Test
    fun resetRequiresBothIndependentConfirmations() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalStoreResetConfirmation(
                preserveEncryptedRecoveryBundle = true,
                understandsReenrollmentRequired = false,
            ).requireExplicitApproval()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalStoreResetConfirmation(
                preserveEncryptedRecoveryBundle = false,
                understandsReenrollmentRequired = true,
            ).requireExplicitApproval()
        }
    }

    @Test
    fun resetAllowsOnlyCompleteExplicitApproval() {
        LocalStoreResetConfirmation(
            preserveEncryptedRecoveryBundle = true,
            understandsReenrollmentRequired = true,
        ).requireExplicitApproval()
    }

    private fun withdrawnBundle(noBackup: File, manifest: String) = File(noBackup, "chronicle-recovery/bundle").apply {
        mkdirs()
        File(this, "manifest.txt").writeText(manifest)
        File(this, "diagnostic-snapshot.enc").writeText("sealed diagnostics")
        File(this, "artifact-1-chronicle_encrypted.enc").writeText("sealed research data")
    }

    /**
     * A previous withdrawal left a partial diagnostics copy: this erasure removes the research data, keeps the
     * diagnostics in the bundle and drops the bad copy, and the next erasure completes with a verified copy.
     */
    @Test
    fun aPartialDiagnosticsCopyKeepsTheDiagnosticsUntilTheNextErasureCompletes() {
        val noBackup = tmp.newFolder("no_backup_partial")
        val bundle = withdrawnBundle(noBackup, "owner_scope_sha256=A\ndiagnostic_artifact=diagnostic-snapshot.enc\n")
        val partial = File(noBackup, "chronicle-diagnostics-recovery/bundle/diagnostic-snapshot.enc")
        checkNotNull(partial.parentFile).mkdirs()
        partial.writeText("sealed diag")

        LocalStoreRecoveryManager.eraseForScope(File(noBackup, "chronicle-recovery"), "A")
        assertEquals(listOf("diagnostic-snapshot.enc", "manifest.txt"), bundle.list().orEmpty().sorted())
        assertFalse("the bad copy does not block the next attempt", partial.exists())

        LocalStoreRecoveryManager.eraseForScope(File(noBackup, "chronicle-recovery"), "A")
        assertFalse(bundle.exists())
        assertEquals("sealed diagnostics", partial.readText())
    }

    /** The diagnostics directory cannot be created (the path is taken): the diagnostics stay; research data goes. */
    @Test
    fun diagnosticsThatCannotBeCopiedStayInTheBundle() {
        val noBackup = tmp.newFolder("no_backup_blocked")
        val bundle = withdrawnBundle(noBackup, "owner_scope_sha256=A\ndiagnostic_artifact=diagnostic-snapshot.enc\n")
        File(noBackup, "chronicle-diagnostics-recovery").writeText("not a directory")
        LocalStoreRecoveryManager.eraseForScope(File(noBackup, "chronicle-recovery"), "A")
        assertEquals(listOf("diagnostic-snapshot.enc", "manifest.txt"), bundle.list().orEmpty().sorted())
    }

    /** The manifest lists a diagnostics file that is already gone: the present one is kept and erasure completes. */
    @Test
    fun aMissingListedDiagnosticDoesNotBlockErasure() {
        val noBackup = tmp.newFolder("no_backup_missing")
        val bundle = withdrawnBundle(noBackup,
            "owner_scope_sha256=A\ndiagnostic_artifact=diagnostic-snapshot.enc\ndiagnostic_artifact=lost.enc\n")
        LocalStoreRecoveryManager.eraseForScope(File(noBackup, "chronicle-recovery"), "A")
        assertFalse(bundle.exists())
        assertEquals("sealed diagnostics", File(noBackup, "chronicle-diagnostics-recovery/bundle/diagnostic-snapshot.enc").readText())
    }
}
