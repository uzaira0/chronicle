package com.openlattice.chronicle.storage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests cannot load SQLCipher, so this pins the conversion order in source; the on-device
 * conversion is ChronicleDbMigrationTest.plaintextDatabaseAtAnOlderSchemaIsEncryptedThenMigrated.
 */
class ChronicleDbPlaintextSchemaVersionTest {
    @Test
    fun conversionCopiesThePlaintextSchemaVersionBeforeDetaching() {
        val source = File("src/main/java/com/openlattice/chronicle/storage/ChronicleDb.kt")
            .readText()
            .substringAfter("private fun migrateUnencryptedDb")
            .substringBefore("private fun databaseBundleExists")
        val export = source.indexOf("sqlcipher_export('main', 'plaintext')")
        val read = source.indexOf("PRAGMA plaintext.user_version")
        val nonZero = source.indexOf("check(schemaVersion > 0)")
        val write = source.indexOf("PRAGMA main.user_version = \$schemaVersion")
        val detach = source.indexOf("DETACH DATABASE plaintext")

        assertTrue("export must precede the version copy", export in 0 until read)
        assertTrue("a zero plaintext version must fail the conversion", nonZero in read until write)
        assertTrue("the version must be written to the encrypted copy before DETACH", write in nonZero until detach)
    }
}
