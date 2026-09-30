package com.openlattice.chronicle.collection.directboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Before
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectBootStorageAdmissionTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun clearPauseState() {
        DirectBootStorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(DirectBootStorageAdmission, Long.MIN_VALUE)
        ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_storage_admission", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun callbackAdmissionCatchesPauseCommitFailureAndClearKeepsFailureVisible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editor = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            if (method.name == "commit") false else proxy
        } as android.content.SharedPreferences.Editor
        val delegate = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_storage_admission", Context.MODE_PRIVATE)
        val prefs = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") editor else method.invoke(delegate, *(args ?: emptyArray()))
        } as android.content.SharedPreferences
        val protected = object : android.content.ContextWrapper(context.createDeviceProtectedStorageContext()) {
            override fun getFilesDir(): File = object : File(temp.root.absolutePath) { override fun getUsableSpace(): Long = 0 }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = prefs
        }
        val hooked = object : android.content.ContextWrapper(context) {
            override fun createDeviceProtectedStorageContext(): Context = protected
        }
        val cipher = object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
            override fun decrypt(blob: ByteArray) = blob.reversedArray()
        }
        DirectBootStorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(DirectBootStorageAdmission, Long.MIN_VALUE)
        assertFalse(DirectBootStorageAdmission.allowed(hooked, DirectBootSampleBuffer(temp.newFolder(), cipher)))
        assertThrows(IllegalStateException::class.java) { DirectBootStorageAdmission.clear(hooked) }
    }

    @Test fun callbackAdmissionFailsClosedWhenJournalOrPreferencesThrow() {
        assertFalse(DirectBootStorageAdmission.safeDecision { error("journal unavailable") })
        assertTrue(DirectBootStorageAdmission.safeDecision { true })
    }

    @Test fun pauseEpisodeIsPersistedBeforeJournalingAndJournaledOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher = object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
            override fun decrypt(blob: ByteArray) = blob.reversedArray()
        }
        val journalFile = File(temp.root, "diagnostics.bin")
        val journal = DirectBootDiagnosticsJournal(journalFile, cipher)
        val buffer = DirectBootSampleBuffer(temp.newFolder("buffer"), cipher)
        val low = LOCAL_STORAGE_RESERVE_BYTES - 1
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_storage_admission", Context.MODE_PRIVATE)

        // The journal is unavailable (a crash in the same place): the episode must survive it.
        val failing = DirectBootDiagnosticsJournal(File(temp.root, "missing/dir/diagnostics.bin"), object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("keystore unavailable")
            override fun decrypt(blob: ByteArray): ByteArray = error("keystore unavailable")
        })
        assertThrows(Exception::class.java) { DirectBootStorageAdmission.evaluate(context, buffer, low, failing) }
        val episode = prefs.getString("pause_episode_id", null)
        assertNotNull(episode)

        assertFalse(DirectBootStorageAdmission.evaluate(context, buffer, low, journal))
        assertFalse(DirectBootStorageAdmission.evaluate(context, buffer, low, journal))

        assertEquals(episode, prefs.getString("pause_episode_id", null))
        val state = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
            cipher.decrypt(journalFile.readBytes()).toString(Charsets.UTF_8),
        )
        assertEquals(1, state?.events?.size)
        assertEquals(1, state?.events?.single()?.count)
    }

    @Test fun recoveryRecordsPendingEpisodeOnceAfterPauseJournalWriteFailed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher = object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray()
            override fun decrypt(blob: ByteArray) = blob.reversedArray()
        }
        val buffer = DirectBootSampleBuffer(temp.newFolder("buffer"), cipher)
        val file = File(temp.root, "diagnostics.bin")
        val journal = DirectBootDiagnosticsJournal(file, cipher)
        val failing = DirectBootDiagnosticsJournal(File(temp.root, "failure/diagnostics.bin"), object : DirectBootRecordCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("journal unavailable")
            override fun decrypt(blob: ByteArray): ByteArray = error("journal unavailable")
        })
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_storage_admission", Context.MODE_PRIVATE)
        assertThrows(Exception::class.java) {
            DirectBootStorageAdmission.evaluate(context, buffer, LOCAL_STORAGE_RESERVE_BYTES - 1, failing)
        }
        val episode = prefs.getString("pause_episode_id", null)
        val occurredAt = prefs.getString("pause_episode_at", null)
        assertNotNull(episode)
        assertThrows(Exception::class.java) {
            DirectBootStorageAdmission.evaluate(context, buffer, LOCAL_STORAGE_RESERVE_BYTES, failing)
        }
        assertEquals(episode, prefs.getString("pause_episode_id", null))

        assertTrue(DirectBootStorageAdmission.evaluate(context, buffer, LOCAL_STORAGE_RESERVE_BYTES, journal))
        // Simulate a crash after the journal write but before the preferences acknowledgment.
        prefs.edit().putBoolean("paused", true).putString("pause_episode_id", episode)
            .putString("pause_episode_at", occurredAt).putBoolean("pause_episode_recorded", false).commit()
        assertTrue(DirectBootStorageAdmission.evaluate(context, buffer, LOCAL_STORAGE_RESERVE_BYTES, journal))
        assertTrue(DirectBootStorageAdmission.evaluate(context, buffer, LOCAL_STORAGE_RESERVE_BYTES, journal))

        val event = JsonSerializer.fromJson<DirectBootDiagnosticsJournal.State>(
            cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8),
        )!!.events.single()
        assertEquals(episode, event.id)
        assertEquals("COLLECTION_PAUSED_STORAGE", event.code)
        assertEquals(occurredAt, event.occurredAt)
        assertEquals(1, event.count)
        assertNull(prefs.getString("pause_episode_id", null))
    }

    @Test fun cachedDecisionExpiresAfterThirtySeconds() {
        assertEquals(false, DirectBootStorageAdmission.cachedDecision(1_000, 30_999, false))
        assertNull(DirectBootStorageAdmission.cachedDecision(1_000, 31_000, false))
        assertNull(DirectBootStorageAdmission.cachedDecision(Long.MIN_VALUE, 1_000, true))
        assertNull(DirectBootStorageAdmission.cachedDecision(2_000, 1_000, true))
    }
}
