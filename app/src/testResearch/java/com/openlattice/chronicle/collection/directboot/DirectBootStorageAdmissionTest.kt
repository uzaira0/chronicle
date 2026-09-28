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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectBootStorageAdmissionTest {
    @get:Rule val temp = TemporaryFolder()

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

    @Test fun cachedDecisionExpiresAfterThirtySeconds() {
        assertEquals(false, DirectBootStorageAdmission.cachedDecision(1_000, 30_999, false))
        assertNull(DirectBootStorageAdmission.cachedDecision(1_000, 31_000, false))
        assertNull(DirectBootStorageAdmission.cachedDecision(Long.MIN_VALUE, 1_000, true))
        assertNull(DirectBootStorageAdmission.cachedDecision(2_000, 1_000, true))
    }
}
