package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.audit.auditWorkerParameters
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.work.ListenableWorker
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.services.notifications.NotificationsWorker
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import com.openlattice.chronicle.services.notifications.armReminder
import com.openlattice.chronicle.services.notifications.reminderAlarms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchSensorResumeTest : ResearchErasureTestFixture() {
    @Test fun decliningOneSensorRetiresBootSnapshotWhileSiblingRemainsActiveAndRestartIsDeferred() {
        val declined = AndroidSensorType.accelerometer
        val retained = AndroidSensorType.gyroscope
        val snapshot = DirectBootSensorSnapshot(context)
        assertTrue(snapshot.write(setOf(declined, retained).associateWith { DirectBootSensorSnapshot.SensorConfig() }, "owner"))
        var deferred = false
        val observedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? {
                deferred = true
                error("background foreground-service start denied")
            }
        }

        decide(SensorCollectionModules.moduleFor(declined), false, observedContext)
        stopOnPersistenceWorker {}

        assertTrue("the remaining sensor still requests the service", deferred)
        assertTrue(CollectionLoopStore.of(context).collects(SensorCollectionModules.moduleFor(retained)))
        assertFalse(CollectionLoopStore.of(context).collects(SensorCollectionModules.moduleFor(declined)))
        assertFalse("a reboot must not reuse the pre-decline snapshot", snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))
    }

    /** The reminder read answers NOT_ENROLLED with no forms; NotificationsWorker cancels the armed reminders. */
    @Test fun serverNotEnrolledReminderReadCancelsArmedReminders() {
        val expected = owner.copy(apiKey = "test-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val reminder = NotificationDetails("form", NotificationType.QUESTIONNAIRE,
            "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0", "Check-in", "Tap")
        EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(reminder.requestCode()))
        assertNotNull(armReminder(context, reminder,
            Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)))
        assertEquals(1, reminderAlarms(context).size)

        withStudyApi { method ->
            if (method == "getMobileReminderSchedule") {
                MobileReminderConfiguration(ParticipationStatus.NOT_ENROLLED, emptyList())
            } else {
                error(method)
            }
        }.use {
            assertEquals(ListenableWorker.Result.success(),
                onPersistenceWorker { NotificationsWorker(context, auditWorkerParameters()).doWork() })
            assertTrue("NOT_ENROLLED cancels armed reminders", reminderAlarms(context).isEmpty())
            assertTrue(EnrollmentSettings(context).getMobileReminderRequestCodes().isEmpty())
        }
    }
}
