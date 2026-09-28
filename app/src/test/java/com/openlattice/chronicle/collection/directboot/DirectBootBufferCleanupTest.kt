package com.openlattice.chronicle.collection.directboot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectBootBufferCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun quarantinedJournalIsInventoriedOnEveryRetryByCiphertextDigest() {
        val journal = File(tmp.root, "diagnostics-corrupt-stale-name.bin")
        journal.writeText("encrypted journal")
        val durableIds = linkedSetOf<String>()
        repeat(2) {
            inventoryQuarantinedDirectBootJournals(tmp.root) { durableIds += it }
            assertTrue(journal.exists())
        }
        assertEquals(1, durableIds.size)
        assertTrue(durableIds.single() != "stale-name")
    }
}
