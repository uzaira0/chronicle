package com.openlattice.chronicle.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class WithdrawalDashboardAuditTest {
    @Test fun settingsUploadLabelsUseTheOverviewWithdrawalState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, enrolled = true)
        WithdrawalStateStore(context).beginWithdrawal()
        val snapshot = DashboardDataRepository.load(context)
        val summary = activeEnrollmentSummary(snapshot.studyId, snapshot.participantId,
            snapshot.servers.single(), context.copyResolver())
        assertEquals("Your withdrawal request is pending. Data collection has stopped.", snapshot.serverHealth.message)
        for ((stop, label) in mapOf(
            ParticipationStop.WITHDRAWAL_PENDING to "stopping (withdrawal pending)",
            ParticipationStop.ENDED to "stopped",
            ParticipationStop.NOT_ENROLLED to "not configured",
            ParticipationStop.PAUSED to "active",
            ParticipationStop.COMPLETED to "active",
        )) {
            val server = snapshot.servers.single().copy(participationStop = stop)
            val text = activeEnrollmentSummary(snapshot.studyId, snapshot.participantId, server, context.copyResolver())
            assertEquals("Uploads: $label", text.lineSequence().single { it.startsWith("Uploads:") })
        }
        val fragment = SettingsHomeFragment()
        withHostedFragment(fragment) { controller ->
            val expected = "Uploads: stopping (withdrawal pending)"
            val row = serverRow(controller, fragment, expected)
            assertEquals(listOf(expected, expected), listOf(summary, row).map { text ->
                text.lineSequence().single { it.startsWith("Uploads:") }
            })
        }
    }

    @Test fun pausedAndCompletedSnapshotsAndSettingsKeepUploadHealth() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, enrolled = true)
        val fragment = SettingsHomeFragment()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(connectivity.activeNetwork)
        val db = ChronicleDb.getInstance(context)
        val server = db.uploadServerDao().getConfiguredServer()!!.copy(lastUploadTime = "2026-10-01T18:00:00Z")
        withHostedFragment(fragment) { controller ->
            for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)) {
                onPersistenceWorker { EnrollmentSettings(context).setParticipationStatus(status) }
                val stop = requireNotNull(ParticipationStop.of(status))
                db.uploadServerDao().update(server)
                shadowOf(connectivity).setNetworkCapabilities(network, NetworkCapabilities())
                assertSettingsHealth(controller, fragment, stop, context.getString(R.string.server_health_offline))
                val capabilities = NetworkCapabilities().apply {
                    shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
                assertSettingsHealth(controller, fragment, stop, context.getString(R.string.server_health_healthy))
                db.uploadServerDao().update(server.copy(consecutiveFailures = 12))
                assertSettingsHealth(controller, fragment, stop, context.getString(R.string.server_health_uploads_failing))
            }
        }
    }

    private suspend fun assertSettingsHealth(controller: ActivityController<AppCompatActivity>,
        fragment: SettingsHomeFragment, stop: ParticipationStop, expected: String) {
        val context = fragment.requireContext()
        val snapshot = DashboardDataRepository.load(context)
        assertEquals(stop, snapshot.participationStop)
        assertEquals(expected, snapshot.serverHealth.message)
        assertEquals(expected, snapshot.servers.single().healthLabel)
        val summary = activeEnrollmentSummary(snapshot.studyId, snapshot.participantId,
            snapshot.servers.single(), context.copyResolver())
        assertTrue(summary, summary.contains(context.getString(R.string.summary_connection, expected)))
        val row = serverRow(controller, fragment, expected)
        assertTrue(row, row.contains(expected))
    }

    private fun serverRow(controller: ActivityController<AppCompatActivity>, fragment: SettingsHomeFragment,
        expected: String): String {
        controller.pause().resume()
        val container = fragment.requireView().findViewById<LinearLayout>(R.id.settingsServerList)
        fun rowText(): String = ((container.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView)?.text.toString()
        awaitDashboardUi { rowText().contains(expected) }
        return rowText()
    }

    @Test fun durablePendingWithdrawalTakesPrecedenceOverTeamTerminationCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AuditStores.install(context, enrolled = true)
        val store = WithdrawalStateStore(context)
        store.beginWithdrawal()
        EnrollmentSettings(context).setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
        val snapshot = DashboardDataRepository.load(context)
        assertEquals("WITHDRAWAL_PENDING", snapshot.participationStop?.name)
        assertEquals(0, snapshot.collection.active)
        assertTrue(snapshot.collection.message.contains("withdrawal", ignoreCase = true))
        assertFalse(snapshot.collection.message.contains("study team", ignoreCase = true))
        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
    }
}
