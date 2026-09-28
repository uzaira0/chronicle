package com.openlattice.chronicle.collection.directboot

import com.openlattice.chronicle.serialization.JsonSerializer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectBootDiagnosticsJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun sampleOwnerKeyRetainsEnrollmentEpoch() {
        val owner = DirectBootDiagnosticsJournal.Owner("study", "participant", "device", "epoch")
        val decoded = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.Owner>(
            DirectBootSampleBuffer.ownerKey(owner),
        )
        assertEquals(owner, decoded)
    }

    @Test fun retryingTheSameIncidentDoesNotDoubleCount() {
        val cipher = object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
            override fun decrypt(blob: ByteArray) = blob.reversedArray()
        }
        val file = File(temp.root, "diagnostics.bin")
        val journal = DirectBootDiagnosticsJournal(file, cipher)
        journal.record("DIRECT_BOOT_CORRUPT_RECORD", 1, "same-tail")
        journal.record("DIRECT_BOOT_CORRUPT_RECORD", 1, "same-tail")

        val state = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
            cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8),
        )
        assertEquals(1, state?.events?.size)
        assertEquals(1, state?.events?.single()?.count)
    }

    @Test fun corruptJournalMovesAsideAndCanBeReplaced() {
        val cipher = object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
            override fun decrypt(blob: ByteArray) = blob.reversedArray()
        }
        val file = File(temp.root, "diagnostics.bin")
        file.writeText("corrupt")
        val journal = DirectBootDiagnosticsJournal(file, cipher)
        assertTrue(journal.quarantineCorruptJournal())
        journal.record("DIRECT_BOOT_CORRUPT_RECORD", 1)
        assertEquals(1, temp.root.listFiles()?.count { it.name.startsWith("diagnostics-corrupt-") })
    }
}
