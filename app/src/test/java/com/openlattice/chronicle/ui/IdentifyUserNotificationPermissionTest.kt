package com.openlattice.chronicle.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyUserNotificationPermissionTest {
    @Test
    fun grantedNotificationsAllowIdentifyUser() {
        assertEquals(
            IdentifyUserNotificationAction.PROCEED,
            identifyUserNotificationAction(
                sdkInt = 35,
                runtimePermissionGranted = true,
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun android13AndNewerRequestsTheRuntimePermission() {
        assertEquals(
            IdentifyUserNotificationAction.REQUEST_RUNTIME,
            identifyUserNotificationAction(
                sdkInt = 33,
                runtimePermissionGranted = false,
                notificationsEnabled = false,
            ),
        )
    }

    @Test
    fun grantedRuntimePermissionWithBlockedDeliveryRecoversThroughSettings() {
        assertEquals(
            IdentifyUserNotificationAction.OPEN_SETTINGS,
            identifyUserNotificationAction(
                sdkInt = 35,
                runtimePermissionGranted = true,
                notificationsEnabled = false,
            ),
        )
    }

    @Test
    fun olderAndroidRecoversThroughAppNotificationSettings() {
        assertEquals(
            IdentifyUserNotificationAction.OPEN_SETTINGS,
            identifyUserNotificationAction(
                sdkInt = 32,
                runtimePermissionGranted = true,
                notificationsEnabled = false,
            ),
        )
    }

    @Test
    fun permissionRevocationPausesAnEnabledPreferenceAndSurfacesRecovery() {
        val state = userIdentificationRuntimeState(
            authorized = true,
            preferenceEnabled = true,
            notificationPermissionGranted = false,
        )

        assertEquals(UserIdentificationRuntimeState.NEEDS_NOTIFICATION_RECOVERY, state)
        assertFalse(state.effective)
    }

    @Test
    fun permissionRecoveryRestoresTheAuthorizedEnabledRuntime() {
        val state = userIdentificationRuntimeState(
            authorized = true,
            preferenceEnabled = true,
            notificationPermissionGranted = true,
        )

        assertEquals(UserIdentificationRuntimeState.RUNNING, state)
    }

    @Test
    fun backgroundStartDeferralDoesNotAppearActive() {
        val state = userIdentificationRuntimeState(
            authorized = true,
            preferenceEnabled = true,
            notificationPermissionGranted = true,
            runtimeStartDeferred = true,
        )

        assertEquals(UserIdentificationRuntimeState.START_DEFERRED, state)
        assertFalse(state.effective)
    }

    @Test
    fun notificationPermissionCannotEnableAnUnauthorizedOrLocallyDisabledFeature() {
        listOf(
            userIdentificationRuntimeState(false, true, true),
            userIdentificationRuntimeState(true, false, true),
        ).forEach { state ->
            assertEquals(UserIdentificationRuntimeState.OFF, state)
        }
    }

    @Test
    fun silentPromptChannelKeepsIdentificationRunningButSurfacesTheFix() {
        val state = userIdentificationRuntimeState(
            authorized = true,
            preferenceEnabled = true,
            notificationPermissionGranted = true,
            promptPopsUp = false,
        )

        assertEquals(UserIdentificationRuntimeState.RUNNING_SILENT, state)
        assertTrue(state.effective)
    }

    @Test
    fun silentChannelIsNotReportedWhileIdentificationIsOff() {
        val state = userIdentificationRuntimeState(
            authorized = true,
            preferenceEnabled = false,
            notificationPermissionGranted = true,
            promptPopsUp = false,
        )

        assertEquals(UserIdentificationRuntimeState.OFF, state)
    }
}
