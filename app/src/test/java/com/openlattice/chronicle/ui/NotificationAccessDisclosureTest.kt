package com.openlattice.chronicle.ui

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assume.assumeTrue

class NotificationAccessDisclosureTest {
    @Test
    fun playHidesTheExcludedNotificationListenerControl() {
        assertFalse(notificationAccessControlVisible(restrictedResearchPermissions = false))
        assertTrue(notificationAccessControlVisible(restrictedResearchPermissions = true))
    }

    @Test
    fun playBuildRejectsNotificationAccessDisclosureForExcludedModule() {
        assumeTrue(BuildConfig.DISTRIBUTION_CHANNEL == "PLAY")
        val copy = notificationAccessDisclosure(setOf(CollectionModuleId.AUDIO_CONTENT))

        assertEquals("Access unavailable", copy.title)
        assertTrue(copy.affordanceMessage.contains("not included"))
        assertTrue(copy.body.contains("does not request"))
    }

    @Test
    fun playBuildRejectsMixedNotificationAccessDisclosure() {
        assumeTrue(BuildConfig.DISTRIBUTION_CHANNEL == "PLAY")
        val copy = notificationAccessDisclosure(
            setOf(
                CollectionModuleId.AUDIO_ACTIVITY,
                CollectionModuleId.NOTIFICATION_ACTIVITY,
            ),
        )

        assertEquals("Access unavailable", copy.title)
        assertTrue(copy.body.contains("Play release"))
    }
}
