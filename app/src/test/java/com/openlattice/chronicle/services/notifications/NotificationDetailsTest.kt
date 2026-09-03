package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.constants.NotificationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationDetailsTest {
    @Test
    fun requestCodeIsStableAcrossRefreshedParticipantAccessCodes() {
        val first = reminder(accessCode = "first-code", serverUrl = "https://first.example")
        val refreshed = reminder(accessCode = "second-code", serverUrl = "https://second.example")

        assertEquals(first.requestCode(), refreshed.requestCode())
    }

    @Test
    fun requestCodeChangesForAnotherFormOrSchedule() {
        val baseline = reminder()

        assertNotEquals(baseline.requestCode(), reminder(id = "questionnaire-2").requestCode())
        assertNotEquals(
            baseline.requestCode(),
            reminder(recurrenceRule = "FREQ=WEEKLY;BYDAY=MO").requestCode(),
        )
    }

    @Test
    fun diagnosticStringRedactsParticipantAccessCodeAndDestination() {
        val accessCode = "secret-participant-access-code"
        val serverUrl = "https://private.example"

        val diagnostic = reminder(accessCode = accessCode, serverUrl = serverUrl).toString()

        assertFalse(diagnostic.contains(accessCode))
        assertFalse(diagnostic.contains(serverUrl))
    }

    private fun reminder(
        id: String = "questionnaire-1",
        recurrenceRule: String = "FREQ=DAILY",
        serverUrl: String = "https://chronicle-screentime-app.research.bcm.edu",
        accessCode: String = "access-code",
    ) = NotificationDetails(
        id = id,
        type = NotificationType.QUESTIONNAIRE,
        recurrenceRule = recurrenceRule,
        title = "Check-in",
        message = "Tap to complete questionnaire",
        serverUrl = serverUrl,
        accessCode = accessCode,
    )
}
