package com.openlattice.chronicle.services.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every state that stops the device-user prompt from being seen, and the settings page that fixes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IdentifyUserNotificationChannelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun channel(importance: Int) =
        manager.createNotificationChannel(NotificationChannel(IDENTIFY_USER_CHANNEL_ID, "Device user prompts", importance))

    @Test
    fun highImportanceChannelPopsUpAndIsEnabled() {
        channel(NotificationManager.IMPORTANCE_HIGH)
        assertTrue(hasNotificationPermission(app, IDENTIFY_USER_CHANNEL_ID))
        assertTrue(notificationPopsUp(app, IDENTIFY_USER_CHANNEL_ID))
    }

    @Test
    fun silentOrNoPopUpChannelStillPostsButDoesNotPopUp() {
        for (importance in listOf(NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_LOW)) {
            channel(importance)
            assertTrue(hasNotificationPermission(app, IDENTIFY_USER_CHANNEL_ID))
            assertFalse(notificationPopsUp(app, IDENTIFY_USER_CHANNEL_ID))
            manager.deleteNotificationChannel(IDENTIFY_USER_CHANNEL_ID)
        }
    }

    @Test
    fun blockedChannelBlocksIdentificationAndOpensTheChannelPage() {
        channel(NotificationManager.IMPORTANCE_NONE)
        assertFalse(hasNotificationPermission(app, IDENTIFY_USER_CHANNEL_ID))
        val intent = notificationSettingsIntent(app, IDENTIFY_USER_CHANNEL_ID)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(IDENTIFY_USER_CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun appLevelSwitchOffOpensTheAppPage() {
        channel(NotificationManager.IMPORTANCE_HIGH)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(hasNotificationPermission(app, IDENTIFY_USER_CHANNEL_ID))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notificationSettingsIntent(app, IDENTIFY_USER_CHANNEL_ID).action)
    }

    @Test
    fun deniedRuntimePermissionOpensTheAppPage() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        channel(NotificationManager.IMPORTANCE_HIGH)
        assertFalse(hasNotificationPermission(app, IDENTIFY_USER_CHANNEL_ID))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notificationSettingsIntent(app, IDENTIFY_USER_CHANNEL_ID).action)
    }
}
