package com.openlattice.chronicle.services.upload

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.ui.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AccessLossDiagnosticsTest {
    private class MemoryPersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()
        override fun load() = buckets
        override fun save(buckets: List<LocalUploadIssueBucket>) {
            this.buckets = buckets
        }
    }

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("access-test", Context.MODE_PRIVATE)
    private val persistence = MemoryPersistence()
    private val store = LocalUploadDiagnosticsStore(persistence)
    private val interaction = setOf(LocalUploadModuleFamily.INTERACTION)
    private val t0 = OffsetDateTime.parse("2026-10-01T19:05:38Z")

    private fun accessMissing() = persistence.buckets.filter { it.issue == "COLLECTION_ACCESS_MISSING" }

    @Test
    fun disabledAccessibilityServiceMapsToTheInteractionFamily() {
        assertEquals(interaction, missingAccessFamilies(PermissionStatus(emptyList(), false, needAccessibility = true)))
        assertEquals(emptySet<LocalUploadModuleFamily>(), missingAccessFamilies(PermissionStatus(emptyList(), false)))
    }

    @Test
    fun oneDiagnosticAndOneNoticePerLossEpisode() {
        recordAccessEpisodes(store, prefs, "s:p", emptySet(), interaction, t0.minusHours(1))
        // Force-stop removes the granted service: the episode begins and is reported once.
        assertEquals(interaction, recordAccessEpisodes(store, prefs, "s:p", interaction, interaction, t0))
        // Still missing on later syncs: no new notice, no duplicate diagnostic.
        assertEquals(emptySet<LocalUploadModuleFamily>(),
            recordAccessEpisodes(store, prefs, "s:p", interaction, interaction, t0.plusHours(1)))
        assertEquals(1, accessMissing().size)
        assertEquals("INTERACTION", accessMissing().single().moduleFamily)
        assertEquals(t0.toString(), accessMissing().single().firstOccurredAt)

        // Re-enabled ends the episode; a second loss is a new episode.
        recordAccessEpisodes(store, prefs, "s:p", emptySet(), interaction, t0.plusHours(2))
        assertEquals(interaction, recordAccessEpisodes(store, prefs, "s:p", interaction, interaction, t0.plusHours(3)))
        assertEquals(2, accessMissing().size)
    }

    @Test
    fun accessNeverGrantedIsReportedWithoutANotice() {
        // Accepted in consent, not yet granted (onboarding): server diagnostic, no "turn it back on".
        assertEquals(emptySet<LocalUploadModuleFamily>(),
            recordAccessEpisodes(store, prefs, "s:p", interaction, interaction, t0))
        assertEquals(1, accessMissing().size)
        // Granted, then lost: now the participant is told.
        recordAccessEpisodes(store, prefs, "s:p", emptySet(), interaction, t0.plusHours(1))
        assertEquals(interaction, recordAccessEpisodes(store, prefs, "s:p", interaction, interaction, t0.plusHours(2)))
    }

    @Test
    fun aNewEnrollmentStartsItsOwnEpisode() {
        recordAccessEpisodes(store, prefs, "s:p", emptySet(), interaction, t0.minusHours(1))
        recordAccessEpisodes(store, prefs, "s:p", interaction, interaction, t0)
        // The new enrollment never had the access: diagnostic only, and nothing kept from the old one.
        assertEquals(emptySet<LocalUploadModuleFamily>(),
            recordAccessEpisodes(store, prefs, "s:q", interaction, interaction, t0.plusHours(1)))
        assertEquals(setOf("s:q|INTERACTION"), prefs.all.keys)
    }
}
