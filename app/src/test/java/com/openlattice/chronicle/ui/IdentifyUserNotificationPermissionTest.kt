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

        assertFalse(state.effective)
        assertTrue(state.needsNotificationRecovery)
    }

    @Test
    fun permissionRecoveryRestoresTheAuthorizedEnabledRuntime() {
        val state = userIdentificationRuntimeState(
            authorized = true,
            preferenceEnabled = true,
            notificationPermissionGranted = true,
        )

        assertTrue(state.effective)
        assertFalse(state.needsNotificationRecovery)
    }

    @Test
    fun backgroundStartDeferralDoesNotAppearActive() {
        val state = userIdentificationRuntimeState(
            authorized = true,
            preferenceEnabled = true,
            notificationPermissionGranted = true,
            runtimeStartDeferred = true,
        )

        assertFalse(state.effective)
        assertTrue(state.startDeferred)
    }

    @Test
    fun notificationPermissionCannotEnableAnUnauthorizedOrLocallyDisabledFeature() {
        listOf(
            userIdentificationRuntimeState(false, true, true),
            userIdentificationRuntimeState(true, false, true),
        ).forEach { state ->
            assertFalse(state.effective)
            assertFalse(state.needsNotificationRecovery)
        }
    }
}
