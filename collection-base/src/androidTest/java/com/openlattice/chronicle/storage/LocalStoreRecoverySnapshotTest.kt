package com.openlattice.chronicle.storage

import android.util.Base64

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.io.DataInputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4::class)
class LocalStoreRecoverySnapshotTest {
    @After fun cleanUpLiveStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ChronicleDb.resetAfterVerifiedRecoveryBundle(context)
        DatabaseKeyManager.clearStoredPassphrase(context)
        File(context.noBackupFilesDir, "chronicle-recovery").deleteRecursively()
    }

    @Test fun exportKeepsDiagnosticNumbersAndQuarantineBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        try {
            val now = "2026-10-06T00:00:00Z"
            db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity("diagnostic", null, null, null, null,
                "2026-10-06", "SENSOR", "DIRECT_BOOT_CORRUPT_RECORD", 2, now, now, null, null))
            val bytes = byteArrayOf(0, 1, -1)
            db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                "quarantine", "direct_boot_diagnostic", "source", null, null, null, bytes,
                "corrupt", "2026-10-06T00:00:00Z",
            ))
            val result = JSONObject(LocalStoreRecoveryManager.diagnosticSnapshot(db).toString(Charsets.UTF_8))
            val diagnostic = result.getJSONArray("upload_diagnostics").getJSONObject(0)
            assertTrue(diagnostic.get("count") is Number)
            assertEquals(2, diagnostic.getInt("count"))
            val quarantine = result.getJSONArray("diagnostic_quarantine").getJSONObject(0)
            assertFalse(quarantine.has("studyId"))
            assertArrayEquals(bytes, Base64.decode(quarantine.getString("rawData"), Base64.NO_WRAP))
        } finally { db.close() }
    }

    /**
     * The production preservation of a real SQLCipher store. The bundle's verified digests are the exact
     * bytes of every live source, no canary sits in it in plaintext, and only then are the live store and its key gone.
     */
    @Test fun preservationVerifiesEveryLiveSourceBeforeItResets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val sources = seedLiveStore(context) + DatabaseKeyManager.keyMetadataFile(context)
        val digests = sources.associate { it.name to sha256(it.readBytes()) }

        val bundle = LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
            LocalStoreResetConfirmation(preserveEncryptedRecoveryBundle = true, understandsReenrollmentRequired = true),
            LocalStoreRecoveryManager.scopeDigest(STUDY, PARTICIPANT)).recoveryBundleDirectory
        try {
            val artifacts = File(bundle, "manifest.txt").readLines().filter { it.startsWith("artifact=") }
                .associate { line ->
                    val fields = line.removePrefix("artifact=").split(',')
                    val ciphertext = File(bundle, fields[0])
                    assertEquals("the encrypted artifact recovers its exact source", fields[2].removePrefix("sha256="),
                        sha256(decrypt(ciphertext)))
                    fields[0].substringAfter('-').substringAfter('-').removeSuffix(".enc") to fields[2].removePrefix("sha256=")
                }
            assertEquals(digests, artifacts.filterKeys { it in digests })
            checkNotNull(bundle.listFiles()).forEach { file ->
                assertFalse("${file.name} holds the canary in plaintext", String(file.readBytes(), Charsets.ISO_8859_1).contains(CANARY))
            }
            assertTrue("the live store is gone only after the bundle verified", ChronicleDb.recoverySourceFiles(context).isEmpty())
            assertFalse(DatabaseKeyManager.keyMetadataFile(context).readTextOrEmpty().contains("encrypted_passphrase"))
        } finally {
            bundle.deleteRecursively()
        }
    }

    /** A preservation that cannot write its bundle, or was not confirmed, leaves the live store and key. */
    @Test fun aPreservationThatCannotFinishLeavesTheLiveStoreAndItsKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val sources = seedLiveStore(context)
        val before = sources.associate { it.name to sha256(it.readBytes()) }
        val key = DatabaseKeyManager.keyMetadataFile(context).readBytes()
        val root = File(context.noBackupFilesDir, "chronicle-recovery")
        root.deleteRecursively()
        check(root.createNewFile()) // a file where the bundle directory must go: no destination space
        try {
            assertThrows(IllegalArgumentException::class.java) {
                LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
                    LocalStoreResetConfirmation(preserveEncryptedRecoveryBundle = true, understandsReenrollmentRequired = false))
            }
            assertThrows(IllegalStateException::class.java) {
                LocalStoreRecoveryManager.preserveAndReset(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
                    LocalStoreResetConfirmation(preserveEncryptedRecoveryBundle = true, understandsReenrollmentRequired = true))
            }
            assertEquals(before, ChronicleDb.recoverySourceFiles(context).associate { it.name to sha256(it.readBytes()) })
            assertArrayEquals(key, DatabaseKeyManager.keyMetadataFile(context).readBytes())
            val reopened = ChronicleDb.openEncryptedDatabase(context)
            try { assertEquals(1, reopened.batterySampleDao().count()) } finally { reopened.close() }
        } finally {
            root.delete()
            context.deleteDatabase("chronicle_encrypted")
        }
    }

    /** The app's own encrypted store, one canary row in it, closed so every committed page is in the main file. */
    private fun seedLiveStore(context: android.content.Context): List<File> {
        context.deleteDatabase("chronicle_encrypted")
        val db = ChronicleDb.openEncryptedDatabase(context)
        try {
            db.batterySampleDao().insertAllUnchecked(listOf(BatterySampleEntry(CANARY, "2026-10-30T12:00:00.000Z",
                "America/Santiago", 50, "CHARGING", "AC", 300, 4000, "GOOD")))
        } finally { db.close() }
        return ChronicleDb.recoverySourceFiles(context).also { check(it.isNotEmpty()) }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Decode the recovery format with the platform cipher, independently of the production verifier. */
    private fun decrypt(artifact: File): ByteArray = DataInputStream(artifact.inputStream()).use { input ->
        val magic = ByteArray(7).also(input::readFully)
        assertArrayEquals("CHRREC1".toByteArray(Charsets.US_ASCII), magic)
        val iv = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keyStore.getKey("chronicle_recovery_key_v1", null), GCMParameterSpec(128, iv))
        }.doFinal(input.readBytes())
    }

    private fun File.readTextOrEmpty() = if (isFile) readText() else ""

    private companion object {
        const val STUDY = "11111111-1111-1111-1111-111111111111"
        const val PARTICIPANT = "participant-a"
        const val CANARY = "canary-recovery-5d1"
    }
}
