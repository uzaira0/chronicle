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
    fun withdrawalErasesMatchingAndLegacyBundlesButKeepsOtherScopes() {
        val root = tmp.newFolder("chronicle-recovery")
        val matching = File(root, "matching").apply { mkdir(); File(this, "manifest.txt").writeText("owner_scope_sha256=A") }
        val other = File(root, "other").apply { mkdir(); File(this, "manifest.txt").writeText("owner_scope_sha256=B") }
        val legacy = File(root, "legacy").apply { mkdir() }
        LocalStoreRecoveryManager.eraseForScope(root, "A")
        assertFalse(matching.exists())
        assertFalse(legacy.exists())
        assertTrue(other.exists())
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
}
