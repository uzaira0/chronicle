package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One low-storage pause is one diagnostic, even across a crash between persisting and recording. */
@RunWith(RobolectricTestRunner::class)
class StoragePauseEpisodeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = EncryptedPrefsHelper.getEncryptedPrefs(context)
    private val low = LOCAL_STORAGE_RESERVE_BYTES - 1
    private val enough = LOCAL_STORAGE_RESERVE_BYTES

    @Before fun setUp() = TestStores.install(context, enrolled = true)

    private fun episodeId() = prefs.getString("collection_storage_pause_episode_id", null)

    private fun pauses() = ChronicleDb.getInstance(context).openHelper.readableDatabase
        .query("SELECT coalesce(sum(count), 0) FROM upload_diagnostics WHERE issueCode = 'COLLECTION_PAUSED_STORAGE'")
        .use { it.moveToFirst(); it.getInt(0) }

    @Test fun repeatedLowStorageChecksAreOneEpisode() {
        assertFalse(StorageAdmission.allowed(context, low))
        val first = episodeId()
        assertFalse(StorageAdmission.allowed(context, low))

        assertEquals(first, episodeId())
        assertEquals(1, pauses())

        assertTrue(StorageAdmission.allowed(context, enough))
        assertNull(episodeId())
        assertFalse(StorageAdmission.allowed(context, low))

        assertNotEquals(first, episodeId())
        assertEquals(2, pauses())
    }

    @Test fun episodePersistedBeforeACrashIsRecordedOnce() {
        val scope = "${prefs.getString(STUDY_ID, "")}:${prefs.getString(PARTICIPANT_ID, "")}"
        // State left by a process that died after persisting the episode, before or after recording it.
        prefs.edit().putString("collection_storage_enrollment_scope", scope)
            .putBoolean("collection_storage_paused", true)
            .putString("collection_storage_pause_episode_id", "episode-1").commit()

        StorageAdmission.allowed(context, low)
        StorageAdmission.allowed(context, low)

        assertEquals("episode-1", episodeId())
        assertEquals(1, pauses())
    }
}
