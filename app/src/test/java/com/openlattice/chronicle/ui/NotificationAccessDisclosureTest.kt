package com.openlattice.chronicle.ui

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationAccessDisclosureTest {
    @Test
    fun playHidesTheExcludedNotificationListenerControl() {
        assertFalse(notificationAccessControlVisible(restrictedResearchPermissions = false))
        assertTrue(notificationAccessControlVisible(restrictedResearchPermissions = true))
    }

    @Test
    fun playBuildRejectsNotificationAccessDisclosureForExcludedModule() {
        if (BuildConfig.DISTRIBUTION_CHANNEL != "PLAY") return
        val copy = notificationAccessDisclosure(setOf(CollectionModuleId.AUDIO_CONTENT))

        assertEquals("Access unavailable", copy.title)
        assertTrue(copy.affordanceMessage.contains("not included"))
        assertTrue(copy.body.contains("does not request"))
    }

    @Test
    fun playBuildRejectsMixedNotificationAccessDisclosure() {
        if (BuildConfig.DISTRIBUTION_CHANNEL != "PLAY") return
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
