package com.openlattice.chronicle.receivers.lifecycle

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DETAILS
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.services.notifications.ReminderLinkActivity
import com.openlattice.chronicle.services.notifications.SURVEY_ENROLLMENT_SCOPE
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import com.openlattice.chronicle.services.notifications.alarmOperation
import com.openlattice.chronicle.services.notifications.reminderAlarms
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult

/** An alarm that cold-starts the process must wait for authorization, not drop the reminder. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SurveyReminderColdStartTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The test stores outlive the test; a later test must not start enrolled with this one's cached reminders. */
    @After fun tearDown() = runOffMain {
        ChronicleDb.getInstance(context).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    @Test fun coldStartReminderIsPostedOnceAuthorizationPublishes() {
        AuditStores.install(context, enrolled = true,
            manifestOverrides = mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val owner = runOffMain {
            ResearchPersistenceGate.initialize(context)
            ResearchPersistenceGate.captureOwner(context)!!
        }
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)!!.key
        // Cold process: nothing published yet.
        EnrollmentTestStores.leaveEnrollmentUnpublished(context)
        assertNull(ResearchPersistenceGate.captureOwner(context))

        deliverReminder(owner, scope)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))

        assertEquals(1, notifications.allNotifications.size)
        val alarms = reminderAlarms(context)
        assertEquals(1, alarms.size)
        val alarmOperation = checkNotNull(alarmOperation(alarms.single()))
        val nextReminder = Gson().fromJson(
            shadowOf(alarmOperation).savedIntent.getStringExtra(NOTIFICATION_DETAILS),
            NotificationDetails::class.java,
        )
        assertTrue(nextReminder.scheduledAtMillis!! > System.currentTimeMillis())
        // The tap goes through the app for a fresh one-time code, never straight to the posted link.
        assertEquals(
            ReminderLinkActivity::class.java.name,
            shadowOf(notifications.allNotifications.single().contentIntent).savedIntent.component?.className,
        )
        // The tap is checked against the enrollment scope this alarm was admitted under.
        assertEquals(scope, shadowOf(notifications.allNotifications.single().contentIntent).savedIntent
            .getStringExtra(SURVEY_ENROLLMENT_SCOPE))
    }

    @Test fun postedSurveyHasGenericPrivateLockScreenVersion() {
        coldStartReminderIsPostedOnceAuthorizationPublishes()
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue("public lock-screen version required", notification.publicVersion != null)
        val publicText = notification.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() +
            notification.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(publicText.contains("Chronicle"))
        assertTrue(!publicText.contains("Check-in") && !publicText.contains("localhost") && !publicText.contains("form"))
    }

    /** Fix 4: an alarm whose request code a sync retired meanwhile posts nothing. */
    @Test fun retiredRequestCodeIsNotPosted() {
        AuditStores.install(context, enrolled = true,
            manifestOverrides = mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val owner = runOffMain {
            ResearchPersistenceGate.initialize(context)
            ResearchPersistenceGate.captureOwner(context)!!
        }
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)!!.key
        val details = NotificationDetails("form", NotificationType.QUESTIONNAIRE, "FREQ=DAILY", "Check-in",
            "Tap", serverUrl = "https://localhost", accessCode = "b".repeat(40))
        EnrollmentSettings(context).setMobileReminderRequestCodes(emptySet())
        val receiver = SurveyNotificationsReceiver()
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(SURVEY_NOTIFICATION_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(SURVEY_NOTIFICATION_ACTION).setPackage(context.packageName)
            .putExtra(SURVEY_ENROLLMENT_SCOPE, scope)
            .putExtra(NOTIFICATION_DETAILS, Gson().toJson(details))
            .putExtra(STUDY_ID, owner.studyId)
            .putExtra(PARTICIPANT_ID, owner.participantId))
        shadowOf(Looper.getMainLooper()).idle()
        // The receiver's worker thread finishes its goAsync result once it has decided.
        Shadow.extract<ShadowBroadcastPendingResult>(
            shadowOf(receiver).originalPendingResult,
        ).future.get(8, TimeUnit.SECONDS)

        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    /** MR-P1: with notifications blocked the reminder cannot be posted; the study is told through a diagnostic. */
    @Test fun blockedNotificationsRecordTheUndeliveredReminder() {
        AuditStores.install(context, enrolled = true,
            manifestOverrides = mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = true)))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val owner = runOffMain {
            ResearchPersistenceGate.initialize(context)
            ResearchPersistenceGate.captureOwner(context)!!
        }
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        notifications.setNotificationsEnabled(false)

        deliverReminder(owner, ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)!!.key)

        assertTrue(notifications.allNotifications.isEmpty())
        val undelivered = runOffMain {
            ChronicleDb.getInstance(context).openHelper.readableDatabase.query(
                "SELECT coalesce(sum(count),0) FROM upload_diagnostics WHERE moduleFamily = ? AND issueCode = ?",
                arrayOf(LocalUploadModuleFamily.APP_RUNTIME.name, LocalOperationalIssue.COLLECTION_ACCESS_MISSING.name),
            ).use { it.moveToFirst(); it.getInt(0) }
        }
        assertEquals(1, undelivered)
    }

    /** Sends one admitted reminder alarm and waits for the receiver's worker thread to finish. */
    private fun deliverReminder(owner: UploadServerEntity, scope: String) {
        val details = NotificationDetails("form", NotificationType.QUESTIONNAIRE, "FREQ=DAILY", "Check-in",
            "Tap", serverUrl = "https://localhost", accessCode = "b".repeat(40))
        EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(details.requestCode()))
        // Robolectric only routes manifest receivers that declare an intent filter; register it.
        val receiver = SurveyNotificationsReceiver()
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(SURVEY_NOTIFICATION_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(Intent(SURVEY_NOTIFICATION_ACTION).setPackage(context.packageName)
            .putExtra(SURVEY_ENROLLMENT_SCOPE, scope)
            .putExtra(NOTIFICATION_DETAILS, Gson().toJson(details))
            .putExtra(STUDY_ID, owner.studyId)
            .putExtra(PARTICIPANT_ID, owner.participantId))
        shadowOf(Looper.getMainLooper()).idle()
        Shadow.extract<ShadowBroadcastPendingResult>(
            shadowOf(receiver).originalPendingResult,
        ).future.get(8, TimeUnit.SECONDS)
    }

    private fun <T> runOffMain(action: () -> T): T {
        val task = FutureTask(action)
        Thread(task).start()
        return task.get(30, TimeUnit.SECONDS)
    }
}
