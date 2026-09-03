package com.openlattice.chronicle.ui

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaySettingsSummaryTest {

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
                "Study ID: study-123\n" +
                "Participant reference: participant-456\n" +
                "Study server: Example University Sleep Study\n" +
                "Server host: research.example.org\n" +
                "Server origin: https://research.example.org/chronicle\n" +
                "Responsible institution: Example University\n" +
                "Research contact: sleep-study@example.org\n" +
                "Disclosure version: consent-3\n" +
                "Uploads: active\n" +
                "Connection: Healthy",
            activeEnrollmentSummary("study-123", "participant-456", server),
        )
    }

    @Test
    fun missingEnrollmentHasAnExplicitSingularSummary() {
        assertEquals(
            "No active study enrollment is configured on this device.",
            activeEnrollmentSummary("", "", null),
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
    fun playSettingsDoNotHideTheEnrollmentSummaryOrClaimABcmFixedServer() {
        val source = settingsSource()
        val playBlock = source.substringAfter("if (BuildConfig.DISTRIBUTION_CHANNEL == \"PLAY\")")
            .substringBefore("bindControls(view)")

        assertFalse(playBlock.contains("R.id.settingsServerSummary"))
        assertTrue(playBlock.contains("R.id.settingsServerList"))
        assertTrue(playBlock.contains("R.id.openServerSettingsButton"))
        assertTrue(playBlock.contains("R.id.addServerSettingsButton"))
        assertFalse(source.contains("fixed to BCM"))
        assertFalse(source.contains("upload server(s) configured"))
        assertFalse(source.contains("each enrolled server"))
        assertTrue(source.contains("R.string.platform_privacy_policy_url"))
        assertTrue(source.contains("studyPrivacyPolicyButton"))
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
                    CollectionModuleState(
                        moduleId = CollectionModuleId.BATTERY_TELEMETRY,
                        serverEnabled = true,
                        decision = ParticipantDecision.DECLINED,
                        decidedAtEpochMillis = 1L,
                        requiredApplied = true,
                        appliedVersion = 1,
                        appliedPolicySnapshot = null,
                        lastDisposition = null,
                    ),
                ),
            ),
        )

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
        appliedPolicySnapshot = null,
        lastDisposition = null,
    )

    private fun settingsSource(): String {
        val module = sequenceOf(File("."), File("app"))
            .map(File::getAbsoluteFile)
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module")
        return File(
            module,
            "src/main/java/com/openlattice/chronicle/ui/SettingsHomeFragment.kt",
        ).readText()
    }
}
