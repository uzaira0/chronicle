package com.openlattice.chronicle.collection.directboot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectBootSensorErasureTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val studyId = "11111111-1111-1111-1111-111111111111"
    private lateinit var db: ChronicleDb
    private val cipher = object : DirectBootRecordCipher {
        override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
        override fun decrypt(blob: ByteArray) = blob.reversedArray()
    }

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("direct-boot-erasure-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, studyId).putString(PARTICIPANT_ID, "participant").commit()
        setField(EncryptedPrefsHelper::class.java, "instance", EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        setField(ChronicleDb::class.java, "INSTANCE", null, db)
    }

    @After fun tearDown() {
        setField(ChronicleDb::class.java, "INSTANCE", null, null)
        setField(EncryptedPrefsHelper::class.java, "instance", EncryptedPrefsHelper, null)
        db.close()
    }

    private fun sample(id: String, sensor: String = "accelerometer") = SensorSampleEntry(
        id, sensor, "2026-09-29T01:00:00Z", "UTC", 1f, 2f, 3f, null, 3,
    )

    private fun buffer(owner: String) = DirectBootSampleBuffer(
        File(temp.root, "samples"), cipher, NoOpCollectionLog, ownerForAppend = { owner },
    )

    @Test fun interruptedRewriteAccountsErasedIdsOnceOnRetry() {
        var failRewrite = false
        val rewriteCipher = object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                if (failRewrite) error("checkpoint unavailable")
                return cipher.encrypt(plaintext)
            }
            override fun decrypt(blob: ByteArray) = cipher.decrypt(blob)
        }
        val buffer = DirectBootSampleBuffer(File(temp.root, "samples"), rewriteCipher, NoOpCollectionLog)
        buffer.append(listOf(sample("erased"), sample("keep", "gyroscope")))
        val file = File(temp.root, "diagnostics.bin")
        val journal = DirectBootDiagnosticsJournal(file, cipher)
        failRewrite = true
        org.junit.Assert.assertThrows(Exception::class.java) {
            DistributionRestrictedRuntime.eraseDirectBootSensorSamples(context, "accelerometer", buffer, journal, {})
        }
        org.junit.Assert.assertTrue("erasure accounting must precede the retryable file rewrite", file.isFile)
        failRewrite = false
        DistributionRestrictedRuntime.eraseDirectBootSensorSamples(context, "accelerometer", buffer, journal, {})
        val events = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
            cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8),
        )!!.events
        assertEquals(1, events.filter { it.code == "MODULE_POLICY_ERASED" }.sumOf { it.count })
        val ids = mutableListOf<String>()
        buffer.drain { batch -> ids += batch.map { it.id }; DirectBootSampleBuffer.DrainTransfer(batch.mapTo(hashSetOf()) { it.id }) }
        assertEquals(listOf("keep"), ids)
    }

    @Test fun erasingManySamplesWritesOneJournalEventPerFileNotOnePerSample() {
        val buffer = DirectBootSampleBuffer(File(temp.root, "samples"), cipher, NoOpCollectionLog)
        buffer.append((1..500).map { sample("s$it") })
        val file = File(temp.root, "diagnostics.bin")
        val journal = DirectBootDiagnosticsJournal(file, cipher)
        DistributionRestrictedRuntime.eraseDirectBootSensorSamples(context, "accelerometer", buffer, journal, {})
        val erased = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
            cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8),
        )!!.events.filter { it.code == "MODULE_POLICY_ERASED" }
        assertEquals(1, erased.size)
        assertEquals(500, erased.single().count)
    }

    @Test fun pausedDestinationSensorDiscardErasesSamplesAndRecordsTheConfiguredOwner() {
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "study", url = "https://localhost", studyId = studyId,
            participantId = "participant", sourceDeviceId = "device", enabled = false,
        ))
        val server = db.uploadServerDao().getById(id)!!
        val owner = DirectBootDiagnosticsJournal.Owner(studyId, "participant", "device", "$id:${server.createdAt}")
        val ownerKey = DirectBootSampleBuffer.ownerKey(owner)
        val buffer = buffer(ownerKey)
        buffer.append(listOf(sample("erase"), sample("keep", "gyroscope")))
        val file = File(temp.root, "diagnostics.bin")
        val journal = DirectBootDiagnosticsJournal(file, cipher)
        var enqueued = 0
        assertNull(exactActiveEnrollmentServer(context, db))

        DistributionRestrictedRuntime.eraseDirectBootSensorSamples(
            context, "accelerometer", buffer, journal, enqueueDrain = { enqueued++ },
        )

        val retained = mutableListOf<String>()
        buffer.drain(ownerKey) { batch ->
            retained += batch.map { it.id }
            DirectBootSampleBuffer.DrainTransfer(batch.mapTo(hashSetOf()) { it.id })
        }
        assertEquals(listOf("keep"), retained)
        val event = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
            cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8),
        )!!.events.single()
        assertEquals(owner, event.owner)
        assertEquals("MODULE_POLICY_ERASED", event.code)
        assertEquals(1, event.count)
        assertEquals(1, enqueued)
    }

    @Test fun missingDestinationSensorDiscardErasesEveryOwnerAndReportsAnObfuscatedJournal() {
        buffer("owner-A").append(listOf(sample("A"), sample("keep", "gyroscope")))
        buffer("owner-B").append(listOf(sample("B")))
        val file = File(temp.root, "diagnostics.bin")
        val obfuscated = cipher.encrypt("""{"a":null,"b":[]}""".toByteArray())
        file.writeBytes(obfuscated)

        DistributionRestrictedRuntime.eraseDirectBootSensorSamples(
            context, "accelerometer", buffer("owner-B"), DirectBootDiagnosticsJournal(file, cipher),
            enqueueDrain = {},
        )

        val retained = mutableListOf<String>()
        buffer("owner-A").drain("owner-A") { batch ->
            retained += batch.map { it.id }
            DirectBootSampleBuffer.DrainTransfer(batch.mapTo(hashSetOf()) { it.id })
        }
        assertEquals(listOf("keep"), retained)
        val events = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
            cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8),
        )!!.events
        assertEquals(listOf("DIRECT_BOOT_CORRUPT_RECORD", "MODULE_POLICY_ERASED"), events.map { it.code }.distinct())
        assertEquals(2, events.filter { it.code == "MODULE_POLICY_ERASED" }.sumOf { it.count })
        events.forEach { assertNull(it.owner) }
        assertEquals(1, temp.root.listFiles()!!.count {
            it.name.startsWith("diagnostics-corrupt-") && it.readBytes().contentEquals(obfuscated)
        })
    }

    private fun setField(owner: Class<*>, name: String, receiver: Any?, value: Any?) {
        owner.getDeclaredField(name).apply { isAccessible = true }.set(receiver, value)
    }
}
