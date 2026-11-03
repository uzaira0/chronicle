package com.openlattice.chronicle.services.upload

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.CapabilityEnvironment
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.PermissionStatus
import com.openlattice.chronicle.collection.capability.activeModulePermissionStatus
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AccessLossDiagnosticsTest {
    private class MemoryPersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()
        var failNextSave = false
        override fun load() = buckets
        override fun save(buckets: List<LocalUploadIssueBucket>) {
            if (failNextSave) { failNextSave = false; error("process died before the diagnostic write") }
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

    @Test fun runtimeGrantLossReportsOnlyActiveActivityAndSleepEpisodesAndRecoveryClosesThem() {
        val modules = listOf(CollectionModuleId.ACTIVITY_RECOGNITION,
            CollectionModuleId.SLEEP)
        val states = modules.map { CollectionModuleState(
            it, true, ParticipantDecision.ACCEPTED, 0, false, 1) }
        val environment = CapabilityEnvironment(
            33, DistributionChannel.RESEARCH, true, true, true, true,
            emptySet(), true, true, restrictedCollectorsCompiledIn = true)
        val expected = setOf(LocalUploadModuleFamily.ACTIVITY_RECOGNITION, LocalUploadModuleFamily.SLEEP)
        val missing = missingAccessFamilies(activeModulePermissionStatus(states, environment))
        assertEquals(expected, missing)
        recordAccessEpisodes(store, prefs, "s:p", missing, expected, t0)
        recordAccessEpisodes(store, prefs, "s:p", missing, expected, t0.plusMinutes(1))
        assertEquals(2, accessMissing().size)
        val recovered = missingAccessFamilies(activeModulePermissionStatus(states,
            environment.copy(grantedRuntimePermissions = setOf(
                ModulePermissions.ACTIVITY_RECOGNITION))))
        assertEquals(emptySet<LocalUploadModuleFamily>(), recovered)
        recordAccessEpisodes(store, prefs, "s:p", recovered, expected, t0.plusMinutes(2))
        assertEquals(0, prefs.all.keys.count { !it.contains("granted|") })
        assertEquals(emptySet<LocalUploadModuleFamily>(), missingAccessFamilies(
            activeModulePermissionStatus(
            states.map { it.copy(serverEnabled = false) }, environment)))
    }

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
    fun anEpisodeWhoseDiagnosticNeverPersistedIsReportedWhenAccessReturns() {
        persistence.failNextSave = true
        runCatching { recordAccessEpisodes(store, prefs, "s:p", interaction, interaction, t0) }
        assertEquals(0, accessMissing().size)

        recordAccessEpisodes(store, prefs, "s:p", emptySet(), interaction, t0.plusHours(1))

        assertEquals(t0.toString(), accessMissing().single().firstOccurredAt)
        assertEquals(0, prefs.all.keys.count { !it.contains("granted|") })
        // A replay after the checkpoint is gone adds nothing.
        recordAccessEpisodes(store, prefs, "s:p", emptySet(), interaction, t0.plusHours(2))
        assertEquals(1, accessMissing().size)
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
