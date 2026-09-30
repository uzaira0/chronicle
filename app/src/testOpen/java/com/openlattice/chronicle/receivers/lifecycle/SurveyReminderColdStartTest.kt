package com.openlattice.chronicle.receivers.lifecycle

import android.app.NotificationManager
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
    }

    private fun <T> runOffMain(action: () -> T): T {
        val task = java.util.concurrent.FutureTask(action)
        Thread(task).start()
        return task.get(30, java.util.concurrent.TimeUnit.SECONDS)
    }
}
