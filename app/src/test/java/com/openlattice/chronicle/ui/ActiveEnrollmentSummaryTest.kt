package com.openlattice.chronicle.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.PlatformPolicyActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.ServerEnrollmentActivity
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "en")
class ActiveEnrollmentSummaryTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun resetBrowserAndToasts() {
        shadowOf(context as Application).checkActivities(false)
        ShadowToast.reset()
    }

    @Test
    fun activeEnrollmentSummaryIdentifiesOneStudyParticipantAndServer() {
        val server = UploadServerSummary(
            id = 17L,
            name = "Example University Sleep Study",
            url = "https://research.example.org/chronicle",
            enabled = true,
            healthLabel = "Healthy",
            lastSuccess = null,
            history = emptyList(),
            responsibleInstitution = "Example University",
            researchContact = "sleep-study@example.org",
            privacyPolicyUrl = "https://research.example.org/privacy",
            disclosureVersion = "consent-3",
        )

        assertEquals(
            "Active study enrollment\n" +
                "Study ID: 11111111-1111-1111-1111-111111111111\n" +
                "Participant reference: participant-456\n" +
                "Study server: Example University Sleep Study\n" +
                "Server host: research.example.org\n" +
                "Server origin: https://research.example.org/chronicle\n" +
                "Responsible institution: Example University\n" +
                "Research contact: sleep-study@example.org\n" +
                "Disclosure version: consent-3\n" +
                "Uploads: active\n" +
                "Connection: Healthy",
            activeEnrollmentSummary(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "participant-456", server, context.copyResolver()),
        )
    }

    @Test
    fun missingEnrollmentHasAnExplicitSingularSummary() {
        assertEquals(
            "No active study enrollment is configured on this device.",
            activeEnrollmentSummary(null, "", null, context.copyResolver()),
        )
    }

    @Test
    fun userIdentificationControlsRequireActiveManifestScopeAndLocalChoice() {
        listOf(
            userIdentificationControlState(
                activeEnrollment = false,
                studyAuthorized = true,
                participantEnabled = true,
            ),
            userIdentificationControlState(
                activeEnrollment = true,
                studyAuthorized = false,
                participantEnabled = true,
            ),
        ).forEach { unavailable ->
            assertFalse(unavailable.visible)
            assertFalse(unavailable.switchEnabled)
            assertFalse(unavailable.targetChoicesEnabled)
        }

        val availableButOff = userIdentificationControlState(
            activeEnrollment = true,
            studyAuthorized = true,
            participantEnabled = false,
        )
        assertTrue(availableButOff.visible)
        assertTrue(availableButOff.switchEnabled)
        assertFalse(availableButOff.targetChoicesEnabled)

        assertEquals(
            UserIdentificationControlState(
                visible = true,
                switchEnabled = true,
                targetChoicesEnabled = true,
            ),
            userIdentificationControlState(
                activeEnrollment = true,
                studyAuthorized = true,
                participantEnabled = true,
            ),
        )
    }

    @Test
    fun settingsShowTheEnrollmentSummaryAndDistributionAppropriateServerControls() {
        AuditStores.install(context, enrolled = true)
        val server = requireNotNull(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer())
        val fragment = SettingsHomeFragment()
        withHostedFragment(fragment) { controller ->
            val view = fragment.requireView()
            val summary = view.findViewById<TextView>(R.id.settingsServerSummary)
            awaitDashboardUi { summary.text.contains(server.name) }
            assertEquals(View.VISIBLE, summary.visibility)
            val manualVisibility = when (DistributionChannel.current()) {
                DistributionChannel.AMAZON -> View.GONE
                else -> View.VISIBLE
            }
            assertEquals(manualVisibility, view.findViewById<View>(R.id.settingsServerList).visibility)
            // Every flavor reaches Withdraw from study through the study server details.
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.openServerSettingsButton).visibility)
            assertFalse(summary.text.contains("fixed to BCM"))
            assertTrue(view.findViewById<View>(R.id.studyPrivacyPolicyButton).hasOnClickListeners())
            view.findViewById<View>(R.id.privacyPolicyButton).performClick()
            assertEquals(PlatformPolicyActivity::class.java.name,
                shadowOf(controller.get()).nextStartedActivity.component?.className)
        }
    }

    /** The participant who wants to leave the study reaches this study's server screen from Settings. */
    @Test
    fun theStudyServerControlsOpenThisEnrollmentsServerScreen() {
        AuditStores.install(context, enrolled = true)
        val server = requireNotNull(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer())
        val fragment = SettingsHomeFragment()
        withHostedFragment(fragment) { controller ->
            val view = fragment.requireView()
            awaitDashboardUi { view.findViewById<TextView>(R.id.settingsServerSummary).text.contains(server.name) }
            fun opened(): Intent? = shadowOf(controller.get()).nextStartedActivity
            view.findViewById<View>(R.id.openServerSettingsButton).performClick()
            awaitDashboardUi { shadowOf(controller.get()).peekNextStartedActivity() != null }
            val details = checkNotNull(opened())
            assertEquals(ServerEnrollmentActivity::class.java.name, details.component?.className)
            assertEquals(server.id, details.getLongExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, -1))
            val list = view.findViewById<android.view.ViewGroup>(R.id.settingsServerList)
            if (list.visibility == View.VISIBLE) {
                awaitDashboardUi { list.childCount == 1 }
                ((list.getChildAt(0) as android.view.ViewGroup).getChildAt(0)).performClick()
                val row = checkNotNull(opened())
                assertEquals(ServerEnrollmentActivity::class.java.name, row.component?.className)
                assertEquals(server.id, row.getLongExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, -1))
            }
        }
    }

    /**
     * H-POLICY-LINK: on Android 11+ a browser the app cannot see still opens the study's privacy and withdrawal pages
     * (no resolveActivity gate), and a phone with no browser at all says so instead of doing nothing.
     */
    @Test
    @Config(sdk = [34])
    fun studyPolicyLinksOpenTheBrowserOrSayThereIsNone() {
        AuditStores.install(context, enrolled = true)
        val server = requireNotNull(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer())
        val fragment = SettingsHomeFragment()
        withHostedFragment(fragment) { controller ->
            val view = fragment.requireView()
            awaitDashboardUi { view.findViewById<TextView>(R.id.settingsServerSummary).text.contains(server.name) }
            val links = mapOf(R.id.studyPrivacyPolicyButton to "https://research.example.org/privacy",
                R.id.studyWithdrawalSettingsButton to "https://research.example.org/withdraw")
            awaitDashboardUi { links.keys.all { view.findViewById<View>(it).visibility == View.VISIBLE } }
            for ((button, url) in links) {
                generateSequence { shadowOf(controller.get()).nextStartedActivity }.toList()
                assertTrue(view.findViewById<View>(button).performClick())
                val opened = shadowOf(controller.get()).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, opened?.action)
                assertEquals(Uri.parse(url), opened?.data)
            }
            shadowOf(context as Application).checkActivities(true)
            for (button in links.keys) {
                ShadowToast.reset()
                assertTrue(view.findViewById<View>(button).performClick())
                assertEquals(context.getString(R.string.no_browser_for_link), ShadowToast.getTextOfLatestToast())
                assertFalse(controller.get().isFinishing)
            }
        }
    }

    @Test
    @Config(sdk = [34])
    fun surveyNotificationRecoveryIsIndependentOfIdentificationConsent() {
        AuditStores.install(context, enrolled = true)
        val db = ChronicleDb.getInstance(context)
        val server = requireNotNull(db.uploadServerDao().getConfiguredServer())
        onPersistenceWorker {
            val dao = db.collectionModuleStateDao()
            dao.upsert(dao.get(CollectionModuleId.USER_IDENTIFICATION.id)!!.copy(decision = ParticipantDecision.DECLINED.name))
            EnrollmentSettings(context).setAwarenessNotificationsEnabled(true)
        }
        shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val fragment = SettingsHomeFragment()
        withHostedFragment(fragment) {
            val view = fragment.requireView()
            awaitDashboardUi { view.findViewById<TextView>(R.id.settingsServerSummary).text.contains(server.name) }
            val button = view.findViewById<View>(R.id.surveyNotificationSettingsButton)
            val status = view.findViewById<TextView>(R.id.surveyNotificationStatus)
            val visibility = if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) View.VISIBLE else View.GONE
            assertEquals(visibility, button.visibility)
            assertEquals(visibility, status.visibility)
            if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
                assertTrue(status.text.contains("blocked"))
                assertTrue(button.hasOnClickListeners())
            }
        }
    }

    @Test
    fun suppressedBatteryGuidanceStillHasAStatusAndRecoveryControl() {
        AuditStores.install(context, enrolled = true)
        EnrollmentSettings(context).toggleBatteryOptimizationDialog(false)
        val fragment = SettingsHomeFragment()
        withHostedFragment(fragment) {
            awaitDashboardUi { fragment.requireView().findViewById<View>(R.id.batterySettingsButton).visibility == View.VISIBLE }
            assertTrue(fragment.requireView().findViewById<TextView>(R.id.backgroundAccessStatus).text.contains("missing"))
            assertTrue(fragment.requireView().findViewById<View>(R.id.batterySettingsButton).performClick())
        }
    }

    @Test
    fun suppressedOemGuidanceStillHasARecoveryControl() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("samsung")
        AuditStores.install(context, enrolled = true)
        EnrollmentSettings(context).toggleOemGuidanceDialog(false)
        val fragment = SettingsHomeFragment()
        withHostedFragment(fragment) {
            val button = fragment.requireView().findViewById<View>(R.id.oemSettingsButton)
            awaitDashboardUi { button.visibility == View.VISIBLE }
            assertTrue(button.performClick())
        }
    }

    @Test
    fun notificationListenerAccessRequiresAnActiveAcceptedModuleAndNoWithdrawal() {
        val accepted = notificationState(
            serverEnabled = true,
            decision = ParticipantDecision.ACCEPTED,
        )
        assertTrue(
            notificationAccessMayBeRequested(
                activeEnrollment = true,
                withdrawalState = WithdrawalState.NONE,
                moduleStates = listOf(accepted),
            ),
        )

        listOf(
            notificationState(serverEnabled = false, decision = ParticipantDecision.ACCEPTED),
            notificationState(serverEnabled = true, decision = ParticipantDecision.UNDECIDED),
            notificationState(serverEnabled = true, decision = ParticipantDecision.DECLINED),
        ).forEach { inactive ->
            assertFalse(
                notificationAccessMayBeRequested(
                    activeEnrollment = true,
                    withdrawalState = WithdrawalState.NONE,
                    moduleStates = listOf(inactive),
                ),
            )
        }
        assertFalse(
            notificationAccessMayBeRequested(
                activeEnrollment = false,
                withdrawalState = WithdrawalState.NONE,
                moduleStates = listOf(accepted),
            ),
        )
        WithdrawalState.entries.filterNot { it == WithdrawalState.NONE }.forEach { withdrawal ->
            assertFalse(
                notificationAccessMayBeRequested(
                    activeEnrollment = true,
                    withdrawalState = withdrawal,
                    moduleStates = listOf(accepted),
                ),
            )
        }
        assertFalse(
            notificationAccessMayBeRequested(
                activeEnrollment = true,
                withdrawalState = WithdrawalState.NONE,
                moduleStates = listOf(
                    accepted,
                    notificationState(
                        moduleId = CollectionModuleId.BATTERY_TELEMETRY,
                        serverEnabled = true,
                        decision = ParticipantDecision.DECLINED,
                    ).copy(requiredApplied = true),
                ),
            ),
        )

    }

    @Test
    fun audioOnlyStudyCanRequestNotificationListenerAccess() {
        val audioOnly = notificationState(
            moduleId = CollectionModuleId.AUDIO_CONTENT,
            serverEnabled = true,
            decision = ParticipantDecision.ACCEPTED,
        )
        assertTrue(
            notificationAccessMayBeRequested(
                activeEnrollment = true,
                withdrawalState = WithdrawalState.NONE,
                moduleStates = listOf(audioOnly),
            ),
        )
        assertEquals(
            setOf(CollectionModuleId.AUDIO_CONTENT),
            activeNotificationAccessModules(listOf(audioOnly)),
        )
    }

    private fun notificationState(
        moduleId: CollectionModuleId = CollectionModuleId.NOTIFICATION_ACTIVITY,
        serverEnabled: Boolean,
        decision: ParticipantDecision,
    ): CollectionModuleState = CollectionModuleState(
        moduleId = moduleId,
        serverEnabled = serverEnabled,
        decision = decision,
        decidedAtEpochMillis = if (decision == ParticipantDecision.UNDECIDED) null else 1L,
        requiredApplied = false,
        appliedVersion = 1,
    )

}
