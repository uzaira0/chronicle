package com.openlattice.chronicle.receivers.lifecycle

import android.app.NotificationManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DETAILS
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.services.notifications.SURVEY_ENROLLMENT_SCOPE
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** An alarm that cold-starts the process must wait for authorization, not drop the reminder. */
@RunWith(RobolectricTestRunner::class)
class SurveyReminderColdStartTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun coldStartReminderIsPostedOnceAuthorizationPublishes() {
        TestStores.install(context, enrolled = true,
            manifestOverrides = mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val owner = runOffMain {
            ResearchPersistenceGate.initialize(context)
            ResearchPersistenceGate.captureOwner(context)!!
        }
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)!!.first
        // Cold process: nothing published yet.
        ResearchPersistenceGate::class.java.getDeclaredField("authorization").apply {
            isAccessible = true
            set(ResearchPersistenceGate, type.getDeclaredConstructor().apply { isAccessible = true }.newInstance())
        }
        assertNull(ResearchPersistenceGate.captureOwner(context))

        val details = NotificationDetails("form", NotificationType.QUESTIONNAIRE, "FREQ=DAILY", "Check-in",
            "Tap", serverUrl = "https://localhost", accessCode = "b".repeat(40))
        com.openlattice.chronicle.preferences.EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(details.requestCode()))
        // Robolectric only routes manifest receivers that declare an intent filter; register it.
        androidx.core.content.ContextCompat.registerReceiver(context, SurveyNotificationsReceiver(),
            android.content.IntentFilter(SURVEY_NOTIFICATION_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(SURVEY_NOTIFICATION_ACTION).setPackage(context.packageName)
            .putExtra(SURVEY_ENROLLMENT_SCOPE, scope)
            .putExtra(NOTIFICATION_DETAILS, Gson().toJson(details))
            .putExtra(STUDY_ID, owner.studyId)
            .putExtra(PARTICIPANT_ID, owner.participantId))
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        val deadline = System.currentTimeMillis() + 8_000
        while (notifications.allNotifications.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }

        assertEquals(1, notifications.allNotifications.size)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, alarms.size)
        // Robolectric 4.17 has no getter for the alarm's PendingIntent.
        @Suppress("DEPRECATION") val alarmOperation = alarms.single().operation
        val nextReminder = Gson().fromJson(
            shadowOf(alarmOperation).savedIntent.getStringExtra(NOTIFICATION_DETAILS),
            NotificationDetails::class.java,
        )
        assertTrue(nextReminder.scheduledAtMillis!! > System.currentTimeMillis())
        // The tap goes through the app for a fresh one-time code, never straight to the posted link.
        assertEquals(
            com.openlattice.chronicle.services.notifications.ReminderLinkActivity::class.java.name,
            shadowOf(notifications.allNotifications.single().contentIntent).savedIntent.component?.className,
        )
        // V-9: the tap is checked against the enrollment scope this alarm was admitted under.
        assertEquals(scope, shadowOf(notifications.allNotifications.single().contentIntent).savedIntent
            .getStringExtra(SURVEY_ENROLLMENT_SCOPE))
    }



    /** Fix 4: an alarm whose request code a sync retired meanwhile posts nothing. */
    @Test fun retiredRequestCodeIsNotPosted() {
        TestStores.install(context, enrolled = true,
            manifestOverrides = mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val owner = runOffMain {
            ResearchPersistenceGate.initialize(context)
            ResearchPersistenceGate.captureOwner(context)!!
        }
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)!!.first
        val details = NotificationDetails("form", NotificationType.QUESTIONNAIRE, "FREQ=DAILY", "Check-in",
            "Tap", serverUrl = "https://localhost", accessCode = "b".repeat(40))
        com.openlattice.chronicle.preferences.EnrollmentSettings(context).setMobileReminderRequestCodes(emptySet())
        val receiver = SurveyNotificationsReceiver()
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
            android.content.IntentFilter(SURVEY_NOTIFICATION_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(SURVEY_NOTIFICATION_ACTION).setPackage(context.packageName)
            .putExtra(SURVEY_ENROLLMENT_SCOPE, scope)
            .putExtra(NOTIFICATION_DETAILS, Gson().toJson(details))
            .putExtra(STUDY_ID, owner.studyId)
            .putExtra(PARTICIPANT_ID, owner.participantId))
        shadowOf(Looper.getMainLooper()).idle()
        // The receiver's worker thread finishes its goAsync result once it has decided.
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBroadcastPendingResult>(
            shadowOf(receiver).originalPendingResult,
        ).future.get(8, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    private fun <T> runOffMain(action: () -> T): T {
        val task = java.util.concurrent.FutureTask(action)
        Thread(task).start()
        return task.get(30, java.util.concurrent.TimeUnit.SECONDS)
    }
}
