package com.openlattice.chronicle.ui

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NotificationAccessDisclosureTest {
    @Test
    fun playHidesTheExcludedNotificationListenerControl() {
        assertFalse(notificationAccessControlVisible(restrictedResearchPermissions = false))
        assertTrue(notificationAccessControlVisible(restrictedResearchPermissions = true))
    }

    @Test
    fun amazonBuildRejectsNotificationAccessDisclosureForExcludedModule() {
        assumeTrue(DistributionChannel.current() == DistributionChannel.AMAZON)
        val copy = notificationAccessDisclosure(setOf(CollectionModuleId.AUDIO_CONTENT))

        assertEquals("Access unavailable", copy.title)
        assertTrue(copy.affordanceMessage.contains("not included"))
        assertTrue(copy.body.contains("does not request"))
    }

    @Test
    fun amazonBuildRejectsMixedNotificationAccessDisclosure() {
        assumeTrue(DistributionChannel.current() == DistributionChannel.AMAZON)
        val copy = notificationAccessDisclosure(
            setOf(
                CollectionModuleId.AUDIO_ACTIVITY,
                CollectionModuleId.NOTIFICATION_ACTIVITY,
            ),
        )

        assertEquals("Access unavailable", copy.title)
        assertTrue(copy.body.contains("This release"))
    }
}
