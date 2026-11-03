package com.openlattice.chronicle.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChronicleDbPassphraseLifetimeTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val prefix = "passphrase-test-${UUID.randomUUID()}-"
    private val databaseName = prefix + "database"
    private val preferences = mutableSetOf<String>()
    private val scoped = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferences += prefix + name
            return base.getSharedPreferences(prefix + name, mode)
        }
    }

    @After fun cleanUp() {
        base.deleteDatabase(databaseName)
        preferences.forEach(base::deleteSharedPreferences)
    }

    @Test fun encryptedHelperCanReopenAfterItsFirstSuccessfulOpen() {
        val db = ChronicleDb.openEncryptedDatabase(scoped, databaseName)
        try {
            db.openHelper.close()
            db.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(0) == "ok")
            }
        } finally { db.close() }
    }

    /** The pilot rows of the only offline copy survive a fresh database opener, unreadable at rest. */
    @Test fun seededPilotRowsSurviveAFreshOpenerAndNeverSitInPlaintext() {
        val seeded = seedAndClose()

        val reopened = ChronicleDb.openEncryptedDatabase(scoped, databaseName)
        try {
            assertEquals(seeded, dump(reopened))
            reopened.batterySampleDao().insertAllUnchecked(listOf(battery("after-reopen")))
            assertEquals(2, reopened.batterySampleDao().count())
        } finally { reopened.close() }
        val files = ChronicleDb.recoverySourceFiles(scoped, databaseName)
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val bytes = file.readBytes()
            assertFalse("${file.name} is a plaintext SQLite file", bytes.copyOf(16).contentEquals(SQLITE_HEADER))
            assertFalse("${file.name} holds the canary in plaintext", String(bytes, Charsets.ISO_8859_1).contains(CANARY))
        }
    }

    /** Lost or partial key metadata refuses the open and leaves the encrypted store byte for byte. */
    @Test fun lostOrPartialKeyMetadataRefusesTheOpenAndKeepsTheStore() {
        val seeded = seedAndClose()
        val keyPrefs = scoped.getSharedPreferences("chronicle_db_key_prefs", Context.MODE_PRIVATE)
        val saved = keyPrefs.all.mapValues { it.value as String }
        val before = storeBytes()

        assertTrue(keyPrefs.edit().remove("passphrase_iv").commit())
        assertEquals(LocalStoreRecoveryReason.INVALID_KEY_MATERIAL, refusal())
        assertTrue(keyPrefs.edit().clear().commit())
        assertEquals(LocalStoreRecoveryReason.MISSING_KEY_MATERIAL, refusal())
        assertTrue(keyPrefs.edit().putString("encrypted_passphrase", "AAAA").putString("passphrase_iv", "AAAA").commit())
        assertEquals(LocalStoreRecoveryReason.INVALID_KEY_MATERIAL, refusal())
        assertStoreUnchanged(before)

        assertTrue(keyPrefs.edit().clear().apply { saved.forEach { (key, value) -> putString(key, value) } }.commit())
        val restored = ChronicleDb.openEncryptedDatabase(scoped, databaseName)
        try { assertEquals(seeded, dump(restored)) } finally { restored.close() }
    }

    /** A store written by a newer schema is refused as replaced and kept, never recreated. */
    @Test fun aDowngradedAppRefusesTheNewerStoreAndKeepsIt() {
        seedAndClose()
        val db = ChronicleDb.openEncryptedDatabase(scoped, databaseName)
        try { db.openHelper.writableDatabase.execSQL("PRAGMA user_version = ${ChronicleDb.VERSION + 1}") } finally { db.close() }
        val before = storeBytes()

        assertEquals(LocalStoreRecoveryReason.LOCAL_SCHEMA_REPLACED, refusal())
        assertStoreUnchanged(before)
    }

    private fun seedAndClose(): Map<String, List<List<String?>>> {
        val db = ChronicleDb.openEncryptedDatabase(scoped, databaseName)
        try {
            db.uploadServerDao().insert(UploadServerEntity(name = "Study", url = "https://study.example",
                studyId = STUDY, participantId = "participant-$CANARY", sourceRandomDeviceId = DEVICE,
                apiKey = "ck_$CANARY"))
            db.queueEntryData().insertEntries(listOf(QueueEntry(1_000L, 7L, "[{\"app\":\"$CANARY\"}]".toByteArray())))
            db.userQueueEntryData().insertSelection(1_000L, "Someone else")
            db.batterySampleDao().insertAllUnchecked(listOf(battery("battery-1")))
            db.uploadDiagnosticDao().insertIfAbsentUnchecked(UploadDiagnosticEntity("diagnostic-1", STUDY,
                "participant-$CANARY", "device", "epoch", "2026-10-30", "USAGE", "TIMEOUT", 1,
                "2026-10-30T12:00:00Z", "2026-10-30T12:00:00Z", null, null))
            db.pendingCollectionAckDao().insertAll(listOf(PendingCollectionAckEntity(stableKey = "ack-1", record = "{\"$CANARY\":1}")))
            return dump(db).also { rows ->
                SEEDED_TABLES.forEach { assertTrue("$it holds a seeded row", rows.getValue(it).isNotEmpty()) }
            }
        } finally { db.close() }
    }

    private fun battery(id: String) =
        BatterySampleEntry(id, "2026-10-30T12:00:00Z", "America/Santiago", 50, "CHARGING", "AC", 300, 4000, "GOOD")

    /** Every column of every pilot store, as text (blobs in hex), in row order. */
    private fun dump(db: ChronicleDb): Map<String, List<List<String?>>> = SEEDED_TABLES.associateWith { table ->
        db.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    if (cursor.getType(column) == android.database.Cursor.FIELD_TYPE_BLOB) {
                        cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                    } else cursor.getString(column)
                })
            }
        }
    }

    private fun refusal(): LocalStoreRecoveryReason =
        assertThrows(LocalStoreRecoveryRequiredException::class.java) {
            ChronicleDb.openEncryptedDatabase(scoped, databaseName).close()
        }.recoveryReason

    /** The main file: a clean close checkpoints every committed page into it (the -shm index changes on any open). */
    private fun storeBytes(): ByteArray = scoped.getDatabasePath(databaseName).readBytes()

    private fun assertStoreUnchanged(before: ByteArray) = assertArrayEquals("the refused store changed", before, storeBytes())

    private companion object {
        const val STUDY = "11111111-1111-1111-1111-111111111111"
        const val DEVICE = "22222222-2222-2222-2222-222222222222"
        const val CANARY = "canary7f3a9"
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val SEEDED_TABLES = listOf("upload_servers", "dataQueue", "userQueue", "battery_samples", "upload_diagnostics",
            "pending_collection_acks", "upload_queue_evidence")
    }
}
