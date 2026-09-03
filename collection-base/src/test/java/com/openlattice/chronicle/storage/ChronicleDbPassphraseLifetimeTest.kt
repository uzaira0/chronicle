package com.openlattice.chronicle.storage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChronicleDbPassphraseLifetimeTest {
    @Test
    fun successfulRoomOpenDoesNotZeroThePassphraseRetainedBySqlCipher() {
        val source = File("src/main/java/com/openlattice/chronicle/storage/ChronicleDb.kt")
            .readText()
            .substringAfter("private fun buildEncryptedDatabase")
            .substringBefore("private fun buildRoomInstance")

        assertTrue(source.contains("databaseOpened = true"))
        assertTrue(source.contains("if (!databaseOpened) passphrase.fill(0)"))
        assertFalse(
            "SQLCipher's open helper retains the supplied array for later connection opens.",
            source.contains("finally {\n                passphrase.fill(0)"),
        )
    }
}
