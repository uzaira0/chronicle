package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.ChronicleDb
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A pause episode captured for enrollment A is never inserted under a replacement enrollment B. */
@RunWith(RobolectricTestRunner::class)
class StoragePauseEpisodeOwnerTest {
    @get:org.junit.Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val at = OffsetDateTime.parse("2026-09-29T00:00:00Z")

    private fun episodes(id: String): Int = ChronicleDb.getInstance(context).openHelper.readableDatabase
        .query("SELECT count(*) FROM upload_diagnostics WHERE id = ?", arrayOf(id))
        .use { it.moveToFirst(); it.getInt(0) }

    @Test fun episodeFromAReplacedEnrollmentIsDropped() {
        TestStores.install(context, enrolled = true)
        val current = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
        val store = LocalUploadDiagnosticsStore.of(context)

        store.recordOperationalOnce("episode-a", LocalUploadModuleFamily.LOCAL_STORE,
            LocalOperationalIssue.COLLECTION_PAUSED_STORAGE, at,
            ownerScope = "22222222-2222-2222-2222-222222222222:previous-participant")
        store.recordOperationalOnce("episode-b", LocalUploadModuleFamily.LOCAL_STORE,
            LocalOperationalIssue.COLLECTION_PAUSED_STORAGE, at,
            ownerScope = "${current.studyId}:${current.participantId}")

        assertEquals(0, episodes("episode-a"))
        assertEquals(1, episodes("episode-b"))
    }
}
